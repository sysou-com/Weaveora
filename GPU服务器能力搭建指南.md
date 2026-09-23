# GPU 服务器能力搭建指南

> 适用：Weaveora 自托管 GPU 服务器（当前主力机 **GPU#2 / RTX 4090 48G**）。
> 目的：把服务器上已落地的能力（**文生图 / 图生图（多参考改图）/ 图转视频 / 对口型 / 整脸口型(talk) / 配音 / 配乐**）连模型、版本、下载地址、体积、加载顺序**和踩过的坑**一次写清，做到「换机可照着重装、查问题时能对得上号」。
>
> 关联文档：`docs/方案-FLUX2dev-替换Qwen出图通路-2026-09-23.md`（**出图现役方案的唯一出处**）、`docs/磁盘操作记录-2026-09-23-FLUX2部署.md`、`docs/lipsync-setup.md`、`docs/gpu-模型清单与镜像备份.md`
> 更新：**2026-09-24 01:xx**（出图切换为 FLUX.2 [dev] 并跑通生产；出片切 LTX-2.5；补今日 5 个新坑）
> ⚠️ **地址/端口一律不写在本文件里**：每次现场读（① DB `user_engine_settings.gpu_server_url/gpu_server_port` ② VPS `/etc/weaveora/weaveora-gpu-worker.env` 的 `WEAVEORA_COMFY_URL` ③ 盒上 `/system_stats` 的 `argv[0]` 作身份判据）。
> 🗑️ **2026-09-24 用户裁定移除的历史内容**：Windows 上搭 GPU 栈的步骤、以及**第一台 GPU 服务器（容器时代）**的整套内容（`/home/dataset-local` 持久卷、`scp/sftp 被关改 base64-over-ssh`、`36.103.182.217:30250`、`24G 卡` 档位推导、容器 overlay 会重置那几条）。
> 它们**没有丢**：全在 git 历史里（`git log -p -- GPU服务器能力搭建指南.md`），也可以用 `git show <旧提交>:GPU服务器能力搭建指南.md` 取回整份。

---

## 0. 一句话总览

| 能力 | 任务 kind | 跑在哪 | 主模型（现役） | 状态 |
|---|---|---|---|---|
| **文生图（定妆照 / 无参考帧）** | `portrait`、`still`(txt2img) | ComfyUI :8001 | **FLUX.2 [dev] fp8mixed** 33.02 GiB + **Mistral-3-Small fp8** 16.80 GiB + small decoder（官方口径 **20 步 / guidance 4.0**） | ✅ **生产在用**（2026-09-23 22:37 起；试枪 152s@1024²，生产 1664×928 实测 176–366s/张） |
| **图生图 / 多参考改图（关键帧）** | `still`(edit) | ComfyUI :8001 | 同上 + **ReferenceLatent 串链**（3 槽，与 `refs[:3]` 对齐；参考图缩到目标尺寸） | ✅ **生产在用**（关键帧 226s 实测；参考槽措辞自动改写 `Picture N → 参考图 N`） |
| **出图后放大** | `still`(后处理) | ComfyUI :8001 | SeedVR2 3B fp8（`WEAVEORA_IMAGE_UPSCALE` 开关；**当前关**） | 🟡 备用（权重在系统盘） |
| **图转视频（出片）** | `clip` | ComfyUI :8001 | **LTX-2.5 22B distilled int8** 21.5 GB + **gemma4-12b int8** 15.4 GB（1280×704 / 121 帧 / 24fps，可选 48fps×2） | ✅ **生产在用**（引擎页可切） |
| **图转视频（旧档）** | `clip` | ComfyUI :8001 | Wan2.2 I2V-A14B 双专家 fp8（2×14.29 GB）+ lightx2v 4 步 LoRA | 🟡 权重在位，引擎页可切回 |
| **对口型（普通对白）** | `lipsync` | ComfyUI :8001（LatentSync 节点） | **LatentSync 1.6** | ✅ 生产在用 |
| **整脸口型（喊叫/吟唱）** | `talk` | talk_server :8094 | **EchoMimicV3** + Wan2.1-Fun-1.3B 管线（22 GB） | ✅ 已接线生产在用 |
| **配音** | `voice` | tts_server :8091 | **CosyVoice2-0.5B** / CosyVoice-300M-SFT | ✅ 生产在用 |
| **配乐** | `bgm` | ComfyUI :8001 | **ACE-Step 1.5 Turbo aio**（9.34 GiB） | ✅ 生产在用 |
| **人脸/关键点/身份** | （内部依赖） | face_server :8093 | **insightface buffalo_l** + det_10g | ✅ 在用（非商用许可） |
| **转写（字幕/逐字）** | （按需） | tts_server :8091 `/transcribe` | **Whisper**（base / tiny） | ✅ 可用 |

> ⚠️ **已经退役/删掉的**（别再去找）：`qwen_image_fp8_e4m3fn`（旧文生图主力）与 `qwen_image_edit_2511_fp8mixed`（旧改图主力）**仍在盘上**但已不接生产（保留作 A/B 基线与回滚件）；
> `flux1-schnell-fp8` / `t5xxl_fp8` / `clip_l` / `vae/ae`（旧 FLUX.1 备选档）**已删**（2026-09-23）。

---

## 1. 机器档案与运行环境

### 1.1 主机（**以现网为准；地址/端口现场读，不写死**）

> 🔴 **地址纪律**：本文不记录任何 IP/端口当“当前值”（历史上换过十几次：`.166/167/177/169` 与容器时代的 `10558/21264`…）。
> 唯一权威来源 = 平台「生成引擎配置 → GPU 服务器地址 + 端口」；现场读取命令：
> ```bash
> ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select gpu_server_url, gpu_server_port from user_engine_settings where gpu_server_url is not null and gpu_server_port is not null;\""
> ```
> 代码/脚本里**不写死任何 GPU IP**：填了 GPU 服务器地址后，ComfyUI 推导为 `<gpu>`（网关根）、配音/转写 `<gpu>/audio`、整脸口型 `<gpu>/talk`、人脸 `<gpu>`。

| 项 | 值（2026-09-24 实测） |
|---|---|
| 机型 | **GPU#2 = 云主机 VM**（主机名 `ubuntu24`；ssh 别名 `gpu`/`gpu2`/`weaveora-gpu-a14b` 指向同一台，改端口**三处必须同步**） |
| GPU | **RTX 4090 48G**（`nvidia-smi` 49140 MiB；ComfyUI `/system_stats` `vram_total` 47.4 GiB） |
| CPU / 内存 | 16 vCPU / **48 GiB RAM**（48168 MB）+ 8 GiB swap |
| OS | Ubuntu 24.04（kernel 6.x） |
| 系统盘 | `/dev/vda1` 196 GiB，**余 ~8.8 GiB（~96% 已用）** —— 提醒：ComfyUI 的 `input/output/temp` 都写在系统盘 |
| 数据盘 | `/dev/vdb1` ext4 **98 GiB → `/addDisk`**（持久盘，换实例还在；另建兼容软链 `/media/vipuser/addDisk → /addDisk`），**余 ~3.5 GiB（97%）** |
| 持久卷 | **`/opt/weaveora`**（模型/脚本/日志/产物；模型可在 `/addDisk` + 软链） |
| 已知瑕疵 | `nvidia-smi` 有时报 NVML 版本错配（内核模块 vs 用户态）→ 计算不受影响；**判断显存一律读 ComfyUI `/system_stats`，判断忙闲读 `/queue`** |

> ⛔ **本机最大的结构约束（2026-09-24 两次真事故）**：48 GiB 内存 + 47.4 GiB 显存装不下两套大模型 ——
> **图像家族 49.8 GiB**（FLUX.2 33 + 编码器 16.8）与**视频家族 34 GiB**（LTX 20 + gemma 14）**必须错开**。
> 不重启就换家族 = OOM killer 杀 ComfyUI（`anon-rss 47.8 GB`），而 worker 只看到 `Connection refused`。
> 现役解法：**worker 自动重启 ComfyUI**（判据与实现见 §4 坑 47–49）。

### 1.2 服务矩阵（systemd 单元 `weaveora-stack.service`，平台自启）

| 端口 | 服务 | 启动脚本 | 能力 |
|---|---|---|---|
| **8800** | `edge_proxy.py` | `/opt/weaveora/edge_proxy.py` | 单端口多路复用（公网只映射一个口）+ 两个自用路由（`/__edge/health`、`/__edge/reload_comfy`） |
| **8001** | ComfyUI **0.34.0** | `ComfyUI/main.py --listen 0.0.0.0 --port 8001 **--cache-ram 16 16** --reserve-vram 0.5` | 出图 / 出片 / 对口型 / 配乐 的宿主 |
| **8091** | `tts_server.py` | `audio/tts_server.py 8091` | 配音 `/tts`、转写 `/transcribe`、`/load` `/unload` |
| 8092 | `music_server.py` | 默认**不起**（HTTP 兜底） | 配乐（主线走 ComfyUI 原生节点） |
| **8093** | `face_server.py --device cpu` | `face/face_server.py` | `/face/probe`（关键点/嘴张开度）、`/face/embed`（身份向量） |
| **8094** | `talk_server.py` | `talk/talk_server.py` | 整脸口型 `/talk`、`/talk_batch` |

> 🔴 **ComfyUI 启动参数为什么是 `--cache-ram 16 16`**（2026-09-24 修正；本文件旧版写的 `--disable-smart-memory --cache-none` 是**错的且危险**，已删）：
> 盒上源码 `comfy/model_management.py:720 ensure_pin_budget()` 的余量 = `max(RAM_CACHE_HEADROOM/2, 2GB)`，而 `RAM_CACHE_HEADROOM` ← `--cache-ram` 的**第一个值**；
> 只给一个值（旧配置 `--cache-ram 8`）时，`main.py:356` 会把第二个值（inactive/pin 阈值）默认成**总内存的 100%**，
> 于是换模型时旧模型赖着不卸 → OOM。现网实测：`16 16` 下同家族连续出图正常，跨家族由 worker 主动重启兜住。
> ⛔ **`--cache-none` 是铁律③ 明令禁止的**（每个节点每次重跑）；`--disable-smart-memory` 语义是“更激进地往 RAM 卸”（方向相反，当年那轮 OOM 就是它）。

### 1.3 网关路由（`deploy/edge_proxy.py`）

| 公网路径 | 转发 | 覆盖 |
|---|---|---|
| `/audio/*` | `127.0.0.1:8091`（剥前缀） | 配音、转写 |
| `/bgm/*` | `127.0.0.1:8092`（剥前缀） | 配乐兜底 |
| `/face/*` | `127.0.0.1:8093` | 人脸探测 |
| `/talk*` | `127.0.0.1:8094` | 整脸口型（**已接线**；`/talk/health`、`/talk_batch`、`/talk` 三条都保留原路径） |
| `/__edge/health` | 网关自检 | 路由表 |
| `/__edge/reload_comfy` (**POST**) | 网关节点的**内部管理路由** | `X-WV-Token` = 盒上 `WEAVEORA_EDGE_ADMIN_TOKEN`；作用 = 只重启 ComfyUI（杀 `main.py` 再跑幂等的 `services_up.sh`），**网关/TTS/face/talk 不受影响**，~10–18s 回来。**给 worker 做跨模型家族切换用**（见 §4 坑 47–49）；无 token 一律 403 |
| 其余 `/*` | `127.0.0.1:8001` | ComfyUI（`/prompt` `/history` `/view` `/upload/image` `/ws`） |

