#!/usr/bin/env node
/**
 * Weaveora · GPU 镜像同步 / 一致性校验器（SSH 传输版）
 * ============================================================================
 * 「大文件下载策略」的 SSH 扩展：把 deploy/windows/gpu_model_downloader.js 那套
 *   10 路分片 + 断点续传 + .meta 进度 + 只出进度日志 + 后台静默
 * 原封不动搬到 SSH 传输上（HTTP Range → `dd if=... skip_bytes count_bytes`），
 * 另加两条本地没有的能力：
 *   * 远端清单 / 本地索引 / 逐文件哈希三方对账（完整性校验）
 *   * 小文件走 tar 批量流（16 万个文件不可能一个 ssh 一个文件）
 *
 * 用法（Git Bash）：
 *   node deploy/windows/gpu_mirror_sync.js <mode> [选项]
 *
 *   manifest   拉远端清单（stat，几秒）           → _mirror/manifest.tsv
 *   scan       扫本地索引（只读，不 hash）          → _mirror/local-index.tsv
 *   plan       对账 → 差异报告（missing/truncated/…）→ _mirror/plan.txt|json
 *   verify     哈希比对（默认 采样/小文件全量）      → _mirror/verify.txt|json
 *   symlinks   导出软链清单 + 生成恢复脚本          → _mirror/symlinks.tsv|restore_symlinks.sh
 *   sync       传输（大文件 10 路分片 / 小文件 tar）  → 需 --apply 才真写盘
 *   all        manifest → scan → plan → verify（全只读）
 *   status     只读看进度（配合后台跑时轮询）
 *
 * 常用选项：
 *   --host gpu                  SSH 别名（默认 gpu，见 ~/.ssh/config）
 *   --remote /opt/weaveora      远端根
 *   --local  E:/ComfyUI/weaveora 本地根
 *   --state  E:/ComfyUI/_mirror  工作目录（报告/日志/进度）
 *   --threads 10                每文件分片数（铁律默认 10）
 *   --tar-streams 2             小文件批次并发流数
 *   --small-threshold 64M       超过它走 10 路分片，否则 tar 批量
 *   --deep                      verify/sync 对大文件做全文 sha256（慢，磁盘密集）
 *   --no-deref                  软链一律只记不拷（省掉 /addDisk 那 ~64G）
 *   --only <regex>              只处理匹配的路径（例：--only '^models/'）
 *   --apply                     sync 真写盘（默认 dry-run）
 *   --prune-extra               删除本地多余的（默认只报告）
 *   --no-prefix-resume          不做"半截文件前缀续传"验证（直接重传）
 *   --heartbeat <秒>            只读阶段的进度间隔（manifest/scan/verify，默认 30）
 *   --detach                    自重投到后台：父进程打印 log/status 路径后**立即返回**，
 *                               绝不在前台等（铁律 §0.2-4：长任务一律后台静默启动）
 *
 * 铁律对齐（CLAUDE.md §0.2 / §0.3）：
 *   * 后台静默 + 只出进度日志（每 10s 一行）+ 进度落 status.json
 *   * **只读阶段也有进度**（2026-09-19 用户要求）：manifest/scan/verify 每 30s 一行
 *     （已处理数 / 总大小 / 速率 / 已用时间 / 当前文件），并同步落 status.json；用 --heartbeat 调间隔
 *   * **--detach**：父进程 spawn(detached:true) 后立即 exit，终端/会话不会被堵住；
 *     子进程 stdout+stderr 全部落到 <state>/logs/<stamp>-<mode>.detach.log
 *   * 断点续传：<file>.mirror.json 记每段 pos，中断重跑自动续
 *   * 绝不把原始 JSON / 日志倒进上下文：报告全部落盘，人只看路径
 */
'use strict';

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const REPO = path.resolve(__dirname, '..', '..');
const REMOTE_SCRIPTS = path.join(REPO, 'deploy', 'gpu-sync');
const NULL_DEV = process.platform === 'win32' ? 'NUL' : '/dev/null';
const MIB = 1024 * 1024;

// ---------------------------------------------------------------- 配置
const CFG = {
  host: 'gpu',
  remote: '/opt/weaveora',
  local: 'E:/ComfyUI/weaveora',
  state: 'E:/ComfyUI/_mirror',
  threads: 10,
  tarStreams: 2,
  smallThreshold: 64 * MIB,
  batch: 2000,
  smallHashMax: 4 * MIB,   // ≤ 此大小：小文件批量通道 + 全文 sha256
  deep: false,
  deref: true,
  only: null,
  apply: false,
  pruneExtra: false,
  prefixResume: true,
  nice: true,
  retries: 30,
  logIntervalMs: 10000,
  heartbeatMs: 30000,   // 只读阶段心跳间隔（--heartbeat <秒>）
  detach: false,   // --detach：自重投后台（父进程立即返回）
};
const SIDE_TMP = ['.mirror.json', '.mirror-done', '.part', '.meta.json', '.done'];

// ---------------------------------------------------------------- 参数解析
const MODES = ['manifest', 'scan', 'plan', 'verify', 'symlinks', 'sync', 'all', 'status'];
function parseArgs(argv) {
  const mode = argv[0];
  if (!MODES.includes(mode)) {
    console.error(`用法: node gpu_mirror_sync.js <${MODES.join('|')}> [选项]`);
    process.exit(2);
  }
  const sizeK = { k: 1024, m: 1024 ** 2, g: 1024 ** 3 };
  const addSize = v => {
    const m = /^(\d+(?:\.\d+)?)\s*([kmg]?)b?$/i.exec(String(v).trim());
    if (!m) return Number(v);
    return Math.round(Number(m[1]) * (sizeK[m[2].toLowerCase()] || 1));
  };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i], nv = () => argv[++i];
    switch (a) {
      case '--host': CFG.host = nv(); break;
      case '--remote': CFG.remote = nv().replace(/\/+$/, ''); break;
      case '--local': CFG.local = nv().replace(/\/+$/, ''); break;
      case '--state': CFG.state = nv().replace(/\/+$/, ''); break;
      case '--threads': CFG.threads = Number(nv()); break;
      case '--tar-streams': CFG.tarStreams = Number(nv()); break;
      case '--small-threshold': CFG.smallThreshold = addSize(nv()); break;
      case '--small-hash-max': CFG.smallHashMax = addSize(nv()); break;
      case '--deep': CFG.deep = true; break;
      case '--no-deref': CFG.deref = false; break;
      case '--only': CFG.only = new RegExp(nv()); break;
      case '--apply': CFG.apply = true; break;
      case '--prune-extra': CFG.pruneExtra = true; break;
      case '--no-prefix-resume': CFG.prefixResume = false; break;
      case '--no-nice': CFG.nice = false; break;
      case '--heartbeat': CFG.heartbeatMs = Math.max(0, Number(nv())) * 1000; break;
      case '--detach': CFG.detach = true; break;
      default: console.error('未知选项: ' + a); process.exit(2);
    }
  }
  return mode;
}
const MODE = parseArgs(process.argv.slice(2));

