# 方案 · 用 FLUX.2 [dev] 替换 Qwen-Image 出图通路（文生图 + 图生图 / 多参考改图）

> 起草：2026-09-23（本机 `Invisible`）。**本文只出方案与执行步骤，未执行任何下载 / 删除 / 配置改动。**
> 事实核查时间戳：**2026-09-23**，来源优先级 = 官方（BFL 官方仓库与官方 blog / Comfy-Org 官方模板仓库 / ComfyUI 官方文档与核心源码 / ModelScope 官方镜像 API）> 官方发行物 > 第三方。
> 关联：`docs/方案-FLUX2-替换评估-2026-09-22.md`（上一版评估）、`docs/方案-权重清单-A2Vid-DubIt与图片模型-2026-09-23.md`、`GPU服务器能力搭建指南.md`、`docs/gpu-模型清单与镜像备份.md`、`CLAUDE.md §四`。
> 前提（用户 2026-09-23 给定）：**暂不考虑许可证问题**；盒子 **48 GB 显存 / 48 GB 内存**；**假设磁盘空间够**；GPU 服务器当前不可用，本文件等恢复后照抄执行。
> ⚠️ 与 `Weaveora.md §0 / §30` 冲突时以 §0 / §30 为准。本文不含任何地址 / 端口当"当前值"（地址一律现场读配置）。
> ✅ 合规性先说明：`Weaveora.md §30 #26`（模型矩阵中立化）+ `§11.2`（出图质量档默认 = FLUX 系）**已把 FLUX.2 列为候选**，且注明「具体模型版本为配置项，不写死在文档」⇒ **本次换版本属配置变更，不属产品语义变更**。

---

## 0.5 ⚙️ 执行进展（2026-09-23 21:20–21:40 追加 —— **现场实测覆盖本文件后面的推算**）

> 用户裁示：**「按 A 档部署」「下载同时同步做」「负词折进正词 可以」**；新实例 = `root@180.127.11.169 -p 27732`（网关 HTTP **57712**，平台页已同步）。

### 0.5.1 已完成

| # | 事项 | 结果 |
|---|---|---|
| 1 | 盒身份核验 | `argv[0]=/opt/weaveora/ComfyUI/main.py` ✅；ComfyUI **0.34.0**，templates 0.11.48；8 个 Flux2 所需节点全 OK；`CLIPLoader.type` 含 `flux2` ✅ |
| 2 | 硬件事実 | RTX 4090 **49140 MiB**；RAM 48168 MB（47 GiB）；16 核；ComfyUI 启动参数 `--cache-ram 8 --reserve-vram 0.5` |
| 3 | SSH 三别名 | `~/.ssh/config` 的 `gpu` / `gpu2` / `weaveora-gpu-a14b` → `180.127.11.169:27732`（备份 `config.bak.20260923-211839`） |
| 4 | 权重删除（已授权） | 只删了 `vae/ae.safetensors`（335,304,388 B，第二梯队 ⑨ 的 FLUX.1 遗留）+ 悬空软链；**⑥⑦⑧⑨ 其余项在盘上已不存在**（详见 `docs/磁盘操作记录-2026-09-23-FLUX2部署.md`） |
| 5 | 代码（worker） | `worker/comfy_client.py` 新增 **Flux2 注入分支**（识别 / 尺寸双写 / steps→Flux2Scheduler、cfg→FluxGuidance、seed→RandomNoise、denoise→SplitSigmasDenoise / 正词回溯 / 空槽摘除 / 负词折进正词 / LoRA 8步档）；已 `deploy/vps-worker-deploy.sh` 上线（5 文件语法校验 OK、worker active、IMPORTS_OK） |
| 6 | 三个工作流 | `deploy/comfy/flux2_dev_{txt2img,edit,img2img}_api.json`（仓库 + 盒 `/opt/weaveora/workflows/`，**md5 三方一致**） |
| 7 | 回归测试 | 新增 `worker/test_flux2_injection.py` —— **38/38 通过**（含 Qwen 通路「不被误判 / 走旧逻辑」的防回归断言） |
| 8 | 维护自检 | `deploy/gpu2_post_maint_check.sh` 增加 5 件权重字节校验 + **同名多副本检查**（只数真文件），已同步盒上 |
| 9 | 下载脚本 | `deploy/gpu2_dl_flux2.sh`（含**空间预检**，不够直接 exit 2）；已推送盒上 `/opt/weaveora/dl_flux2.sh` |
| 10 | 主模型下载 | `/addDisk` 上后台跑（33.02 GiB，实测 **13.6 MiB/s**，约 45 分钟）；完成后自动建软链进 `models/diffusion_models/`（下一步做） |

### 0.5.2 ⛔ 唯一阻塞：系统盘还缺 **3.96 GiB**

| 盘 | 可用（21:31 实测） | 本次需要 | 结论 |
|---|---|---|---|
| `/addDisk` `/dev/vdb1` | 35,472,056,320 B（删 ae 后）| 主模型 35,455,599,592 B | ✅ 刚好（余 15.7 MB） |
| `/` `/dev/vda1` | **17,133,830,144 B**（15.96 GiB） | 编码器 16.80 + Turbo LoRA 2.57 + vae 0.31 + smalldec 0.23 = **21,381,187,623 B**（19.91 GiB） | ❌ **缺 4,247,357,479 B（3.96 GiB）** |

`bash /opt/weaveora/dl_flux2.sh` 的预检已经会这么报（`缺 4050 MiB`）——**腾够 4 GiB 就能一键跑完剩下 4 件**。
候选（均为**非权重**、且非本项目生产资产，**待逐项确认**）：`/root/venv` 9.6 GiB（镜像自带、2026-07-06、无进程引用）｜`/opt/weaveora/ComfyUI/output` 4.0 GiB（7084 个 bench_*/cmp_*/editv_*/p4_* 调试产物）｜`/var/lib/snapd` 3.5 GiB。

### 0.5.3 尚未做（等权重到齐 / 等确认）

1. 剩 4 件权重下载 → 2. 三个工作流 **POST 试一枪**（真跑，验证节点类型/接线）→ 3. `services.image.*` 切到 flux2（**必须先试枪通过**，否则会打断线上出图）→ 4. G1–G5 A/B 验收。

---

## 0. 结论速览