### 1.4 软件版本矩阵
| 组件 | 版本 | 位置 |
|---|---|---|
| ComfyUI | **0.34.0**（`__version__`） | `/opt/weaveora/ComfyUI`（venv 独立） |
| ComfyUI 自定义节点 | `ComfyUI-LatentSyncWrapper`（含 `/weaveora/version` 版本接口） | `ComfyUI/custom_nodes/` |
| talk 环境 | **torch 2.7.1+cu126**、torchvision 0.22.1、**diffusers 0.35.1**、**transformers 4.57.6**、accelerate 1.15.0、insightface 2.0、onnxruntime 1.30.0、numpy 2.5.3、safetensors 0.8.0、torchdiffeq 0.2.5、torchsde 0.2.6、huggingface_hub 0.36.2 | `/opt/weaveora/envs/talk` |
| 配音环境 | CosyVoice 依赖栈 | `/opt/weaveora/envs/cosy` |
| 下载器 | Node `gpu_model_downloader.js`（10 路 Range + `.meta.json` 续传 + `.done`） | `/opt/weaveora/opt-node/bin/node` |

### 1.5 配置项映射（能力 ↔ 平台配置页字段）

| 能力 | 平台「生成引擎配置」里的字段 | 留空时的行为 |
|---|---|---|
| 文生图 | **图像引擎 = GPU 服务器** +「服务地址 → 文生图」：`出图引擎`（comfy/builtin）、`ComfyUI 地址`、`文生图工作流`、`图生图工作流`、`主模型名`、`步数`、`图生图 denoise` | 用 worker 自带默认（老 SDXL 路线）；ComfyUI 地址自动取 `<GPU 服务器>:8001` |
| 图转视频（出片） | 「视频引擎 = GPU 服务器」+「视频参数」（preset/steps/cfg/lora/shift 等，透传为 `services.motion`） | worker 默认档 `balanced` |
| 对口型 | 「服务地址 → 对口型 ComfyUI 地址 / 工作流路径 / 超时 / 帧率」 | ComfyUI 自动取 `<GPU 服务器>`；工作流路径用 worker 本机默认 |
| **整脸口型（talk）** | 「服务地址 → 整脸口型服务地址 / 下颌曲线增益 jaw_gain」 | 自动取 `<GPU 服务器>/talk`；jaw_gain=1.0（原生） |
| 配音 | 「服务地址 → 配音（TTS）服务地址」 | 自动取 `<GPU 服务器>/audio` |
| 配乐 | 「服务地址 → 配乐引擎（comfy/http）/ 配乐服务地址 / 配乐权重名」 | comfy + `<GPU 服务器>`（ACE-Step） |
| 转写 | 「服务地址 → 转写服务地址」 | 自动取 `<GPU 服务器>/audio` |
| 人脸 | 「服务地址 → 人脸服务地址 / 节点目录」 | 自动取 `<GPU 服务器>`；再留空则用 worker 本机 insightface |
| GPU 机器能力 | 「GPU 服务器地址 + 端口」「GPU 最大支持分辨率」 | —（必须配，否则走 worker 本地默认） |

> 两道纪律（都是踩坑换的）：① 机器本地地址（`127.0.0.1:8091` 这类）**绝不能**当默认值下发——API 主机不是 worker 主机；
> ② 但用户填了「GPU 服务器地址」后，各服务地址一律**从它推导**，这样公网 IP 变了只改一处。

### 1.6 目录约定

```
/opt/weaveora/
├── ComfyUI/                 # ComfyUI 0.34.0；models → 软链到 ../models
├── models/                  # ComfyUI 读的模型根（用户新增盘也软链到这里）
│   ├── diffusion_models/ text_encoders/ vae/ loras/ checkpoints/ face_aux/ echo_mimic/ face_id/
├── latentsync/              # LatentSync 1.6：latentsync_unet.pt / vae / whisper / config.json
├── audio/CosyVoice/pretrained_models/{CosyVoice2-0.5B,CosyVoice-300M-SFT}
├── echo_mimic_v3/           # EchoMimicV3 代码（models/ → 软链到 models/echo_mimic）
├── envs/{talk,cosy}/        # 两个 venv
├── talk/ talk_in/ talk_out/ talk_work/   # talk 服务与收发料
├── img_out/                 # 出图产物
├── logs/                    # 全部日志（dl_* / service / sha256_manifest.txt）
├── *.sh                     # 下载/部署脚本（gpu2_dl_*.sh、hashall.sh …）
└── models 之外的持久数据：模型必须放这里，**不放 /tmp、不放系统盘**
```

**新盘接入方式（2026-09-15 起）**：所有**新增**大文件下到 `/media/vipuser/addDisk/weaveora/models/<子目录>/`，再在 `/opt/weaveora/models/<子目录>/` 建**同名软链**。ComfyUI 每次请求都会重扫目录，**不需要重启**。

---

## 2. 能力清单总表（模型 / 版本 / 作用 / 地址 / 体积 / sha256）

> 所有 sha256 均为 **本机实测**（脚本 `/opt/weaveora/hashall.sh` → `logs/sha256_manifest.txt`）；标注「=官方」的表示与官方公布值逐字节一致。

### 2.1 文生图 / 图生图（多参考改图）—— **FLUX.2 [dev]（现役主力）**

| # | 文件（落盘） | 版本/作用 | 字节数 | sha256 | 下载地址 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/flux2_dev_fp8mixed.safetensors`（→ `/addDisk` + 软链） | **FLUX.2 [dev] 32B** 主模型 fp8mixed（带 `_quantization_metadata`，逐层 `float8_e4m3fn`；556 个张量） | 35,455,599,592 | `863a82e4ff950a42a6b0e80bea824828f129eb1a8fbbdbd9e8cb29859127b486`（**=官方**） | ModelScope `Comfy-Org/flux2-dev` → `split_files/diffusion_models/flux2_dev_fp8mixed.safetensors` |
| 2 | `text_encoders/mistral_3_small_flux2_fp8.safetensors` | **Mistral-3-Small 24B** 文本编码器 fp8（`CLIPLoader.type=flux2`） | 18,034,640,095 | `e3467b7d912a234fb929cdf215dc08efdb011810b44bc21081c4234cc75b370e`（=官方） | 同上 → `split_files/text_encoders/mistral_3_small_flux2_fp8.safetensors` |
| 3 | `vae/full_encoder_small_decoder.safetensors` | FLUX.2 VAE（**官方模板当前默认**；解码省显存） | 249,519,092 | `ea4273f02d1fafbf…`（前 16 位） | ModelScope `black-forest-labs/FLUX.2-small-decoder` → `full_encoder_small_decoder.safetensors` |
| 4 | `vae/flux2-vae.safetensors` | FLUX.2 VAE（备选，官方量化档模板用） | 336,213,556 | `d64f3a68e1cc4f9f4e29b6e0da38a0204fe9a49f2d4053f0ec1fa1ca02f9c4b5`（=官方） | `Comfy-Org/flux2-dev` → `split_files/vae/flux2-vae.safetensors` |
| 5 | `loras/Flux_2-Turbo-LoRA_comfyui.safetensors` | **Turbo 8 步加速档**（挂了就把 20 步→8 步，guidance 仍 4.0） | 2,760,814,880 | `011487390b8020baf22a9d543930c90d74a4809b7241bee6b0622777b17b413b`（=官方） | 同上 → `split_files/loras/Flux_2-Turbo-LoRA_comfyui.safetensors` |
| | **小计** | | **≈ 52.9 GiB** | | |

**工作流**（仓库内 API 格式，worker 直接 `POST /prompt`；盒上同位置三处 md5 一致）：
`deploy/comfy/flux2_dev_txt2img_api.json`（文生图/定妆照）、`flux2_dev_edit_api.json`（**多参考改图**：`LoadImage → ImageScale → VAEEncode → ReferenceLatent` 串链，3 槽对齐 `refs[:3]`）、`flux2_dev_img2img_api.json`（真 img2img：`SplitSigmasDenoise.low_sigmas`）。

**参数口径（与 Qwen 完全不是一套旋钮，不可互推）**：`Flux2Scheduler(steps=20, W, H)` + `FluxGuidance(4.0)` + `KSamplerSelect(euler)` + `SamplerCustomAdvanced` + `BasicGuider`。
- worker 把 `services.image.cfg` **映射到 `FluxGuidance.guidance`**（不是真 CFG）；
- **负词在 guidance 蒸馏下不生效**（无 uncond 分支）⇒ worker 把负词**折进正词**（`_flux2_fold_negative`，可用 `WEAVEORA_FLUX2_FOLD_NEGATIVE=0` 关）；
- 生产正词是中文、且写着 Qwen 口径的 `Picture N (imageN)` ⇒ worker 自动改写成 `参考图 N`（`_flux2_slot_rewrite`），否则参考图映射会丢；
- 区域条件（`ConditioningSetArea*`）对 Flux2 **无效**（同 Qwen），位置只写进提示词。

**实测**：1024²/20 步 txt2img 151.5s、单参考 edit 114.3s、img2img(0.65) 49.3s（试枪）；生产 1664×928 定妆照 366s→176s（第二张起是稳态）、关键帧 226s。

### 2.2 已退役 / 已删的旧出图档（**别再照它重装**）

| 档 | 文件 | 现状 |
|---|---|---|
| Qwen-Image（旧文生图主力） | `qwen_image_fp8_e4m3fn` 20.43 GB + `qwen_2.5_vl_7b_fp8_scaled` 9.38 GB + `qwen_image_vae` 0.25 GB + `Qwen-Image-Lightning-8steps-V1.0` 1.70 GB | **权重仍在盘上**，但已不接生产（留作 A/B 基线与回滚件）；回滚 = `bash deploy/image_variant_switch.sh qwen` |
| Qwen-Image-Edit-2511（旧改图主力） | `qwen_image_edit_2511_fp8mixed` 20.53 GB（→ `/addDisk`） | 同上（回滚件） |
| FLUX.1-schnell 备选档 | `flux1-schnell-fp8`、`t5xxl_fp8_e4m3fn`、`clip_l`、`vae/ae` | **已删**（2026-09-23，只被两份 `flux_schnell_*.json` 引用；恢复见 `docs/磁盘操作记录-2026-09-23-FLUX2部署.md §2`） |
| 出图后放大（现役开关） | `seedvr2_3b_fp8_e4m3fn` 3.16 GiB（2026-09-23 从 `/addDisk` **移到系统盘**） | `WEAVEORA_IMAGE_UPSCALE` 开关，**当前关** |

### 2.3 图转视频（出片）—— **LTX-2.5 22B（现役）** / Wan2.2 I2V-A14B（旧档）

**现役 = LTX-2.5**（`services.motion.engine=ltx25`）：1280×704 / 121 帧 / 24fps（可开 48fps 时间轴×2，实测 +7.7% 耗时、+0.3 GiB）。