/**
 * --detach：把**自己**重投到后台，父进程只打印 log/status 路径后立即返回。
 *
 * 为什么要它（2026-09-19 事故 + 铁律 §0.2-4）：模型镜像同步是长任务（远端 16 万文件 stat +
 * 大文件分片传输），一旦在前台跑就会把会话堵死、且用户看不到进度。现在只需要这一条命令：
 *   node gpu_mirror_sync.js sync --apply --detach
 * 父进程 spawn(detached) 后立刻 exit，进度只通过日志与 status.json 观察（子进程照样每 10s 一行）。
 *
 * 注意：这里**不能**先 await ensureState()，否则父进程也会去建目录/写日志（那就不是"立即返回"了）。
 */
function detachAndExit() {
  const stateDir = CFG.state;
  const logsDir = path.join(stateDir, 'logs');
  fs.mkdirSync(logsDir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..*$/, '').replace('T', '-');
  const logFile = path.join(logsDir, stamp + '-' + MODE + '.detach.log');
  // ★ 写两次：spawn 前先占位（否则子进程/用户可能读到不存在的 status.json，实测踩过），
  //   spawn 后补真实 pid（否则 status 里 pid=null，排查时没用）。
  const writeStatus = patch => {
    try {
      const st = path.join(stateDir, 'status.json');
      const prev = (() => { try { return JSON.parse(fs.readFileSync(st, 'utf8')); } catch { return {}; } })();
      const next = {
        ...prev,
        mode: MODE, phase: 'spawned-detached',
        startedAt: prev.startedAt || new Date().toISOString(),
        log: path.relative(stateDir, logFile),
        cfg: { host: CFG.host, remote: CFG.remote, local: CFG.local, state: CFG.state, apply: CFG.apply },
        hint: '后台已启动；观察：node ' + path.basename(__filename) + ' status --state ' + CFG.state,
        ...patch,
      };
      fs.writeFileSync(st + '.tmp', JSON.stringify(next, null, 2));
      fs.renameSync(st + '.tmp', st);
    } catch (e) { console.error('[detach] 写 status.json 失败（不静默）: ' + (e && e.message)); }
  };
  writeStatus({ pid: null });
  const fd = fs.openSync(logFile, 'a');
  const childArgs = process.argv.slice(2).filter(a => a !== '--detach');
  const child = spawn(process.execPath, [__filename, ...childArgs], {
    detached: true,
    windowsHide: true,
    stdio: ['ignore', fd, fd],
  });
  child.unref();
  fs.closeSync(fd);
  writeStatus({ pid: child.pid, detachedAt: new Date().toISOString() });
  console.log('[detach] 已后台启动 pid=' + child.pid + ' mode=' + MODE);
  console.log('[detach] 日志: ' + logFile);
  console.log('[detach] 状态: ' + path.join(stateDir, 'status.json'));
  console.log('[detach] 查询: node ' + path.relative(process.cwd(), __filename) + ' status --state ' + CFG.state);
  console.log('[detach] 父进程立即退出（铁律 §0.2-4：禁止前台阻塞；只出进度日志）');
  process.exit(0);
}
if (CFG.detach) detachAndExit();

const F = {
  manifest: path.join(CFG.state, 'manifest.tsv'),
  localIndex: path.join(CFG.state, 'local-index.tsv'),
  planJson: path.join(CFG.state, 'plan.json'),
  planTxt: path.join(CFG.state, 'plan.txt'),
  verifyTsv: path.join(CFG.state, 'verify.tsv'),
  verifyJson: path.join(CFG.state, 'verify.json'),
  verifyTxt: path.join(CFG.state, 'verify.txt'),
  symlinks: path.join(CFG.state, 'symlinks.tsv'),
  restoreSymlinks: path.join(CFG.state, 'restore_symlinks.sh'),
  status: path.join(CFG.state, 'status.json'),
};

// ---------------------------------------------------------------- 工具
const t0 = Date.now();
const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 15);
let logStream = null;
function log(...a) {
  const line = `[${new Date().toISOString().slice(11, 19)}] ${a.join(' ')}`;
  process.stdout.write(line + '\n');
  if (logStream) logStream.write(line + '\n');
}
const fmt = n => (n >= 1024 ** 3 ? (n / 1024 ** 3).toFixed(2) + ' GiB'
  : n >= MIB ? (n / MIB).toFixed(1) + ' MiB' : n + ' B');
const spd = bps => bps >= MIB ? (bps / MIB).toFixed(1) + ' MiB/s' : (bps / 1024).toFixed(0) + ' KiB/s';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const sq = s => `'` + String(s).replace(/'/g, `'\\''`) + `'`;
/**
 * 只读阶段的进度心跳（2026-09-19 用户要求：**每 30s 一行**，间隔 --heartbeat <秒> 可调）。
 *
 * 为什么需要：manifest（远端 16 万文件 stat）/ scan（本地索引）/ verify（逐文件哈希比对）
 * 以前**全程静默**、只在结束时写一次 status → 用户看到的就是"没进度"（实测事故）。
 * 字段口径对齐铁律 §0.2-5（下载每 10s 一行）：**已处理数 / 总大小 / 速率 / 已用时间 / 当前文件**。
 */
function makeTicker(tag, totalHint) {
  let done = 0, bytes = 0, cur = '', last = 0;
  let total = Number(totalHint) || 0;
  const t0 = Date.now();
  const emit = (force, phase) => {
    const now = Date.now();
    if (!force && CFG.heartbeatMs > 0 && now - last < CFG.heartbeatMs) return;
    last = now;
    const secs = Math.max(0.001, (now - t0) / 1000);
    const rate = done / secs;
    const eta = total > 0 && done > 0 ? '  ETA≈' + Math.round((total - done) / rate) + 's' : '';
    const pct = total > 0 ? ((100 * done) / total).toFixed(1) + '%  ' : '';
    log('[' + tag + '] ' + pct + done + (total > 0 ? '/' + total : '') + ' 个  ' + fmt(bytes) +
        '  ' + rate.toFixed(0) + ' 个/s  ' + fmt(bytes / secs) + '/s  已用 ' + secs.toFixed(0) + 's' + eta +
        (cur ? '  当前 ' + cur : ''));
    status({ phase: phase || tag, done, total: total || null, bytes, elapsedSecs: +secs.toFixed(1),
             ratePerSec: +rate.toFixed(2), current: cur });
  };
  const tickMs = CFG.heartbeatMs > 0 ? Math.min(5000, CFG.heartbeatMs) : 1000;
  const timer = setInterval(() => emit(false), tickMs);
  if (timer.unref) timer.unref();
  return {
    setTotal(n) { total = Number(n) || 0; },
    tick(n = 1, b = 0, file = '') { done += n; bytes += b; if (file) cur = String(file).slice(0, 140); emit(false); },
    heartbeat(msg) { if (msg) cur = msg; emit(true); },
    done(extra) { clearInterval(timer); emit(true); if (extra) status(extra); },
  };
}

const nowIso = () => new Date().toISOString();
const key = p => p.replace(/^\.\//, '').replace(/\\/g, '/').toLowerCase();

function status(obj) {
  const prev = (() => { try { return JSON.parse(fs.readFileSync(F.status, 'utf8')); } catch { return {}; } })();
  const next = { ...prev, ...obj, updatedAt: nowIso(), log: logFileRel };
  try {
    fs.writeFileSync(F.status + '.tmp', JSON.stringify(next, null, 2));
    fs.renameSync(F.status + '.tmp', F.status);
  } catch (e) { /* 进度写失败不影响主流程 */ }
}
let logFileRel = null;

function sshArgs() {
  return ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=8', '-o', 'Compression=no', '-o', 'LogLevel=ERROR', '-T', CFG.host];
}
function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const c = spawn(cmd, args, { windowsHide: true, ...opts });
    let out = '', err = '';
    if (c.stdout) c.stdout.on('data', d => { if (out.length < 4 * MIB) out += d.toString(); });
    if (c.stderr) c.stderr.on('data', d => { if (err.length < 64 * 1024) err += d.toString(); });
    c.on('error', reject);
    c.on('close', code => resolve({ code, out, err }));
  });
}
const sshRun = (remoteCmd, opts) => run('ssh', [...sshArgs(), remoteCmd], opts);
/** 远端命令套 nice/ionice，别把生产盒子 IO 打满 */
function niceify(core) {
  if (!CFG.nice) return core;
  const w = `if command -v ionice >/dev/null 2>&1; then ionice -c3 -n7 nice -n19 sh -c ${sq(core)}; else nice -n19 sh -c ${sq(core)}; fi`;
  return w;
}

