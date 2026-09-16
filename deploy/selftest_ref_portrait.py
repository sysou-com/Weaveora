#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""部署后自检（2026-09-16 夜新增的两个行为）：

① 定妆照守门：零参考图的定妆任务**必须被拒**（否则会退化成纯文生图 → 实测出噪声图）。
② 从资产复制成参考图：POST …/assets/{aid}/as-reference 要真的产生一张新的 kind=reference
   （幂等：同一资产再调一次返回同一张）；验完把副本删掉（不影响原件）。

用**一次性项目**（默认 E2E 纸船），自清理；在 VPS 上跑（需 psql + JWT secret）：

    python3 selftest_ref_portrait.py --project "E2E 纸船"
"""
import argparse
import io
import json
import sys

sys.path.insert(0, "/tmp/wv-bf")
import backfill_setting_traits as b  # noqa: E402  复用 token/psql/http 辅助

API = "http://127.0.0.1:8080"
DB = "weaveora"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", default="E2E 纸船")
    ap.add_argument("--api", default=API)
    args = ap.parse_args()

    secret = b.read_env_secret("/etc/weaveora/weaveora-api.env")
    rows = b.psql("select p.id::text, p.workspace_id::text, p.created_by::text, "
                  "coalesce(p.approved_revision_id::text, "
                  "(select r.id::text from prompt_revisions r where r.project_id=p.id order by revision_no desc limit 1)) "
                  "from projects p where p.deleted_at is null and translate(p.title,' '||chr(12288),'') = %s "
                  "order by p.updated_at desc;" % b.sql_str("".join(args.project.split())), DB)
    if not rows or not rows[0][3]:
        print("✗ 找不到项目或方案版本")
        return 1
    pid, ws, uid, rid = rows[0]
    token = b.mint_token(secret, uid)
    base = "%s/api/v1/projects/%s" % (args.api, pid)
    fail = 0

    # ---------- ① 零参考定妆任务必须被拒 ----------
    # 前置：必须找**真正零输入**的人物主体（没绑定妆照 **且** 方案里没有 refs）——
    # 2026-09-16 踩过：只看 portraitAssetId 为空就下结论，结果方案里有 checkedRefs，
    # 后端走 fallback 拿到了输入（不是 bug），我的脚本却报“没拦住”，还白白触发一个真任务。
    print("① 零参考定妆任务守门")
    plan = b.http_json("GET", "%s/revisions/%s" % (base, rid), token, ws)[1]["plan"]
    subs = plan.get("subjects") or []

    def zero_input(s):
        return (s.get("kind") == "person"
                and not (s.get("portraitAssetId") or "").strip()
                and len([r for r in (s.get("refs") or []) if r.get("checked") is not False]) == 0)

    person = next((s for s in subs if zero_input(s)), None)
    if person is None:
        print("   ⚠ 该项目没有“真正零输入”的人物主体，跳过（换一个项目测，或先在 UI 里验）")
    else:
        before = b.psql("select count(*) from generation_jobs where project_id = %s and kind = 'portrait';"
                        % b.sql_str(pid), DB)[0][0]
        body = {"revisionId": rid, "kind": "portrait", "subject": person["name"], "refAssetIds": []}
        try:
            b.http_json("POST", base + "/jobs", token, ws, body)
            print("   ✗ 竟然创建成功了（零参考没被拦）")
            fail += 1
        except RuntimeError as e:
            msg = str(e)
            ok = "没有可用的参考图" in msg
            print("   %s %s 被拒：%s" % ("OK" if ok else "✗", person["name"], msg[:130]))
            fail += 0 if ok else 1
        after = b.psql("select count(*) from generation_jobs where project_id = %s and kind = 'portrait';"
                       % b.sql_str(pid), DB)[0][0]
        if after == before:
            print("   OK 没有新增任务行（拒在创建前，不会浪费 GPU）")
        else:
            print("   ✗ 新增了 %d 个任务行 —— 需要手工取消" % (after - before))
            fail += 1

    # ---------- ② 从资产复制成参考图 ----------
    print("② 从资产复制成参考图（as-reference）")
    ar = b.psql("select id::text, kind from assets where project_id = %s and kind in ('still','portrait') "
                "order by created_at limit 1;" % b.sql_str(pid), DB)
    if not ar:
        print("   ⚠ 该项目没有 still/portrait 资产，跳过")
    else:
        aid, kind = ar[0]
        made = None
        try:
            r1 = b.http_json("POST", "%s/assets/%s/as-reference" % (base, aid), token, ws)[1]
            r2 = b.http_json("POST", "%s/assets/%s/as-reference" % (base, aid), token, ws)[1]
            made = r1.get("id")
            same = r1.get("id") == r2.get("id")
            print("   %s %s → 参考图 %s（kind=%s）；幂等重调返回同一张：%s"
                  % ("OK" if r1.get("kind") == "reference" and same else "✗",
                     kind, (made or "")[:8], r1.get("kind"), same))
            fail += 0 if (r1.get("kind") == "reference" and same) else 1
        finally:
            if made:
                d = b.http_json("POST", base + "/assets/delete", token, ws, {"assetIds": [made]})[1]
                left = b.psql("select count(*) from assets where id = %s;" % b.sql_str(made), DB)[0][0]
                print("   清理：删除副本 → %s，库里剩余 %s 行" % (d, left))

    print("=> %s" % ("PASS" if fail == 0 else "FAIL(%d)" % fail))
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