| # | 问题 | 结论 |
|---|---|---|
| 1 | **用哪个版本** | **FLUX.2 [dev]（32B，Comfy-Org 官方重打包 `flux2_dev_fp8mixed`）**。理由：它是**当前唯一开源的 FLUX 旗舰**；FLUX 3（2026-07-23）只有 **API Early Access、无开源权重**；klein 4B/9B 是**速度档**（步数蒸馏，4 步），质量档仍是 dev。 |
| 2 | **要下哪些权重** | **5 个文件 / 52.93 GiB**：`flux2_dev_fp8mixed` 33.02 + `mistral_3_small_flux2_fp8` 16.80 + `flux2-vae` 0.31 + `Flux_2-Turbo-LoRA_comfyui` 2.57 + `full_encoder_small_decoder` 0.23（§3）。**全部走 ModelScope 官方镜像，免登录、支持 Range 分片**（HF 上 `FLUX.2-dev` 是 gated）。 |
| 3 | **盒子支持吗** | ✅ 现网 `ComfyUI 0.34.0`（2026-08-25）已含 Flux2 全套核心节点 + 12 份官方模板；small decoder 支持自 **0.19.0（2026-04-13）** 起 ⇒ **不必升级 ComfyUI**（升级反而会动 Qwen/LTX 既有工作流，见 §6.5）。 |
| 4 | **48G 显存够吗** | ✅ 够，但要选对组合。`fp8mixed 33.02 + fp8 TE 16.80 = 49.82 GiB` **略超** 47.4 GiB 可用显存 ⇒ 指望 ComfyUI 的「先编码文本、再换主模型」卸载路径；若实测换页严重，换 **fp4_mixed TE（11.43）→ 主模型+TE = 44.45 GiB，可两者常驻**（§4 三档）。 |
| 5 | **能替代什么** | 只替代**出图通路**（定妆照 txt2img + 关键帧多参考 edit + img2img）。**换不掉** Wan2.2 / LTX-2.5 图生视频、LatentSync 对口型、EchoMimicV3、CosyVoice2 TTS、ACE-Step 配乐。 |
| 6 | **负词怎么办** | ⛔ **FLUX.2 [dev] 是 guidance 蒸馏（`BasicGuider` 单条件，无 uncond 分支）⇒ 负词架构上不生效，且不报错**。必须把负词**折进正词**（产品语义改动，需确认，§6.3）。 |
| 7 | **磁盘不够可删谁** | **本文只出候选清单，不动手**（§5）。第一优先是切换后即失去用途的 Qwen 出图四件套（约 48.7 GiB）；`docs/待恢复模型清单-2026-09-19.md` 亲笔标注的 `…lightning…4steps_v1.0`（19.04 GiB）**动前必须逐项问用户**。 |
| 8 | **一句话** | 盒子软件齐全、模型全在 ModelScope 免登录可下；**真正的工程风险不在能不能跑，而在「换完之后 ①负词失效 ②identity 一致性是否真比 2511 强」——这两条必须用 A/B 拿到数再切生产**（§8 已写死判据）。 |

---

## 1. 事实核查（来源 + 日期）

| # | 事实 | 来源 | 取数方式 / 日期 |
|---|---|---|---|
| 1 | FLUX.2 [dev] = **32B** flow-matching transformer，**guidance 蒸馏**；支持 t2i / 单参考编辑 / **多参考编辑**；发布 **2025-11-25** | 官方仓库 `github.com/black-forest-labs/flux2` README（News 段逐字 + 模型表） | ghfast 代理 raw，2026-09-23 |
| 2 | 官方 README 的选型建议逐字：消费级卡（RTX 3090/4070）→ klein 4B；**最大质量、无延迟约束 → FLUX.2 [dev]** | 同上「Which Model Should I Use?」表 | 2026-09-23 |
| 3 | **FLUX 3 = 2026-07-23 发布，Early Access**，多模态（图/视频/音频同一架构）；**Image 部分原文 "We will open up an early access phase for FLUX 3 Image in the following weeks"** | 官方 blog `bfl.ai/blog/flux-3` | 2026-09-23 |
| 4 | **FLUX 3 无开源权重**：BFL 的 HF 组织 35 个仓，最新是 `FLUX.2-small-decoder`（2026-04-07），**没有任何 FLUX 3 仓**；FLUX 3 只出现在 API 文档（POST FLUX 3 / FLUX 3 Video） | hf-mirror API `author=black-forest-labs&sort=lastModified`；`docs.bfl.ai/api-reference` | 2026-09-23 |
| 5 | klein 家族：4B/9B/9B-KV（2026-01-15 发布，9B-KV `lastModified` 2026-03-12）；4B/4B-Base = Apache-2.0，其余非商用 | 官方 README 模型表 + hf-mirror API | 2026-09-23 |
| 6 | **官方 ComfyUI 模板**（`image_flux2_fp8`，dev 量化档）= `flux2_dev_fp8mixed` + `mistral_3_small_flux2_fp8` + `flux2-vae` + `Flux2TurboComfyv2`(可选 8 步) | Comfy-Org/workflow_templates `templates/image_flux2_fp8.json` 逐节点解析 | 2026-09-23 |
| 7 | **官方质量档模板**（`image_flux2` / `image_flux2_text_to_image`）= `flux2_dev_fp8mixed` + `mistral_3_small_flux2_bf16`(33.14 GiB!) + **`full_encoder_small_decoder`** + `Flux_2-Turbo-LoRA_comfyui` | 同上两份 JSON | 2026-09-23 |
| 8 | **官方参数**：`Flux2Scheduler(steps=20, W, H)` + `FluxGuidance(4)` + `KSamplerSelect(euler)` + `EmptyFlux2LatentImage(W,H,1)` + `SamplerCustomAdvanced` + `BasicGuider`；多参考 = `ReferenceLatent` **串链**；输出尺寸由**参考图经 `ImageScaleToTotalPixels(area, 1MP)`** 推导 | 同上（含 links 全量还原） | 2026-09-23 |
| 9 | 官方 ComfyUI 教程页给 dev 列的模型 = `mistral_3_small_flux2_bf16` + `flux2_dev_fp8mixed` + `flux2-vae` | `docs.comfy.org/tutorials/flux/flux-2-dev` | 2026-09-23 |
| 10 | ComfyUI 最新 tag **0.37.1**；现网盒 **0.34.0**（2026-08-25，tag `12d5279`）；Flux2 支持自 0.3.72(2025-11-25)；**small flux.2 decoder 自 0.19.0(2026-04-13)** | `data.jsdelivr.com/v1/packages/gh/comfyanonymous/ComfyUI`；上一版评估文档 §1 | 2026-09-23 |
| 11 | **fp4 / nvfp4 的"快路"要求 Blackwell**：源码 `supports_nvfp4_compute()` 逐字 `if props.major < 10: return False` ⇒ **RTX 4090（Ada sm_89）拿不到 nvfp4 快路** | ComfyUI 源码 `comfy/model_management.py:2018` | 2026-09-23 |
| 12 | 量化权重以 `_quantization_metadata` 逐层声明 format（`float8_e4m3fn` / `nvfp4`），**不支持的算子自动回退 dequantize + 高精度分发** | ComfyUI 仓库 `QUANTIZATION.md` | 2026-09-23 |
| 13 | 权重字节数 / sha256（§3 表） | ModelScope 官方镜像 API `Comfy-Org/flux2-dev`、`black-forest-labs/FLUX.2-small-decoder` | 2026-09-23 |
| 14 | **ModelScope 镜像了 BFL 官方仓且免登录**：`black-forest-labs/FLUX.2-dev`（40 blob）、`…-NVFP4`、`…-klein-4B`、`…-klein-9b-kv`、`FLUX.2-small-decoder` 全部实测可达；而 HF 上 `FLUX.2-dev` 是 `gated: auto` | ModelScope files API + hf-mirror model API，逐个实测 | 2026-09-23 |

---

## 2. 「最新版本用哪个」——候选对比与裁定