| # | 文件 | 作用 | 字节数 | 地址 |
|---|---|---|---|---|
| 1 | `diffusion_models/ltx-2.5-22b-distilled-transformer-comfy-int8-convrot.safetensors` | LTX-2.5 22B **distilled** int8 主干 | 21,504,034,224 | ModelScope `Lightricks/LTX-2.5` |
| 2 | `text_encoders/gemma4-12b-with-proj-ltx-2.5-comfy-int8-convrot.safetensors` | **Gemma4-12B** 文本编码器 int8 | 15,372,969,374 | 同上 |
| 3 | `vae/ltx-2.5-video-vae-bf16.safetensors` / `vae/ltx-2.5-audio-vae-bf16.safetensors` | 视频 / 音频 VAE（音频 VAE 被三个 LTX 工作流引用，**别动**） | 1,472,223,346 / 364,866,540 | 同上 |
| 4 | `latent_upscale_models/ltx-2.5-latent-{spatial,temporal}-upscaler-x2-bf16-1.0.safetensors` | 空间/时间 latent 放大器（48fps 用） | 995,778,752 / 261,944,000 | 同上 |
| 5 | `loras/ltx-2.5-22b-ic-lora-pixel-spatial-upscaler-x2-1.0.safetensors`、`model_patches/ltx-2.5-duration-head-bf16.safetensors` | **零引用**（只在下载脚本里）；2026-09-23 已移到系统盘 | 327,322,640 / 3,843,690 | 同上 |
| | **小计** | | **≈ 40.3 GB** | |

> ⚠️ **LTX 的 dev 主干（int8，21.50 GB）与 distilled LoRA（8.90 GB）没下**（A2Vid 两段式才需要）—— 如需重置，见 `docs/方案-LTX2-A2Vid与DubIt评估-2026-09-23.md`。
> `ltx-2.5` 暂**没有**文生图 / 图生图能力（官方只有 t2v/i2v/flf2v）—— 出图別指望它。

**旧档 = Wan2.2 I2V-A14B 双专家**（引擎页可切回 `wan22`；试枪/回滚用）：

| # | 文件 | 版本/作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors` | **高噪声专家** 14B fp8（前 N 步） | 14,294,742,832 | `6122e79d55e0f235698d11d657f3b196c5273c830da00b2b013c5a048d5e6a42` | ModelScope `Comfy-Org/Wan_2.2_ComfyUI_Repackaged` → `split_files/diffusion_models/…` |
| 2 | `diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors` | **低噪声专家** 14B fp8（后 N 步） | 14,294,742,832 | `5471a457b6ac404202a5fbe6c11595a3d5641fc766b00f38763f72303fffc21e` | 同上 |
| 3 | `text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` | **UMT5-XXL** 文本编码器 fp8 + per-tensor scale | 6,735,906,897 | `c3355d30191f1f066b26d93fba017ae9809dce6c627dda5f6a66eaa651204f68` | 同上 → `split_files/text_encoders/…` |
| 4 | `vae/wan_2.1_vae.safetensors` | **Wan2.1 VAE**（A14B 必须用 2.1，**不是** wan2.2_vae） | 253,815,318 | `2fc39d31359a4b0a64f55876d8ff7fa8d780956ae2cb13463b0223e15148976b` | 同上 → `split_files/vae/wan_2.1_vae.safetensors` |
| 5 | `loras/Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | **lightx2v 4 步蒸馏 LoRA（高噪声）** | 630,695,648 | `8e0a86e765ade42a1deca52eb7411348254a019147be8c4eed88c7ad465d3399` | ModelScope/HF `Kijai/WanVideo_comfy` → `LoRAs/Wan22_Lightx2v/…` |
| 6 | `loras/Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | **lightx2v 4 步蒸馏 LoRA（低噪声）** | 630,695,648 | `09e10abd98460b66439bd77ea671e94c10fdc8251e0b986aa196d1904e3cc583` | 同上 |
| | **小计** | | **≈ 36.8 GB** | | |

### 2.5 对口型（普通对白）—— LatentSync 1.6 + ComfyUI 节点

| # | 文件 | 作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `latentsync/latentsync_unet.pt` | **LatentSync 1.6 UNet**（音频条件 latent 去噪） | 5,072,222,488 | `0a478e89eb660f82da4c35dbdde8a5adfb27f99d1b4e50edd03729e1e98316d3` | aifasthub `ByteDance/LatentSync-1.6` |
| 2 | `latentsync/vae/diffusion_pytorch_model.safetensors` | SD VAE **ft-mse**（帧编解码） | 334,643,276 | `a1d993488569e928462932c8c38a0760b874d166399b14414135bd9c42df5815` | aifasthub `stabilityai/sd-vae-ft-mse` |
| 3 | `latentsync/whisper/tiny.pt` | **Whisper tiny**（音频→特征） | 75,572,083 | `65147644a518d12f04e32d6f3b26facc3f8dd46e5390956a9424a650c0ce22b9` | aifasthub `ByteDance/LatentSync-1.6` |
| 4 | `…/checkpoints/auxiliary/models/buffalo_l/*.onnx` ×5 | insightface **buffalo_l v0.7**（脸检测/关键点/身份） | 288,621,354（zip） | 见 §2.9 | `ghfast.top/https://github.com/deepinsight/insightface` release v0.7 `buffalo_l.zip` |
| 5 | `latentsync/config.json` / `vae/config.json` | 结构配置 | 32 / 547 | — | aifasthub |
| — | `stable_syncnet.pt`（评估用，**推理不加载**） | 1.50 GiB | 未下（可后置） | aifasthub |
| | **小计（推理必需）** | | **≈ 5.4 GB** | | |

**工作流**（`docs/lipsync-setup.md`，API 格式：`D:\ComfyUI\_setup\lipsync_workflow_api.json`，已同步 GPU 侧）：

```
1 LoadVideo(title=video) ─┐
2 GetVideoComponents      ├─► 4 LatentSyncNode(images, audio, seed, lips_expression, inference_steps)
3 LoadAudio(title=audio) ─┘        └─► 5 CreateVideo(images, audio, fps) ─► 6 SaveVideo(filename_prefix)
```

worker 按**节点标题**找节点（`video`/`audio` 可 env 改名），按**输入键**注入上传文件名。

### 2.6 整脸口型（喊叫/吟唱镜）—— EchoMimicV3（talk / jaw-lip）

> 官方管线是**完整 Wan-Fun diffusers 管线**：文本编码器 + 图像编码器 + VAE + transformer 一个都不能少 —— 所以体积偏大是官方代码结构决定的。

| # | 文件（`models/echo_mimic/…`） | 作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `transformer/diffusion_pytorch_model.safetensors` | **EchoMimicV3 transformer**（音频+图→说话头） | 3,414,541,616 | `ba25a75c7511ba57a346480d50567b57270282c1f3ab121558c07b65a97adf1f` | ModelScope `BadToBest/EchoMimicV3`（`transformer/`） |
| 2 | `Wan2.1-Fun-V1.1-1.3B-InP/diffusion_pytorch_model.safetensors` | Wan-Fun **1.3B 基座** transformer | 3,128,957,992 | `4ec199076538b946935ebcb3ba808d3c427e638f29519a3c3c98d31d821e5eed`（=官方） | aifasthub `alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP` |
| 3 | `Wan2.1-Fun-V1.1-1.3B-InP/models_t5_umt5-xxl-enc-bf16.pth` | **UMT5-XXL bf16 原始 .pth**（文本条件） | 11,361,920,418 | `7cace0da2b446bbbbc57d031ab6cf163a3d59b366da94e5afe36745b746fd81d`（=官方） | 同上 |
| 4 | `Wan2.1-Fun-V1.1-1.3B-InP/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth` | **CLIP xlm-roberta-large-ViT-H/14**（图像/文本条件） | 4,772,359,047 | `628c9998b613391f193eb67ff68da9667d75f492911e4eb3decf23460a158c38`（=官方） | aifasthub / hf-mirror `alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP` |
| 5 | `Wan2.1-Fun-V1.1-1.3B-InP/Wan2.1_VAE.pth` | **Wan2.1 VAE**（.pth 版） | 507,609,880 | `38071ab59bd94681c686fa51d75a1968f64e470262043be31f7a094e442fd981`（=官方） | 同上 |
| 6 | `wav2vec2-base-960h/model.safetensors` | **wav2vec2-base-960h**（音频→特征） | 377,607,901 | `8aa76ab2243c81747a1f832954586bc566090c83a0ac167df6f31f0fa917d74a`（=官方） | ModelScope `facebook/wav2vec2-base-960h` |
| 7 | `Wan2.1-Fun-…/{config.json,google/umt5-xxl/*,xlm-roberta-large/*}` | tokenizer/配置（11 个小文件） | ≈ 5 MB | — | 同上 |
| | **小计** | | **≈ 23.6 GB** | | |

⚠️ **两个 umt5 不是同一个文件**（详见 `deploy/gpu2_echo_models_MANIFEST.txt`）：
- `models/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` → fp8+scale，**ComfyUI 键名**（Wan2.2 I2V 出片用）；
- `models/echo_mimic/…/models_t5_umt5-xxl-enc-bf16.pth` → bf16 原始 .pth，**官方 Wan 键名**（EchoMimic 用）。
两者**禁止互换/软链混用**（喂错会静默错值）。

**服务**：`talk/talk_server.py`（:8094）提供 `/talk`（单镜）与 `/talk_batch`（批量摊薄加载），内含**下颌曲线层**（响度包络 → 下半脸 remap，`jaw_gain` 1.0=原生 / 1.25=增强），OOM 自动降档（113→81 帧 / 768→512 / steps≤8）。

### 2.7 配音 —— CosyVoice

| # | 文件 | 版本/作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `CosyVoice2-0.5B/llm.pt` | **CosyVoice2-0.5B** LLM（语音 token 生成） | 2,023,316,821 | `b144ef55b51ce8cfb79a73c90dbba0bdaba4e451c0ebcfab20f769264f84a608` | ModelScope `iic/CosyVoice2-0.5B` |
| 2 | `CosyVoice2-0.5B/flow.pt` | flow matching（token→mel） | 450,575,567 | `ff4c2f867674411e0a08cee702996df13fa67c1cd864c06108da88d16d088541` | 同上 |
| 3 | `CosyVoice2-0.5B/hift.pt` | HiFiT 声码器（mel→波形 24 kHz） | 83,390,254 | `3386cc880324d4e98e05987b99107f49e40ed925b8ecc87c1f4939432d429879` | 同上 |
| 4 | `CosyVoice2-0.5B/speech_tokenizer_v2.onnx` | 语音 tokenizer | 496,082,973 | `d43342aa12163a80bf07bffb94c9de2e120a8df2f9917cd2f642e7f4219c6f71` | 同上 |
| 5 | `CosyVoice2-0.5B/CosyVoice-BlankEN/model.safetensors` | 文本编码基座（Qwen 系） | 988,097,824 | `130282af0dfa9fe5840737cc49a0d339d06075f83c5a315c3372c9a0740d0b96` | 同上 |
| 6 | `CosyVoice-300M-SFT/{llm.pt,flow.pt,hift.pt,speech_tokenizer_v1.onnx}` | **CosyVoice-300M-SFT**：8 个内置音色（无需参考音频） | 1,242,994,835 / 419,900,943 / 81,896,716 / 522,624,269 | `d198ce56…` / `21eae78c…` / `91e679b6…` / `23b5a723…` | ModelScope `iic/CosyVoice-300M-SFT` |
| | **小计** | | **≈ 6.0 GB** | | |

