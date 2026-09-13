#!/usr/bin/env node
/**
 * Weaveora 大文件分片下载器（10 进程 Range 分片 + 断点续传 + 进度日志）
 *
 * 设计要点（遵守 Weaveora.md §0.2「大文件下载铁律」）：
 *  - 每文件 **10 个 curl 子进程**并发拉取 Range 分片（等价格 aria2 -x10）
 *  - 断点续传：<file>.meta.json 记录每段 s/e/pos，中断后重跑自动续传
 *  - 只用短命 Range 请求，不做任何长连接 / snapshot_download / git lfs
 *  - 每 10s 一行进度：百分比 + 总大小 + 已下载 + 实时速度
 *  - 完成后写 <file>.done；大小不符视为失败
 *
 * 为什么用 curl 子进程而不是 Node https：
 *  hf-mirror.com 的 TLS 握手会让 Node 的 https 挂死（curl 正常），且用户要求「10 进程」。
 *
 * 用法：
 *   数组模式（内置 FILES 清单）：node gpu_model_downloader.js
 *   单文件模式：node gpu_model_downloader.js <URL> <输出绝对路径> [并发数，默认 10]
 */
'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const { spawn } = require('child_process');

const ROOT = 'D:/model';
const THREADS = 10;
const NULL_DEV = process.platform === 'win32' ? 'NUL' : '/dev/null';
const CURL = process.env.WEAVEORA_CURL || 'curl.exe';
const CONNECT_TIMEOUT = 30;   // 建连超时（秒）
const LOW_SPEED_LIMIT = 1024; // 低于 1 KiB/s 且持续 60s → curl 退出，交给外层重试
const LOW_SPEED_TIME = 60;

