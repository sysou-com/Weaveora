#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""既有方案回填：setting{era,notes} + 主体人物档案（P14 数据补齐，2026-09-16）

背景：P14（commit 955b7c2）之前创建的方案里没有 `setting.era`，`subjects[]` 也只有名字，
LLM 与视觉模型只能靠名字猜 → 实测「宝玉被画成女性」。本脚本把已确认的补上。

纪律（见 docs/交接快照-2026-09-16-夜.md §9 / Weaveora.md §7.5.2）：
  * **只补空字段**（默认）：用户已填的值一律不动；`--force` 才覆盖。
  * **走产品接口写入**（`PATCH …/revisions/{rid}/plan/inplace` 写 setting、
    `POST …/revisions/{rid}/subjects/meta` 写档案），不直连改库 —— 与前端手填走同一条路。
  * **幂等**：跑第二遍应输出 `no change`。
  * **写前现读、写后回读**：库里可能正被 UI 编辑，写完全部回读校验并打印。
  * 只补「方案当前生效版本」= 项目 `approved_revision_id`（没有则取最新版本）。

在 VPS 上运行（要用到本机 psql 与 /etc/weaveora/weaveora-api.env 里的 JWT secret）：

    scp deploy/backfill_setting_traits.py deploy/backfill_setting_traits.json root@sysou:/tmp/wv-bf/
    ssh root@sysou 'python3 /tmp/wv-bf/backfill_setting_traits.py --data /tmp/wv-bf/backfill_setting_traits.json --dry-run'
    ssh root@sysou 'python3 /tmp/wv-bf/backfill_setting_traits.py --data /tmp/wv-bf/backfill_setting_traits.json'

参数：--data 数据 JSON ｜ --api 默认 http://127.0.0.1:8080 ｜ --db 默认 weaveora
      --env 默认 /etc/weaveora/weaveora-api.env ｜ --project 只处理某个标题
      --dry-run 只打印 ｜ --force 覆盖已填值