**服务**：`audio/tts_server.py`（:8091）`/tts`（SFT 音色 / zero-shot 克隆）、`/transcribe`（转写）、`/load`、`/unload`（**出片前让显存**用）。

### 2.8 配乐 —— ACE-Step 1.5 Turbo

| # | 文件 | 版本/作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `checkpoints/ace_step_1.5_turbo_aio.safetensors` | **ACE-Step 1.5 Turbo（all-in-one）**：整片配乐生成 | 10,025,478,736 | `67b0f43aa5c51c840bd0228e6a935d8ff416ec87e5df2fc0637da17a561252bc` | ModelScope `Comfy-Org/ace_step_1.5_ComfyUI_files` → `checkpoints/ace_step_1.5_turbo_aio.safetensors` |

> 官方另有 `split_files/`（qwen 编码器 + vae ≈20 GB），**仅原生分体工作流需要，本机未下**（aio 单文件自含全部组件）。

### 2.9 人脸 / 关键点 / 身份（内部依赖）

| 文件 | 作用 | 字节数 | 地址 |
|---|---|---|---|
| `latentsync/…/buffalo_l/1k3d68.onnx` | 3D 68 点关键点 | 143,607,619 | `ghfast.top/https://github.com/deepinsight/insightface` v0.7 `buffalo_l.zip` |
| `…/buffalo_l/w600k_r50.onnx` | 人脸识别/身份向量（512 维） | 174,383,860 | 同上 |
| `…/buffalo_l/det_10g.onnx` | 人脸检测 | 16,923,827 | 同上 |
| `…/buffalo_l/2d106det.onnx` | 106 点关键点（嘴张开度口径） | 5,030,888 | 同上 |
| `…/buffalo_l/genderage.onnx` | 性别年龄 | 1,322,532 | 同上 |
| `models/face_aux/models/buffalo_l/det_10g.onnx` | 私有 aux（face 服务用） | 16,923,827 | 同上（单独拷贝） |
| `models/face_id/*.onnx`（可选） | 定妆照身份锚定 | ~0.02 GB | 见 `docs/gpu-server-setup.md` |

**服务**：`face/face_server.py`（:8093，`--device cpu`）→ `/face/probe`（脸宽 px、`mouth_open` 比值）、`/face/embed`（512 维身份向量）。

### 2.10 转写 —— Whisper

| 文件 | 作用 | 字节数 | 地址 |
|---|---|---|---|
| `latentsync/whisper/tiny.pt` | LatentSync 内部音频特征（也是最小转写档） | 75,572,083 | aifasthub `ByteDance/LatentSync-1.6` |
| Whisper `base.pt`（按需） | 字幕/逐字转写（`/audio/transcribe`） | 0.14 GiB | 见 `docs/gpu-newserver-models.md` |

---

## 3. 各能力的权重加载顺序（调用链）

> 「顺序」= 一次请求里，服务端实际**依次加载/串联**的东西；显存峰值出现在最高点，换能力前必须让上一个释放。

### 3.1 文生图 / 图生图（**FLUX.2 [dev]**，ComfyUI） —— 旧版本节写的是 Qwen-Image，已切换

```
UNETLoader(qwen_image_fp8_e4m3fn, weight_dtype=default)
  → CLIPLoader(qwen_2.5_vl_7b_fp8_scaled, type=qwen_image)
  → VAELoader(qwen_image_vae)
  → LoraLoaderModelOnly(Qwen-Image-Lightning-8steps, strength=1.0)     # 8 步蒸馏
  → ModelSamplingAuraFlow(shift=3.0)
  → CLIPTextEncode(正词) / CLIPTextEncode(负词)
  → EmptySD3LatentImage(1344×768, batch=1)
  → KSampler(euler / simple, steps=8, cfg=1.0, seed)
  → VAEDecode → SaveImage
```

**图生图（关键帧当底图）**：把 `EmptySD3LatentImage` 换成 `LoadImage(关键帧) → VAEEncode`，`denoise` 控改动幅度（**0.65 保构图人物 / 0.85 大改**）。
**显存**：Qwen-Image 20G + Qwen2.5-VL 9.4G + VAE/LoRA ≈ **31 GB**（48G 卡单跑轻松；**不能与 A14B 出片同时驻留**）。
**实测（2026-09-15，62G 内存 + 已恢复 `--disable-smart-memory`）**：

| 档 | 配置 | 出图耗时（含加载） |
|---|---|---|
| **默认（B 高步数电影档）** | Qwen-Image **去 Lightning**、`cfg 3.5`、**24 步**、参考图走 img2img `denoise 0.50` | 86–180 s |
| A（旧档） | Lightning **8 步**、**cfg 1.0** → **负向词完全失效**、风格遵从弱（**这是"偏插画/古典、与云端差距大"的主因**） | 86 s |
| C（参考图锚定） | **Qwen-Image-Edit**（2 张参考图编码进 conditioning） | 121 s |
| D（快档/写实） | **FLUX.1-schnell** img2img 0.50 / 4 步 | 43 s |

> 档位通过「生成引擎配置 → 服务地址 → 文生图」切换：`workflow`（无参考图）、`img2imgWorkflow`（有参考图）、
> `editWorkflow`（填了它则**参考图锚定**优先，否则走 img2img）、`steps`、`cfg`、`denoise`。
> 对应工作流文件在 worker 机器 `/opt/weaveora/workflows/`：`qwen_image_{txt2img,img2img}_film_api.json`（B）、
> `qwen_image_{txt2img,img2img}_api.json`（A）、`qwen_image_edit_api.json`（C）、`flux_schnell_{txt2img,img2img}_api.json`（D）。
> **风格永远来自项目配置**（payload 里的正/负词已由后端注入项目风格模板），档位只改模型/步数/引导，不写死风格。

### 3.2 图转视频（Wan2.2 I2V-A14B 双专家）

```
CLIPLoader(umt5_xxl_fp8_e4m3fn_scaled)                     # 文本编码
VAELoader(wan_2.1_vae)                                     # ⚠ 必须 2.1 VAE
UNETLoader(wan2.2_i2v_high_noise_14B_fp8_scaled) + LoRA(high)
UNETLoader(wan2.2_i2v_low_noise_14B_fp8_scaled)  + LoRA(low)
  → 首图 LoadImage → VAEEncode（起始帧）
  → 高级采样：前 `switch` 步用 high 专家，之后切 low 专家
  → VAEDecode → CreateVideo(fps=16 原生) → SaveVideo
```

档位预设（`worker/comfy_client.py`；★ hero/full = 2026-09-20 对齐官方口径后改定）：

| preset | steps | switch | cfg_high | cfg_low | lora_high | lora_low | shift | 口径 / 来源 |
|---|---|---|---|---|---|---|---|---|
| draft | 4 | 2 | 1.0 | 1.0 | 0.0 | 1.0 | 5.0 | 官方**加速档**骨架（只留低噪蒸馏） |
| balanced | 6 | 3 | 1.0 | 1.0 | 0.0 | 1.0 | 5.0 | 生产默认 |
| motion | 8 | 4 | 1.5 | 1.0 | 0.0 | 1.0 | 5.0 | 动态优先 |
| **hero** | **20** | **10** | **3.5** | **3.5** | **0.0** | **0.0** | **5.0** | **官方质量档**：ComfyUI 模板 `video_wan2_2_14B_i2v.json`（不蒸馏） |
| **full** | **40** | **36** | **3.5** | **3.5** | **0.0** | **0.0** | **5.0** | **官方原厂档**：`wan/configs/wan_i2v_A14B.py`（不蒸馏） |

**官方口径（2026-09-20 实抓一手源）**：
- Wan 仓库 `wan/configs/wan_i2v_A14B.py`：`sample_shift=5.0`、`sample_steps=40`、`boundary=0.900`、`sample_guide_scale=(3.5, 3.5)`（低噪/高噪）
  → 我们按**步序**切专家（官方 `boundary` 按**时间步**，非同一语义）⇒ full 的 switch ≈ 0.9×40 = **36**。
- ComfyUI 模板 `video_wan2_2_14B_i2v.json`：质量档 `steps 20 / switch 10 / cfg 3.5 / 不挂 LoRA / shift 5.0 / euler+simple`；
  加速档 `steps 4 / switch 2 / cfg 1.0 / **高噪+低噪两个专家都挂 4 步蒸馏 LoRA@1.0** / shift 5.0`。
  模板自带耗时表（RTX4090D 24G @640×640/81 帧）：fp8_scaled **≈536s / 513s**；fp8_scaled + 4steps LoRA **≈97s / 71s**。
- ⚠ 盒子差异：官方加速档给**高噪声专家**也挂 LoRA，而我们在 48G 上实测会 OOM（fp8 权重需再 dequant 一份 ≈28GiB）
  ⇒ 生产一律 `lora_high=0`；要复现官方加速档只能换 Comfy 官方 repack 的 `wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors` 并先做显存验证。
- ⚠ 我们盘上的蒸馏 LoRA 是 **260412 rank64 fp16（2026-04-20，720p 重训版）**，比模板引用的 `..._4steps_lora_v1_*` 新一代 ⇒ **强度约定可能不同，勿直接照搬 1.0**。

**显存**：双专家 fp8 + umt5 ≈ **峰值 42 GB** → 与 talk/LatentSync/TTS **必须串行**（worker 会在 clip 前调 TTS `/unload` 让显存）。

### 3.3 对口型（LatentSync 1.6）

```
LoadVideo(底片: motion 片段 或 still 静帧)  +  LoadAudio(该镜配音 wav)
  → GetVideoComponents（拆帧 + 原音轨）
  → LatentSyncNode(images, audio, seed, lips_expression, inference_steps)
        ├─ 内部：Whisper tiny 提音频特征
        ├─ insightface buffalo_l 做脸对齐/掩码
        └─ latentsync_unet.pt + SD-VAE(ft-mse) 去噪
  → CreateVideo(images, audio, fps) → SaveVideo
```

**底片体检（出片前，B 保护）**：`face_server` 量 `mouth_open`（106 点中 **52–71** 为嘴部）与脸宽 px：
- 硬拒：`mouth_open ≥ 1.05`（默认 `WEAVEORA_LIPSYNC_MOUTH_MAX`）或脸宽 `< 64 px`；
- 软提醒：`≥ 0.90` 或脸宽 `< 96 px`；
- 实测标尺：**平静脸 0.44–0.66**、略开 0.59–0.78、**真·大张 1.23**。
**极端表情分流（C 保护）**：正词/action 命中 喊叫/尖叫/失声/scream… → ① 底片默认自动改**静帧**；② `lips_expression` 从 1.5 降到 `1.0`（节点下限）；③ 选镜弹窗标「⚠ 大张口风险」。

### 3.4 整脸口型 talk（EchoMimicV3 + 下颌曲线层）