### 2.1 开源侧（可自托管）全景，2026-09-23

| 候选 | 体积 | 步数 | 显存工作点 | 许可 | 裁定 |
|---|---|---|---|---|---|
| **FLUX.2 [dev] fp8mixed**（Comfy-Org 重打包） | **33.02 GiB** | 20 步 / guidance 4（非蒸馏） | 官方原话需 H100 级；量化后消费级可跑 | 非商用（本次不考虑） | ✅ **选它**——质量档唯一开源旗舰 |
| FLUX.2 [dev] bf16 原版 | **60.02 GiB**（`flux2-dev.safetensors` 64,446,596,128 B） | 同上 | ❌ 48 GB 装不下且必然重度换页 | 非商用 | ❌ 不用 |
| FLUX.2 [dev] **NVFP4**（BFL 官方，2025-12-31） | 21.21 / 19.59 GiB | 同上 | 存储省，但 **Ada 无 nvfp4 快路 → 逐层 dequant**（更慢） | 非商用 | 🟡 仅当 fp8 换页严重时的兜底实验 |
| FLUX.2 [dev] **GGUF**（city96，第三方） | Q4_K_M 18.70 / Q5_K_M 22.41 / Q8_0 32.60 GiB | 同上 | 最省显存 | 非商用 | 🟡 需装 `ComfyUI-GGUF` 自定义节点（**盒上目前没有**），③级来源 + 掉质量 |
| FLUX.2 [klein] 4B（distilled） | 3.79 GiB(fp8) | **4 步** | 官方：8.4 GB VRAM / ~1.2s（5090） | Apache-2.0 | 🟡 速度档；质量明显弱于 dev |
| FLUX.2 [klein] 9B / 9B-KV（distilled） | 9.15 / 9.14 GiB(fp8) | 4 步 | 消费级 | 非商用 | 🟡 中间档 |
| Z-Image-Turbo（阿里，2025-11-27） | 19.4 GiB | 8 步内 | 消费级 | — | ❌ 不在本次议题 |
| 现役 Qwen-Image / Qwen-Image-Edit-2511 | 19.03 / 19.12 GiB | 40 步 / cfg 4.0 | 已跑通 | Apache-2.0 | 保留作 A/B 基线（不删，见 §5） |

### 2.2 闭源侧（不能自托管，列出来是为了"别选错"）

| 候选 | 状态 | 裁定 |
|---|---|---|
| **FLUX 3**（图 + 视频 + 音频） | 2026-07-23 **Early Access**，**只有 API**；Image 部分官方说"未来几周开放 early access" | ❌ **不能本地部署**；等开源权重或 GA 再评 |
| FLUX.2 [pro] / [max] / [flex] | BFL API 闭源付费 | ❌ 与 `Weaveora.md §0-2`（GPU 推理在自家 worker）冲突；且云 API 档在 §30 #26 里被排到租用 GPU 之后 |

### 2.3 版本口径的最终裁定

> **降载版用 `flux2_dev_fp8mixed.safetensors`（Comfy-Org 官方重打包，33.02 GiB）+ `mistral_3_small_flux2_fp8`（16.80 GiB）+ `flux2-vae`（0.31 GiB）**，可选加 `Flux_2-Turbo-LoRA_comfyui`（2.57 GiB，8 步加速档）与 `full_encoder_small_decoder`（0.23 GiB，官方模板当前默认的 VAE）。
> **不选** bf16 TE（33.14 GiB，48 GB 机器上是纯负担）；**TE 的 fp4_mixed 版（11.43 GiB）作为"显存不够时的第一顺位替换件"预置**（§4-B）。
> 任何情况下**不选 NVFP4 作为首选**：Ada 无快路，官方也不推荐在非 Blackwell 上用它。

---

## 3. 权重清单（下什么 / 字节 / 放哪 / 校验）

### 3.1 主集（必下，5 件，**52.93 GiB**）

| # | 目标路径（`ComfyUI/models/` 下） | 字节 | GiB | sha256（前 16 位） | 来源仓 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/flux2_dev_fp8mixed.safetensors` | 35,455,599,592 | **33.02** | `863a82e4ff950a42` | `Comfy-Org/flux2-dev` |
| 2 | `text_encoders/mistral_3_small_flux2_fp8.safetensors` | 18,034,640,095 | **16.80** | `e3467b7d912a234f` | `Comfy-Org/flux2-dev` |
| 3 | `vae/flux2-vae.safetensors` | 336,213,556 | 0.31 | `d64f3a68e1cc4f9f` | `Comfy-Org/flux2-dev` |
| 4 | `loras/Flux_2-Turbo-LoRA_comfyui.safetensors`（8 步档，可选但建议） | 2,760,814,880 | 2.57 | `011487390b8020ba` | `Comfy-Org/flux2-dev` |
| 5 | `vae/full_encoder_small_decoder.safetensors`（官方模板当前默认 VAE） | 249,519,092 | 0.23 | `ea4273f02d1fafbf` | `black-forest-labs/FLUX.2-small-decoder` |
| | **合计** | **56,836,787,215** | **52.93** | | |

> 注：`Comfy-Org/flux2-dev` 里还有 `Flux2TurboComfyv2.safetensors`（2,760,814,872 B，与 #4 字节几乎一致）。
> **两份只下 #4 `Flux_2-Turbo-LoRA_comfyui`**——它是**官方质量档模板**用的那份；`Flux2TurboComfyv2` 是量化档模板用的（上一版评估已记其上游指向第三方账号 `ByteZSzn`，镜像那份也不建议）。

### 3.2 备件（**先不下**，只在 §4-B/§4-C 触发时下）

| 路径 | 字节 | GiB | 触发条件 |
|---|---|---|---|
| `text_encoders/mistral_3_small_flux2_fp4_mixed.safetensors` | 12,275,678,071 | 11.43 | fp8 TE 导致明显换页 / OOM |
| `diffusion_models/flux2-dev-Q4_K_M.gguf`（GGUF） | 20,082,414,560 | 18.70 | 显存/内存双双不够（需另装 `ComfyUI-GGUF`） |

### 3.3 下载命令（**同一份脚本、同一套纪律**）