async function ensureState() {
  await fsp.mkdir(path.join(CFG.state, 'logs'), { recursive: true });
  logFileRel = path.join('logs', `${stamp}-${MODE}.log`);
  logStream = fs.createWriteStream(path.join(CFG.state, logFileRel), { flags: 'a' });
}
async function uploadRemoteScript(name) {
  const body = await fsp.readFile(path.join(REMOTE_SCRIPTS, name));
  const dst = `/tmp/weaveora-mirror/${name}`;
  return new Promise((resolve, reject) => {
    const c = spawn('ssh', [...sshArgs(), `mkdir -p /tmp/weaveora-mirror && cat > ${dst} && echo UPLOADED`], { windowsHide: true });
    c.stdin.end(body);
    let out = '';
    c.stdout.on('data', d => { out += d.toString(); });
    c.on('close', code => (code === 0 && out.includes('UPLOADED')) ? resolve(dst) : reject(new Error(`上传 ${name} 失败 code=${code} ${out.slice(0, 200)}`)));
  });
}

// ---------------------------------------------------------------- 本地扫描
function excludedLocal(rel) {
  const p = '/' + rel.replace(/\\/g, '/');
  if (/\/__pycache__\//.test(p) || p.endsWith('/__pycache__')) return true;
  if (p.endsWith('.pyc')) return true;
  for (const d of ['/logs/', '/ComfyUI/output/', '/ComfyUI/temp/', '/ComfyUI/user/', '/talk_work/', '/talk_in/', '/img_out/']) {
    if (p.startsWith(d)) return true;
  }
  return false;
}
async function localScan(tick) {
  const out = [];
  const stack = [''];
  while (stack.length) {
    const rel = stack.pop();
    const abs = rel ? path.join(CFG.local, rel) : CFG.local;
    let ents;
    try { ents = await fsp.readdir(abs, { withFileTypes: true }); } catch { continue; }
    for (const e of ents) {
      const r = rel ? rel + '/' + e.name : e.name;
      if (excludedLocal(r)) continue;
      let st;
      try { st = await fsp.lstat(path.join(CFG.local, r)); } catch { continue; }
      if (st.isDirectory()) { stack.push(r); out.push([r, 'd', 0, Math.round(st.mtimeMs)]); }
      else if (st.isSymbolicLink()) out.push([r, 'l', 0, Math.round(st.mtimeMs)]);
      else out.push([r, 'f', st.size, Math.round(st.mtimeMs)]);
      if (tick) tick.tick(1, st.isFile() ? st.size : 0, r);
    }
  }
  out.sort((a, b) => (a[0] < b[0] ? -1 : 1));
  await fsp.writeFile(F.localIndex, out.map(x => `${x[1]}\t${x[2]}\t${x[3]}\t${x[0]}`).join('\n') + '\n');
  return out;
}
function loadLocalIndex() {
  const map = new Map();
  let txt = '';
  try { txt = fs.readFileSync(F.localIndex, 'utf8'); } catch { return null; }
  for (const line of txt.split('\n')) {
    if (!line) continue;
    const [type, size, mtime, ...rest] = line.split('\t');
    const rel = rest.join('\t');
    map.set(key(rel), { type, size: Number(size), mtimeMs: Number(mtime), rel });
  }
  return map;
}

// ---------------------------------------------------------------- 远端清单
async function remoteManifest() {
  await uploadRemoteScript('remote_probe.sh');
  log(`远端清单：${CFG.host}:${CFG.remote} （只 stat，不读内容）`);
  const tMf = makeTicker('manifest');
  tMf.heartbeat('远端 stat 进行中…（16 万文件时这里慢，静默最久的就是这一段）');
  await new Promise((resolve, reject) => {
    const c = spawn('ssh', [...sshArgs(), `bash /tmp/weaveora-mirror/remote_probe.sh ${sq(CFG.remote)}`], { windowsHide: true });
    const ws = fs.createWriteStream(F.manifest);
    let _tail = '';
    c.stdout.on('data', d => {
      const txt = d.toString();
      const lines = txt.split('\n');
      if (lines[lines.length - 1]) _tail = lines[lines.length - 1];
      tMf.tick(Math.max(0, lines.length - 1), Buffer.byteLength(txt), _tail);
    });
    c.stdout.pipe(ws);
    let err = '';
    c.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    c.on('error', reject);
    c.on('close', code => { tMf.done(); return code === 0 ? ws.end(resolve) : reject(new Error(`远端清单失败 code=${code} ${err.slice(0, 200)}`))});
  });
  const txt = fs.readFileSync(F.manifest, 'utf8');
  if (!/^#END$/m.test(txt)) throw new Error('远端清单没有 #END 收尾（连接中断 / find 出错），别用这份清单');
  const man = parseManifest(txt);
  log(`远端清单完成：${man.entries.size} 条  主机=${man.host}`);
  return man;
}
function parseManifest(txt) {
  const header = /^#HOST (.*)$/m.exec(txt);
  const map = new Map();
  const links = new Map();
  for (const line of txt.split('\n')) {
    if (!line) continue;
    if (line.startsWith('L\t')) {          // 软链解析段：kind / 目标字节数 / 路径
      const [, kind, size, rel] = line.split('\t');
      if (rel) links.set(key(rel.replace(/^\.\//, '')), { kind, size: Number(size) });
      continue;
    }
    if (line.startsWith('#')) continue;
    const [type, size, mtime, rel, target] = line.split('\t');
    if (!rel || !'fld'.includes(type)) continue;
    const r = rel.replace(/^\.\//, '');
    map.set(key(r), { type, size: Number(size), mtime: Number(mtime), rel: r, target: target || '', linkKind: '', linkSize: -1 });
  }
  for (const [k, info] of links) {
    const e = map.get(k);
    if (e) { e.linkKind = info.kind; e.linkSize = info.size; }
  }
  return { host: header ? header[1] : '(unknown)', entries: map };
}
function loadManifest() {
  let txt;
  try { txt = fs.readFileSync(F.manifest, 'utf8'); } catch { return null; }
  return parseManifest(txt);
}

// ---------------------------------------------------------------- 哈希
/** 本地 quick 签名：sha256(size + "\n" + 前1MiB + (size>1MiB? 后1MiB)) —— 与 remote_hash.sh 逐字节等价 */
async function localQuickSig(abs, size) {
  const h = crypto.createHash('sha256');
  h.update(String(size) + '\n');
  const fh = await fsp.open(abs, 'r');
  try {
    const head = Buffer.alloc(Math.min(MIB, size));
    if (head.length) { await fh.read(head, 0, head.length, 0); h.update(head); }
    if (size > MIB) {
      const tail = Buffer.alloc(MIB);
      await fh.read(tail, 0, MIB, size - MIB);
      h.update(tail);
    }
  } finally { await fh.close(); }
  return h.digest('hex');
}
function localFullSha(abs) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    const st = fs.createReadStream(abs);
    st.on('data', d => h.update(d));
    st.on('end', () => resolve(h.digest('hex')));
    st.on('error', reject);
  });
}
function tierOf(size) {
  if (CFG.deep) return 'full';
  return size <= CFG.smallHashMax ? 'small' : 'quick';
}
/** 远端哈希：一批相对路径 → Map(rel → hash) */
async function remoteHash(paths, tier, progressTag) {
  if (!paths.length) return new Map();
  const tH = makeTicker(progressTag ? progressTag + ':remote-hash' : 'remote-hash');
  tH.setTotal(paths.length);
  await uploadRemoteScript('remote_hash.sh');
  const mode = tier === 'small' ? 'small' : tier === 'full' ? 'full' : 'quick';
  const args = [...sshArgs(), niceify(`bash /tmp/weaveora-mirror/remote_hash.sh ${mode} ${sq(CFG.remote)}`)];
  const map = new Map();
  if (mode === 'small') {
    // 小文件批量：分批灌 stdin，逐批收集（避免一次性 16 万行 + 输出缓冲）
    for (let i = 0; i < paths.length; i += 5000) {
      const chunk = paths.slice(i, i + 5000);
      const r = await new Promise((resolve, reject) => {
        const c = spawn('ssh', args, { windowsHide: true });
        let out = '', err = '';
        c.stdout.on('data', d => { out += d.toString(); });
        c.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
        c.on('error', reject);
        c.on('close', code => resolve({ code, out, err }));
        c.stdin.end(chunk.join('\n') + '\n');
      });
      if (r.code !== 0) throw new Error('远端 small 哈希失败: ' + r.err.slice(0, 300));
      for (const line of r.out.split('\n')) {
        if (!line) continue;
        const [h, p] = line.split('\t');
        if (h && p) map.set(key(p), h);
      }
      tH.tick(Math.min(5000, paths.length - i));
      if (progressTag) log(`  ${progressTag} 远端小文件哈希 ${Math.min(i + 5000, paths.length)}/${paths.length}`);
    }
    return map;
  }
  // 逐文件模式（quick/full）：一次 ssh，流式喂 + 流式收
  await new Promise((resolve, reject) => {
    const c = spawn('ssh', args, { windowsHide: true });
    let buf = '', err = '';
    c.stdout.on('data', d => {
      buf += d.toString();
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        if (!line) continue;
        const [h, p] = line.split('\t');
        if (h && p) { map.set(key(p), h); tH.tick(1, 0, p); }
      }
    });
    c.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    c.on('error', reject);
    c.on('close', code => { tH.done(); return code === 0 ? resolve() : reject(new Error(`远端 ${mode} 哈希失败 code=${code} ${err.slice(0, 200)}`))});
    let idx = 0;
    const feed = () => {
      while (idx < paths.length) {
        if (!c.stdin.write(paths[idx++] + '\n')) { c.stdin.once('drain', feed); return; }
      }
      c.stdin.end();
    };
    feed();
  });
  return map;
}

/** 远端文件【前 n 字节】的 sha256 —— 证明本地半截文件是远端真前缀（前缀续传的安全前提） */
async function remoteHashPrefix(rel, bytes) {
  await uploadRemoteScript('remote_hash.sh');
  const args = [...sshArgs(), niceify(`bash /tmp/weaveora-mirror/remote_hash.sh prefix ${sq(CFG.remote)}`)];
  return new Promise((resolve, reject) => {
    const c = spawn('ssh', args, { windowsHide: true });
    let out = '', err = '';
    c.stdout.on('data', d => { out += d.toString(); });
    c.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    c.on('error', reject);
    c.on('close', code => {
      if (code !== 0) return reject(new Error(`远端 prefix 哈希失败 code=${code} ${err.slice(0, 200)}`));
      const [h, p] = (out.trim().split('\n')[0] || '').split('\t');
      resolve(p && key(p) === key(rel) ? h : null);
    });
    c.stdin.end(rel + '\t' + bytes + '\n');
  });
}

// ---------------------------------------------------------------- plan
// venv 管道件（bin/python、lib64…）/ 内部链：只记不拷（拷了到 Windows 也没用，恢复用 restore_symlinks.sh）
const DEREF_SKIP = /(^|\/)(bin|sbin|lib64)\//;
function buildPlan(man, loc) {
  const rows = [];
  const remoteHit = new Set();
  for (const [k, e] of man.entries) {
    remoteHit.add(k);
    const d = !CFG.only || CFG.only.test(e.rel);
    const l = loc.get(k);
    let status, deref = false, size = e.size;
    if (e.type === 'd') status = d ? (l && l.type !== 'd' ? 'type-mismatch' : 'dir') : 'filtered';
    else if (e.type === 'l') {
      const targetInside = e.target.startsWith(CFG.remote + '/');
      if (!d) status = 'filtered';
      else if (targetInside) status = 'link-internal';
      else if (CFG.deref && e.linkKind === 'file' && !DEREF_SKIP.test(e.rel)) {
        deref = true;
        size = e.linkSize >= 0 ? e.linkSize : e.size;
        if (!l) status = 'missing';
        else if (l.type !== 'f') status = 'type-mismatch';
        else if (l.size === size) status = 'size-ok';
        else if (l.size < size) status = 'truncated';
        else status = 'oversize';
      } else status = 'link-external-skip';
    } else {
      if (!d) status = 'filtered';
      else if (!l) status = 'missing';
      else if (l.type !== 'f') status = 'type-mismatch';
      else if (l.size === e.size) status = 'size-ok';
      else if (l.size < e.size) status = 'truncated';
      else status = 'oversize';
    }
    rows.push({ rel: e.rel, type: e.type, status, size, mtimeMs: Math.round(e.mtime * 1000), target: e.target, deref, localSize: l ? l.size : 0, localType: l ? l.type : '' });
  }
  const extra = [];
  for (const [k, l] of loc) {
    if (remoteHit.has(k)) continue;
    if (SIDE_TMP.some(s => l.rel.endsWith(s))) { extra.push({ rel: l.rel, kind: 'sidecar' }); continue; }
    extra.push({ rel: l.rel, kind: l.type, size: l.size });
  }
  return { rows, extra };
}
function planText(plan, man) {
  const by = {};
  for (const r of plan.rows) by[r.status] = (by[r.status] || 0) + 1;
  const bytesToCopy = plan.rows.filter(r => ['missing', 'truncated', 'oversize', 'type-mismatch'].includes(r.status))
    .reduce((a, r) => a + r.size, 0);
  const partial = plan.rows.filter(r => r.status === 'truncated');
  const L = [];
  L.push(`# GPU 镜像对账报告  ${nowIso()}`);
  L.push(`远端: ${man.host} ${CFG.remote}   本地: ${CFG.local}   软链解引用: ${CFG.deref ? '是（含 /addDisk 内容）' : '否（只记链）'}`);
  L.push(`远端条目 ${man.entries.size}（含软链 / 目录）　本地多余条目 ${plan.extra.length}`);
  L.push('');
  L.push('## 状态统计');
  for (const k of Object.keys(by).sort()) L.push(`  ${k.padEnd(22)} ${by[k]}`);
  L.push(`  extra-local（本地多余）      ${plan.extra.length}`);
  L.push('');
  L.push(`## 需要传输: ${plan.rows.filter(r => ['missing', 'truncated', 'oversize', 'type-mismatch'].includes(r.status)).length} 个文件 / ${fmt(bytesToCopy)}`);
  L.push('');
  L.push('## 半截文件（Xftp 中断的典型产物）');
  for (const r of partial.slice(0, 40)) L.push(`  ${fmt(r.localSize)}/${fmt(r.size)}  ${((100 * r.localSize) / r.size).toFixed(1)}%  ${r.rel}`);
  if (partial.length > 40) L.push(`  … 另有 ${partial.length - 40} 个`);
  L.push('');
  L.push('## 缺失文件 Top30（按大小）');
  for (const r of plan.rows.filter(x => x.status === 'missing').sort((a, b) => b.size - a.size).slice(0, 30)) L.push(`  ${fmt(r.size)}  ${r.rel}`);
  const bigDeref = plan.rows.filter(r => r.deref);
  if (bigDeref.length) {
    L.push('');
    L.push('## 软链外部目标（/addDisk 等，解引用后本地也要占空间）');
    for (const r of bigDeref) L.push(`  ${fmt(r.size)}  ${r.rel}  ->  ${r.target}  [${r.status}]`);
  }
  const ex = plan.extra.filter(e => e.kind !== 'sidecar');
  if (ex.length) {
    L.push('');
    L.push(`## 本地多余条目 ${ex.length} 个（非旁挂文件，默认只报告）`);
    for (const e of ex.sort((a, b) => (b.size || 0) - (a.size || 0)).slice(0, 30)) L.push(`  ${fmt(e.size || 0)}  [${e.kind}]  ${e.rel}`);
  }
  return L.join('\n') + '\n';
}

// ---------------------------------------------------------------- 传输
function sshCopyArgs() { return sshArgs(); }
function remoteAbs(rel) { return CFG.remote + '/' + rel; }
function localAbs(rel) { return path.join(CFG.local, rel); }

async function transferBig(entry, expected, idx, total) {
  const abs = localAbs(entry.rel);
  const metaPath = abs + '.mirror.json';
  const donePath = abs + '.mirror-done';
  const totalBytes = entry.size;
  await fsp.mkdir(path.dirname(abs), { recursive: true });

  let meta = null;
  try { meta = JSON.parse(await fsp.readFile(metaPath, 'utf8')); } catch { meta = null; }
  if (!meta || meta.total !== totalBytes || meta.src !== entry.rel) meta = null;

  // ---- 断点续传的两种来源：① 本工具上次中断（.mirror.json） ② Xftp 半截文件（必须先证明是真前缀）
  if (!meta) {
    meta = { src: entry.rel, total: totalBytes, mtimeMs: entry.mtimeMs, segs: null, seeded: 0 };
    let cur = -1;
    try { cur = (await fsp.stat(abs)).size; } catch { cur = -1; }
    if (cur > 0 && cur < totalBytes && cur >= CFG.smallThreshold && CFG.prefixResume) {
      log(`[${idx}/${total}] ${entry.rel} 本地半截 ${fmt(cur)}/${fmt(totalBytes)}，先验证是否为远端真前缀…`);
      const [localSha, remotePre] = await Promise.all([
        localFullSha(abs),
        remoteHashPrefix(entry.rel, cur),
      ]);
      if (localSha && remotePre && localSha === remotePre) {
        meta.seeded = cur;
        log(`  前缀一致（${localSha.slice(0, 12)}）→ 从 ${fmt(cur)} 续传`);
      } else {
        log(`  前缀不一致（本地 ${String(localSha).slice(0, 12)} vs 远端 ${String(remotePre).slice(0, 12)}）→ 丢弃半截整传`);
      }
    }
  }

  // 分段：从 seeded 起按 threads 等分，1MiB 对齐
  if (!meta.segs) {
    const start = meta.seeded || 0;
    const remain = totalBytes - start;
    const nSeg = Math.max(1, Math.min(CFG.threads, Math.ceil(remain / MIB)));
    const segSize = Math.ceil(Math.ceil(remain / nSeg) / MIB) * MIB;
    meta.segs = [];
    for (let i = 0; i < nSeg; i++) {
      const s = Math.min(totalBytes, start + i * segSize);
      const e = Math.min(totalBytes, s + segSize);
      if (s >= e) break;
      meta.segs.push({ s, e, pos: s });
    }
  }
  if (!(await exists(abs))) await fsp.writeFile(abs, '');
  const fh = await fsp.open(abs, 'r+');
  await fh.truncate(totalBytes);

  const done0 = meta.segs.reduce((a, s) => a + (s.pos - s.s), 0) + (meta.seeded || 0);
  let got = done0, t1 = Date.now(), lastLog = 0;
  const onBytes = n => { got += n; };
  const saveMeta = () => fsp.writeFile(metaPath, JSON.stringify(meta)).catch(() => {});
  const timer = setInterval(() => {
    const now = Date.now();
    if (now - lastLog < CFG.logIntervalMs) return;
    lastLog = now;
    log(`[${idx}/${total}] ${entry.rel}  ${((100 * got) / totalBytes).toFixed(1)}%  ${fmt(got)}/${fmt(totalBytes)}  ${spd((got * 1000) / Math.max(1, now - t1))}`);
    status({ mode: MODE, phase: 'transfer', current: entry.rel, currentPct: +((100 * got) / totalBytes).toFixed(1) });
  }, 2000);

  const fetchSegment = seg => new Promise((resolve, reject) => {
    // ★ 2026-09-19 线上复现的致命 bug：`entry.rel` 是**相对远端根**的路径，而 `dd` 没有 cd →
    //   dd 在 root 家目录(/root)下找不到文件 → 每个分片都报 "No such file or directory"，重试 30 次后整文件失败。
    //   实测：`dd if='models/loras/…'` → 134 B（错误输出）；`cd /opt/weaveora && dd …` → 1024 B ✓。
    //   （小文件 tar 通道用 `tar -C <root>` 所以没这个问题；别只修一处。）
    const core = `cd ${sq(CFG.remote)} && dd if=${sq(entry.rel)} bs=1048576 iflag=skip_bytes,count_bytes skip=${seg.pos} count=${seg.e - seg.pos} status=none`;
    const c = spawn('ssh', [...sshCopyArgs(), niceify(core)], { windowsHide: true });
    let err = '', buf = [], len = 0, pending = Promise.resolve();
    c.stdout.on('data', d => {
      buf.push(d); len += d.length;
      if (len < MIB) return;
      const chunk = Buffer.concat(buf, len); buf = []; len = 0;
      const at = seg.pos; seg.pos += chunk.length;
      pending = pending.then(() => fh.write(chunk, 0, chunk.length, at)).then(() => onBytes(chunk.length));
    });
    c.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    c.on('error', reject);
    c.on('close', async code => {
      try {
        if (len) {
          const chunk = Buffer.concat(buf, len);
          const at = seg.pos; seg.pos += chunk.length;
          await fh.write(chunk, 0, chunk.length, at);
          onBytes(chunk.length);
        }
        await pending;
      } catch (e) { return reject(e); }
      if (code !== 0) return reject(new Error(`ssh/dd exit ${code} ${err.trim().slice(0, 160)}`));
      resolve();
    });
  });

  const runSeg = async seg => {
    let attempt = 0;
    while (seg.pos < seg.e) {
      try { await fetchSegment(seg); attempt = 0; }
      catch (e) {
        attempt++;
        if (attempt > CFG.retries) throw new Error('分片失败(重试 ' + CFG.retries + ' 次): ' + e.message);
        const wait = Math.min(60000, 2000 * attempt);
        log(`  ${entry.rel} 分片重试 ${attempt} (${e.message}) ${wait}ms`);
        await sleep(wait);
      }
      saveMeta();
    }
  };

  try {
    log(`[${idx}/${total}] 开始 ${entry.rel}  总 ${fmt(totalBytes)}  ${meta.segs.length} 路` + (done0 ? `  续传自 ${fmt(done0)}` : ''));
    await Promise.all(meta.segs.map(runSeg));
    clearInterval(timer);
    await fh.close();
    const st = await fsp.stat(abs);
    if (st.size !== totalBytes) throw new Error('最终大小不符 ' + st.size);
    await fsp.utimes(abs, new Date(), new Date(entry.mtimeMs));
    const sig = expected ? expected.hash : await localQuickSig(abs, totalBytes);
    const m = expected && expected.tier === 'small' ? await localFullSha(abs) : await localQuickSig(abs, totalBytes);
    const ok = expected ? sameHash(m, sig, expected) : true;
    if (expected && !ok) throw new Error('传输后哈希不符（远端 ' + sig.slice(0, 12) + ' 本地 ' + m.slice(0, 12) + '）');
    await fsp.writeFile(donePath, `${totalBytes}\t${m}\t${nowIso()}\n`);
    await fsp.unlink(metaPath).catch(() => {});
    const secs = (Date.now() - t1) / 1000;
    log(`[${idx}/${total}] DONE ${entry.rel}  ${fmt(totalBytes)}  ${(secs / 60).toFixed(1)} min  均速 ${spd(totalBytes / Math.max(1, secs))}`);
  } catch (e) {
    clearInterval(timer);
    saveMeta();
    try { await fh.close(); } catch { }
    throw e;
  }
}
function sameHash(local, expectedHash, expected) {
  if (!expectedHash) return true;
  return local === expectedHash;
}

async function transferSmallBatch(entries, batchNo, batches) {
  const listFile = path.join(CFG.state, `batch-${batchNo}.list`);
  await fsp.writeFile(listFile, entries.map(e => e.rel).join('\n') + '\n');
  const core = `tar -C ${sq(CFG.remote)} -cf - -T -`;
  await new Promise((resolve, reject) => {
    const r = spawn('ssh', [...sshCopyArgs(), niceify(core)], { windowsHide: true });
    const w = spawn('tar', ['-C', CFG.local, '-xf', '-'], { windowsHide: true, stdio: [r.stdout, 'ignore', 'pipe'] });
    let err = '';
    w.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    r.stderr.on('data', d => { if (err.length < 4096) err += d.toString(); });
    r.on('error', reject); w.on('error', reject);
    w.on('close', code => code === 0 ? resolve() : reject(new Error(`本地 tar 解包失败 code=${code} ${err.slice(0, 200)}`)));
    const ls = fs.createReadStream(listFile);
    ls.pipe(r.stdin);
    ls.on('error', reject);
  });
  // 传输后校验：size + 哈希
  let bad = 0;
  for (const e of entries) {
    const abs = localAbs(e.rel);
    let st = null;
    try { st = await fsp.stat(abs); } catch { }
    if (!st || st.size !== e.size) { bad++; log(`  !! ${e.rel} 大小不符`); continue; }
    const h = e.hash || await localFullSha(abs);
    if (e.hash && h !== e.hash) { bad++; log(`  !! ${e.rel} 哈希不符`); }
  }
  log(`[小文件批 ${batchNo}/${batches}] ${entries.length} 个文件完成（${fmt(entries.reduce((a, e) => a + e.size, 0))}）${bad ? '  异常 ' + bad : ''}`);
  await fsp.unlink(listFile).catch(() => { });
  return bad;
}

const exists = p => fsp.access(p).then(() => true, () => false);

// ---------------------------------------------------------------- 主流程
async function cmdManifest() { const m = await remoteManifest(); status({ phase: 'manifest-done', remoteEntries: m.entries.size, remoteHost: m.host }); }
async function cmdScan() {
  const t = makeTicker('scan');
  const loc = await localScan(t);
  t.done();
  log(`本地索引：${loc.length} 条`);
  status({ phase: 'scan-done', localEntries: loc.length });
}
async function cmdPlan() {
  const man = loadManifest() || await remoteManifest();
  if (!fs.existsSync(F.localIndex)) await cmdScan();
  const loc = loadLocalIndex() || new Map();
  const plan = buildPlan(man, loc);
  await fsp.writeFile(F.planJson, JSON.stringify({ generatedAt: nowIso(), remote: { host: man.host, root: CFG.remote }, local: CFG.local, deref: CFG.deref, rows: plan.rows, extra: plan.extra }, null, 1));
  await fsp.writeFile(F.planTxt, planText(plan, man));
  // 软链清单 + 恢复脚本
  const links = plan.rows.filter(r => r.type === 'l');
  await fsp.writeFile(F.symlinks, links.map(r => `${r.rel}\t${r.target}`).join('\n') + '\n');
  await fsp.writeFile(F.restoreSymlinks, [
    '#!/usr/bin/env bash',
    '# 由 gpu_mirror_sync.js plan 生成：在重建的 GPU 盒上恢复软链（镜像里存的是真实内容/或未解引用的链）',
    `set -eu; ROOT="\${1:-${CFG.remote}}"`,
    'while IFS=$\'\\t\' read -r p t; do [ -z "$p" ] && continue; mkdir -p "$(dirname "$ROOT/$p")"; ln -sfn "$t" "$ROOT/$p"; done < "$(dirname "$0")/symlinks.tsv"',
    'echo "symlinks restored"',
  ].join('\n') + '\n');
  const by = {};
  for (const r of plan.rows) by[r.status] = (by[r.status] || 0) + 1;
  log('对账完成: ' + JSON.stringify(by) + ` extra=${plan.extra.length}`);
  log('报告: ' + F.planTxt);
  status({ phase: 'plan-done', counts: by, extra: plan.extra.length, planTxt: F.planTxt });
  return plan;
}
async function cmdVerify() {
  const plan = JSON.parse(await fsp.readFile(F.planJson, 'utf8'));
  const man = loadManifest();
  const rows = plan.rows.filter(r => r.type === 'f' || r.deref);
  const targets = rows;
  const small = targets.filter(r => tierOf(r.size) === 'small');
  const bigTier = targets.filter(r => tierOf(r.size) !== 'small');
  log(`校验目标 ${targets.length} 个（小文件全量 ${small.length}，大文件 ${CFG.deep ? '全文' : '采样'} ${bigTier.length}）`);
  const remote = new Map();
  for (const [k, v] of await remoteHash(small.map(r => r.rel), 'small', 'verify')) remote.set(k, v);
  for (const [k, v] of await remoteHash(bigTier.map(r => r.rel), CFG.deep ? 'full' : 'quick', 'verify')) remote.set(k, v);
  void man;

  let ok = 0, bad = 0, absent = 0;
  const lines = [], outRows = [];
  const tV = makeTicker('verify');
  tV.setTotal(targets.length);
  for (const r of targets) {
    tV.tick(0, 0, r.rel);
    const k = key(r.rel);
    const abs = localAbs(r.rel);
    let st = null;
    try { st = await fsp.stat(abs); } catch { }
    if (!st) { absent++; tV.tick(1, 0, r.rel); outRows.push({ ...r, verify: 'missing', remoteHash: remote.get(k) || '' }); lines.push(`MISSING\t${r.rel}`); continue; }
    if (st.size !== r.size) {
      bad++; outRows.push({ ...r, verify: 'size', remoteHash: remote.get(k) || '', localSize: st.size });
      tV.tick(1, st.size, r.rel);
      lines.push(`SIZE\t${st.size}\t${r.size}\t${r.rel}`); continue;
    }
    const want = remote.get(k);
    const got = tierOf(r.size) === 'small' ? await localFullSha(abs) : await localQuickSig(abs, r.size);
    if (want && want !== got) { bad++; outRows.push({ ...r, verify: 'hash', remoteHash: want, localHash: got }); lines.push(`HASH\t${r.rel}`); }
    else { ok++; outRows.push({ ...r, verify: 'ok', remoteHash: want || '' }); }
    tV.tick(1, st.size, r.rel);
  }
  await fsp.writeFile(F.verifyTsv, lines.join('\n') + (lines.length ? '\n' : ''));
  await fsp.writeFile(F.verifyJson, JSON.stringify({ generatedAt: nowIso(), deep: CFG.deep, tier: CFG.deep ? 'full' : 'sampled', ok, bad, absent, rows: outRows }, null, 1));
  const txt = [
    `# 完整性校验报告  ${nowIso()}`,
    `模式: ${CFG.deep ? '全文 sha256' : '小文件全文 + 大文件采样(size+前1MiB+后1MiB)'}`,
    `通过 ${ok}   缺失 ${absent}   不一致 ${bad}   合计 ${targets.length}`,
    '',
    ...lines.slice(0, 60),
    lines.length > 60 ? `… 另有 ${lines.length - 60} 行，见 verify.tsv` : '',
    '',
    '注：采样模式抓不到"大文件中段静默损坏"；要 100% 内容级结论请跑 --deep（本机与远端各全量读一遍，磁盘密集）。',
  ].join('\n') + '\n';
  await fsp.writeFile(F.verifyTxt, txt);
  log(`校验完成: 通过 ${ok} / 缺失 ${absent} / 不一致 ${bad}   报告 ${F.verifyTxt}`);
  tV.done();
  status({ phase: 'verify-done', verify: { ok, bad, absent, deep: CFG.deep } });
  return { ok, bad, absent };
}

async function cmdSync() {
  const plan = JSON.parse(await fsp.readFile(F.planJson, 'utf8'));
  let verifyRows = null;
  try { verifyRows = JSON.parse(await fsp.readFile(F.verifyJson, 'utf8')); } catch { }
  const hashOf = new Map();
  if (verifyRows) for (const r of verifyRows.rows) hashOf.set(key(r.rel), { hash: r.remoteHash, tier: tierOf(r.size) });

  // 待传清单 = 缺失 / 半截 / 超大 / 类型冲突 / 外部软链(解引用) ∪ 校验不一致
  const need = new Map();
  const push = r => {
    if (CFG.only && !CFG.only.test(r.rel)) return;
    need.set(key(r.rel), { rel: r.rel, size: r.size, mtimeMs: r.mtimeMs, status: r.status });
  };
  for (const r of plan.rows) {
    if (r.type === 'd') continue;
    if (['missing', 'truncated', 'oversize', 'type-mismatch'].includes(r.status)) push(r);
  }
  if (verifyRows) for (const r of verifyRows.rows) if (r.verify !== 'ok') push(r);

  const list = [...need.values()];
  const bytes = list.reduce((a, r) => a + r.size, 0);
  const big = list.filter(r => r.size >= CFG.smallThreshold);
  const small = list.filter(r => r.size < CFG.smallThreshold);
  const bigBytes = big.reduce((a, r) => a + r.size, 0);
  log(`待传输 ${list.length} 个 / ${fmt(bytes)}   其中 ≥${fmt(CFG.smallThreshold)} 的 ${big.length} 个走 ${CFG.threads} 路分片（${fmt(bigBytes)}），其余 ${small.length} 个走 tar 批量（${fmt(bytes - bigBytes)}）`);

  // 空间闸门
  const st = fs.statfsSync(CFG.local.replace(/\/+$/, '') + (CFG.local.endsWith(':') ? '/' : ''));
  const free = Number(st.bavail) * Number(st.bsize);
  log(`目标盘可用 ${fmt(free)}，需要 ${fmt(bytes)}（含 5% 余量 ${fmt(bytes * 1.05)}）`);
  if (free < bytes * 1.05) {
    const msg = `空间不足：可用 ${fmt(free)} < 需要 ${fmt(bytes * 1.05)}（差 ${fmt(bytes * 1.05 - free)}）。请清理目标盘、改 --local，或加 --no-deref 省掉 /addDisk 副本。`;
    log('中止：' + msg);
    status({ phase: 'sync-blocked', blocked: msg, needBytes: bytes, freeBytes: free });
    process.exitCode = 3;
    return;
  }
  if (!CFG.apply) {
    log('dry-run（未加 --apply）：只列出计划，不写盘。');
    await fsp.writeFile(path.join(CFG.state, 'sync-list.txt'),
      list.sort((a, b) => b.size - a.size).map(r => `${r.size}\t${r.status}\t${r.rel}`).join('\n') + '\n');
    status({ phase: 'sync-dryrun', toCopy: list.length, toCopyBytes: bytes, list: path.join(CFG.state, 'sync-list.txt') });
    return;
  }

  let doneBytes = 0, fail = 0, i = 0;
  const t1 = Date.now();
  for (const r of big.sort((a, b) => b.size - a.size)) {
    i++;
    try {
      await transferBig(r, hashOf.get(key(r.rel)), i, big.length);
      doneBytes += r.size;
      status({ mode: MODE, phase: 'transfer', bigDone: i, bigTotal: big.length, doneBytes, fail });
    } catch (e) {
      fail++;
      log(`FAIL ${r.rel}: ${e.message}`);
      status({ mode: MODE, phase: 'transfer', fail, lastError: r.rel + ': ' + e.message });
    }
  }
  // 小文件 tar 批量（并发 tarStreams 条流）
  const batches = [];
  for (let k = 0; k < small.length; k += CFG.batch) batches.push(small.slice(k, k + CFG.batch).map(r => ({ ...r, hash: hashOf.get(key(r.rel))?.hash })));
  let bIdx = 0;
  const worker = async () => {
    while (bIdx < batches.length) {
      const my = ++bIdx;
      try { await transferSmallBatch(batches[my - 1], my, batches.length); }
      catch (e) { fail++; log(`FAIL 小文件批 ${my}: ${e.message}`); }
      status({ mode: MODE, phase: 'small-batches', batchDone: my, batchTotal: batches.length, fail });
    }
  };
  await Promise.all(Array.from({ length: Math.min(CFG.tarStreams, batches.length) }, worker));

  const secs = (Date.now() - t1) / 1000;
  log(`同步结束：大文件 ${big.length} 个小文件批次 ${batches.length} 个，失败 ${fail}，用时 ${(secs / 60).toFixed(1)} min  均速 ${spd((doneBytes + (bytes - bigBytes)) / Math.max(1, secs))}`);
  status({ phase: 'sync-done', fail, doneBytes, secs: Math.round(secs) });
  if (fail) process.exitCode = 1;
}

async function cmdSymlinksOnly() {
  const plan = JSON.parse(await fsp.readFile(F.planJson, 'utf8'));
  const links = plan.rows.filter(r => r.type === 'l');
  await fsp.writeFile(F.symlinks, links.map(r => `${r.rel}\t${r.target}`).join('\n') + '\n');
  log(`软链 ${links.length} 条 → ${F.symlinks}`);
}

async function cmdStatus() {
  try {
    const s = JSON.parse(fs.readFileSync(F.status, 'utf8'));
    console.log(JSON.stringify(s, null, 2));
  } catch { console.log('(无 status.json，任务可能还没启动)'); }
}

(async () => {
  await ensureState();
  if (MODE !== 'status') {
    status({ mode: MODE, phase: 'start', startedAt: nowIso(), pid: process.pid, cfg: { host: CFG.host, remote: CFG.remote, local: CFG.local, deref: CFG.deref, deep: CFG.deep, threads: CFG.threads, apply: CFG.apply } });
    log(`== gpu_mirror_sync ${MODE} == host=${CFG.host} remote=${CFG.remote} local=${CFG.local} state=${CFG.state} deref=${CFG.deref} deep=${CFG.deep} apply=${CFG.apply}`);
  }
  try {
    switch (MODE) {
      case 'manifest': await cmdManifest(); break;
      case 'scan': await cmdScan(); break;
      case 'plan': await cmdPlan(); break;
      case 'verify': await cmdVerify(); break;
      case 'symlinks': await cmdSymlinksOnly(); break;
      case 'sync': await cmdSync(); break;
      case 'all':
        await cmdManifest(); await cmdScan(); await cmdPlan(); await cmdVerify();
        status({ phase: 'all-done' });
        break;
      case 'status': await cmdStatus(); break;
    }
    if (MODE !== 'status') log(`== ${MODE} 结束，用时 ${((Date.now() - t0) / 1000).toFixed(1)}s ==`);
  } catch (e) {
    log('ERROR: ' + (e && e.stack || e));
    status({ phase: 'error', error: String(e && e.message || e) });
    process.exitCode = 1;
  } finally {
    if (logStream) logStream.end();
  }
})();