"""

import argparse
import base64
import hashlib
import hmac
import io
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

WS_HEADER = "X-Workspace-Id"
TRAIT_KEYS = ["gender", "age", "height", "build", "personality", "appearance"]

# VPS 的 locale 可能是 C/POSIX（ASCII）——中文打印/读文件都会炸，这里统一成 utf-8
if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")


# ---------------------------------------------------------------- 基础工具

def run(cmd, stdin_text=None):
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE)
    out, err = p.communicate(input=(stdin_text.encode("utf-8") if stdin_text else None))
    return p.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def psql(sql, db):
    """用 postgres 系统账号跑只读查询（SQL 走 stdin，避免引号/中文转义问题）。"""
    rc, out, err = run(["sudo", "-u", "postgres", "psql", "-d", db, "-At", "-F", "\t", "-f", "-"], sql)
    if rc != 0:
        raise RuntimeError("psql 失败：%s" % (err.strip() or out.strip()))
    return [ln.split("\t") for ln in out.splitlines() if ln != ""]


def sql_str(s):
    return "'" + str(s).replace("'", "''") + "'"


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def mint_token(secret, user_id, ttl_sec=1800):
    """与后端 JwtUtil 同口径：HS256，claims = sub(userId) + type=access。"""
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": user_id, "type": "access", "iat": now, "exp": now + ttl_sec}
    signing = b64url(json.dumps(header, separators=(",", ":")).encode()) + "." + \
              b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode("utf-8"), signing.encode("ascii"), hashlib.sha256).digest()
    return signing + "." + b64url(sig)


def http_json(method, url, token, workspace_id, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header(WS_HEADER, workspace_id)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.getcode(), (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        raise RuntimeError("HTTP %s %s → %s %s" % (method, url, e.code, raw[:400]))


def read_env_secret(path):
    with io.open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("WEAVEORA_JWT_SECRET="):
                return line.split("=", 1)[1].strip().strip('"')
    raise RuntimeError("在 %s 里没找到 WEAVEORA_JWT_SECRET" % path)


# ---------------------------------------------------------------- 回填逻辑

def txt(v):
    return "" if v is None else str(v).strip()


def fill(cur, new, force):
    """只补空：库里有值就保留（除非 force）。"""
    cur = txt(cur)
    if cur and not force:
        return cur
    return txt(new)


def find_subject(subjects, name):
    """按本名或别称找主体（与后端 PlanSubjects.isSameSubject 同思路的简化版）。"""
    for s in subjects:
        if s.get("name") == name:
            return s
    for s in subjects:
        if name and name in (s.get("aliases") or []):
            return s
    for s in subjects:
        cur = s.get("name") or ""
        if name and cur and (cur in name or name in cur):
            return s
    return None


def plan_targets(project_title, db):
    # 标题比对忽略空白（含全角空格）——粘贴标题时容易差一个空格
    norm = "".join(project_title.split()).replace("\u3000", "")
    sql = ("select p.id::text, p.workspace_id::text, p.created_by::text, "
           "coalesce(p.approved_revision_id::text, "
           "(select r.id::text from prompt_revisions r where r.project_id = p.id "
           " order by r.revision_no desc limit 1)) "
           "from projects p where p.deleted_at is null "
           "and translate(p.title, ' ' || chr(12288), '') = %s "
           "order by p.updated_at desc;" % sql_str(norm))
    rows = psql(sql, db)
    if not rows or not rows[0][3]:
        return None
    return {"project_id": rows[0][0], "workspace_id": rows[0][1],
            "user_id": rows[0][2], "revision_id": rows[0][3]}


def backfill_one(entry, token, api, db, dry_run=False, force=False):
    title = entry["title"]
    print("\n=== 项目《%s》 ===" % title)
    tgt = plan_targets(title, db)
    if not tgt:
        print("  ⚠ 找不到项目或其方案版本，跳过")
        return 1
    pid, rid, ws = tgt["project_id"], tgt["revision_id"], tgt["workspace_id"]
    base = "%s/api/v1/projects/%s/revisions/%s" % (api, pid, rid)
    _, detail = http_json("GET", base, token, ws)
    plan = detail.get("plan") or {}
    if plan.get("mode") != "video":
        print("  ⚠ 非视频方案（mode=%s），跳过" % plan.get("mode"))
        return 1
    print("  版本 rev=%s 方案=%s" % (detail.get("revisionNo"), plan.get("title")))

    # ---- 1) setting（era / notes）
    setting = plan.get("setting") or {}
    new_setting = {
        "era": fill(setting.get("era"), (entry.get("setting") or {}).get("era"), force),
        "notes": fill(setting.get("notes"), (entry.get("setting") or {}).get("notes"), force),
    }
    set_changed = (new_setting["era"] != txt(setting.get("era"))
                   or new_setting["notes"] != txt(setting.get("notes")))
    if set_changed:
        print("  setting: era=%r notes=%r" % (new_setting["era"], new_setting["notes"][:40] + "…"))
    else:
        print("  setting: 已是最新（era=%r）" % new_setting["era"])

    # ---- 2) 主体档案（只补空，逐字段）
    subs = plan.get("subjects") or []
    patch_subjects = []
    for want in entry.get("subjects") or []:
        cur = find_subject(subs, want.get("name"))
        if cur is None:
            print("  ⚠ 方案里没有主体「%s」，跳过（不新建，避免造错数据）" % want.get("name"))
            continue
        merged = {}
        diffs = []
        for k in TRAIT_KEYS:
            if k not in want:
                continue
            got = fill(cur.get(k), want.get(k), force)
            merged[k] = got
            if got != txt(cur.get(k)):
                diffs.append("%s: %r → %r" % (k, txt(cur.get(k)), got))
        if not merged:
            continue
        if not diffs:
            continue
        print("  %s（%s）: %s" % (cur.get("name"), cur.get("kind"),
                                 "；".join(diffs[:3]) + ("…" if len(diffs) > 3 else "")))
        patch_subjects.append(dict({"name": cur.get("name"), "hasTraits": True}, **merged))

    if dry_run:
        print("  [dry-run] 不写入：setting %s，主体 %d 条"
              % ("有变化" if set_changed else "无变化", len(patch_subjects)))
        return 0

    # ---- 3) 写入：先 setting（整份 plan 就地回写，与 UI「保存」同一条路），再主体档案
    if set_changed:
        plan["setting"] = new_setting
        http_json("PATCH", base + "/plan/inplace", token, ws, {"plan": plan})
        print("  ✓ setting 已写入")
    if patch_subjects:
        http_json("POST", base + "/subjects/meta", token, ws, {"subjects": patch_subjects})
        print("  ✓ 主体档案已写入 %d 条" % len(patch_subjects))

    # ---- 4) 回读校验（顺便暴露「写期间被 UI 改动」的痕迹）
    _, after = http_json("GET", base, token, ws)
    ap = after.get("plan") or {}
    era = (ap.get("setting") or {}).get("era", "")
    print("  校验：era=%r，主体 %d 个" % (era, len(ap.get("subjects") or [])))
    for s in ap.get("subjects") or []:
        if s.get("kind") != "person":
            continue
        have = [k for k in TRAIT_KEYS if txt(s.get(k))]
        print("    %-8s %s" % (s.get("name"), "、".join(have) if have else "⚠ 仍为空"))
    return 0


def portrait_prompt(entry, token, api, db, names):
    """只读校验：让后端用**已写入的档案**渲染定妆照默认提示词。

    这是「档案 → 定妆照提示词」这条链路的免 GPU 验证（含「严重禁止画成女性」的硬句）。
    """
    tgt = plan_targets(entry["title"], db)
    if not tgt:
        print("找不到项目")
        return 1
    base = "%s/api/v1/projects/%s/revisions/%s" % (api, tgt["project_id"], tgt["revision_id"])
    _, detail = http_json("GET", base, token, tgt["workspace_id"])
    subs = (detail.get("plan") or {}).get("subjects") or []
    for name in names:
        s = find_subject(subs, name)
        if s is None:
            print("⚠ 没有主体 %s" % name)
            continue
        url = base + "/portrait-prompt?subject=%s&refCount=1" % urllib.parse.quote(s.get("name"))
        _, res = http_json("GET", url, token, tgt["workspace_id"])
        print("\n──── %s ────" % s.get("name"))
        for k in ("positive", "positivePrompt", "negative", "negativePrompt"):
            if res.get(k):
                print("[%s] %s" % (k, res[k]))
        if not any(res.get(k) for k in ("positive", "positivePrompt")):
            print(json.dumps(res, ensure_ascii=False)[:600])
    return 0


def selftest_guard(api, db, title, secret):
    """部署后自检：验证「保存路径的 setting 继承守卫」（DirectorService.inheritSettingIfAbsent）是否生效。

    步骤（全在一个一次性项目上，末尾自清理）：
      1. 起点必须没有 setting（否则不动，避免洗用户数据）
      2. 写入哨兵 era（PATCH plan/inplace）
      3. 模拟“旧页面草稿”：提交里**没有 setting 键**再 PATCH 一次 → 旧代码会把 era 洗掉，新代码应继承
      4. 清理：提交 setting={}（键在 → 不继承）把哨兵抹掉
    """
    print("=== setting 继承守卫自检（项目：%s）===" % title)
    tgt = plan_targets(title, db)
    if not tgt:
        print("  ✗ 找不到项目")
        return 1
    pid, rid, ws = tgt["project_id"], tgt["revision_id"], tgt["workspace_id"]
    token = mint_token(secret, tgt["user_id"])
    base = "%s/api/v1/projects/%s/revisions/%s" % (api, pid, rid)
    _, d0 = http_json("GET", base, token, ws)
    plan = d0.get("plan") or {}
    if (plan.get("setting") or {}).get("era"):
        print("  ✗ 该项目已有 era（%r）——请换一个干净的一次性项目" % plan["setting"]["era"])
        return 1
    sentinel = "守卫自检·勿用"

    p1 = dict(plan)
    p1["setting"] = {"era": sentinel, "notes": "selftest"}
    http_json("PATCH", base + "/plan/inplace", token, ws, {"plan": p1})
    _, d1 = http_json("GET", base, token, ws)
    step1 = (d1["plan"].get("setting") or {}).get("era") == sentinel
    print("  1) 写入哨兵 era …………………… %s" % ("OK" if step1 else "✗ 写不进"))

    p2 = dict(d1["plan"])
    p2.pop("setting", None)          # ★ 形态①模拟旧草稿：整份 plan 里没有 setting 键
    http_json("PATCH", base + "/plan/inplace", token, ws, {"plan": p2})
    _, d2 = http_json("GET", base, token, ws)
    step2 = (d2["plan"].get("setting") or {}).get("era") == sentinel
    print("  2) 不带 setting 键的保存后 era ……… %s"
          % ("OK（继承住了 = 守卫生效）" if step2 else "✗ 被洗掉了（守卫未生效！）"))

    p2b = dict(d2["plan"])
    p2b["setting"] = {"era": "", "notes": ""}   # ★ 形态②前端手清/旧草稿：字段是空串
    http_json("PATCH", base + "/plan/inplace", token, ws, {"plan": p2b})
    _, d2b = http_json("GET", base, token, ws)
    step2b = (d2b["plan"].get("setting") or {}).get("era") == sentinel
    print("  2b) era 空串的保存后 era …………… %s"
          % ("OK（逐字段补空生效）" if step2b else "✗ 被空串洗掉了"))
    d2 = d2b

    # 清理：★ 不能用“空提交”—— 按设计，空值会被继承回来（这正是守卫的目的）。
    #        一次性测试项目的收尾就直接删键（产品路径不提供“清空年代”，因为 era 是必填语义）。
    psql("update prompt_revisions set schema_json = schema_json - 'setting' where id = %s;"
         % sql_str(rid), db)
    _, d3 = http_json("GET", base, token, ws)
    step3 = not (d3["plan"].get("setting") or {}).get("era")
    print("  3) 清理哨兵（SQL 删键）…………… %s" % ("OK" if step3 else "✗ 没清干净"))

    print("  => %s" % ("PASS" if (step1 and step2 and step2b and step3) else "FAIL"))
    return 0 if (step1 and step2 and step2b and step3) else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--api", default="http://127.0.0.1:8080")
    ap.add_argument("--db", default="weaveora")
    ap.add_argument("--env", default="/etc/weaveora/weaveora-api.env")
    ap.add_argument("--project", default=None)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--print-portrait", default=None,
                    help="只读校验：打印这些主体的定妆照默认提示词（逗号分隔，如 宝玉,可卿）")
    ap.add_argument("--selftest-guard", default=None, metavar="项目标题",
                    help="部署后自检：验证保存路径的 setting 继承守卫（用一次性项目，自清理）")
    args = ap.parse_args()

    with io.open(args.data, "r", encoding="utf-8") as f:
        data = json.load(f)
    secret = read_env_secret(args.env)

    todo = [p for p in data["projects"]
            if not args.project or "".join(p["title"].split()).replace("\u3000", "") ==
            "".join(args.project.split()).replace("\u3000", "")]

    if args.print_portrait:
        entry = todo[0] if todo else None
        if entry is None:
            print("没有匹配的项目")
            return 1
        tgt = plan_targets(entry["title"], args.db)
        return portrait_prompt(entry, mint_token(secret, tgt["user_id"]), args.api, args.db,
                               [n.strip() for n in args.print_portrait.split(",") if n.strip()])

    if args.selftest_guard:
        return selftest_guard(args.api, args.db, args.selftest_guard, secret)

    print("回填计划：%d 个项目%s" % (len(todo), "（dry-run）" if args.dry_run else ""))

    bad = 0
    for entry in todo:
        tgt = plan_targets(entry["title"], args.db)
        if not tgt:
            print("\n=== 项目《%s》 ===\n  ⚠ 找不到，跳过" % entry["title"])
            bad += 1
            continue
        token = mint_token(secret, tgt["user_id"])
        try:
            bad += backfill_one(entry, token, args.api, args.db, args.dry_run, args.force)
        except Exception as e:
            bad += 1
            print("  ✗ 失败：%s" % e)
    print("\n完成：%d 个项目，%d 个有问题" % (len(todo), bad))
    return 0


if __name__ == "__main__":
    sys.exit(main())