```bash
# 盒上（node v20.18.0 在 /opt/weaveora/opt-node/bin/node；脚本与仓库 md5 一致 ea3c2f1241acb1c1be0945cdecafc177）
# ★ Linux 上必须显式 WEAVEORA_CURL=curl（脚本默认写的是 curl.exe）
# ★ 长任务一律后台化（Weaveora.md §0.3）：setsid … </dev/null >>log 2>&1 &，启动命令立刻返回
BASE=https://www.modelscope.cn/models
DEST=/addDisk/weaveora/models        # ← 以 Step 0 现场盘点结果为准（数据盘吃紧就拆开放，见 §5 注）

dl () {  # $1=url  $2=输出绝对路径
  WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js "$1" "$2" 10
}
setsid bash -c '
set -e
BASE=https://www.modelscope.cn/models
DEST=/addDisk/weaveora/models
WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js \
  "$BASE/Comfy-Org/flux2-dev/resolve/master/split_files/diffusion_models/flux2_dev_fp8mixed.safetensors" \
  "$DEST/diffusion_models/flux2_dev_fp8mixed.safetensors" 10
WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js \
  "$BASE/Comfy-Org/flux2-dev/resolve/master/split_files/text_encoders/mistral_3_small_flux2_fp8.safetensors" \
  "$DEST/text_encoders/mistral_3_small_flux2_fp8.safetensors" 10
WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js \
  "$BASE/Comfy-Org/flux2-dev/resolve/master/split_files/vae/flux2-vae.safetensors" \
  "$DEST/vae/flux2-vae.safetensors" 10
WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js \
  "$BASE/Comfy-Org/flux2-dev/resolve/master/split_files/loras/Flux_2-Turbo-LoRA_comfyui.safetensors" \
  "$DEST/loras/Flux_2-Turbo-LoRA_comfyui.safetensors" 10
WEAVEORA_CURL=curl /opt/weaveora/opt-node/bin/node /opt/weaveora/gpu_model_downloader.js \
  "$BASE/black-forest-labs/FLUX.2-small-decoder/resolve/master/full_encoder_small_decoder.safetensors" \
  "$DEST/vae/full_encoder_small_decoder.safetensors" 10
' </dev/null >>/opt/weaveora/logs/dl_flux2_$(date +%Y%m%d_%H%M).log 2>&1 &
echo "下载已后台化，PID=$!；进度只看日志尾部一行（不要 while 轮询）"
```

**下载纪律（照旧，一条都不松）**
1. 只走 `gpu_model_downloader.js`（10 路 Range 分片 + 断点续传 + 后台静默）；⛔ **不许单连接 curl 拉大文件**。
2. 启动即返回；agent 只做 **1~2 次轻量短查询**（`tail -1 log`），不 `sleep`、不 `while` 轮询。
3. **下完必须核对实体文件**：`stat -c '%s %n'` 与 §3.1 字节数逐字节比对，`sha256sum` 对前 16 位。
   ⚠️ **`.done` 只是标记，不代表文件还在**（09-23 二段坑 3）——判"权重在不在"只能看实体文件。
4. 下载与其它任务**并行**（不要等它跑完再干别的）。

---

## 4. 48 GB 显存 / 48 GB 内存的三种配置档

**硬件前提（用户给定）**：显存 48 GB（上一实例实测 `vram_total` 47.4 GiB）、内存 48 GB（上一实例实测 47 GiB，swap 8 GiB 且已轻度使用）。

### 4.1 三档

| 档 | 主模型 | 文本编码器 | VAE | 权重合计 | 显存可行性（推算，须实测） |
|---|---|---|---|---|---|
| **A（推荐，官方口径）** | `flux2_dev_fp8mixed` 33.02 | `mistral_3_small_flux2_fp8` **16.80** | `full_encoder_small_decoder` 0.23 | **50.05 GiB** | 🟡 两者**不能同时常驻**（合计 > 47.4）。依赖 ComfyUI 的「先编码文本 → 卸载编码器 → 装主模型」路径（该路径 0.4.0 起有专门的 Flux2 TE 卸载记账修复）。**采样阶段峰值 ≈ 33 + 激活 ≈ 36~38 GiB，没问题**；代价是每次生成要重读 16.8 GiB 编码器。 |
| **B（显存/内存紧时）** | 同上 | `mistral_3_small_flux2_fp4_mixed` **11.43** | 同上 | **44.69 GiB** | ✅ 主模型 + 编码器**可同时常驻**（33.02 + 11.43 = 44.45，再加 VAE 0.23），留 ~3 GiB 给激活 ⇒ 最省心的档。代价：Ada 无 nvfp4 快路 ⇒ 编码器逐层 dequant（**只影响文本编码那几秒**，不影响 20 步采样）。 |
| **C（兜底）** | `flux2-dev-Q4_K_M.gguf` 18.70 | fp8 16.80 | 同上 | 35.73 GiB | ✅ 最宽松，但要装 `ComfyUI-GGUF` 自定义节点 + 掉质量 + ③级来源。**仅当 A/B 都不行时**。 |

> ❌ **不选的档**：bf16 TE（33.14 GiB）——与主模型合计 66.16 GiB，显存和内存双双放不下；官方质量档模板是给 H100/多卡写的，48 GB 单卡不要照抄。

### 4.2 内存侧的账（**这是 48 GB 机器上比显存更紧的一条**）

- ComfyUI 用 safetensors mmap 流式读 33 GiB 主模型 → 这部分是**可回收的 page cache**，不是硬 RSS；但 `--cache-ram N` 控制的是**卸载后仍留在内存的模型缓存上限**。
- 上一实例启动参数是 `--cache-ram 8 --reserve-vram 0.5`；`gpu-模型清单与镜像备份.md` 记的同类实例是 `--cache-ram 32`。
  ⇒ **`--cache-ram 8` 下，16.8 GiB 的编码器一次都留不住，每次生成都要重下 16.8 GiB 盘**；这是"48 GB 内存"场景下最值得调的一个旋钮。
  **建议 Step 3 实测后在 8 / 24 / 32 三个值里选**（判据：单张耗时、`free -m` 的 available、`/proc/pressure/memory`、swap 增量）。
- 同时常驻的还有 tts(8091) / face(8093) / talk(8094) 三个服务 + LTX-2.5 权重（21.5 + 15.4 GB，出片时用）。**出图与出片不要并发跑**（既有运维铁律）。

### 4.3 速度预期（**估算，不是承诺**）

- 计算量粗估：`2 × 32e9 参数 × 6032 token ≈ 3.9e14 FLOP/步`（1664×928 的 latent 16× 下采样 ⇒ 序列长 = 1664×928/256 = **6032 token**）。
- **有利面**：Flux2 的 16× latent 让高分辨率的 token 数只有 Qwen-Image（8×）的 **1/4**；官方 benchmark 主打多参考一致性 + 文字渲染。
- **不利面**：参数量 32B（Qwen-Image 是 20B），且 20 步非蒸馏。
- ⇒ **必须实测**：`单张 1664×928 / 20 步` 的耗时、峰值显存、是否换页；挂 Turbo LoRA 后 `8 步` 的耗时与质量差。三项都拿到数才能决定生产档位（§8）。

---

## 5. 磁盘账 + 「不够时可删」候选（**未执行，逐项待确认**）

### 5.1 为什么现在就要准备这份清单

上一轮实测（2026-09-23）：`/addDisk` 剩 **33 GB**、系统盘 `/` 剩 **16 GB**（196 G 用 170 G，92% 满）⇒ **52.93 GiB 的主集当时根本装不下**。用户本次说"假设磁盘空间够"，但**新实例的数据盘大小会变**（历史见过 98 GB 的数据盘），所以 Step 0 必须先现场盘点；不够则按 §5.2 逐项走确认流程。

> 注：`/addDisk` 是**持久盘**（换实例还在），所以历史占用会被带过来。系统盘 `/` 随镜像走。

### 5.2 ⛔ 候选清单（**只列不删**；依据、体积、不可逆程度、恢复路径都在表里）

**第一梯队 —— 切到 FLUX.2 后即失去用途（Qwen 出图链路本体，约 48.7 GiB）**

