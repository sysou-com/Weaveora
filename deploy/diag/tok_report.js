#!/usr/bin/env node
// tok_report.js —— pi 会话 token / 成本看板（纯本地解析，**不调用任何模型 → 0 token**）
//
// 用途（2026-09-21 教训）：一次跨天会话在 ~98 万 token 的上下文上跑了 837 次请求，
//   其中 95% 的体量是 16 张 3~4 MB 的大图 → 单会话 $3.93。成本 ≈ 上下文 × 请求数，
//   所以要在"跑飞"的当天就看见它，而不是等欠费。
//
// 用法：
//   node deploy/diag/tok_report.js                 # 默认 = day + 最贵 5 个会话
//   node deploy/diag/tok_report.js day [--days 7]  # 按天成本（近 N 天，默认 14）
//   node deploy/diag/tok_report.js sessions [N]    # 最贵 N 个会话（默认 10）
//   node deploy/diag/tok_report.js compose [sub]   # 上下文构成 + >20k 字符大块归因
//   node deploy/diag/tok_report.js images [N]      # 大图归因（哪次会话塞了几张多大的图）
//   node deploy/diag/tok_report.js anomaly         # 只看异常（日成本突增 / 单会话 cacheRead 过大）
//   --root <dir>   指定会话目录（默认 %USERPROFILE%\.pi\agent\sessions；跨机核对时用）
//   --json         机器可读输出（给别的东西消费）
//
// 口径说明（重要）：
//   · cost 是 pi 按**本地价目表**估算的，不等于 provider 实际账单；以 provider 为准。
//   · 只能看到本机 pi 会话；别的工具/程序用同一 key 产生的消耗这里看不到。
//   · chars 只用于看"结构占比"（1 汉字≈1 token、英文/符号≈0.25 token），不是 token 数。
//
// 双机实测画像（2026-09-21，两台机器各跑一次）：
//   HomePC  ：14 天 $14.97；最贵单会话 = 09-18 20:52→09-19 16:17（837 请求 / maxCtx 983k /
//             cacheRead 365M / $3.93），其中 **95% 体量是 16 张 3~4MB 大图**（37MB）。
//   Invisible：14 天 $9.54；**不是大图型**，而是"同一会话被反复复用、每轮重放接近满窗的上下文"——
//             异常 4 条会话 maxCtx 全在 946k~984k、请求 846~1605 次，合计 $8.47 = 14 天的 89%；
//             极端一条 **08-21 → 09-10 跨三周复用**（1305 请求 / $1.94）。
//   ⇒ 两个最大成本项：① 长会话/跨天跨周复用（上下文越大、请求越多，成本≈上下文×请求数，近二次放大）；
//     ② 把大图（截图/成片 PNG）贴进对话（一旦进上下文，之后每次请求都要重读）。
//   ⇒ 对策：阶段完成就快照并**换新会话**（`Weaveora.md` §0.3 本就有此要求）；交接只传"路径 + hash +
//     3 行结论"；工具输出先落盘只回路径与关键数字；不要往对话里塞整图。
//   省多少钱可以**直接用本工具算**，不必做实验：cacheRead ≈ Σ(每轮上下文)，把一条 avg 上下文 497k 的
//   会话拆成若干条 avg 150k 的新会话，cacheRead 约降到 30%（该条 $2.86 → ≈$0.9）。
'use strict';
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf('--' + name);
  if (i >= 0 && args[i + 1] && !args[i + 1].startsWith('--')) return args[i + 1];
  return def;
};
const flag = (name) => args.includes('--' + name);
const mode = (args[0] && !args[0].startsWith('--')) ? args[0] : 'all';
const root = opt('root') || path.join(process.env.USERPROFILE || process.env.HOME || '', '.pi', 'agent', 'sessions');
const days = parseInt(opt('days', '14'), 10);

function walk(dir, out = []) {
  let ents = [];
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of ents) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.jsonl')) out.push(p);
  }
  return out;
}

const fmt = (n) => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
const money = (x) => '$' + x.toFixed(2);
const day = {};
const sessions = [];
const bigBlocks = [];