```
talk_server :8094  /talk(或 /talk_batch)
  ├─ 参考图（该镜底片/静帧）+ 音频 wav
  ├─ wav2vec2-base-960h            → 音频特征
  ├─ models_t5_umt5-xxl-enc-bf16   → 文本/条件
  ├─ CLIP xlm-roberta-large-ViT-H  → 图像/文本条件
  ├─ Wan2.1_VAE.pth                → 图像/latent 编解码
  └─ Wan-Fun 1.3B 基座 + EchoMimicV3 transformer → 逐帧生成
  → 解码 mp4
  → 【我们的下颌曲线层】响度包络 → 下半脸 remap（jaw_gain；1.0 原生 / 1.25 增强）
```

**显存**：加载 ≈ 20 GB 级；`/talk_batch` 把"每请求重载 20 GB"摊薄到整批 → 单镜从 ≥10 min 降到 **2–2.5 min**（512²/81 帧/8 步）。OOM 自动降档：113→81 帧、768→512、steps≤8。

### 3.5 配音（CosyVoice）

```
tts_server :8091  /tts
  ├─ SFT 模式：CosyVoice-300M-SFT（llm.pt → flow.pt → hift.pt + speech_tokenizer_v1.onnx）
  └─ zero-shot 克隆：CosyVoice2-0.5B
        CosyVoice-BlankEN(model.safetensors) + speech_tokenizer_v2.onnx → llm.pt → flow.pt → hift.pt(24 kHz)
```
`/unload` 释放显存供出片；下次 `/tts` 自动懒加载。

### 3.6 配乐（ACE-Step，ComfyUI 原生节点）

```
CheckpointLoaderSimple(ace_step_1.5_turbo_aio)      # aio 单文件自含全部组件
  → ModelSamplingAuraFlow(shift=3)
  → TextEncodeAceStepAudio1.5(mood/prompt + 时长)
  → EmptyAceStep1.5LatentAudio
  → KSampler(euler / simple, steps=8, cfg=1.0)
  → VAEDecodeAudio → SaveAudioMP3(320k)
```
HTTP 兜底 `music_server.py`（:8092）默认不起，主线走 ComfyUI。

### 3.7 渲染混音（成片音轨）

```
静音底轨 → 逐镜 voice（按镜起点 adelay + apad 补齐）
         + bgm（volume 0.30，apad）
         → sidechaincompress（说话段自动压低音乐；失败退化为固定低音量）
```

### 3.8 出图「位置 + 参考图→主体」映射（P5，2026-09-16）

多主体同框时，Qwen-Image-Edit 拿到 `image1/image2` 却**不知道哪张脸是哪个角色**，只能自己猜 → 串脸/换人。
所以后端在正词里**点名主体**，并把「谁在哪」一并写清（不依赖 LLM 是否听话）：

```
plan.referenceAssets[].region            # ② 方案级默认位置（「位置预览」卡）
plan.subjects[].refs[].region            # ② 同上（前端 syncReferenceAssets 同时写两处）
shots[].lipsync_targets{subject:{x,y}}   # ③ 预览图点选（只有点 → 默认框 0.30×0.45）
shots[].layout=[{subject,x,y,w,h}]       # ① 逐镜位置（UI「画面位置」编辑器）—— 优先级最高
         ↓ JobService.applyLayoutRegions(plan, shot, refs)
referenceRegions[] = [{x,y,w,h} | null]   # 与 referenceKeys 严格同序（路线 B 的区域条件用）
positive_prompt += """
  Reference image mapping: image1 = 宝玉 (upper-left, x=0.19, y=0.09, box 0.30x0.45);
  image2 = 可卿 (upper-right, ...). Each subject's face, hair and costume strictly follow its own
  reference image, and each character is placed exactly at the position and relative size given
  (normalized frame coordinates, origin top-left; smaller y = higher in frame, larger box = closer
  to camera); keep the characters clearly apart."""
```

- **槽位顺序**（`image1` = `referenceKeys[0]`）与 worker 的 `comfy_client._wf_set_image()` 一致：
  `LoadImage` 按**节点 id 字符串排序**依次取图 → 在 `qwen_image_edit_api.json` 里就是 `12`(→`image1`)、`14`(→`image2`)。
  **改工作流时不要打乱 LoadImage 的 id 顺序**，否则提示词里的 imageN 会指错人。
- **槽位上限 = 3（2026-09-16 扩到 3）**：`qwen_image_edit_api.json` 现在有 3 个 `LoadImage`（节点 `12`/`14`/`16`，按 id 字符串序 = image1/2/3），两个 `TextEncodeQwenImageEditPlus`（正/负）都接了 `image1/image2/image3`。
  - ⚠️ 原来只有 2 个槽 → **第 3 个主体不会报错、图被静默丢掉**；实测导致「三主体镜（宝玉/可卿/警幻）里警幻没有参考图 → 人物不一致」。
  - worker 现在会：**多于槽位 → 告警**；**少于槽位 → 摘掉空槽**（`_wf_prune_unused_images`，不再复用第一张 —— 否则同一张脸会被注入两次）。
  - 上游某张参考图上传失败时槽位会**前移一格**（提示词里的 `imageN` 会错位）——日志 `[comfy] 参考图#N 上传失败（跳过）` 是唯一线索。
- 即使**一个位置都没设**，只要绑定了参考图也照样写映射（退化为「只点名、无坐标」）；
  参考图中**没有 subject 名的通用风格图不点名**（否则会凭空造出一个角色）。
- 路线 A（提示词描述位置，默认生效） vs 路线 B（`ConditioningSetAreaPercentage` 区域条件，`WEAVEORA_IMAGE_AREA_COND=1`，
  在 Edit 档会抛 `IndexError: tuple index out of range`，待离线调通）。UI 与后端**两路都发**，切开关不用改代码。
- 单测：`api/src/test/java/studio/weaveora/job/JobLayoutRegionsTest.java`（14 例：三档优先级、中文/英文句、主体点名、`cast` 空镜语义）。

### 3.9 出图档位旋钮（2026-09-16：修好 cfg 推送）

| 旋钮 | 下发路径 | 优先级 | 备注 |
|---|---|---|---|
| `workflow` / `editWorkflow` / `img2imgWorkflow` | `services.image.*` → worker `IMAGE_*_WF` | — | 路径是 **worker（VPS）机器上的绝对路径**，不是 GPU 机；有参考图且有 editWorkflow → Edit |
| `steps` | `services.image.steps` → `IMAGE_STEPS` | 配置页 > `payload.params.steps` > 工作流 JSON | |
| `cfg` | `services.image.cfg` → `IMAGE_CFG` | 配置页 > `payload.params.cfg` > 工作流 JSON | ⛔ **2026-09-16 前是死字段**：worker 从没读过它（配置页填了也不生效）。修好后启动日志会打印 `cfg=` |
| `denoise` | `services.image.denoise` → `IMAGE_DENOISE` | 配置页 > 工作流 JSON | Edit 档恒 1.0（由代码强制） |

**Qwen-Image-Edit 2511 官方档位（基准）**：`steps 40` / `cfg(true_cfg_scale) 4.0` / `euler + simple` / `denoise 1.0` /
`ModelSamplingAuraFlow shift 3.1`（2509 是 3.0）；Qwen 官方口径 40/4.0，ComfyUI 模板同值。
低步数档（Lightning LoRA）才是 4 步 / cfg 1.0 —— **本项目不用 Lightning**（用户要求不为速度牺牲细节）。

**参考图接线（官方三套模板一致，我们已对齐）**：参考图 **同时**走两条线 ——
① `ImageScale → VAEEncode → KSampler.latent_image`（决定输出画布尺寸；`denoise=1.0` 时内容被丢弃、只有 shape 生效）；
② 同一张图 → `TextEncodeQwenImageEditPlus` 的 `image1/image2/image3` **且必须接 `vae`**（否则只进 VL 语义、没有 reference latents → 出图与参考图毫无关系）。
负词节点也用 `TextEncodeQwenImageEditPlus`（prompt 留空 + 同样接图与 vae），官方如此。

---

## 4. 部署过程中遇到的坑（现象 → 根因 → 处置）

### 4.1 网络与下载

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | `huggingface.co` 直连超时/被墙 | 出口网络 | **禁写死 huggingface.co**；改用 ModelScope API（`https://modelscope.cn/api/v1/models/<org>/<repo>/repo?Revision=master&FilePath=<urlencoded>`）或 aifasthub / `hf-mirror.com` |
| 2 | HF 镜像 API 一度 **403** | 镜像策略 | 切 ModelScope；用 `Range: bytes=0-0` 读 `Content-Range` **实测真实体积**再下 |
| 3 | ~~scp/sftp 到 GPU 被关~~ | ~~平台收敛（**容器时代**）~~ | ✅ **已不适**：现在是 VM，`scp` 直接可用（2026-09-23/24 实测）。仅当平台策略回退时才需 base64-over-ssh |
| 4 | `FileNotFoundError: /tmp/xxx`（**本机**） | Windows 上 python 把 `/tmp` 解析成 `D:\tmp` | 属**本机工具链**范畴，见 `docs/notes/本机工具链坑.md`；查本地文件一律用 `C:/Users/<用户>/AppData/Local/Temp/...` |
| 5 | 下载慢/断流 | 单连接限速（实测单连接 ~1.3 MiB/s，被服务端限速） | 统一走 `gpu_model_downloader.js`：**10 路 Range + `.meta.json` 断点续传 + `.done` 标记**；单实例 `flock`；长任务一律 `setsid … </dev/null >>log 2>&1 &`。⚠️ Linux 上必须 `WEAVEORA_CURL=curl`（默认写的是 `curl.exe`） |
| 6 | `pkill -f xxx` **把自己的 ssh 命令也杀了**（三次，含 2026-09-24 一次） | `pkill -f` 匹配到自身命令行 | 用 `pkill -f '[x]xx'` 防自匹配（括号技巧），或先 `ps -eo pid,cmd` 再按 PID 杀。**2026-09-24 实例**：`pkill -f '/opt/weaveora/ComfyUI/main.py'` 把包着它那条 `bash -c` 也杀了 ⇒ “杀了不拉”——重启脚本后半段永远不执行 |

### 4.2 磁盘与文件完整性

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 7 | 磁盘写满，下载中断 | 系统盘 196 GiB 被模型吃满（2026-09-24 实测只剩 ~8.8 GiB） | ① 下载脚本加**磁盘闸门**（不足直接 exit 2）；② 大件放**数据盘 `/addDisk`** + 在 `models/` 建同名**软链**（ComfyUI 逐请求重扫，**不重启**）；③ 兼容软链 `mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk`（挂载点历史变过） |
| 8 | **字节数达标但文件其实没写完** | 下载器**稀疏预分配**（先 `truncate` 成完整长度） | 判定“下完”必须 **日志百分比 + safetensors 头可解析**；
`ls -la` 会**立刻显示完整大小**（稀疏文件），不能当凭据；`.done` 也只是标记（实体删了它会变僵尸） |
| 9 | `buffalo_l` 的 `1k3d68.onnx`/`w600k_r50.onnx` 只有 **32 MiB**（正常 143/174 MB） | 下载中断 + 旧脚本按“文件存在”跳过 | 重下并**自检形状**（`emb=512`、`lm=(106,2)`）；活副本在 `ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/auxiliary/models/buffalo_l/` |
| 10 | ~~模型与脚本混在 `/tmp`、容器重启就没了~~ | ~~平台容器会回收（**容器时代**）~~ | ✅ **已不适**：现在是 VM —— `/opt/weaveora` 随镜像/系统盘走、`/addDisk` 是**持久盘**；仍禁写 `/tmp` 与非持久目录 |