| # | 路径 | 体积 | 角色 | 恢复路径 |
|---|---|---|---|---|
| ① | `…/diffusion_models/qwen_image_edit_2511_fp8mixed.safetensors` | **19.12 GiB**（20,533,762,817 B） | 关键帧 / 改图（edit 通路） | ModelScope `Comfy-Org/…` 重下（下前须核对 URL 仍有效） |
| ② | `…/diffusion_models/qwen_image_fp8_e4m3fn.safetensors` | **19.03 GiB**（20,430,635,136 B） | 文生图主力（定妆照 / 无参考帧） | 同上 |
| ③ | `…/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors` | **8.74 GiB**（9,384,670,680 B） | Qwen 出图链路文本编码器 | 同上 |
| ④ | `…/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors` | **1.58 GiB**（1,698,951,104 B） | Qwen 8 步蒸馏 LoRA | 同上 |
| ⑤ | `…/vae/qwen_image_vae.safetensors` | **0.24 GiB**（253,806,246 B） | Qwen 出图 VAE | 同上 |
| | **小计** | **≈ 48.71 GiB** | | |

**第二梯队 —— 独立于本次切换的"早已是备选/未接生产"（约 54.1 GiB）**

> ★ **2026-09-23 21:2x 现场盘点结果（覆盖下表）**：新实例上 ⑥⑦⑧⑨ 的**绝大多数已不存在**（`qwen_image_edit_2511_…lightning_4steps`、`qwen_image_edit_fp8_e4m3fn`、`qwen_image_2512`、`flux1-schnell-fp8`、`t5xxl_fp8`、`clip_l` 全部不在盘上）。**实际只剩 ⑨ 里的 `vae/ae.safetensors`**（335,304,388 B，只被 `flux_schnell_*` 两份备选工作流引用）—— 已按用户授权删除，见 `docs/磁盘操作记录-2026-09-23-FLUX2部署.md`。下表保留作历史记录（**不要照着它去删已经不存在的文件**）。

| # | 路径 | 体积 | 依据 | 不可逆程度 / 恢复 |
|---|---|---|---|---|
| ⑥ | `…/diffusion_models/qwen_image_edit_2511_fp8_e4m3fn_scaled_lightning_comfyui_4steps_v1.0.safetensors` | ~~19.04 GiB~~（**已不存在**） | `docs/待恢复模型清单-2026-09-19.md:65` 亲笔：「**不是误删项、未接生产**——如需腾地方，**必须先问用户**再动」 | 可从 `deploy/gpu2_dl_upgrade.sh` 记的 ModelScope URL 重下（须先核对 URL） |
| ⑦ | `…/diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors` | ~~19.03 GiB~~（**已不存在**） | 基础版 Edit（备选） | ModelScope 重下 |
| ⑧ | `…/diffusion_models/qwen_image_2512_fp8_e4m3fn.safetensors` | ~~19.03 GiB~~（**已不存在**） | 2512 档，`0.34 出噪声`（慎用） | ModelScope 重下 |
| ⑨ | `…/diffusion_models/flux1-schnell-fp8.safetensors` + `text_encoders/t5xxl_fp8_e4m3fn` + `text_encoders/clip_l` + `vae/ae` | ~~21.15 GiB~~（**只剩 `vae/ae` 0.31 GiB，已删**） | FLUX.1 时代遗留，当前无生产引用 | `https://www.modelscope.cn/models/AI-ModelScope/FLUX.1-schnell/resolve/master/ae.safetensors` |

### 5.3 红线（照 `CLAUDE.md §二` 原文执行，不放松）

1. **判定"没人用"不构成授权**：`grep` 未命中、引用计数 0、读代码推断"生产不可达" —— **只能当建议依据，不能当执行依据**。
2. 执行前必须先：**列清单（完整路径 + 体积 + 角色 + 影响面 + 不可逆程度）→ 停下等用户**逐项**确认 → 才执行**；并把**恢复命令**在动手前贴给用户。
3. 适用范围与体积无关：`rm`、`mv` 覆盖、`truncate`、DB `DELETE/UPDATE`、镜像/快照清理一律适用。
4. **本次建议的时序**：先在 B 档（fp4 TE 省 5.4 GiB）或按 §5.4 的"增量最小集"下载；**A/B 验收通过后**再讨论删 Qwen。**回滚价值 = 一套 19 GiB 的权重，不值得为省 19 GiB 提前破坏回滚路径。**
5. 数据盘 / 系统盘拆分放置（当盘不够时的合法做法，不涉及删除）：主模型 33 GiB 放数据盘、编码器 16.8 GiB 放系统盘（**前提：系统盘余量 ≥ 20 GiB，且不写出图产物到系统盘**）。

---

## 6. 需要改的代码与工作流（改动点已定位到函数）

### 6.1 三个新工作流（API 格式，落 `deploy/comfy/` + 盒上 `/opt/weaveora/workflows/`）

⚠️ 官方模板是 **subgraph 格式**，必须摊平成 API 格式（老流程做过：`qwen_image_edit_api.json`）。摊平后的节点接线（**已从官方 JSON 的 links 逐条还原**）：

**A. `flux2_dev_txt2img_api.json`（定妆照 / 无参考帧）**
```
1  UNETLoader            unet_name=flux2_dev_fp8mixed.safetensors, weight_dtype=default
2  CLIPLoader            clip_name=mistral_3_small_flux2_fp8.safetensors, type=flux2, device=default
3  VAELoader             vae_name=full_encoder_small_decoder.safetensors
6  CLIPTextEncode        clip=[2,0], text=""        _meta.title="positive"
7  CLIPTextEncode        clip=[2,0], text=""        _meta.title="negative"（占位；Flux2 通路不接线，见 §6.3）
8  FluxGuidance          conditioning=[6,0], guidance=4.0
9  EmptyFlux2LatentImage width=1664, height=928, batch_size=1
10 Flux2Scheduler        steps=20, width=1664, height=928
11 KSamplerSelect        sampler_name=euler
12 BasicGuider           model=[1,0], conditioning=[8,0]
13 RandomNoise           noise_seed=0
14 SamplerCustomAdvanced noise=[13,0], guider=[12,0], sampler=[11,0], sigmas=[10,0], latent_image=[9,0]
15 VAEDecode             samples=[14,0], vae=[3,0]
16 SaveImage             images=[15,0], filename_prefix=weaveora_flux2_t2i
```
> 8 步加速档 = 挂 `Flux_2-Turbo-LoRA_comfyui` + `Flux2Scheduler.steps=8`（**guidance 仍为 4**，官方量化档模板即如此）。

