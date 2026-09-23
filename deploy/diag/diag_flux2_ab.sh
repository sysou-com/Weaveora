#!/bin/bash
# =============================================================================
# FLUX.2 [dev] vs Qwen-Image 出图 A/B（**同 prompt / 同 seed / 同尺寸 / 同参考图**）
#
# 为什么这么写：所有注入都走**生产注入器**（diag_flux2_smoke.py 直接 import comfy_client），
# 提示词前缀也走同一真源 `_image_edit_prefix` ⇒ 两个模型的对照只差"模型本身"，不差流程。
#
# ★ 措辞适配（公平对照的必要条件）：生产正词里写的是 Qwen 的口径「Picture N (imageN)」
#   （TextEncodeQwenImageEditPlus 的约定），而 FLUX.2 的参考图机制是 ReferenceLatent、
#   官方模板口径是 "Reference Image N" —— 不换口径就等于让 FLUX.2 去找一个不存在的
#   "Picture 2"（映射全丢），对照就不公平。本脚本自动派生一份 FLUX.2 措辞的正词。
#
# 用法（在 GPU 盒或 VPS 上后台跑；VPS 上要加 --url 指向 GPU 网关）：
#   setsid nohup bash /opt/weaveora/diag/diag_flux2_ab.sh \
#       --prompt-file /opt/weaveora/diag_out/ab_flux2/prompt_qwen.txt \
#       --ref 宝玉=/path/baoyu.png --ref 可卿=/path/keqing.png --ref 警幻=/path/jinghuan.png \
#       --size 1664x928 --seed 12345 --skip-t2i \
#       > /opt/weaveora/logs/ab_flux2.log 2>&1 </dev/null &
#   进度：tail -n 5 /opt/weaveora/logs/ab_flux2.log
#
# 矩阵（默认为 6 次生成；--skip-t2i 只跑 edit 三条）：
#   qwen-t2i 40步/cfg4 ｜ flux2-t2i 20步/g4 ｜ flux2-t2i+Turbo 8步
#   qwen-edit 40步/cfg4 ｜ flux2-edit 20步/g4 ｜ flux2-edit+Turbo 8步
# =============================================================================
set -u
PROBE=/opt/weaveora/diag/diag_flux2_smoke.py
WF=/opt/weaveora/workflows
PY=/usr/bin/python3
URL=""
PROMPT=""; SIZE=1024x1024; SEED=20260923; OUTDIR=/opt/weaveora/diag_out/ab_flux2; SKIP_T2I=0
REFS=(); NAMES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --prompt) PROMPT="$2"; shift 2;;
    --prompt-file) PROMPT="$(cat "$2")"; shift 2;;
    --size) SIZE="$2"; shift 2;;
    --seed) SEED="$2"; shift 2;;
    --outdir) OUTDIR="$2"; shift 2;;
    --url) URL="$2"; shift 2;;
    --skip-t2i) SKIP_T2I=1; shift 1;;
    --ref) NAMES+=("${2%%=*}"); REFS+=("${2#*=}"); shift 2;;
    *) echo "未知参数 $1"; exit 2;;
  esac
done
mkdir -p "$OUTDIR"
[ -n "$PROMPT" ] || { echo "!! 必须给 --prompt 或 --prompt-file"; exit 2; }
URLARG=""; [ -n "$URL" ] && URLARG="--url $URL"
REFP=""; for r in "${REFS[@]:-}"; do [ -n "$r" ] && REFP="$REFP --ref $r"; done

# ★ FLUX.2 措辞适配（Picture N (imageN) → 参考图 N）
PROMPT_F2="$(printf '%s' "$PROMPT" | sed -E 's/Picture ([0-9]+) \(image\1\)/参考图 \1/g; s/(^|[^A-Za-z])image([0-9]+)/\1参考图 \2/g')"
printf '%s' "$PROMPT" > "$OUTDIR/prompt_qwen.txt"
if [ "$PROMPT_F2" != "$PROMPT" ]; then
  printf '%s' "$PROMPT_F2" > "$OUTDIR/prompt_flux2.txt"
  echo "[ab] FLUX.2 措辞适配：Picture N (imageN) → 参考图 N（$(printf '%s' "$PROMPT" | wc -c) → $(printf '%s' "$PROMPT_F2" | wc -c) 字节）"
else
  echo "[ab] 正词里没有 Qwen 口径的 Picture/image 编号，FLUX.2 侧用原文"
fi