### 4.3 依赖与版本

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 11 | EchoMimic 起不来：`ImportError: FLAX_WEIGHTS_NAME` | **transformers 5.x** 删了该常量，**diffusers 0.35.1** 仍在引用 | talk venv **transformers 降到 4.57.6**（记录：torch 2.7.1+cu126 / diffusers 0.35.1 / transformers 4.57.6） |
| 12 | ComfyUI 节点加载失败/能力缺失 | GPU 侧节点补丁与 worker 预期版本不一致（worker 会查 `/weaveora/version`） | 统一节点补丁版本（当前 LatentSync 节点 **2026-09-14.1**）；worker 侧发现版本过旧会明确报缺哪些能力 |
| 13 | `nvidia-smi` 报 NVML 版本错配 | 内核模块 595.71.05 vs 用户态 595.84 | **计算不受影响**；判断显存一律读 ComfyUI `/system_stats`（`/queue` 判断忙闲） |

### 4.4 工作流与推理

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 14 | 给 `lips_expression` 传 0.8 → 任务**秒失败** `value_smaller_than_min` | LatentSync 节点该参数 **min=1.0** | 默认给 **1.0**，并加 `_clamp_node_scalar` 兜底；`WEAVEORA_LIPSYNC_EXPRESSION_RISK` 默认 1.0 |
| 15 | 静帧底片出片后**画幅被破坏**（16:9 变带黑边方片） | 旧实现把静帧 **pad 成 512×512** | 改为**等比缩放**（长边 ≤1280，`WEAVEORA_LIPSYNC_STILL_MAX`） |
| 16 | 某镜对口型"画面被破坏"（第 5 镜） | 底片嘴全程大张（`mouth_open 1.232/1.238`），LatentSync 要先合嘴再重开 | A/B/C 保护：底片可选 + 体检硬拒 + 极端表情分流；并重出"闭嘴近景"静帧闭环（新产物 `mouth_open 0.443`） |
| 17 | 嘴部指标不可用/口径不一 | 关键点索引写错（曾用 87–105） | 正确为 buffalo_l 106 点中的 **52–71**；可用 `WEAVEORA_LIPSYNC_MOUTH_IDX` 覆盖；拿不到指标就**跳过**不拦 |
| 18 | 出片 OOM | A14B 峰值 42 G，与其它服务抢显存 | 串行编排：clip 前 TTS `/unload`；talk OOM 自动降档；`--reserve-vram 0.5` |
| 19 | 25 步 @768²/113 帧在 48G 上 OOM | 帧数/分辨率/步数三者乘积 | 用档位预设（draft/balanced/motion/hero），talk 走 512²/81 帧/8 步 |
| 20 | **VAE 用错**：I2V-A14B 加载了 `wan2.2_vae.safetensors` | 旧清单写的是 **TI2V-5B** 的 VAE | A14B **必须** `wan_2.1_vae.safetensors`（已在代码常量里写死注释） |
| 21 | ~~24G 卡跑不动 Wan~~ | ~~显存口径（**第一台服务器**）~~ | ✅ 已不适：现在是 48G 卡；fp8 量化 + `weight_dtype=default` 即可（历史行保留在 git） |
| 22 | 出图工作流里 `CLIPLoader.type` / latent 节点填错 | 不同模型家族的加载器口径不同：Qwen-Image 用 `type=qwen_image` + `EmptySD3LatentImage`；**FLUX.2 用 `type=flux2` + `EmptyFlux2LatentImage` + `Flux2Scheduler` + `FluxGuidance` + `BasicGuider`**，**根本没有 `KSampler`** | 已固化在 `deploy/comfy/flux2_dev_*.json`；worker 靠 `_wf_is_flux2()` 判分派（否则参数注入静默失效） |