**B. `flux2_dev_edit_api.json`（关键帧 / 多参考 edit，**3 个参考槽**，与现网 `refs[:3]` 对齐）**
```
1  UNETLoader      flux2_dev_fp8mixed.safetensors
2  CLIPLoader      mistral_3_small_flux2_fp8.safetensors / type=flux2
3  VAELoader       full_encoder_small_decoder.safetensors
6  CLIPTextEncode  text=""                     _meta.title="positive"
7  CLIPTextEncode  text=""                     _meta.title="negative"（占位）
20 FluxGuidance    conditioning=[6,0], guidance=4.0
── 参考槽 1 ── 12 LoadImage → 13 ImageScale(lanczos,1664,928) → 14 VAEEncode(vae=[3,0])
── 参考槽 2 ── 15 LoadImage → 16 ImageScale(lanczos,1664,928) → 17 VAEEncode
── 参考槽 3 ── 18 LoadImage → 19 ImageScale(lanczos,1664,928) → 21 VAEEncode
22 ReferenceLatent conditioning=[20,0], latent=[14,0]     ← 槽1
23 ReferenceLatent conditioning=[22,0], latent=[17,0]     ← 槽2
24 ReferenceLatent conditioning=[23,0], latent=[21,0]     ← 槽3
30 BasicGuider     model=[1,0], conditioning=[24,0]
31 EmptyFlux2LatentImage  1664, 928, 1
32 Flux2Scheduler  20, 1664, 928
33 KSamplerSelect  euler
34 RandomNoise     0
35 SamplerCustomAdvanced → 36 VAEDecode → 37 SaveImage(weaveora_flux2_edit)
```
> 参考图缩放口径：官方模板用 `ImageScaleToTotalPixels(area, 1MP)`；我们**沿用现网 Qwen edit 的 `ImageScale(目标尺寸)`**（与 `_wf_inject_size` 现有行为一致、可注入）。⚠️ 但 **token 账要算清**：参考图按 1664×928 缩放 ⇒ 每张 = 6032 token，3 张 + 目标 6032 = **24,128 token**（≈ 4× 单张成本）。**Step 5 必须做一次"参考图缩到 1MP"的对照**（官方口径）再定生产值。

**C. `flux2_dev_img2img_api.json`（真 img2img，历史"关键帧当底图"兜底档）**
```
与 A 相同，但：
  12 LoadImage → 13 ImageScale ← 由 _wf_inject_size 注入目标尺寸 → 14 VAEEncode
  10 Flux2Scheduler(20,W,H) → 16 SplitSigmasDenoise(denoise=0.65) 的 **low_sigmas** 输出 → SamplerCustomAdvanced.sigmas
  SamplerCustomAdvanced.latent_image = [14,0]
```
> `SplitSigmasDenoise.low_sigmas` 是 ComfyUI 核心 `nodes_custom_sampler.py` 的标准 img2img 用法（已读源码确认两个输出语义）。

### 6.2 `worker/comfy_client.py` 需要新增的注入分支（**现网注入器完全不认 Flux2**）

| 函数（现位置） | 现状 | 必须新增 |
|---|---|---|
| `_wf_inject_sampler()`（L224） | 只认 `KSampler`/`KSamplerAdvanced` 的 steps/cfg/denoise | **Flux2 分支**：`Flux2Scheduler.steps` ← steps；`FluxGuidance.guidance` ← cfg（语义映射：cfg→guidance）；`RandomNoise.noise_seed` ← seed；`SplitSigmasDenoise.denoise` ← denoise。**不做就是这个坑**：UI 改了步数/guidance 但静默无效（四段坑 3 的翻版） |
| `_wf_inject_text()`（L175） | 按 `KSampler.positive/negative` 接线找条件节点 | Flux2 无 KSampler ⇒ 必须按 `FluxGuidance.conditioning` 反查正词节点，`_meta.title` 兜底；负词按 §6.3 处理 |
| `_wf_inject_size()`（L150） | 白名单只有 `EmptySD3LatentImage/EmptyLatentImage/ImageScale/ImageResize` | 加 **`EmptyFlux2LatentImage`**；且 Flux2 要把 w/h **同时**写进 `Flux2Scheduler`（两处必须一致，否则 scheduler 排程与 latent 尺寸不符） |
| `_wf_latent_is_img2img()`（L335） | 只看 `KSampler.latent_image` | 加 Flux2 分支（`SamplerCustomAdvanced.latent_image` 来自 `VAEEncode`） |
| `_wf_inject_lora()`（L247） | 通用（找 UNETLoader → 插 `LoraLoaderModelOnly` → 重写所有 `model` 引用） | ✅ **无需改**，Flux2 图里 `model` 引用指向 `BasicGuider`，重写逻辑照样命中 |
| `_wf_prune_unused_images()`（L277） | 只摘多余的 `LoadImage` 与"指向它们的引用" | ⚠️ **必须扩展**：Flux2 的 edit 图里 `LoadImage → ImageScale → VAEEncode → ReferenceLatent` 是**链**；只摘 LoadImage 会留下"缺输入"的中间节点 ⇒ ComfyUI 直接 400（09-23 二段坑 1）。做法：摘图后**从 SaveImage 反向可达性裁剪**，把不可达节点全删并重接 ReferenceLatent 链 |
| `_wf_apply_areas()`（L351） | 区域条件靠 `ConditioningSetAreaPercentage` | Flux2 走 `BasicGuider`，`area` 同样**架构上不生效**（与 Qwen-Image 同性质）⇒ 保持 `WEAVEORA_IMAGE_AREA_COND=0`，位置只靠正词表达 |
| `IMAGE_LORA_STEPS`（L68，默认 4） | Qwen Lightning = 4 步 | Flux2 Turbo LoRA 的工作点是 **8 步** ⇒ 需要一个"Flux2 档"的默认值（新增 env `WEAVEORA_IMAGE_LORA_STEPS_FLUX2=8` 或把 LoRA 步数做成引擎配置项） |

### 6.3 ⛔ 负词（**最大的产品语义改动，需产品确认**）

- 官方 Flux2 图用的是 `BasicGuider`（**单条件**）⇒ **没有 uncond 分支，负词不参与计算，且不报错**。`FluxGuidance` 是蒸馏引导，不是 CFG。
- 两条路：
  - **(a) 折进正词**（推荐）：把"不要白底 / 不要棚拍背景 / 不要 3D 渲染 / 不要证件照"改写成正向约束句；
  - (b) 换 `CFGGuider` + cfg=1.0 保留负词接线 —— **等于放弃 dev 的蒸馏工作点，输出分布会变**，不推荐。
- **涉及资产**：定妆照模板（负词含 `illustration/cartoon/plastic skin`）、关键帧 edit 注入的 `white background, solid color background, studio portrait, character sheet, front facing ID photo, 3d render, cgi`，以及项目风格前缀。⇒ **§10 待确认清单第 4 条**。

### 6.4 配置链路（**三处必须成对**，否则"UI 配了没效果"）

已定位的现成链路（**不需要新增键**，只需换值）：

