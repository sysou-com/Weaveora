# -*- coding: utf-8 -*-
"""把「取消控制」接进 worker（配合 API 的 progress 返回 cancelRequested）：
   ① execute_job 开始时设置 comfy_client.CANCEL_CHECK（每轮轮询/每次 progress 都会问一次）；
   ② progress 上报统一走 _prog(jid)，记录"最近进度"，取消检查复用它（进度不回退）；
   ③ 收到 JobCancelled → 明确记日志并按失败回执（API 侧已 cancelRequested → 落 cancelled）。
"""
import io
import re
import shutil
import time

p = 'worker/stub_worker.py'
s = io.open(p, encoding='utf-8').read()
orig = s

helpers = '''

# ── 取消控制（2026-09-16）──────────────────────────────────────────────────
# 背景：取消原先只改 API 的 state，worker 不知情 → 继续等 ComfyUI 跑完（24 步 720p 100+ 秒），
# 而 ComfyUI 串行 → 僵尸 prompt 把队列堵死（表现为「新任务一直 queued」「一张关键帧等好几分钟」）。
# 现在：API 的 /progress 会回 `cancelRequested`；worker 每轮轮询都问一次，取消即 /interrupt 并退出。
_LAST_PROGRESS = {"p": 10}


def _prog(jid):
    """统一进度上报：记录最近进度（供取消检查复用，避免进度回退）。"""
    def fn(p, stage):
        try:
            _LAST_PROGRESS["p"] = max(int(p or 0), _LAST_PROGRESS["p"])
        except (TypeError, ValueError):
            pass
        return _req("POST", "/internal/jobs/%s/progress" % jid,
                    {"progress": _LAST_PROGRESS["p"], "stage": stage})
    return fn


def _cancel_check(jid):
    """给 comfy_client 的钩子：返回 True = 本任务已被取消。"""
    def fn():
        try:
            _st, body = _req("POST", "/internal/jobs/%s/progress" % jid,
                             {"progress": _LAST_PROGRESS["p"], "stage": "cancel_check"}, timeout=15)
            return bool((body or {}).get("cancelRequested"))
        except Exception:
            return False
    return fn

'''

if '_cancel_check' in s:
    print('SKIP: 已接过取消控制')
else:
    anchor = 'def execute_job(job):'
    assert anchor in s, '锚点未找到'
    s = s.replace(anchor, helpers.lstrip('\n') + '\n' + anchor, 1)

    # 设置/清理 CANCEL_CHECK（放在 jid 取到之后）
    old_head = '''    jid = job["jobId"]
    payload = job.get("payload") or {}
    kind = payload.get("kind") or "still"'''
    new_head = '''    jid = job["jobId"]
    _LAST_PROGRESS["p"] = 10
    try:
        import comfy_client as _cc_cancel
        _cc_cancel.CANCEL_CHECK = _cancel_check(jid)
    except Exception:
        pass
    payload = job.get("payload") or {}
    kind = payload.get("kind") or "still"'''
    assert old_head in s, '头部锚点未找到'
    s = s.replace(old_head, new_head, 1)

    # 统一 progress 上报（两种写法都换）
    s = re.sub(r'progress_fn=lambda p, s: _req\(\s*"POST", "/internal/jobs/%s/progress" % jid,\s*'
               r'\{"progress": p, "stage": s\}\)', 'progress_fn=_prog(jid)', s)
    s = re.sub(r'progress_fn=lambda p, st: _req\(\s*"POST", "/internal/jobs/%s/progress" % jid,\s*'
               r'\{"progress": p, "stage": st\}\)', 'progress_fn=_prog(jid)', s)
    s = re.sub(r'progress_fn=lambda p, s: _req\(\s*"POST", "/internal/jobs/%s/progress" % jid,\s*'
               r'\{"progress": p, "stage": s\}\)', 'progress_fn=_prog(jid)', s)

    io.open(p, 'w', encoding='utf-8').write(s)
    print('OK: 已接入取消控制（helpers + CANCEL_CHECK + progress 统一上报）')

# 统计替换了多少处 progress_fn
n_lambda = len(re.findall(r'progress_fn=lambda', s))
print('剩余 lambda 形式 progress_fn：%d 处' % n_lambda)
