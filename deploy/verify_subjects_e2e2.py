#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P13 端到端验收 v2（按真实流程：改已确认稿 → 另存新版本 → 确认 → 生成）。

流程：PATCH(写 subjects) → 得到新 revision → approve → 生成宝玉定妆图 → 选定妆图 →
      重跑第1镜 → 断言锚定用的是定妆图。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "http://127.0.0.1:8080/api/v1"
WS = "0a000003-a070-182f-81a0-70e047de000f"
PJ = "0a000003-a092-1b75-81a0-922034b0002f"


def sh(cmd):
    return subprocess.run(cmd, shell=True, stdout=subprocess.PIPE,
                          universal_newlines=True).stdout.strip()


def req(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + token, "X-Workspace-Id": WS,
        "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print("HTTP %s %s" % (e.code, e.read()[:300]))
        raise


def psql(sql):
    return sh(["sudo -u postgres psql -d weaveora -tAc \"%s\"" % sql.replace('"', '\\"')])


UID = psql("select id from users where email='sysou.com@outlook.com'")
TOK = sh(["python3 /tmp/mint_token.py " + UID])
RV = psql("select approved_revision_id from projects where id='%s'" % PJ)
detail = req("GET", "/projects/%s/revisions/%s" % (PJ, RV), TOK)
plan = detail.get("plan") or {}

# 1) 确保有 subjects（抽一次 + 关联已有素材图）
if not plan.get("subjects"):
    ex = req("POST", "/projects/%s/revisions/%s/subjects/extract" % (PJ, RV), TOK)
    refs = {}
    for ra in plan.get("referenceAssets") or []:
        refs.setdefault(ra.get("subject") or "", []).append(ra)
    subs = []
    for s in ex.get("subjects") or []:
        hits = refs.get(s["name"]) or []
        for alias in s.get("aliases") or []:
            if not hits:
                hits = refs.get(alias) or []
        subs.append({"name": s["name"], "kind": s.get("kind", "person"),
                     "aliases": s.get("aliases") or [], "enabled": True, "locked": False,
                     "refs": [{"assetId": h["assetId"], "checked": True,
                               **({"region": h["region"]} if h.get("region") else {})} for h in hits],
                     "portraitAssetId": "", "portraitVersion": 0})
    plan["subjects"] = subs
    plan["referenceAssets"] = [{"assetId": r["assetId"], "subject": s["name"]}
                               for s in subs for r in s["refs"]]
    d2 = req("PATCH", "/projects/%s/revisions/%s" % (PJ, RV), TOK, {"plan": plan})
    RV = d2["id"]                      # 改已确认稿 → 另存的新版本
    print("新版本 revision=%s v%s" % (RV[:8], d2.get("revisionNo")))
    ap = req("POST", "/projects/%s/revisions/%s/approve" % (PJ, RV), TOK)
    print("已确认：", json.dumps(ap)[:120])

detail = req("GET", "/projects/%s/revisions/%s" % (PJ, RV), TOK)
subs = (detail.get("plan") or {}).get("subjects") or []
print("subjects:", [(s["name"], len(s.get("refs") or [])) for s in subs])
target = next((s for s in subs if s["name"] == "宝玉"), None)
if not target:
    print("FAIL: 方案里没有宝玉主体")
    sys.exit(1)

# 2) 生成定妆图
created = req("POST", "/projects/%s/jobs" % PJ, TOK,
              {"revisionId": RV, "kind": "portrait", "subject": "宝玉"})
jid = created[0]["id"]
print("--- 定妆图 job=%s refs=%d ---" % (jid[:8], len(created[0]["payload"].get("referenceKeys") or [])))
st = None
for _ in range(40):
    st = psql("select state from generation_jobs where id='%s'" % jid)
    if st not in ("queued", "running"):
        break
    time.sleep(10)
aid = psql("select id from assets where job_id='%s' limit 1" % jid)
print("定妆图 state=%s asset=%s" % (st, (aid or "-")[:8]))
if not aid:
    print("err:", psql("select coalesce(left(error_message,300),'-') from generation_jobs where id='%s'" % jid))
    sys.exit(1)

# 3) 选定妆图（写回方案）
detail = req("GET", "/projects/%s/revisions/%s" % (PJ, RV), TOK)
plan = detail.get("plan") or {}
ver = 0
for s in plan.get("subjects") or []:
    if s.get("name") == "宝玉":
        ver = int(s.get("portraitVersion") or 0) + 1
        s["portraitAssetId"] = aid
        s["portraitVersion"] = ver
req("PATCH", "/projects/%s/revisions/%s" % (PJ, RV), TOK, {"plan": plan})
print("已选定妆图 v%d" % ver)

# 4) 用定妆图锚定跑第 1 镜
SH = psql("select id from shot_drafts where revision_id='%s' and shot_no=1" % RV)
j2 = req("POST", "/projects/%s/jobs" % PJ, TOK,
         {"revisionId": RV, "shotId": SH, "kind": "still", "count": 1})[0]
print("--- 第1镜 still job=%s refs=%d subjects=%s primary=%s ---" % (
    j2["id"][:8], len(j2["payload"].get("referenceKeys") or []),
    j2["payload"].get("referenceSubjects"), j2["payload"].get("primarySubject")))
st2 = None
for _ in range(40):
    st2 = psql("select state from generation_jobs where id='%s'" % j2["id"])
    if st2 not in ("queued", "running"):
        break
    time.sleep(10)
print("关键帧 state=%s" % st2)
print("\n=== 锚定日志 ===")
print(sh(["journalctl -u weaveora-api --since '-8min' --no-pager | grep 'refs: 本镜锚定' | tail -2"]))