| 层 | 位置 | 要改的值 |
|---|---|---|
| DB / 引擎配置页 | `user_engine_settings.services.image.*` | `workflow`→`/opt/weaveora/workflows/flux2_dev_txt2img_api.json`；`editWorkflow`→`…/flux2_dev_edit_api.json`；`img2imgWorkflow`→`…/flux2_dev_img2img_api.json`；`model`→`flux2_dev_fp8mixed.safetensors`（**留空则用工作流自带值，建议留空**）；`steps`→**20**（不是 40）；`cfg`→**4.0**（会被映射成 guidance，数值恰好不变）；`denoise`→1.0 |
| Java | `EngineSettingsService`（image 节点 + `defaultImageWorkflow()` 等三个默认值） | 同步默认值（否则"保存页面会把旧快照写回"——CLAUDE.md §四 已发生过） |
| worker | `comfy_client.apply_services()` → `IMAGE_TXT2IMG_WF / IMAGE_EDIT_WF / IMAGE_IMG2IMG_WF / IMAGE_MODEL / IMAGE_STEPS / IMAGE_CFG / IMAGE_DENOISE`（L3440+） | 同值兜底；`/etc/weaveora/weaveora-gpu-worker.env` 同步 |
| 前端 | `web/src/views/EngineSettingsView.vue` 图片区块 | 若加"图片引擎下拉（Qwen / FLUX.2）"则需要新键 `variant` ⇒ **三处成对**（Java 白名单 + worker IMAGE 键 + 前端 MOTION/IMAGE_KEYS） |

⚠️ **每次保存引擎配置后必须回读**（平台会把旧快照写回）：
```bash
ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select services->'image' from user_engine_settings where gpu_server_url is not null;\""
```

### 6.5 明确**不做**的事

1. **不升级 ComfyUI**（0.34.0 已具备 Flux2 全部所需；升级会牵动 Qwen/LTX 既有工作流与自定义节点）。
2. **不装 `ComfyUI-GGUF`**（除非落到 §4-C 兜底档）。
3. **不动** §0-2/§0-13/§0-17 等任何架构红线；本次不改 Java 推理代码（GPU 推理只在 Python worker）。
4. **不删** Qwen 任何文件（直到 §5.3 流程走完）。

---

## 7. 执行步骤（GPU 服务器恢复后照抄；每步都有"看见什么才算过"）

### Step 0 — 现场盘点（**只读**，不动任何东西）
```bash
# 0.1 地址 / 端口 / 目标分辨率（一律读配置，禁止写死）
ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select gpu_server_url, gpu_server_port, image_max_resolution, services->'image' from user_engine_settings where gpu_server_url is not null;\""

# 0.2 身份判据：argv[0] 必须是 /opt/weaveora/ComfyUI/main.py（否则先停系统自带、起我们那套，再出图）
curl -s "http://<GPU>:<HTTP端口>/system_stats" | python3 -c 'import json,sys;print(json.load(sys.stdin)["system"]["argv"])'

# 0.3 ComfyUI 版本 + Flux2 节点/参数是否齐（齐了才继续）
curl -s "http://<GPU>:<HTTP端口>/object_info" | python3 -c '
import json,sys; j=json.load(sys.stdin)
for k in ["Flux2Scheduler","EmptyFlux2LatentImage","FluxGuidance","ReferenceLatent","SamplerCustomAdvanced","BasicGuider","SplitSigmasDenoise","ImageScaleToTotalPixels"]:
    print(("OK  " if k in j else "MISS"), k)
print("CLIPLoader.type 含 flux2:", "flux2" in j["CLIPLoader"]["input"]["required"]["type"][0])'

# 0.4 磁盘 + 软链 + 同名多副本（静默错值的头号来源）
ssh <gpu-alias> 'df -h / /addDisk /media/vipuser/addDisk 2>/dev/null; \
  readlink -f /opt/weaveora/ComfyUI/models /opt/weaveora/ComfyUI/models/diffusion_models; \
  find / -name "flux2_dev_*.safetensors" -o -name "mistral_3_small_flux2_*.safetensors" -printf "%12s %TY-%Tm-%Td %p\n" 2>/dev/null'

# 0.5 队列必须空（有 queued/running 不动权重/不重启）
ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select state,count(*) from generation_jobs where state in ('queued','running') group by 1;\""

# 0.6 现役出图基线留档（回滚用）：三项 env + 三个工作流路径 + md5
ssh <gpu-alias> 'md5sum /opt/weaveora/workflows/qwen_image_*.json; cp -a /etc/weaveora/weaveora-gpu-worker.env /root/weaveora-backups/weaveora-gpu-worker.env.bak.$(date +%Y%m%d-%H%M%S)'
```
**过 =** argv[0] 命中我们那套 / 8 个节点全 OK / `CLIPLoader.type` 含 flux2 / 磁盘余量 ≥ 53 GiB（不够就回 §5 走确认流程）/ 队列为 0 / 备份文件已生成。

### Step 1 — 下载（后台化，立即返回）
照 §3.3。**过 =** 日志最后一行进度在涨、5 个目标路径的 `.meta.json` 都在。

### Step 2 — 校验实体文件（**不看 `.done`**）
```bash
cd /addDisk/weaveora/models   # ← 按 Step 0 的实际落点
for f in diffusion_models/flux2_dev_fp8mixed.safetensors \
         text_encoders/mistral_3_small_flux2_fp8.safetensors \
         vae/flux2-vae.safetensors \
         loras/Flux_2-Turbo-LoRA_comfyui.safetensors \
         vae/full_encoder_small_decoder.safetensors; do
  printf "%14s  %s  %s\n" "$(stat -c%s "$f" 2>/dev/null || echo MISSING)" "$(sha256sum "$f" 2>/dev/null | cut -c1-16)" "$f"
done
```
**过 =** 字节数与 §3.1 逐字节相同 + sha256 前 16 位相同；不足 5 个文件就是没下完/漏了。

### Step 3 — 工作流落盘 + **先 POST 试一枪**（09-23 二段坑 1：模板摊平出的节点类型问题只有真 POST 才暴露）
```bash
# 3.1 三个 JSON 同时放仓库 deploy/comfy/ 与盒上 /opt/weaveora/workflows/（两处 md5 必须一致）
# 3.2 先拿一个空白底图/一条短正词裸跑，别用量产任务试
curl -s -X POST "http://<GPU>:<HTTP端口>/prompt" -H 'Content-Type: application/json' \
  -d "{\"prompt\": $(cat /opt/weaveora/workflows/flux2_dev_txt2img_api.json), \"client_id\": \"probe-flux2\"}"
```
**过 =** 返回 `prompt_id`（**返回 400 `prompt_outputs_failed_validation` = 节点/接线错，必须先修再继续**）。

### Step 4 — 代码改动 + 部署（§6.2 / §6.4）
```bash
cd /d/workspace/Weaveora && git pull
# 改 worker/comfy_client.py（Flux2 分支，纯增量、对 Qwen 通路零影响）→
bash deploy/vps-worker-deploy.sh      # 先确认脚本真的落盘（历史上出现过"静默空转"）
# 改 Java 默认值（若需要）→ bash deploy/api-deploy.sh ；前端（若加下拉）→ bash deploy/web-deploy.sh
```
**过 =** 部署脚本自己打印的 md5/ts 与本地一致；`/actuator/health` = UP；worker 日志有 `[comfy] 文生图配置（引擎配置下发）：…`。

### Step 5 — 引擎配置切换（灰度：**先只切 edit，或先只切一个非关键项目**）
按 §6.4 改 `services.image.*` → **回读确认**（命令见 §6.4 末尾）。
**过 =** 回读出来的 5 个值全是新值（没有 Qwen 残留）。