const FILES = [
  {
    name: 'SDXL-base-1.0', dir: 'checkpoints', file: 'sd_xl_base_1.0.safetensors',
    url: 'https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors',
  },
  {
    name: 'Wan2.2-TI2V-5B-FP16', dir: 'diffusion_models', file: 'wan2.2_ti2v_5B_fp16.safetensors',
    url: 'https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors',
  },
  {
    name: 'UMT5-XXL-FP8', dir: 'text_encoders', file: 'umt5_xxl_fp8_e4m3fn_scaled.safetensors',
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

// ---- 跑一次 curl，返回 { code, stdout, stderr } ----
function runCurl(args, onStdout) {
  return new Promise((resolve, reject) => {
    const child = spawn(CURL, args, { windowsHide: true });
    let stdout = '', stderr = '';
    if (child.stdout) {
      child.stdout.on('data', d => {
        if (onStdout) onStdout(d);
        else if (stdout.length < 1 << 20) stdout += d.toString('latin1');
      });
    }
    if (child.stderr) child.stderr.on('data', d => { if (stderr.length < 1 << 16) stderr += d.toString(); });
    child.on('error', reject);
    child.on('close', code => resolve({ code, stdout, stderr }));
  });
}

// ---- 探测总大小：Range bytes=0-0，解析 content-range / content-length ----
async function probe(url) {
  let lastErr = null;
  for (let i = 1; i <= 3; i++) {
    try {
      const r = await runCurl([
        '-sSL', '--connect-timeout', String(CONNECT_TIMEOUT),
        '-r', '0-0', '-D', '-', '-o', NULL_DEV, url,
      ]);
      const lines = (r.stdout || '').split(/\r?\n/);
      let total = null, ranged = false;
      for (const line of lines) {
        const m1 = /^content-range:\s*bytes\s+\d+-\d+\/(\d+)/i.exec(line);
        if (m1) { total = Number(m1[1]); ranged = true; }
      }
      if (total == null) {
        for (const line of lines) {
          const m2 = /^content-length:\s*(\d+)/i.exec(line);
          if (m2) total = Number(m2[1]);
        }
      }
      if (!total) throw new Error('probe fail: no size (curl code ' + r.code + ') ' + (r.stderr || '').trim());
      return { total, ranged };
    } catch (e) {
      lastErr = e;
      log('探测失败 ' + i + '/3: ' + e.message);
      await sleep(3000 * i);
    }
  }
  throw new Error('探测 3 次均失败: ' + lastErr.message);
}

// ---- 单段：curl -r 从 pos 拉到 end-1，直接写进 fd 的绝对偏移 ----
function fetchSegment(url, seg, fh, onBytes, tag) {
  return new Promise((resolve, reject) => {
    const args = [
      '-sSL', '--fail', '--connect-timeout', String(CONNECT_TIMEOUT),
      '--speed-limit', String(LOW_SPEED_LIMIT), '--speed-time', String(LOW_SPEED_TIME),
      '-r', seg.pos + '-' + (seg.e - 1), url,
    ];
    const child = spawn(CURL, args, { windowsHide: true });
    let buf = [], bufLen = 0, pending = Promise.resolve(), err = '';
    child.stdout.on('data', d => {
      buf.push(d); bufLen += d.length;
      if (bufLen < 1 << 20) return;
      const chunk = Buffer.concat(buf, bufLen);
      buf = []; bufLen = 0;
      const at = seg.pos;
      seg.pos += chunk.length;
      pending = pending.then(() => fh.write(chunk, 0, chunk.length, at)).then(() => onBytes(chunk.length));
    });
    child.stderr.on('data', d => { if (err.length < 1 << 14) err += d.toString(); });
    child.on('error', reject);
    child.on('close', async code => {
      try {
        if (bufLen) {
          const chunk = Buffer.concat(buf, bufLen);
          const at = seg.pos;
          seg.pos += chunk.length;
          await fh.write(chunk, 0, chunk.length, at);
          onBytes(chunk.length);
        }
        await pending;
      } catch (e) { return reject(e); }
      if (code !== 0) return reject(new Error('curl exit ' + code + ' ' + err.trim().slice(0, 200)));
      resolve();
    });
  });
}

async function downloadOne(entry, index, count, threads) {
  const out = entry.out;
  const metaPath = out + '.meta.json';
  const donePath = out + '.done';
  await fsp.mkdir(path.dirname(out), { recursive: true });

  if (fs.existsSync(donePath)) {
    try {
      if (fs.statSync(out).size === Number(fs.readFileSync(donePath, 'utf8'))) {
        log(`[${index + 1}/${count}] SKIP(已完成) ${entry.file} (${fmt(fs.statSync(out).size)})`);
        return;
      }
    } catch (e) {}
  }

  let meta = null;
  if (fs.existsSync(metaPath)) {
    try { meta = JSON.parse(fs.readFileSync(metaPath, 'utf8')); } catch (e) { meta = null; }
  }
  if (!meta || !meta.total) {
    log(`[${index + 1}/${count}] ${entry.file} 探测大小…`);
    const p = await probe(entry.url);
    meta = { url: entry.url, total: p.total, ranged: p.ranged, segs: null };
  }
  try {
    if (fs.statSync(out).size === meta.total && !meta.segs) {
      fs.writeFileSync(donePath, String(meta.total));
      fs.unlinkSync(metaPath);
      log(`[${index + 1}/${count}] SKIP(已完整) ${entry.file}`);
      return;
    }
  } catch (e) {}

  const nSeg = meta.ranged === false ? 1 : threads; // 服务端不支持 Range 时退化为单连接（curl -C - 续传）
  if (!meta.segs) {
    meta.segs = [];
    const segSize = Math.ceil(meta.total / nSeg);
    for (let i = 0; i < nSeg; i++) {
      const s = i * segSize;
      const e = Math.min(meta.total, s + segSize);
      if (s >= e) break;
      meta.segs.push({ s, e, pos: s });
    }
  }

  if (!fs.existsSync(out)) { const h = await fsp.open(out, 'w'); await h.close(); }
  const fh = await fsp.open(out, 'r+');
  await fh.truncate(meta.total);

  const done0 = meta.segs.reduce((a, s) => a + (s.pos - s.s), 0);
  log(`[${index + 1}/${count}] 开始 ${entry.file}  总 ${fmt(meta.total)}  分片 ${meta.segs.length} 路` +
      (done0 ? `  续传自 ${fmt(done0)}` : '') + (meta.ranged === false ? '  [服务器不支持 Range，单连接续传]' : ''));

  let got = done0, t0 = Date.now(), lastLog = Date.now();
  const onBytes = n => { got += n; };

  const saveMeta = () => {
    fs.writeFile(metaPath, JSON.stringify({ url: meta.url, total: meta.total, ranged: meta.ranged, segs: meta.segs }), () => {});
  };

  const timer = setInterval(() => {
    const now = Date.now();
    if (now - lastLog < 10000) return;
    lastLog = now;
    const pct = (100 * got / meta.total).toFixed(1);
    log(`[${index + 1}/${count}] ${entry.file}  ${pct}%  ${fmt(got)}/${fmt(meta.total)}  ${fmtSpd(got * 1000 / (now - t0))}`);
  }, 1000);

  const fetchInto = async (seg) => {
    let attempt = 0;
    while (seg.pos < seg.e) {
      try {
        await fetchSegment(meta.url, seg, fh, onBytes, entry.file);
        attempt = 0;
      } catch (e) {
        attempt++;
        if (attempt > 30) throw new Error('段失败(重试30次): ' + e.message);
        const wait = Math.min(60000, 2000 * attempt);
        log(`[${index + 1}/${count}] ${entry.file} 段重试 ${attempt} (${e.message}) ${wait}ms后`);
        await sleep(wait);
      }
      saveMeta();
    }
  };

  try {
    await Promise.all(meta.segs.map(fetchInto));
    clearInterval(timer);
    await fh.close();
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
  if (process.argv[2]) {
    const url = process.argv[2];
    const out = path.resolve(process.argv[3]);
    const th = Number(process.argv[4] || THREADS);
    log('单文件分片下载: ' + url);
    await downloadOne({ name: path.basename(out), dir: path.dirname(out), file: path.basename(out), url, out }, 0, 1, th);
    log('ALL_DONE');
    process.exit(0);
  }
  const th = Number(process.env.WEAVEORA_DL_THREADS || THREADS);
  log('Weaveora 模型下载启动 —— 共 ' + FILES.length + ' 个文件, 每文件 ' + th + ' 进程分片');
  let fail = 0;
  for (let i = 0; i < FILES.length; i++) {
    try { await downloadOne(FILES[i], i, FILES.length, th); }
    catch (e) { fail++; log(`FAIL ${FILES[i].file}: ${e.message}（重跑自动续传）`); }
  }
  log(fail === 0 ? 'ALL_DONE' : `FINISHED_WITH_ERRORS (${fail})`);
  process.exit(fail === 0 ? 0 : 1);
})();