### 4.5 服务与生产接线

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 23 | ~~绝不自行重启 ComfyUI~~ | ~~平台会回收/重置容器，重启即事故（**容器时代**）~~ | ⚠️ **已反转（2026-09-24）**：现在是 VM，而且**跨模型家族必须重启 ComfyUI**（否则 OOM，见坑 47）—— 但重启仍要走护栏：先查队列（`deploy/gpu2_restart.sh`）。worker 已内置自动重启（跨家族/首次提交有驻留/不可达） |
| 24 | 平台只开一个公网端口 | 网关限制 | `edge_proxy.py` 单端口按路径多路复用（不用 nginx） |
| 25 | GPU IP/端口变更后生产 404/拒连 | `EngineSettings` 里曾写死旧地址 | 已改成**只配「GPU 服务器地址+端口」一处**，其余服务地址留空自动跟随（`GpuAddressSyncTest` 有回归）；⚠️ **平台配置页保存会把旧快照写回** ⇒ 每次保存后**必须回读**（`bash deploy/image_variant_switch.sh verify`） |
| 29 | 对口型 100% 失败：`PyAVPlugin.write() got an unexpected keyword argument 'macro_block_size'` | ComfyUI venv 被换过（`ImageIO 2.37 + av 18`，无 `imageio-ffmpeg`）→ `imageio` 选中 **pyav 插件**，而它不支持 `macro_block_size`（`torchvision.io.write_video` 内部正是这么调） | 改 `deploy/latentsync-node/files/nodes.py`：**优先用 PyAV 写输入帧**（本机已装 av，ComfyUI 原生 LoadVideo 也用它），torchvision 仅作回退。⚠️ 补丁必须**保留函数内 `import torchvision.io as io` 的局部绑定**，否则后面 `io.read_video` 会 `UnboundLocalError: cannot access local variable 'io'` |
| 30 | 节点一实例化就卡死，ComfyUI 队列（FIFO）被占 52 分钟 | ① venv 无 `pip` 且缺 `accelerate` → 节点自装依赖抛错；② 节点 `checkpoints/` 里 `latentsync_unet.pt` / `whisper/tiny.pt` 不见了 → 节点 `setup_models()` 转去 **huggingface.co 下 5 GB**（国内 ~0.6 MB/s） | ① 装回 `accelerate`（不动其它版本）；② 补节点自装标记 `~/.latentsync16_dependencies_installed`；③ **软链** `/opt/weaveora/latentsync/{latentsync_unet.pt,whisper/tiny.pt}` 回节点目录（零拷贝、不再触发下载）；④ `/etc/hosts` 加 `weaveora-hf-guard` 禁直连 HF/xet（§0.2 本就要求走 ModelScope/aifasthub） |
| 31 | 卡住的任务**无法从外部清除** | `/interrupt` 只在**节点之间**生效，节点内部（`setup_models` 下载）卡住时无效；`ss -K` 内核不支持（Invalid argument）；定向 iptables REJECT 也没断掉在途连接；删半成品文件后它仍写已删除的 inode | 只能**等它自己报错退出**或重启 ComfyUI；重启前务必确认 `/queue` 为空（否则打断生产任务）。本次实测：52 分 44 秒后以 `Model download failed` 自行退出，队列清空，**没重启** |
| 32 | 改节点/依赖后必须重启才生效 | ComfyUI 进程内 `sys.modules` 缓存：改 `.py` 或换包版本都不会热加载（`inference.py` 也是 `import_inference_script()` **进程内**导入） | 与 §4.5 #23 同款纪律：能靠“换工作流 JSON / 改配置”解决的绝不重启；确实要改节点代码才重启，且**先确认队列为空** |
| 33 | 维护/重建后“模型突然不可用”（Phase2/3 权重全部断链） | **数据盘挂载点变了**（`/media/vipuser/addDisk` → `/addDisk`），而 `/opt/weaveora/models/*` 里的软链仍指向旧路径 → 悬空 | 重建**兼容软链** `mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk`（一条命令让所有既有软链重新生效，比逐个重写安全）；并用 `deploy/gpu2_post_maint_check.sh` 逐项验可读 |
| 34 | 重启后**整脸口型（:8094）消失** | `services_up.sh` 里**没有 talk 启动块**（当初是手工 `start_talk.sh` 起的）→ 平台重启后就只剩 ComfyUI/TTS/Face/网关 | 已把 talk 启动块写进 `services_up.sh`（幂等 + 监听汇总含 8094）；**新增服务必须同时进启动脚本**，否则“重启即丢” |
| 35 | 网关路由配了却不生效：`/talk/health` 404 | 「去前缀」实现是 `path[len(prefix):] or "/"` —— 当**前缀本身就是完整路径**时，剥完是空 → 转发到 `/` → 上游 404 | 这类“路径就是全路径”的路由必须 **strip=False**（并让上游服务认该路径，如 talk 服务端同时认 `/health` 与 `/talk/health`）。另：`/talk_batch` 不匹配前缀 `/talk`（规则要求完全相等或前缀+/），必须单独列 |
| 37 | 重启后**首张图要 12 分钟**（ComfyUI 日志 `Prompt executed in 00:12:05`） | **RAM 不够**：机器只有 **31 GB** 内存，而 Qwen-Image 出图一套权重 = 20.4 G（主模型）+ 7.9 G（Qwen2.5-VL 文本编码器）≈ **28 GB**，加上 ComfyUI 自身开销直接超了 → `free` 显示 available ≈ 3 GB、**swap 已用 4.7 GB/8 GB**，加载过程在换页。实测**磁盘不是瓶颈**（顺序读 426–703 MB/s、4K 随机读也正常）、**显存也不是**（47.4 G 够） | 方案（按性价比排序）：① **平台侧把内存加到 64 GB**（最直接，12 min → ~1 min）；② **同类任务批处理**（整批出图/整批出片，把加载摊薄——talk 的 `/talk_batch` 就是这个思路）；③ **重启后预热一次**（极短 prompt 把权重读进 RAM/VRAM，让用户任务不付冷启动）；④ worker 侧**同 kind 优先调度**，避免 still→clip→still 反复换入换出；⑤ 换 **GGUF 量化**的 Qwen-Image+文本编码器（一套 ~15 GB，Q8 近无损）或草稿用 SDXL/FLUX-schnell、精稿才用 Qwen-Image；⑥ 架构级：做**常驻出图服务**（照抄 talk 的做法，进程内常驻 Qwen-Image，单张稳定 ~40 s），彻底绕开 ComfyUI 的换入换出 |
| 38 | **A14B 出片 OOM**（`KSamplerAdvanced: torch.OutOfMemoryError: Allocation on device`），即使 `/free` 后可用 46.5 GiB、并自动降帧到 56 帧仍复现 | 去掉 `--disable-smart-memory`（想省冷加载）后，ComfyUI 会让**双专家同时驻留**（高/低各 13.3 G）+ umt5 6.7 G，再加注意力/解码峰值就超过 47.4 G | **恢复 `--disable-smart-memory`**（双专家必须"用完即卸"）；"首图 12 分钟"的真因是**内存不足+swap**，不是这个 flag（扩容到 62 G 后已消失）。脚本：`deploy/gpu2_restore_smart_memory.py` |
| 39 | `/free` 调了但显存没回来 | ComfyUI 的卸载是**异步**的：发完立刻量 `vram_free` 还是旧值（实测 19.9 → 19.5 G），旧代码量一次就往下走 → 后续任务 OOM | `_free_comfy_models(wait_gb=…)`：POST `/free` 后**轮询等到显存真的回来**（最多 120 s），日志打「卸载完成：可用显存 X GiB（目标 Y）」 |
| 40 | 体检"过得去"但实际 OOM（预估 44.5 G / 总 47.4 G） | VRAM 预估模型没算**碎片/CUDA 上下文/解码瞬时峰值**，且 `MOTION_VRAM_SAFETY_GB` 旧默认是 **0**（等于没有余量）；判定为"根本放不下"时旧代码直接 `raise`（任务失败） | ① 预留默认改 **4 GiB**；② 能放下但余量不足时**自动降帧**（80 → 56 帧，4n 对齐）并打印原因，输出仍由 `_retime_to_fps()` 补到目标时长 → **任务不再失败，只损失运动稠密度**（仅当连 40 帧都放不下才报错） |
| 42 | **重启撞死正在跑的任务**：用户 21:49 发起的对口型任务，21:52 报 `502 edge proxy: upstream error: Cannot connect to host 127.0.0.1:8001` | 我为部署 talk 的 `/health` 改动直接 `systemctl restart weaveora-stack.service` —— **ComfyUI 正在重启**，任务的 `/history` 轮询直接 502。worker 是**单线程认领**（任务不会真并发），所以"重启撞车"才是最大生产风险 | 新增唯一入口 **`deploy/gpu2_restart.sh`**：先查库（queued/running 有就拦下，除非 `FORCE=1`）→ 再重启 → 自检端口与 ComfyUI 就绪。**以后任何重启都走它** |
| 43 | talk 被内核 `oom_kill`（anon-rss 29.4G），连带 `weaveora-stack` 整体 failed | ComfyUI 的 RAM 缓存阈值很宽（旧配置只给一个值 `--cache-ram N` 时，第二个值默认 = **总内存 100%**）→ 会把 20–28G 权重留在内存里；talk 每次请求 fork 的子进程要 29.4G → 相加超内存 → 内核 OOM | ⚠️ **本条旧处置（`--cache-none`）已于 2026-09-24 作废**：`--cache-none` 是 `Weaveora.md` 铁律③ 明令禁止的（每个节点每次重跑）。现行：`--cache-ram 16 16`（两个值都要给）+ worker 跨家族自动重启（坑 47） |
| 47 | ⭐ **跨模型家族不重启 = OOM killer 杀 ComfyUI**（两次真任务：关键帧→motion；worker 重启后的首张图）；worker 只看到 `COMFY_ERROR: Connection refused` | 48 GiB 内存/显存装不下**图像家族 49.8 GiB**（FLUX.2 33+16.8）与**视频家族 34 GiB**（LTX 20+14）；`dmesg` 实测 `anon-rss 47.8 GB`，整栈连网关一起死 | worker `_unload_before_model_switch()` **三类触发 → 重启 ComfyUI 再提交**：① 跨模型家族（模型指纹变化）② 本进程首次提交但盒上显存占用 >8GB（重启/部署后的盲区）③ ComfyUI 不可达（自愈）。重启走网关 `POST /__edge/reload_comfy`（VPS **无**盒上 SSH 权限）。实测：18s 回来，重启后 anon 2.5GB 干净加载 |
| 48 | ⭐ `POST /free {"unload_models": true}` **不能用** | 它把权重**从显存卸到内存**（不是释放）：`free_memory()` 对 `sys.getrefcount(model)>1`（被执行缓存引用中）的模型只 offload | 实测 anon 13.5 → **45.3 GB**、整机 available 仅剩 **179 MB**（直接撞 OOM 线）⇒ 代之以**重启**（坑 47） |
| 49 | ⭐ 预热脚本加载的是 **Qwen**，而生产是 FLUX.2 | `warmup.sh` 默认 `WEAVEORA_WARMUP=qwen`（历史默认），每次重启把 27 GB Qwen 读进来；且“刚加载就被卸载”会触发 ComfyUI 记账损坏 | ① 默认改 **`off`**；② 若要开预热，必须把工作流换成**与生产同族**的（`WEAVEORA_WARMUP=flux2`）；③ 损坏的症状：`'NoneType' object has no attribute 'model_size'`，**之后任何加载 0.04s 必崩**，只能重启 |
| 50 | ⭐ 手工跑 `services_up.sh` 后 ComfyUI 没起 / 网关跑错端口 | 该脚本的两个变量默认值是**历史值**：`WEAVEORA_ROOT` 默认 `/home/dataset-local/weaveora`（第一台服务器的路径）、`WEAVEORA_GATEWAY_PORT` 默认 **8000** | 手工调用必须带全：`WEAVEORA_ROOT=/opt/weaveora WEAVEORA_GATEWAY_PORT=8800 bash services_up.sh`；systemd 起的（unit 里有 Environment）不受影响。`edge_proxy.py` 的 `/__edge/reload_comfy` 已经两个都显式 export |
| 51 | 工作流“看着接好了”但提交即 400 | 模板摊平出的节点类型/接线问题**只有真 `POST /prompt` 才暴露**（`/object_info` 看不出） | 新工作流一律先试一枪：`python3 /opt/weaveora/diag/diag_flux2_smoke.py --mode all`（直接用生产注入器构造 graph） |
| 52 | 部署了 worker，但新工作流在 VPS 上**根本不存在** | worker 在 **VPS 本机**读工作流 JSON（盒上有 ≠ worker 能读）；而 `deploy/vps-worker-deploy.sh` 里「引擎工作流只补齐缺失」那段曾因 `cd` 后相对路径解析错而**静默不执行** | 已修（cd 前存 `SCRIPT_DIR` 绝对路径 + 找不到 `deploy/comfy` 时**显式报错**）；部署后确认输出里有 `+ 补齐缺失：flux2_dev_*.json` 或 `= 一致：…` |
| 44 | 资源让出散落在各分支、且内存读数读错机器 | ① 出图/出片/对口型/配乐/talk 各自零散调 `/free`、`/unload`；② worker 跑在 **VPS** 上，`/proc/meminfo` 读到的是 VPS 的内存（日志里一直显示 5.0 GiB，而 GPU 机实际 49.5G） | ① worker 收口成一个 **`_yield_resources(need_vram_gb, need_ram_gb, label)`**：`/free`(ComfyUI，并等显存真的回来) + `/unload`(TTS) + 打印让出前后资源，**五个重任务全走它**；② 内存预算改从 **GPU 机**取（talk 的 `/health` 新增 `ram_available_gb`，worker 经 `<talk_url>/health` 读取） |
| 41 | 任务按创建顺序跑，`still→clip→still` 来回换大模型（每次重载 20–28 G） | 旧 claim 逻辑严格 FIFO，不同能力交错执行 → 反复卸载/加载 | **A3 同 kind 优先**：claim 时先收集候选再优先挑「与本节点上一个任务同类型」的（`JobService.lastClaimedKind`，进程内 Map）。**单跑行为不变**、跨 kind 不饿死（没同 kind 就取最早的）。实测：入队 `still(6) → clip(5) → still(4)`，实际执行为 `still(6) → still(4) → clip(5)` |
| 36 | `start_talk.sh` 报 “already running” 但它其实没跑 | `ps | grep "[t]alk_server.py"` 匹配到了**执行这条命令的 shell 自身**（命令行里含该字符串）→ 误判 | 用更严格的模式（两段式 `grep "[e]cho_mimic_v3" | grep "[t]alk_server"`）或直接 `pgrep -f 'envs/talk/bin/python .*talk_server.py'`；这就是 §4.1 #6 「`pkill -f` 自匹配」的同族坑 |
| 26 | 前端显示"方案有改动"但其实没改 | `prompt_revisions.schema_json` 是 **jsonb**（PG 重排对象键），前端 `JSON.stringify` 把**纯键序差异**当改动 | 新增 `canonicalJson()` 统一 dirty/pristine 比对；删掉 `startVoice/startBgm/startLipsync` 里会另存未确认版本的兜底保存 |
| 27 | 部署与生产任务互踩 | 同一张卡 | 纪律：**串行、不插队、不抢显存**；部署只在任务空隙；只做 ≤5 s 短查，禁止长轮询（单次等待 ≤60 s） |
| 28 | 许可风险 | FLUX.1-dev 系（含 IP-Adapter/PuLID 派生）**非商用** | 云 API 档走官方服务；自托管只能用**允许的口径**。⚠️ 2026-09-23 用户裁定：**暂不考虑协议** —— 生产已切 FLUX.2 [dev]（**非商用许可**），此事**已入账待批**（见 `docs/方案-FLUX2dev-…md §9`），不是“已合规” |

---

## 5. 运维手册（常用命令）

```bash
# 机器（地址一律现场读，别名里已绑好 IdentityFile）
ssh gpu                                # = gpu2 = weaveora-gpu-a14b（同一台；地址/端口变了要三处同步）
df -h / /addDisk                       # 两块盘（数据盘是 /addDisk；兼容软链 /media/vipuser/addDisk → /addDisk）
curl -s http://127.0.0.1:8001/system_stats | python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"][0];print(round(d["vram_free"]/2**30,1),"G free")'

# 服务健康
for p in 8800 8001 8091 8093 8094; do printf "%s " $p; curl -s -o /dev/null -w "%{http_code}\n" -m 5 http://127.0.0.1:$p/; done
systemctl status weaveora-stack.service --no-pager | head -20

# 任务忙闲（部署/出图前必查）
curl -s http://127.0.0.1:8001/queue

# ★ 重启（唯一入口：先查库 queued/running 就拦，FORCE=1 才强推）
bash deploy/gpu2_restart.sh
# ★ 跨模型家族时 worker 会自动重启 ComfyUI（走网关 POST /__edge/reload_comfy，带 X-WV-Token）
#   手工触发（= 把上方重启降级为只重启 ComfyUI）：
curl -s -X POST -H "X-WV-Token: $WEAVEORA_EDGE_ADMIN_TOKEN" "$GW/__edge/reload_comfy"
# ★ 内存/OOM 观察器（压测、验收、排 OOM 时后台跑；看 AnonPages 峰值与 dmesg oom 计数）
setsid bash /opt/weaveora/ram_watch.sh </dev/null >/dev/null 2>&1 & tail -5 /opt/weaveora/logs/ram_watch.log

# ★ 出图配置回读/自检（切档后必跑；能抓出“steps≤10 但无 lora”这类静默冲配置）
bash deploy/image_variant_switch.sh show    # 只读
bash deploy/image_variant_switch.sh verify  # 自检

# 下载（后台静默；日志即进度；Linux 上必须 WEAVEORA_CURL=curl）
#   FLUX.2 五件套：bash /opt/weaveora/dl_flux2.sh   （REST_ONLY=1 只下系统盘那 4 件）
setsid bash /opt/weaveora/dl_flux2.sh </dev/null >>/opt/weaveora/logs/dl_flux2.log 2>&1 &
tail -n 3 /opt/weaveora/logs/dl_flux2.log ; cat /opt/weaveora/logs/dl_flux2.pid   # 叫停：kill $(cat …)
df -h /addDisk

# 权重自检（字节 + .done（跟 readlink 后的真文件找）+ 同名多副本）
bash /opt/weaveora/post_maint_check.sh | grep -E "flux2|mistral|full_encoder|Turbo|多副本|FAIL"

# 模型完整性（字节 + .done + safetensors 头 / sha256）
bash /opt/weaveora/verify_models.sh                      # 结构自检
bash /opt/weaveora/hashall.sh                            # 全量 sha256 → logs/sha256_manifest.txt

# 出图（等空闲 → 提交 → 取回）
setsid nohup /opt/weaveora/envs/talk/bin/python /opt/weaveora/first_image.py > /opt/weaveora/logs/first_image.log 2>&1 < /dev/null &
```

