#!/usr/bin/env bash
# =============================================================================
# 图片引擎（出图通路）一键切换：Qwen-Image ⇄ FLUX.2 [dev] —— **带回读**
#
# 为什么单独有脚本（而不是手敲 SQL）：CLAUDE.md §四 记过"平台配置页会把旧快照写回"的事故
#   —— 改完不校验 = 以为切了其实没切。本脚本改完**立刻回读**并把 6 个关键字段打出来。
#
# 用法：
#   bash deploy/image_variant_switch.sh show          # 只读回读（不改）
#   bash deploy/image_variant_switch.sh verify        # 只读自检（判当前配置是否“自相矛盾/被冲过”）
#   bash deploy/image_variant_switch.sh flux2         # → FLUX.2 质量档（20 步 / guidance 4）
#   bash deploy/image_variant_switch.sh flux2-turbo   # → FLUX.2 加速档（8 步 + Turbo LoRA）
#   bash deploy/image_variant_switch.sh qwen          # → 回滚到现役 Qwen-Image（40 步 / cfg 4）
#
# ⛔ 已知的**静默冲配置**风险（2026-09-23 从后端源码核实，peer 发现）：
#   后端 `EngineSettingsService.update()` 对 services 是**整块替换**：
#     `if (req.services() != null) s.setServices(req.services().isObject() ? req.services() : null);`
#   而**旧版**引擎配置页的 save() **不带** `image.lora / loraStrength / loraCfg / loraStepsFlux2 / loraCfgFlux2`
#   ⇒ 在旧页面上点一次“保存配置”会把 turbo 档的 LoRA 静默冲掉（steps 还剩 8 
#      ⇒ **8 步还不挂 Turbo LoRA** = 欠采样，画面发虚，而且不报错）。
#   ⇒ 铁律：用了 `flux2-turbo` 就别在**旧版**页面点保存（新版页面已把这 5 个键读写闭环）；
#     每次切完跑一次 `verify` 自检。
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

# ★ 自检：把“看着切了、其实自相矛盾”的组合抬成硬告警（都是今晚踩过的坑）
verify() {
  local line wf ef steps lora
  line="$(Q "select coalesce(services->'image'->>'workflow','') || '|' || coalesce(services->'image'->>'editWorkflow','') || '|' || coalesce(services->'image'->>'steps','') || '|' || coalesce(services->'image'->>'lora','') from user_engine_settings where user_id='${UID_}'::uuid;" | tr -d '\r' | head -1)"
  IFS='|' read -r wf ef steps lora <<< "$line"
  echo "== 自检 =="
  echo "   workflow=${wf:-（空）}"; echo "   editWorkflow=${ef:-（空）}"; echo "   steps=${steps:-（空）}  lora=${lora:-（空）}"
  # ★ 读不到 ≠ 通过（同 CLAUDE.md §四 那条：“查不到就不敢重启”）。空值一律当失败。
  if [ -z "${wf:-}" ]; then
    echo "   ✗ 读不到 services.image.workflow（查询失败或未配置）—— **不许当通过**，先看上面的 psql 报错"
    return 1
  fi
  local bad=0
  case "$wf" in *flux2*) if [ -n "$steps" ] && [ "$steps" -le 10 ] && [ -z "$lora" ]; then
        echo "   ⚠️ **自相矛盾**：steps=$steps（≤10 = 蒸馏加速档的工作点）但没有 lora —— 8 步不挂 Turbo LoRA = 欠采样/画面发虚，且不报错。"
        echo "      最可能原因：有人在**旧版**引擎配置页点过“保存配置”（后端 services 整块替换，见本脚本头注释）。"
        echo "      修复：重跑 `bash deploy/image_variant_switch.sh flux2-turbo`。"; bad=1; fi;; esac
  case "$wf" in *flux2*) if [ -n "$steps" ] && [ "$steps" -gt 20 ]; then
        echo "   ⚠️ steps=$steps 对 FLUX.2 偏多（官方口径 20；40 是 Qwen 的旋钮，混用会白等）"; fi;; esac
  case "$wf" in *qwen*) if [ -n "$steps" ] && [ "$steps" -lt 20 ]; then
        echo "   ⚠️ Qwen 通路 steps=$steps 偏少（官方 40 步 / cfg 4.0）"; fi;; esac
  if echo "$wf" | grep -q flux2 && echo "$ef" | grep -q qwen; then
    echo "   ⚠️ **通路混搭**：workflow=FLUX.2 但 editWorkflow=Qwen —— 关键帧会走 Qwen，而定妆照走 FLUX.2（能跑，但归因时会看错人）"; bad=1; fi
  [ "$bad" = 0 ] && echo "   ✓ 未发现自相矛盾" || true
  return 0
}

case "$VARIANT" in
  show) show; exit 0;;
  verify) verify; exit 0;;
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
  *) echo "用法：$0 {show|verify|qwen|flux2|flux2-turbo}"; exit 2;;
esac

echo "== 切到 ${VARIANT} =="
Q "update user_engine_settings set services = jsonb_set(services, '{image}', ((services->'image') || '${PATCH}'::jsonb)), updated_at = now() where user_id='${UID_}'::uuid;" >/dev/null
echo "   已写入；回读确认（★ 这一步就是防"平台页把旧快照写回"）"
show
echo
verify
echo
  echo "  校验点：workflow/editWorkflow 必须都是 ${VARIANT} 那一套；steps=$([ "$VARIANT" = qwen ] && echo 40 || echo "$([ "$VARIANT" = flux2-turbo ] && echo 8 || echo 20)")；comfyUrl 应仍指向当前 GPU 网关"
