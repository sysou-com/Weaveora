#!/bin/bash
# =============================================================================
# FLUX.2 [dev] vs Qwen-Image 出图 A/B（**同 prompt / 同 seed / 同尺寸 / 同参考图**）
#
# 为什么这么写：所有注入都走**生产注入器**（diag_flux2_smoke.py 直接 import comfy_client），
# 提示词前缀也走同一真源 `_image_edit_prefix` ⇒ 两个模型的对照只差"模型本身"，不差流程。
#
# 用法（在 GPU 盒上，后台跑）：
#   setsid nohup bash /opt/weaveora/diag/diag_flux2_ab.sh \
#       --prompt 'a young woman in a dark red silk hanfu ...' \
#       --ref 宝玉=/path/baoyu.png --ref 可卿=/path/keqing.png \
#       > /opt/weaveora/logs/ab_flux2.log 2>&1 </dev/null &
#   进度：tail -n 5 /opt/weaveora/logs/ab_flux2.log
#   产出：$OUTDIR/{*.json,*.log} + ComfyUI/output/ 里的图；末尾自动跑 wv_faceid 做身份验收
#
# 矩阵（6 次生成）：
#   qwen-t2i 40步/cfg4 ｜ flux2-t2i 20步/g4 ｜ flux2-t2i+Turbo 8步
#   qwen-edit 40步/cfg4 ｜ flux2-edit 20步/g4 ｜ flux2-edit+Turbo 8步
#   （给了 --ref 才跑 edit 三条）
# =============================================================================
set -u
PROBE=/opt/weaveora/diag/diag_flux2_smoke.py
WF=/opt/weaveora/workflows
PY=/usr/bin/python3
PROMPT=""; SIZE=1024x1024; SEED=20260923; OUTDIR=/opt/weaveora/diag_out/ab_flux2
REFS=(); NAMES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --prompt) PROMPT="$2"; shift 2;;
    --prompt-file) PROMPT="$(cat "$2")"; shift 2;;
    --size) SIZE="$2"; shift 2;;
    --seed) SEED="$2"; shift 2;;
    --outdir) OUTDIR="$2"; shift 2;;
    --ref) NAMES+=("${2%%=*}"); REFS+=("${2#*=}"); shift 2;;
    *) echo "未知参数 $1"; exit 2;;
  esac
done
mkdir -p "$OUTDIR"
[ -n "$PROMPT" ] || { echo "!! 必须给 --prompt 或 --prompt-file"; exit 2; }
REFP=""; for r in "${REFS[@]:-}"; do [ -n "$r" ] && REFP="$REFP --ref $r"; done

log(){ echo "[$(date +%H:%M:%S)] $*"; }
run(){  # run <tag> <wf> <mode> <steps> [extra...]
  local tag="$1" wf="$2" mode="$3" steps="$4"; shift 4
  log "=== $tag（$mode / $steps 步）..."
  $PY "$PROBE" --mode "$mode" --tag "$tag" --wf "$wf" --allow-nonflux2 \
      --size "$SIZE" --seed "$SEED" --steps "$steps" --prompt "$PROMPT" \
      --json-out "$OUTDIR/$tag.json" "$@" > "$OUTDIR/$tag.log" 2>&1
  local rc=$?
  $PY - "$OUTDIR/$tag.json" "$tag" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("   !! %s 无 JSON 结果" % sys.argv[2]); raise SystemExit
for r in d:
    print("   %s 耗时=%ss ok=%s 输出=%s %s" % (r["tag"], r["seconds"], r["ok"],
          ",".join(r.get("outputs") or []), r.get("error", "")[:200]))
PY
  return $rc
}

log "A/B 开始 prompt=${#PROMPT}字 size=$SIZE seed=$SEED refs=${#REFS[@]} 输出目录=$OUTDIR"
log "--- 1) 文生图（定妆照/无参考帧）---"
run qwen-t2i   "$WF/qwen_image_txt2img_film_api.json" txt2img 40 || log "!! qwen-t2i 失败（看 $OUTDIR/qwen-t2i.log）"
run flux2-t2i-20    "$WF/flux2_dev_txt2img_api.json"  txt2img 20
run flux2-t2i-turbo8 "$WF/flux2_dev_txt2img_api.json" txt2img 8 --lora Flux_2-Turbo-LoRA_comfyui.safetensors

if [ "${#REFS[@]}" -gt 0 ]; then
  log "--- 2) 关键帧 edit（多参考）---"
  run qwen-edit   "$WF/qwen_image_edit_api.json"  edit 40 $REFP || log "!! qwen-edit 失败"
  run flux2-edit-20    "$WF/flux2_dev_edit_api.json" edit 20 $REFP
  run flux2-edit-turbo8 "$WF/flux2_dev_edit_api.json" edit 8 --lora Flux_2-Turbo-LoRA_comfyui.safetensors $REFP
fi

log "--- 3) 汇总 ---"
$PY - "$OUTDIR" <<'PY'
import glob, json, os, sys
d = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(d, "*.json"))):
    try:
        for r in json.load(open(f)):
            rows.append(r)
    except Exception:
        pass
print("%-22s %-8s %-6s %-6s %-24s %s" % ("tag", "mode", "steps", "秒", "lora", "输出"))
for r in rows:
    print("%-22s %-8s %-6s %-6s %-24s %s" % (r["tag"], r["mode"], r["steps"], r["seconds"],
          (r.get("lora") or "-")[:24], ",".join(r.get("outputs") or []) or ("ERR: " + (r.get("error") or "")[:40])))
PY

if [ "${#REFS[@]}" -gt 0 ] && [ -f /opt/weaveora/diag/wv_faceid.py ]; then
  log "--- 4) 身份验收（wv_faceid：谁在哪 / 像不像）---"
  REFARGS=""; for i in "${!REFS[@]}"; do REFARGS="$REFARGS --ref ${NAMES[$i]}=${REFS[$i]}"; done
  for tag in qwen-edit flux2-edit-20 flux2-edit-turbo8; do
    img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_*_00001_.png 2>/dev/null | head -1)
    case "$tag" in
      qwen-edit)  img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_qwen_edit*_00001_.png 2>/dev/null | head -1);;
      flux2-edit-20)    img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_flux2_edit_00001_.png 2>/dev/null | head -1);;
      flux2-edit-turbo8) img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_flux2_edit_00001_.png 2>/dev/null | head -1);;
    esac
    [ -n "$img" ] || { log "   ($tag 找不到对应输出图，跳过)"; continue; }
    log "   $tag -> $(basename "$img")"
    /opt/weaveora/ComfyUI/venv/bin/python /opt/weaveora/diag/wv_faceid.py --img "$img" $REFARGS \
        > "$OUTDIR/faceid_$tag.log" 2>&1
    tail -n 12 "$OUTDIR/faceid_$tag.log"
  done
fi
log "A/B 结束（结果目录 $OUTDIR）"
