# -*- coding: utf-8 -*-
"""标准流程编排（后台跑，进度写 /tmp/pipeline.log，禁止长挂）：
   关键帧(3,4,6) → motion(3,4,6) → 对口型(3,4,6) → 合成成片。
   配音已现成（每镜都有），不重跑。
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, '/tmp')
import wv  # noqa: E402

LOG = '/tmp/pipeline.log'
SHOTS = [3, 4, 6]


def log(m):
    line = "[%s] %s" % (time.strftime('%H:%M:%S'), m)
    print(line, flush=True)
    with open(LOG, 'a', encoding='utf-8') as fh:
        fh.write(line + '\n')


def psql(sql):
    env = dict(l.strip().split('=', 1) for l in open('/etc/weaveora/weaveora-api.env', encoding='utf-8')
               if '=' in l and not l.strip().startswith('#'))
    os.environ['PGPASSWORD'] = env.get('WEAVEORA_DB_PASSWORD', '')
    r = subprocess.run(['psql', '-h', '127.0.0.1', '-U', 'weaveora', '-d', 'weaveora', '-tA', '-c', sql],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return r.stdout.decode('utf-8', 'replace').strip()


def busy(kind=None):
    where = "state in ('queued','running')"
    if kind:
        where += " and kind='%s'" % kind
    out = psql("select string_agg(kind || '-' || coalesce(payload->>'shot_no','-') || ':' || state || progress, '  ') "
               "from generation_jobs where " + where + ";")
    return out


def wait_idle(kind, label, timeout=3600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        cur = busy(kind)
        if not cur:
            log("%s 完成" % label)
            return True
        time.sleep(20)
    log("%s 超时（仍在跑：%s）" % (label, busy(kind)))
    return False


def revision_id():
    revs = wv.req('GET', '/projects/%s/revisions' % wv.PID)
    rev = ([r for r in revs if r.get('approved')] or revs)[0]
    return rev.get('id')


def create(kind, shots):
    rid = revision_id()
    r = wv.req('POST', '/projects/%s/jobs' % wv.PID, {"revisionId": rid, "kind": kind, "shotNos": shots})
    for j in (r or []):
        log("  建 %s shot=%s id=%s" % (kind, (j.get('payload') or {}).get('shot_no'), j.get('id')))


def main():
    log("=== 标准流程编排开始（镜 %s）===" % SHOTS)
    wait_idle('still', '① 关键帧')
    create('clip', SHOTS)
    wait_idle('clip', '② motion')
    create('lipsync', SHOTS)
    wait_idle('lipsync', '③ 对口型')
    try:
        rid = revision_id()
        a = wv.req('POST', '/projects/%s/render' % wv.PID, {"revisionId": rid, "transition": "cut"})
        log("④ 合成成片 → asset=%s kind=%s" % (a.get('id'), a.get('kind')))
    except Exception as e:
        log("④ 合成失败：%s" % str(e)[:300])
    log("=== 编排结束 ===")


if __name__ == '__main__':
    main()