**日志位置**：`/opt/weaveora/logs/`
`dl_qwen.log`（Phase 1）、`dl_phase23.log`（Phase 2+3）、`dl_echo.log`、`dl_rest.log`、`first_image.log`（自动出图）、`i2i_shot5.log`（图生图）、`sha256_manifest.txt`、`comfyui.log`、`audio_tts.log`。

**产物位置**：`img_out/`（出图）、`talk_out/`（整脸口型）、交付走 VPS `https://sysou.com/weaveora/_share/<file>`。

---

## 6. 全量 sha256 表

> 旧的「2026-09-15 全量主实测」已过时（其中 `qwen_image_fp8_e4m3fn` 等行仍在 §2.2 归档）。
> **现役权重的权威校验请用**：① 盒上 `bash /opt/weaveora/post_maint_check.sh`（逐件字节 + 同名多副本）；
> ② `docs/方案-FLUX2dev-替换Qwen出图通路-2026-09-23.md §3`（FLUX.2 五件的字节 + sha256，且与 ModelScope 官方值逐字节一致）；
> ③ `docs/磁盘操作记录-2026-09-23-FLUX2部署.md`（下载实测：主模型 59.5 min、`sha256 = 863a82e4…27b486` = 官方）。

<details>
<summary>历史基线（2026-09-15，仅作对比，勿当现役——点开）</summary>

| 文件 | 字节 | sha256 |
|---|---|---|
| `models/checkpoints/ace_step_1.5_turbo_aio.safetensors` | 10,025,478,736 | `67b0f43aa5c51c840bd0228e6a935d8ff416ec87e5df2fc0637da17a561252bc` |
| `models/diffusion_models/qwen_image_fp8_e4m3fn.safetensors` | 20,430,635,136 | `98763a127701eb6fb59096f7742cb3aa7d64ed510b9f4e882d8351f8176e3ce3` |
| `models/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors` | 14,294,742,832 | `6122e79d55e0f235698d11d657f3b196c5273c830da00b2b013c5a048d5e6a42` |
| `models/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors` | 14,294,742,832 | `5471a457b6ac404202a5fbe6c11595a3d5641fc766b00f38763f72303fffc21e` |
| `models/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors` | 9,384,670,680 | `cb5636d852a0ea6a9075ab1bef496c0db7aef13c02350571e388aea959c5c0b4` |
| `models/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` | 6,735,906,897 | `c3355d30191f1f066b26d93fba017ae9809dce6c627dda5f6a66eaa651204f68` |
| `models/vae/qwen_image_vae.safetensors` | 253,806,246 | `a70580f0213e67967ee9c95f05bb400e8fb08307e017a924bf3441223e023d1f` |
| `models/vae/wan_2.1_vae.safetensors` | 253,815,318 | `2fc39d31359a4b0a64f55876d8ff7fa8d780956ae2cb13463b0223e15148976b` |
| `models/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors` | 1,698,951,104 | `07b5a999881437f63124979844ba1949ce2438f65b6220628a196a7d30a4fff9` |
| `models/loras/Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_…fp16.safetensors` | 630,695,648 | `8e0a86e765ade42a1deca52eb7411348254a019147be8c4eed88c7ad465d3399` |
| `models/loras/Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_…fp16.safetensors` | 630,695,648 | `09e10abd98460b66439bd77ea671e94c10fdc8251e0b986aa196d1904e3cc583` |
| `models/echo_mimic/transformer/diffusion_pytorch_model.safetensors` | 3,414,541,616 | `ba25a75c7511ba57a346480d50567b57270282c1f3ab121558c07b65a97adf1f` |
| `models/echo_mimic/Wan2.1-Fun-V1.1-1.3B-InP/diffusion_pytorch_model.safetensors` | 3,128,957,992 | `4ec199076538b946935ebcb3ba808d3c427e638f29519a3c3c98d31d821e5eed` |
| `models/echo_mimic/…/models_t5_umt5-xxl-enc-bf16.pth` | 11,361,920,418 | `7cace0da2b446bbbbc57d031ab6cf163a3d59b366da94e5afe36745b746fd81d` |
| `models/echo_mimic/…/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth` | 4,772,359,047 | `628c9998b613391f193eb67ff68da9667d75f492911e4eb3decf23460a158c38` |
| `models/echo_mimic/…/Wan2.1_VAE.pth` | 507,609,880 | `38071ab59bd94681c686fa51d75a1968f64e470262043be31f7a094e442fd981` |
| `models/echo_mimic/wav2vec2-base-960h/model.safetensors` | 377,607,901 | `8aa76ab2243c81747a1f832954586bc566090c83a0ac167df6f31f0fa917d74a` |
| `latentsync/latentsync_unet.pt` | 5,072,222,488 | `0a478e89eb660f82da4c35dbdde8a5adfb27f99d1b4e50edd03729e1e98316d3` |
| `latentsync/vae/diffusion_pytorch_model.safetensors` | 334,643,276 | `a1d993488569e928462932c8c38a0760b874d166399b14414135bd9c42df5815` |
| `latentsync/whisper/tiny.pt` | 75,572,083 | `65147644a518d12f04e32d6f3b26facc3f8dd46e5390956a9424a650c0ce22b9` |
| `audio/…/CosyVoice2-0.5B/llm.pt` | 2,023,316,821 | `b144ef55b51ce8cfb79a73c90dbba0bdaba4e451c0ebcfab20f769264f84a608` |
| `audio/…/CosyVoice2-0.5B/flow.pt` | 450,575,567 | `ff4c2f867674411e0a08cee702996df13fa67c1cd864c06108da88d16d088541` |
| `audio/…/CosyVoice2-0.5B/hift.pt` | 83,390,254 | `3386cc880324d4e98e05987b99107f49e40ed925b8ecc87c1f4939432d429879` |
| `audio/…/CosyVoice2-0.5B/speech_tokenizer_v2.onnx` | 496,082,973 | `d43342aa12163a80bf07bffb94c9de2e120a8df2f9917cd2f642e7f4219c6f71` |
| `audio/…/CosyVoice2-0.5B/CosyVoice-BlankEN/model.safetensors` | 988,097,824 | `130282af0dfa9fe5840737cc49a0d339d06075f83c5a315c3372c9a0740d0b96` |
| `audio/…/CosyVoice-300M-SFT/llm.pt` | 1,242,994,835 | `d198ce56636e1eb1c9d0cb0d6e3529de8fdfd3fd45075c346296b0d6dcfc54ea` |
| `audio/…/CosyVoice-300M-SFT/flow.pt` | 419,900,943 | `21eae78c105b5e1c6c337b04f667843377651b4bcfb2d43247ed3ad7fd0a3470` |
| `audio/…/CosyVoice-300M-SFT/hift.pt` | 81,896,716 | `91e679b6ca1eff71187ffb4f3ab0444935594cdcc20a9bd12afad111ef8d6012` |
| `audio/…/CosyVoice-300M-SFT/speech_tokenizer_v1.onnx` | 522,624,269 | `23b5a723ed9143aebfd9ffda14ac4c21231f31c35ef837b6a13bb9e5488abb1e` |

> 500 MiB–1 GiB 档另有小文件（`config.json`、tokenizer、`face_id` 等），逐字节校验即可，未逐一列 sha256。
> 复算方式：`bash /opt/weaveora/hashall.sh` → `/opt/weaveora/logs/sha256_manifest.txt`。

</details>

---

## 7. 待完成 / 后续

> 现役状态：出图（FLUX.2）/ 出片（LTX-2.5）/ 对口型 / talk / 配音 / 配乐 **全部生产在用**；栈由 systemd 自启，跨家族自动重启已上线。

| 项 | 状态 | 说明 |
|---|---|---|
| **验收（用户亲自）** | 🟡 进行中 | 定妆照 → 关键帧 → 出片 → 再回出图；提交后看这三行：`工作流出图：flux2_dev_…`、`参考槽措辞已改写`、`负词…折进正词`；身份验收可用 `wv_faceid.py` |
| **FLUX.2 中文提示词遵循度** | 🟡 未实测项 | 生产正词是中文，而 FLUX.2 用 Mistral-3 编码器（Qwen 本来以中文见长）—— 这是 A/B 没跑到就转生产的风险点，验收时重点看 |
| **多参考身份一致性** | 🟡 零基线 | 三人同框那类镜，FLUX.2 无官方对比数据 |
| **img2img 的 denoise** | 🟡 待产品定 | DB 当前 `denoise=1.0`（= 底图贡献为零、退化成 txt2img）；该通路**当前走不到**（edit 已配）；非本次引入 |
| **许可（FLUX.2 dev 非商用）** | ⛔ **待处理** | 用户 2026-09-23 裁定“暂不考虑”，但**商用产品上生产必须回来解决**（BFL 商业授权 / 或换 Klein 4B Apache-2.0 等） |
| **`/addDisk` 只剩 ~3.5 GiB** | 🟡 结构性 | 33 GiB 的 FLUX.2 一来就撞满；长期解法 = 扩盘，或验收后按流程处青 `qwen_image_edit_2511`（19.12 GiB，回滚件） |
| **Wan2.2 双专家（26.6 GiB）** | 🟡 待定 | 引擎页可切回 `wan22`；确认不再用就可释放系统盘 |
| **旧模型迁到新盘** | ⏳ 可选 | 必须在任务空隙做（复制 → 校验 → 原子软链替换 → 删旧） |

---

## 8. 许可与合规（红线）

| 模型 | 许可 | 用法 |
|---|---|---|
| Qwen-Image / Qwen-Image-Edit / Qwen2.5-VL | Apache-2.0 | 生产可用 |
| FLUX.1-**schnell** | Apache-2.0 | 生产可用（T2） |
| FLUX.1-**dev** 及其派生（IP-Adapter/PuLID 等） | **非商用** | **禁止用于生产**（本机不装） |
| Wan2.2 I2V-A14B / Wan2.1-Fun | Apache-2.0 | 生产可用 |
| LatentSync 1.6（权重）+ 节点 | Apache-2.0（节点 MIT） | 生产可用 |
| EchoMimicV3 | Apache-2.0 | 生产可用 |
| CosyVoice2 / CosyVoice-300M-SFT | Apache-2.0 | 生产可用 |
| ACE-Step 1.5 | Apache-2.0 | 生产可用 |
| insightface **buffalo_l** | **非商用**（InsightFace 模型） | 仅内部工具链（检测/对齐/指标），**不对外分发模型** |
| Whisper | MIT | 可用 |
