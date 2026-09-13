#!/usr/bin/env python3
"""诊断：motion 150 帧却只出 2s —— 是「参数没送到」还是「模型上限」。py3.6 兼容。"""
import json
import subprocess
import urllib.request
import urllib.error


def sh(cmd):
    return subprocess.run(["bash", "-lc", cmd], stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, universal_newlines=True).stdout.strip()


print("=== 1) 引擎设置：视频模型 + 参数 schema ===")
row = sh("sudo -u postgres psql -d weaveora -tAc \"select json_build_object("
         "'engine', video_engine, 'model', video_model, 'params', video_params, "
         "'schemaErr', video_model_schema_error, 'gateway', gateway_refs_max) "
         "from user_engine_settings limit 1\"")
try:
    cfg = json.loads(row)
    print("  engine:", cfg.get("engine"), " model:", cfg.get("model"))
    print("  video_params(用户填的参数):", json.dumps(cfg.get("params"), ensure_ascii=False))
    print("  schemaErr:", cfg.get("schemaErr"))
except Exception as e:
    print("  parse fail:", e, row[:200])

print("\n=== 2) 模型 schema 里与「时长/帧数/分辨率」相关的字段 ===")
sch = sh("sudo -u postgres psql -d weaveora -tAc \"select video_model_schema from user_engine_settings limit 1\"")
try:
    s = json.loads(sch) if sch else {}
    params = s.get("params") or []
    keys = ("frame", "fps", "duration", "length", "second", "resolution", "size", "shift", "step", "cfg", "width", "height", "video")
    hit = 0
    for p in params:
        n = (p.get("name") or "").lower()
        if any(k in n for k in keys):
            hit += 1
            print("  %-22s type=%-8s default=%-8s max=%-6s enum=%s editable=%s group=%s" % (
                p.get("name"), p.get("type"), p.get("default"), p.get("max"),
                p.get("enum"), p.get("userEditable"), p.get("group")))
    print("  (schema 共 %d 个字段，其中相关 %d 个)" % (len(params), hit))
    mm = s.get("mapping") or {}
    print("  mapping 里与时长相关:", {k: v for k, v in mm.items() if k in
                                 ("frames", "duration", "fps", "width", "height", "steps", "cfg", "size")})
except Exception as e:
    print("  parse fail:", e, (sch or "")[:200])

print("\n=== 3) 最近一次 clip 任务实际发出的 payload ===")
pl = sh("sudo -u postgres psql -d weaveora -tAc \"select payload from generation_jobs where kind='clip' "
        "order by created_at desc limit 1\"")
try:
    p = json.loads(pl)
    keep = ("frames", "frames_per_second", "fps", "duration", "duration_sec", "resolution", "sample_shift",
            "width", "height", "aspect_ratio", "segment_index", "segment_count", "params", "model", "modelName")
    for k in keep:
        if k in p:
            v = p[k]
            if k == "params":
                print("  params:", json.dumps({kk: vv for kk, vv in v.items() if vv not in (None, "", 0)} if isinstance(v, dict) else v, ensure_ascii=False)[:400])
            else:
                print("  %-18s = %s" % (k, v))
    extra = [k for k in p.keys() if k not in keep]
    print("  其它字段:", extra[:14])
except Exception as e:
    print("  parse fail:", e, (pl or "")[:200])

print("\n=== 4) 该任务的产出（时长/尺寸） ===")
print(sh("sudo -u postgres psql -d weaveora -P pager=off -c \"select j.kind, j.state, j.created_at::timestamp(0), "
        "a.duration_ms, a.width, a.height, a.storage_key from generation_jobs j "
        "left join assets a on a.project_id=j.project_id and a.kind=j.kind and a.created_at > j.created_at - interval '1 min' "
        "where j.kind='clip' order by j.created_at desc limit 3\""))

print("\n=== 5) 云 worker 日志（该任务的关键行） ===")
print(sh("journalctl -u weaveora-cloud-worker --since '-3 hours' --no-pager 2>/dev/null | "
         "grep -iE 'frames|fps|duration|size|params|clip' | tail -20"))