### Step 6 — A/B 实测（**拿数才算完**；`md5(positive_prompt)` 逐字节对照）
| 对照项 | 固定不变 | 变量 |
|---|---|---|
| 定妆照 txt2img | 同一正词 / 同 seed / 同尺寸 / 同参考图 | Qwen-Image vs FLUX.2 dev(20 步) vs FLUX.2 dev+Turbo(8 步) |
| 关键帧 edit（**取三人同框那类难镜**） | 同上 | Qwen-Image-Edit-2511 vs FLUX.2 dev edit（参考图 = 目标尺寸 vs 1MP 两组） |

量这 5 个数：`单张耗时 / 峰值显存 / 是否换页（swap 增量 + pressure）/ 参考图遵从度（`deploy/diag/wv_faceid.py` 身份验收）/ 主观像不像真人`。

### Step 7 — 判据与回滚
按 §8 判；不通过就按 §7.1 一键回滚（Qwen 权重要在工作流/env **原地不动**的前提下才回滚得动）。

### 7.1 回滚路径（执行前先把它贴给用户）
```bash
# ① 引擎配置回滚（DB）
sudo -u postgres psql -d weaveora -c "update user_engine_settings set services = jsonb_set(services,'{image}', '<Step 0.6 留档的原值>'::jsonb) where gpu_server_url is not null;"
# ② worker env 回滚
cp /root/weaveora-backups/weaveora-gpu-worker.env.bak.<ts> /etc/weaveora/weaveora-gpu-worker.env && systemctl restart weaveora-gpu-worker
# ③ 代码回滚
git revert <sha>；worker 侧恢复 /opt/weaveora/comfy_client.py.bak.<ts>
```
> ⚠️ 五段踩过的坑：**env 改动必须先留 `.bak`**；`vps-worker-deploy.sh` 会打印回滚命令。

---

## 8. 验收与判决规则（**现在写死，免得事后找理由**）

| 关口 | 判据（全中才算过） | 不中怎么办 |
|---|---|---|
| G1 能跑 | `/prompt` 返回 prompt_id；出图非黑非噪；峰值显存 < 45 GiB | 修工作流；仍不行 → §4-B（fp4 TE）→ 再不行 §4-C（GGUF） |
| G2 速度 | 1664×928 / 20 步 ≤ **Qwen-Image-Edit-2511 @ 40 步**（现网 435~476s @2K/40 步）的 **0.8 倍**；挂 Turbo LoRA(8 步) 后 ≤ 0.5 倍 | 只上 Turbo 档；或把关键帧尺寸降到 1MP |
| G3 一致性 | `wv_faceid.py` 身份验收：**≥ 现役 2511 的结果**；三人同框镜不丢人、不串服饰 | 不过就**不切生产**（这是本次唯一真正的目的） |
| G4 负词 | §6.3 的折进正词方案经产品确认，且"不要白底/不要棚拍感"实际生效（目视） | 回到 §6.3 重新定口径 |
| G5 回滚 | Step 7.1 三条命令实测可用（演练一次） | 先修回滚再谈上线 |

> **纪律（09-23 二段教训）**：指标漂亮 ≠ 功能实现。G3 必须**用户看片/看图**确认，不接受"指标过线"当结论。

---

## 9. 风险与未验证项（诚实清单）

| # | 风险 | 性质 | 缓解 |
|---|---|---|---|
| 1 | `fp8mixed 33.02 + fp8 TE 16.80 = 49.82 GiB > 47.4 GiB` 显存，**能否顺畅卸载换页未实测** | 🔴 最大未知 | §4-B 备件已定；Step 6 量"是否换页" |
| 2 | **48 GB 内存**（上一实例 47 GiB + swap 已用 2 GiB）：33 GiB 主模型 + 16.8 GiB 编码器同时驻留会打爆 | 🔴 | 调 `--cache-ram`（8/24/32 实测选）；出图与出片不并发 |
| 3 | **负词失效**（guidance 蒸馏）——产品语义改动 | 🟠 | §6.3，需产品确认 |
| 4 | 多参考 identity 一致性**是否真强于 2511**，官方无对比 | 🟠 | G3 判据，A/B 定 |
| 5 | 参考图按目标尺寸缩放 ⇒ 3 张参考 = 24k token，**可能比预期慢得多** | 🟠 | Step 5 做"参考图 1MP"对照 |
| 6 | 速度完全未实测（32B / 20 步 / 48 GB 单卡） | 🟠 | 不给承诺；G2 判据卡死 |
| 7 | 工作流"摊平"出的节点类型问题只有真 POST 才暴露 | 🟡 | Step 3 试枪 |
| 8 | 新实例的磁盘/内存可能又变（历史见过 98 GB 盘 / 62 GB 内存） | 🟡 | Step 0 现场盘点，不按记忆走 |
| 9 | 非 Blackwell 上 fp4_mixed TE 只有 dequant 回退（速度未实测） | 🟡 | 只在 A 档不行时启用 |
| 10 | 许可（本次用户明示不考虑；**但进生产前需重新确认**） | ⚪ 记账 | 上一版评估 §6 已列，不重复 |

---

## 10. 待用户逐项确认（**未确认前我不动手**）

1. **版本裁定**：认可「dev fp8mixed（33.02）+ fp8 TE（16.80）」为主集吗？还是要先按 §4-B（fp4 TE，省 5.4 GiB、可两者常驻）下？
2. **下载授权**：是否同意下载 **52.93 GiB / 5 个文件**到盒子数据盘（走 ModelScope 官方镜像 + 10 路分片 + 断点续传 + 后台化）？
3. **腾空间**：若 Step 0 盘点发现装不下 —— §5.2 的 ①②③④⑤（Qwen 出图四件套 + VAE，≈48.7 GiB）与 ⑥⑦⑧⑨，**逐项**告诉我允许动哪些；我会先贴恢复命令再执行。
   （我的建议：**先不动**，等 G3 验收过了再谈删。）
4. **负词口径**：接受「Flux2 通路下负词折进正词」吗？（涉及定妆照模板 + 关键帧 edit 注入 + 项目风格前缀三处资产）
5. **范围与顺序**：同意「先只备好权重 + 代码（离线可做）→ 盒子恢复后 Step 0~3 打通 → 拿 A/B 数 → 再决定是否灰度切生产」这个顺序吗？
6. **要不要现在就把离线部分做完**（3 个工作流 JSON + `comfy_client.py` 的 Flux2 注入分支，**纯增量、对 Qwen 通路零影响**）？这样盒子一恢复只剩"下载 + 试枪 + A/B"。

---

## 11. 一句话给产品

> 盒子的软件和网络都够（Flux2 节点在 0.34.0 里就有，权重在 ModelScope 免登录、可 10 路分片下），**"能不能跑"不是问题**；
> 问题是**换过去要付两笔账**：①负词从"生效"变"不生效"（提示词资产要改写）②32B 模型在 48 GB 卡上的显存/内存换页代价未知。
> 所以建议：**先花 53 GiB 把权重和通路备好，拿一镜难镜（三人同框）做 A/B，用 `wv_faceid.py` 的数和你的眼睛一起判**——数过了再切生产，过不了就停在原地，Qwen 一个字节都不删。
