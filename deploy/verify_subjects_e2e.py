#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P13 端到端验收（生产）：写 subjects → 生成宝玉定妆图 → 用定妆图锚定跑第1镜 → 看锚定日志。

不改动用户方案结构之外的东西；只把第 1 镜重跑一次（会产出 1 张关键帧）。
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
    return subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, universal_newlines=True).stdout.strip()


def req(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + token, "X-Workspace-Id": WS,
        "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print("HTTP %s %s" % (e.code, e.read()[:300]))
        raise


def psql(sql):
    return sh(["sudo -u postgres psql -d weaveora -tAc \"%s\"" % sql.replace('"', '\\"')])


UID = psql("select id from users where email='sysou.com@outlook.com'")
TOK = sh(["python3 /tmp/mint_token.py " + UID])
RV = psql("select approved_revision_id from projects where id='%s'" % PJ)
print("user=%s rev=%s" % (UID[:8], RV[:8]))

# 1) 读方案 → 写 subjects（宝玉：勾选那张素材图）
detail = req("GET", "/projects/%s/revisions/%s" % (PJ, RV), TOK)
plan = detail.get("plan") or {}
subs = plan.get("subjects")
if not subs:
    # 用抽取结果 + 既有 referenceAssets 拼出 subjects
    ex = req("POST", "/projects/%s/revisions/%s/subjects/extract" % (PJ, RV), TOK)
    refs = {}
    for ra in plan.get("referenceAssets") or []:
        refs.setdefault(ra.get("subject") or "", []).append(ra)
    subs = []
    for s in ex.get("subjects") or []:
        hits = refs.get(s["name"]) or []
        if not hits:      # 别名命中也算（可卿 → 秦可卿）
            for alias in s.get("aliases") or []:
                hits = refs.get(alias) or hits
        subs.append({
            "name": s["name"], "kind": s.get("kind", "person"),
            "aliases": s.get("aliases") or [], "enabled": True, "locked": False,
            "refs": [{"assetId": h["assetId"], "checked": True,
                      **({"region": h["region"]} if h.get("region") else {})} for h in hits],
            "portraitAssetId": "", "portraitVersion": 0,
        })
    plan["subjects"] = subs
    legacy = []
    for s in subs:
        for r in s["refs"]:
            if r.get("checked"):
                legacy.append({"assetId": r["assetId"], "subject": s["name"]})
    plan["referenceAssets"] = legacy
    req("PATCH", "/projects/%s/revisions/%s" % (PJ, RV), TOK, {"plan": plan})
    print("已写入 subjects：", [s["name"] for s in subs])
else:
    print("方案已有 subjects：", [s.get("name") for s in subs])

target = next((s for s in subs if s["name"] == "宝玉"), None)
if not target or not target.get("refs"):
    print("FAIL: 找不到带素材图的「宝玉」主体")
    sys.exit(1)
print("宝玉素材图 %d 张（勾选 %d）" % (len(target["refs"]), sum(1 for r in target["refs"] if r.get("checked"))))

# 2) 生成定妆图（kind=portrait）
print("--- 生成宝玉定妆图 ---")
created = req("POST", "/projects/%s/jobs" % PJ, TOK,
              {"revisionId": RV, "kind": "portrait", "subject": "宝玉"})
jid = created[0]["id"]
print("portrait job=%s refs=%d" % (jid[:8], len(created[0]["payload"].get("referenceKeys") or [])))
for i in range(40):
    st = psql("select state from generation_jobs where id='%s'" % jid)
    if st not in ("queued", "running"):
        break
    time.sleep(10)
print("state=%s msg=%s" % (st, psql("select coalesce(left(error_message,200),'-') from generation_jobs where id='%s'" % jid)))
aid = psql("select id from assets where job_id='%s' limit 1" % jid)
print("定妆图资产=%s" % (aid or "-"))
if not aid:
    print("FAIL: 没有产出定妆图")
    sys.exit(1)

# 3) 把定妆图写回方案（选图）
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

# 4) 重跑第 1 镜，验证锚定用的是定妆图
print("--- 用定妆图锚定重跑第 1 镜 ---")
SH = psql("select id from shot_drafts where revision_id='%s' and shot_no=1" % RV)
j2 = req("POST", "/projects/%s/jobs" % PJ, TOK,
         {"revisionId": RV, "shotId": SH, "kind": "still", "count": 1})[0]
print("still job=%s refs=%d subjects=%s primary=%s" % (
    j2["id"][:8], len(j2["payload"].get("referenceKeys") or []),
    j2["payload"].get("referenceSubjects"), j2["payload"].get("primarySubject")))
for i in range(40):
    st2 = psql("select state from generation_jobs where id='%s'" % j2["id"])
    if st2 not in ("queued", "running"):
        break
    time.sleep(10)
print("关键帧 state=%s" % st2)
print("\n=== 锚定日志（应出现 宝玉(定妆图)）===")
print(sh(["journalctl -u weaveora-api --since '-6min' --no-pager | grep -E 'refs: 本镜锚定' | tail -3"]))
