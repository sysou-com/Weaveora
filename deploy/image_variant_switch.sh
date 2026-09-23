#!/usr/bin/env bash
# =============================================================================
# 图片引擎（出图通路）一键切换：Qwen-Image ⇄ FLUX.2 [dev] —— **带回读**
#
# 为什么单独有脚本（而不是手敲 SQL）：CLAUDE.md §四 记过"平台配置页会把旧快照写回"的事故
#   —— 改完不校验 = 以为切了其实没切。本脚本改完**立刻回读**并把 6 个关键字段打出来。
#
# 用法：
#   bash deploy/image_variant_switch.sh show          # 只读回读（不改）
#   bash deploy/image_variant_switch.sh flux2         # → FLUX.2 质量档（20 步 / guidance 4）
#   bash deploy/image_variant_switch.sh flux2-turbo   # → FLUX.2 加速档（8 步 + Turbo LoRA）
#   bash deploy/image_variant_switch.sh qwen          # → 回滚到现役 Qwen-Image（40 步 / cfg 4）
#
# 说明：
#   · 只动 `user_engine_settings.services.image`（引擎配置页存的就是它），**不动**工作流文件/权重；
#   · worker 侧 env 只作**回退默认**（DB 有值就覆盖），所以切这里即刻生效、不必重启 worker；
#   · 「三处成对」纪律：Java 默认值 / worker env 由 deploy 脚本各自维护；本脚本只管**运行时生效值**。
# =============================================================================
set -euo pipefail

VARIANT="${1:-show}"
# ★ SSH 目标默认用 ~/.ssh/config 的别名 `sysou` —— 别名里写死了 IdentityFile；
#   直写 `root@sysou.com` 会走默认身份文件 ⇒ `Permission denied (publickey)`（本脚本已踩过）。
HOST="${WEAVEORA_WORKER_HOST:-sysou}"
KEY="${WEAVEORA_SSH_KEY:-}"
SSH=(ssh)
[ -n "$KEY" ] && SSH+=(-i "$KEY" -o IdentitiesOnly=yes)
EMAIL="${WEAVEORA_USER_EMAIL:-sysou.com@outlook.com}"
WF=/opt/weaveora/workflows

# ★ SQL 一律走 **stdin 管道**（`psql -f -`），不拼进 ssh 的命令行。
#   为什么：JSON patch 里带双引号，若拼进本地双引号包裹的 ssh 参数里，
#   双引号会被本地 shell 吃掉 → 远端收到 `{workflow...}` → `invalid input syntax for type json`（本脚本已踩）。
Q() { printf '%s\n' "$1" | "${SSH[@]}" "$HOST" "sudo -u postgres psql -d weaveora -At -f -"; }

UID_="$(Q "select id from users where email='${EMAIL}';" | tr -d '\r' | head -1)"
[ -n "$UID_" ] || { echo "!! 找不到用户 ${EMAIL} 的 user_id"; exit 1; }

show() {
  echo "== 当前 services.image（用户 ${EMAIL} / ${UID_}）=="
  Q "select jsonb_pretty(services->'image') from user_engine_settings where user_id='${UID_}'::uuid;"
}

case "$VARIANT" in
  show) show; exit 0;;
  qwen)
    PATCH='{"workflow":"'"$WF"'/qwen_image_txt2img_film_api.json","editWorkflow":"'"$WF"'/qwen_image_edit_api.json","img2imgWorkflow":"'"$WF"'/qwen_image_img2img_api.json","model":null,"steps":40,"cfg":4.0,"denoise":1.0,"lora":null,"loraStrength":null,"loraCfg":null}'
    ;;
  flux2)
    # 质量档：官方口径 20 步 / guidance 4（worker 把 cfg 映射到 FluxGuidance）；不挂 LoRA
    PATCH='{"workflow":"'"$WF"'/flux2_dev_txt2img_api.json","editWorkflow":"'"$WF"'/flux2_dev_edit_api.json","img2imgWorkflow":"'"$WF"'/flux2_dev_img2img_api.json","model":null,"steps":20,"cfg":4.0,"denoise":1.0,"lora":null,"loraStrength":null,"loraCfg":null}'
    ;;
  flux2-turbo)
    # 加速档：8 步 + Turbo LoRA（官方量化档模板的工作点：steps 8 / guidance 仍为 4）
    PATCH='{"workflow":"'"$WF"'/flux2_dev_txt2img_api.json","editWorkflow":"'"$WF"'/flux2_dev_edit_api.json","img2imgWorkflow":"'"$WF"'/flux2_dev_img2img_api.json","model":null,"steps":8,"cfg":4.0,"denoise":1.0,"lora":"Flux_2-Turbo-LoRA_comfyui.safetensors","loraStepsFlux2":8,"loraCfgFlux2":4.0}'
    ;;
  *) echo "用法：$0 {show|qwen|flux2|flux2-turbo}"; exit 2;;
esac

echo "== 切到 ${VARIANT} =="
Q "update user_engine_settings set services = jsonb_set(services, '{image}', ((services->'image') || '${PATCH}'::jsonb)), updated_at = now() where user_id='${UID_}'::uuid;" >/dev/null
echo "   已写入；回读确认（★ 这一步就是防"平台页把旧快照写回"）"
show
echo
echo "  校验点：workflow/editWorkflow 必须都是 ${VARIANT} 那一套；steps=$([ "$VARIANT" = qwen ] && echo 40 || echo "$([ "$VARIANT" = flux2-turbo ] && echo 8 || echo 20)")；comfyUrl 应仍指向当前 GPU 网关"
