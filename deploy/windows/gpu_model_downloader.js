#!/usr/bin/env node
/**
 * Weaveora 模型批量下载器（ModelScope 国内源）
 * 特性：
 *  - 每文件 10 路 Range 并发分片
 *  - 断点续传：.meta.json 记录每段进度，中断后从断点续传
 *  - ModelScope 签名 URL 过期自动刷新（403/401/416 时重新 resolve）
 *  - 稀疏预分配目标文件（不占实写空间，NTFS 瞬时完成）
 * 用法：node download_models.js
 */
'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const http = require('http');
const https = require('https');

const ROOT = 'D:/model';
const THREADS = 10;
const MAX_REDIRECT = 8;
const UA = 'weaveora-model-dl/1.0';

const FILES = [
  {
    name: 'SDXL-base-1.0', dir: 'checkpoints', file: 'sd_xl_base_1.0.safetensors',
    url: 'https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors',
  },
  {
    name: 'Wan2.2-TI2V-5B-FP16 (8GB显存请用 loader quantization=fp8_e4m3fn_scaled 加载转fp8)', dir: 'diffusion_models', file: 'wan2.2_ti2v_5B_fp16.safetensors',
    url: 'https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors',
  },
  {
    name: 'UMT5-XXL-FP8 (fp8_e4m3fn_scaled, 8GB显存推荐)', dir: 'text_encoders', file: 'umt5_xxl_fp8_e4m3fn_scaled.safetensors',
    url: 'https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors',
  },
  {
    name: 'Wan2.2-VAE', dir: 'vae', file: 'wan2.2_vae.safetensors',
    url: 'https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/vae/wan2.2_vae.safetensors',
  },
  {
    name: 'IPAdapter-SDXL-ViT-H', dir: 'ipadapter', file: 'ip-adapter_sdxl_vit-h.safetensors',
    url: 'https://www.modelscope.cn/models/AI-ModelScope/IP-Adapter/resolve/master/sdxl_models/ip-adapter_sdxl_vit-h.safetensors',
  },
  {
    name: 'CLIP-ViT-H-14', dir: 'clip_vision', file: 'CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors',
    url: 'https://www.modelscope.cn/models/AI-ModelScope/CLIP-ViT-H-14-laion2B-s32B-b79K/resolve/master/model.safetensors',
  },
].map(f => ({ ...f, out: path.join(ROOT, f.dir, f.file) }));

const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fmt = n => (n >= 1 << 30 ? (n / (1 << 30)).toFixed(2) + ' GiB' : n >= 1 << 20 ? (n / (1 << 20)).toFixed(1) + ' MiB' : n + ' B');
const fmtSpd = bps => bps >= 1 << 20 ? (bps / (1 << 20)).toFixed(1) + ' MiB/s' : (bps / 1024).toFixed(0) + ' KiB/s';

// ---- 单次请求（手动跟随重定向） ----
function raw(u, range) {
  return new Promise((resolve, reject) => {
    let mod;
    try { mod = u.startsWith('https:') ? https : http; } catch (e) { return reject(e); }
    const headers = { 'User-Agent': UA, Accept: '*/*' };
    if (range) headers.Range = range;
    let followed = 0;
    const go = (url) => {
      const m = url.startsWith('https:') ? https : http;
      const req = m.get(url, { headers }, res => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (++followed > MAX_REDIRECT) return reject(new Error('redirect loop: ' + url));
          return go(new URL(res.headers.location, url).href);
        }
        resolve({ status: res.statusCode, headers: res.headers, stream: res, url });
      });
      req.on('error', reject);
      req.setTimeout(120000, () => req.destroy(new Error('req timeout ' + url)));
    };
    go(u);
  });
}

// ---- 探测最终签名 URL 与总大小（Range bytes=0-0） ----
async function probe(url) {
  const r = await raw(url, 'bytes=0-0');
  let total = null;
  const cr = r.headers['content-range'];
  if (cr) { const m = cr.match(/\/(\d+)\s*$/); if (m) total = Number(m[1]); }
  if (total == null) total = Number(r.headers['content-length']);
  r.stream.destroy();
  r.stream.resume();
  if (!total) throw new Error('probe fail: no size from ' + r.url);
  return { finalUrl: r.url, total };
}