log(){ echo "[$(date +%H:%M:%S)] $*"; }
summarize(){  # summarize <json>
  $PY - "$1" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("   !! %s 无 JSON 结果（%s）" % (sys.argv[1], e)); raise SystemExit
for r in d:
    print("   %-22s 耗时=%-8s ok=%-5s 输出=%s %s" % (r["tag"], r["seconds"], r["ok"],
          ",".join(r.get("outputs") or []), (r.get("error") or "")[:160]))
PY
}
run(){  # run <tag> <wf> <mode> <steps> <prompt> [extra...]
  local tag="$1" wf="$2" mode="$3" steps="$4" ptext="$5"; shift 5
  log "=== $tag（$mode / $steps 步）..."
  $PY -u "$PROBE" --mode "$mode" --tag "$tag" --wf "$wf" --allow-nonflux2 $URLARG \
      --size "$SIZE" --seed "$SEED" --steps "$steps" --prompt "$ptext" \
      --json-out "$OUTDIR/$tag.json" "$@" > "$OUTDIR/$tag.log" 2>&1
  summarize "$OUTDIR/$tag.json"
}

log "A/B 开始 size=$SIZE seed=$SEED refs=${#REFS[@]} 输出目录=$OUTDIR url=${URL:-本机8001}"
if [ "$SKIP_T2I" = "0" ]; then
  log "--- 1) 文生图（定妆照 / 无参考帧）---"
  run qwen-t2i         "$WF/qwen_image_txt2img_film_api.json" txt2img 40 "$PROMPT"    || log "!! qwen-t2i 失败（看 $OUTDIR/qwen-t2i.log）"
  run flux2-t2i-20     "$WF/flux2_dev_txt2img_api.json"       txt2img 20 "$PROMPT_F2"
  run flux2-t2i-turbo8 "$WF/flux2_dev_txt2img_api.json"       txt2img 8  "$PROMPT_F2" --lora Flux_2-Turbo-LoRA_comfyui.safetensors
else
  log "--- 1) 文生图：--skip-t2i 跳过 ---"
fi

if [ "${#REFS[@]}" -gt 0 ]; then
  log "--- 2) 关键帧 edit（多参考）---"
  run qwen-edit         "$WF/qwen_image_edit_api.json" edit 40 "$PROMPT"    $REFP || log "!! qwen-edit 失败"
  run flux2-edit-20     "$WF/flux2_dev_edit_api.json"  edit 20 "$PROMPT_F2" $REFP
  run flux2-edit-turbo8 "$WF/flux2_dev_edit_api.json"  edit 8  "$PROMPT_F2" --lora Flux_2-Turbo-LoRA_comfyui.safetensors $REFP
fi

log "--- 3) 汇总 ---"
$PY - "$OUTDIR" <<'PY'
import glob, json, os, sys
d = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(d, "*.json"))):
    if os.path.basename(f) == "smoke.json":
        continue
    try:
        for r in json.load(open(f)):
            rows.append(r)
    except Exception:
        pass
print("%-22s %-8s %-6s %-8s %-24s %s" % ("tag", "mode", "steps", "秒", "lora", "输出"))
for r in rows:
    print("%-22s %-8s %-6s %-8s %-24s %s" % (r["tag"], r["mode"], r["steps"], r["seconds"],
          (r.get("lora") or "-")[:24], ",".join(r.get("outputs") or []) or ("ERR: " + (r.get("error") or "")[:40])))
PY

if [ "${#REFS[@]}" -gt 0 ] && [ -f /opt/weaveora/diag/wv_faceid.py ]; then
  log "--- 4) 身份验收（wv_faceid：谁在哪 / 像不像）---"
  REFARGS=""; for i in "${!REFS[@]}"; do REFARGS="$REFARGS --ref ${NAMES[$i]}=${REFS[$i]}"; done
  for tag in qwen-edit flux2-edit-20 flux2-edit-turbo8; do
    img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_*_00001_.png 2>/dev/null | head -1)
    case "$tag" in
      qwen-edit)         img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_qwen_edit*_00001_.png 2>/dev/null | head -1);;
      flux2-edit-20|flux2-edit-turbo8) img=$(ls -t /opt/weaveora/ComfyUI/output/weaveora_flux2_edit_00001_.png 2>/dev/null | head -1);;
    esac
    [ -n "$img" ] || { log "   ($tag 找不到对应输出图，跳过)"; continue; }
    log "   $tag -> $(basename "$img")"
    /opt/weaveora/ComfyUI/venv/bin/python /opt/weaveora/diag/wv_faceid.py --img "$img" $REFARGS \
        > "$OUTDIR/faceid_$tag.log" 2>&1
    tail -n 12 "$OUTDIR/faceid_$tag.log"
  done
fi
log "A/B 结束（结果目录 $OUTDIR）"