for (const f of walk(root)) {
  let txt;
  try { txt = fs.readFileSync(f, 'utf8'); } catch { continue; }
  const s = { file: path.basename(f), path: f, reqs: 0, inp: 0, out: 0, cr: 0, cw: 0, cost: 0,
              maxCtx: 0, first: '', last: '', kinds: {}, imgChars: 0, imgN: 0, bigChars: 0, bigN: 0, humans: [] };
  for (const ln of txt.split('\n')) {
    if (!ln) continue;
    let o; try { o = JSON.parse(ln); } catch { continue; }
    const ts = o.timestamp || '';
    if (ts) { if (!s.first) s.first = ts; s.last = ts; }
    const u = o.usage || (o.message && o.message.usage);
    if (u) {
      s.reqs++; s.inp += u.input || 0; s.out += u.output || 0;
      s.cr += u.cacheRead || 0; s.cw += u.cacheWrite || 0;
      const c = (u.cost && u.cost.total) || 0; s.cost += c;
      const ctx = (u.input || 0) + (u.cacheRead || 0) + (u.cacheWrite || 0);
      if (ctx > s.maxCtx) s.maxCtx = ctx;
      const d = ts.slice(0, 10);
      if (d) {
        day[d] = day[d] || { cost: 0, reqs: 0, inp: 0, out: 0, cr: 0 };
        day[d].cost += c; day[d].reqs++; day[d].inp += u.input || 0;
        day[d].out += u.output || 0; day[d].cr += u.cacheRead || 0;
      }
    }
    const m = o.message;
    if (!m || !Array.isArray(m.content)) continue;
    for (const p of m.content) {
      const k = (p && p.type) || 'raw';
      const cl = JSON.stringify(p).length;
      s.kinds[k] = (s.kinds[k] || 0) + cl;
      if (k === 'image') { s.imgChars += cl; s.imgN++; }
      if (k === 'text' && m.role === 'user' && p.text && !p.text.startsWith('<')) {
        s.humans.push({ ts: ts.slice(5, 16), t: String(p.text).replace(/\s+/g, ' ').slice(0, 110) });
      }
      if (cl > 20000) {
        s.bigChars += cl; s.bigN++;
        if (mode === 'compose') {
          let hint = p.name || '';
          if (!hint && typeof p.text === 'string') hint = p.text.slice(0, 70);
          bigBlocks.push({ cl, k, ts: ts.slice(5, 16), hint: String(hint).replace(/\s+/g, ' ').slice(0, 90), file: s.file });
        }
      }
    }
  }
  if (s.reqs) sessions.push(s);
}
sessions.sort((a, b) => b.cost - a.cost);