// ---- 下载单个文件 ----
async function downloadOne(entry, index, count) {
  const out = entry.out;
  const metaPath = out + '.meta.json';
  const donePath = out + '.done';
  const dir = path.dirname(out);
  await fsp.mkdir(dir, { recursive: true });

  // 已完整下载（有 .done 标记）
  if (fs.existsSync(donePath)) {
    const want = fs.readFileSync(donePath, 'utf8');
    try { if (fs.statSync(out).size === Number(want)) { log(`[${index + 1}/${count}] SKIP(已完成) ${entry.file} (${fmt(Number(want))})`); return; } } catch (e) {}
  }

  // 载入或新建 meta
  let meta = null;
  if (fs.existsSync(metaPath)) {
    try { meta = JSON.parse(fs.readFileSync(metaPath, 'utf8')); } catch (e) { meta = null; }
  }
  if (!meta || !meta.total) {
    log(`[${index + 1}/${count}] ${entry.file} 探测大小…`);
    const p = await probe(entry.url);
    meta = { url: entry.url, finalUrl: p.finalUrl, total: p.total, refresh: 0, segs: null };
  }
  // 若目标已存在且等于 total 且无 meta 残留成功（历史文件）→ 标 done
  try { if (fs.statSync(out).size === meta.total) { fs.writeFileSync(donePath, String(meta.total)); fs.unlinkSync(metaPath); log(`[${index + 1}/${count}] SKIP(已完整) ${entry.file}`); return; } } catch (e) {}

  // 初始化分段（每段 s/e/pos）
  if (!meta.segs) {
    meta.segs = [];
    const segSize = Math.ceil(meta.total / THREADS);
    for (let i = 0; i < THREADS; i++) {
      const s = i * segSize;
      const e = Math.min(meta.total, s + segSize);
      if (s >= e) break;
      meta.segs.push({ s, e, pos: s });
    }
  }

  log(`[${index + 1}/${count}] 开始 ${entry.file}  总 ${fmt(meta.total)}  并发 ${meta.segs.length} 路`);
  if (!fs.existsSync(out)) { const _h = await fsp.open(out, 'w'); await _h.close(); }  // 创建空文件
  const fh = await fsp.open(out, 'r+');
  await fh.truncate(meta.total);
  meta.fh = fh;

  let done = 0;
  const t0 = Date.now();
  let lastLog = t0;
  const timer = setInterval(() => {
    const now = Date.now();
    if (now - lastLog < 10000) return;
    lastLog = now;
    const got = meta.segs.reduce((a, s) => a + (s.pos - s.s), 0);
    const pct = (100 * got / meta.total).toFixed(1);
    const spd = fmtSpd(got * 1000 / (now - t0));
    log(`[${index + 1}/${count}] ${entry.file}  ${pct}%  ${fmt(got)}/${fmt(meta.total)}  ${spd}`);
  }, 1000);

  const refreshFinal = async () => {
    const p = await probe(meta.url);
    meta.finalUrl = p.finalUrl;
    meta.refresh++;
    if (p.total !== meta.total) throw new Error('服务器总大小变化: ' + p.total + ' != ' + meta.total);
    log(`[${index + 1}/${count}] 签名已刷新 (第 ${meta.refresh} 次)`);
  };

  const saveMeta = () => {
    const { fh: _fh, ...rest } = meta;
    fs.writeFile(metaPath, JSON.stringify(rest), () => {});
  };

  // 拉取一段流并写入（支持从中途 pos 断点续传）
  const fetchInto = async (seg) => {
    let attempt = 0;
    while (seg.pos < seg.e) {
      try {
        const r = await raw(meta.finalUrl, `bytes=${seg.pos}-${seg.e - 1}`);
        if (r.status === 401 || r.status === 403 || r.status === 416) {
          r.stream.destroy(); r.stream.resume();
          if (meta.refresh < 10) { await refreshFinal(); attempt = 0; continue; }
          throw new Error('签名刷新超限 ' + r.status);
        }
        if (r.status !== 206 && r.status !== 200) {
          r.stream.destroy(); r.stream.resume();
          throw new Error('HTTP ' + r.status);
        }
        for await (const chunk of r.stream) {
          if (seg.pos >= seg.e) break;
          const buf = seg.pos + chunk.length > seg.e ? chunk.subarray(0, seg.e - seg.pos) : chunk;
          await fh.write(buf, 0, buf.length, seg.pos);
          seg.pos += buf.length;
        }
        r.stream.destroy();
        attempt = 0;
      } catch (err) {
        attempt++;
        if (attempt > 30) throw new Error('段失败(重试30次): ' + err.message);
        const wait = Math.min(60000, 2000 * attempt);
        log(`[${index + 1}/${count}] ${entry.file} 段重试 ${attempt} (${err.message}) ${wait}ms后`);
        await sleep(wait);
      }
      saveMeta();
    }
    return true;
  };

  try {
    await Promise.all(meta.segs.map(fetchInto));
    clearInterval(timer);
    await fh.close();
    // 校验总长
    if (fs.statSync(out).size !== meta.total) throw new Error('最终大小不符');
    fs.writeFileSync(donePath, String(meta.total));
    if (fs.existsSync(metaPath)) fs.unlinkSync(metaPath);
    const secs = (Date.now() - t0) / 1000;
    log(`[${index + 1}/${count}] DONE ${entry.file}  ${fmt(meta.total)}  用时 ${(secs / 60).toFixed(1)} min  均速 ${fmtSpd(meta.total / secs)}`);
  } catch (err) {
    clearInterval(timer);
    saveMeta();
    try { await fh.close(); } catch (e) {}
    throw err;
  }
}

(async () => {
  // CLI 单文件模式：node download_models.js <url> <输出文件绝对路径> [并发数]
  if (process.argv[2]) {
    const url = process.argv[2];
    const out = path.resolve(process.argv[3]);
    const th = Number(process.argv[4] || THREADS);
    log('单文件模式下载: ' + url);
    await downloadOne({ name: path.basename(out), dir: path.dirname(out), file: path.basename(out), url, out }, 0, 1);
    log('ALL_DONE');
    process.exit(0);
  }
  log('Weaveora 模型下载启动 —— 共 ' + FILES.length + ' 个文件, 每文件 ' + THREADS + ' 路并发');
  let fail = 0;
  for (let i = 0; i < FILES.length; i++) {
    try { await downloadOne(FILES[i], i, FILES.length); }
    catch (e) { fail++; log(`FAIL ${FILES[i].file}: ${e.message}（下次重跑自动续传）`); }
  }
  log(fail === 0 ? 'ALL_DONE' : `FINISHED_WITH_ERRORS (${fail})`);
  process.exit(fail === 0 ? 0 : 1);
})();