const out = [];
const say = (l) => out.push(l);
const printDay = () => {
  const ds = Object.keys(day).sort().slice(-days);
  say('== 按天（' + root + '） ==');
  say(['date', 'cost', 'reqs', 'input', 'output', 'cacheRead'].join('\t'));
  for (const d of ds) {
    const x = day[d];
    say([d, money(x.cost), x.reqs, fmt(x.inp), fmt(x.out), fmt(x.cr)].join('\t'));
  }
  say('合计(近' + ds.length + '天) = ' + money(ds.reduce((a, d) => a + day[d].cost, 0)) +
      '  全部会话合计 = ' + money(sessions.reduce((a, s) => a + s.cost, 0)));
};
const printSessions = (n) => {
  say('== 最贵会话 top' + n + ' ==');
  say(['cost', 'reqs', 'maxCtx', 'cacheRd', 'output', '大图(MB)', '时段(本地)', 'session'].join('\t'));
  for (const s of sessions.slice(0, n)) {
    say([money(s.cost), s.reqs, fmt(s.maxCtx), fmt(s.cr), fmt(s.out), (s.imgChars / 1048576).toFixed(1),
         s.first.slice(5, 16) + '→' + s.last.slice(5, 16), s.file.slice(0, 30)].join('\t'));
  }
};
const printCompose = () => {
  const sub = args[1] && !args[1].startsWith('--') ? args[1] : '';
  const pool = sub ? sessions.filter((s) => s.file.includes(sub)) : sessions;
  const agg = {};
  for (const s of pool) for (const [k, v] of Object.entries(s.kinds)) agg[k] = (agg[k] || 0) + v;
  const tot = Object.values(agg).reduce((a, b) => a + b, 0) || 1;
  say('== 上下文构成（字符量占比，' + pool.length + ' 个会话） ==');
  for (const [k, v] of Object.entries(agg).sort((a, b) => b[1] - a[1])) {
    say('  ' + k.padEnd(12) + fmt(v).padStart(9) + '  ' + (100 * v / tot).toFixed(1) + '%');
  }
  bigBlocks.sort((a, b) => b.cl - a.cl);
  say('== 单条 >20k 字符的大块 top' + Math.min(8, bigBlocks.length) + ' ==');
  for (const b of bigBlocks.slice(0, 8)) say(['  ' + fmt(b.cl), b.k, b.ts, b.hint].join('\t'));
};
const printImages = (n) => {
  const rows = sessions.filter((s) => s.imgN).sort((a, b) => b.imgChars - a.imgChars).slice(0, n);
  say('== 谁把大图塞进了上下文 ==');
  say(['大图MB', '张数', 'cost', 'reqs', 'session'].join('\t'));
  for (const s of rows) {
    say([(s.imgChars / 1048576).toFixed(2), s.imgN, money(s.cost), s.reqs, s.file.slice(0, 30)].join('\t'));
  }
  if (rows[0] && rows[0].humans.length) {
    say('== 该会话里人类指令（说明当时在干什么） ==');
    for (const h of rows[0].humans.slice(0, 6)) say('  [' + h.ts + '] ' + h.t);
  }
};
const printAnomaly = () => {
  const ds = Object.keys(day).sort();
  const bad = [];
  for (let i = 1; i < ds.length; i++) {
    const prev = ds.slice(Math.max(0, i - 7), i);
    const base = prev.reduce((a, d) => a + day[d].cost, 0) / Math.max(1, prev.length);
    if (base > 0.05 && day[ds[i]].cost > Math.max(0.3, base * 3)) {
      bad.push('⚠️ ' + ds[i] + ' 成本 ' + money(day[ds[i]].cost) + ' > 前 7 天均值 ' + money(base) + ' ×3');
    }
  }
  for (const s of sessions) {
    const lastDay = (s.last || '').slice(0, 10);
    if (s.cr > 300e6) bad.push('⚠️ 单会话 cacheRead ' + fmt(s.cr) + '（' + money(s.cost) + '，' + s.reqs + ' 请求，maxCtx ' + fmt(s.maxCtx) + '，最后 ' + lastDay + '）→ ' + s.file.slice(0, 30));
    else if (s.reqs > 600 && s.cost > 1.5) bad.push('⚠️ 长会话 ' + s.reqs + ' 请求 / ' + money(s.cost) + '（最后 ' + lastDay + '）→ ' + s.file.slice(0, 30));
    else if (s.imgChars > 20e6) bad.push('⚠️ 大图 ' + (s.imgChars / 1048576).toFixed(1) + ' MB / ' + s.imgN + ' 张（' + money(s.cost) + '，最后 ' + lastDay + '）→ ' + s.file.slice(0, 30));
  }
  if (!bad.length) say('✅ 无异常（阈值：日成本 > 前7天均值×3；单会话 cacheRead > 300M；>600 请求且 >$1.5；大图 >20MB）');
  else bad.forEach(say);
};

if (mode === 'day') printDay();
else if (mode === 'sessions') printSessions(parseInt(args[1] || '10', 10) || 10);
else if (mode === 'compose') printCompose();
else if (mode === 'images') printImages(parseInt(args[1] || '6', 10) || 6);
else if (mode === 'anomaly') printAnomaly();
else { printAnomaly(); printDay(); printSessions(5); }

if (flag('json')) {
  console.log(JSON.stringify({ root, day, sessions: sessions.map((s) => ({ ...s, kinds: s.kinds })) }));
} else {
  console.log(out.join('\n'));
}
