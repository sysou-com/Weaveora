# GPU 服务器能力搭建指南

> 适用：Weaveora 自托管 GPU 服务器（当前主力机 **GPU#2 / RTX 4090 48G**）。
> 目的：把服务器上已落地的六项能力（**文生图 / 图转视频 / 对口型 / 整脸口型(talk) / 配音 / 配乐**）连模型、版本、下载地址、体积、sha256、加载顺序**和踩过的坑**一次写清，做到「换机可照着重装、查问题时能对得上号」。
>
> 关联文档：`docs/gpu-server-setup.md`、`docs/gpu-newserver-models.md`、`docs/gpu-newserver-batch1.md`、`docs/lipsync-setup.md`、`docs/wan22-dual-expert-prep.md`、`可行性分析-对口型-极端表情-2026-09.md`、`可行性分析-文生图-GPU2-2026-09.md`
> 更新：2026-09-15（Phase 1 文生图已上线出图；Phase 2 Qwen-Image-Edit 下载中；T2 FLUX 排队）

---

## 0. 一句话总览

| 能力 | 任务 kind | 跑在哪 | 主模型 | 许可 | 状态 |
|---|---|---|---|---|---|
| **文生图（T3 主力）** | `still`（imageEngine=gpu） | ComfyUI :8001 | **Qwen-Image fp8** + Qwen2.5-VL 7B + Lightning 8 步 LoRA | Apache-2.0 | ✅ 已出图（1344×768 约 40s/张） |
| **一致性/改图** | `still`（一致性） | ComfyUI :8001 | **Qwen-Image-Edit fp8** | Apache-2.0 | ⏳ 下载中（Phase 2） |
| **文生图（T2 备选）** | — | ComfyUI :8001 | **FLUX.1-schnell fp8** + T5-XXL fp8 + CLIP-L + AE | Apache-2.0 | ⏳ 排队（Phase 3） |
| **图转视频（出片）** | `clip` | ComfyUI :8001 | **Wan2.2 I2V-A14B 双专家** fp8 + lightx2v 4 步 LoRA | Apache-2.0 | ✅ 生产在用 |
| **对口型（普通对白）** | `lipsync` | ComfyUI :8001（LatentSync 节点） | **LatentSync 1.6** | Apache-2.0（权重）+ 节点 MIT | ✅ 生产在用 |
| **整脸口型（喊叫/吟唱）** | `talk` | talk_server :8094 | **EchoMimicV3** + Wan2.1-Fun-1.3B 管线 | Apache-2.0 | 🟡 已实测通过，待部署接线 |
| **配音** | `voice` | tts_server :8091 | **CosyVoice2-0.5B** / CosyVoice-300M-SFT | Apache-2.0 | ✅ 生产在用 |
| **配乐** | `bgm` | ComfyUI :8001 | **ACE-Step 1.5 Turbo aio** | Apache-2.0 | ✅ 生产在用 |
| **人脸/关键点/身份** | （内部依赖） | face_server :8093 | **insightface buffalo_l** + det_10g | 非商用（insightface 模型） | ✅ 在用 |
| **转写（字幕/逐字）** | （按需） | tts_server :8091 `/transcribe` | **Whisper**（base / tiny） | MIT | ✅ 可用 |

---

## 1. 机器档案与运行环境

### 1.1 主机

> ⚠️ **本文里的公网 IP/端口只是当时的事实记录**：GPU 服务器公网地址会变（同一容器同时有**电信**与**移动**两个公网入口，IP 不同、端口相同；历史演变 `180.127.11.166:10558` → `223.109.239.32:10558` → 现 `180.127.11.167:21264`）。
> **唯一权威来源 = 平台「生成引擎配置 → GPU 服务器地址 + 端口」**（保存后随任务下发给 worker，保存即生效）。
> 代码/脚本里**不写死任何 GPU IP**：填了 GPU 服务器地址后，ComfyUI 推导为 `<gpu>:8001`、配音/转写为 `<gpu>/audio`、整脸口型为 `<gpu>/talk`、人脸为 `<gpu>`；换机/换线路只改这一处。

| 项 | 值 |
|---|---|
| 机型 | GPU#2（ssh 别名 `weaveora-gpu-a14b`） |
| 公网（电信） | `180.127.11.167`，ssh `-p 21216`；网关 **`http://180.127.11.167:21264`** |
| 公网（移动） | `223.109.239.30`，ssh `-p 21216`；网关 **`http://223.109.239.30:21264`**（备用线路，实测同样 200） |
| 平台端口映射 | **外网 21264→容器 8800**（网关，唯一入口）；21265→8801、21266→8802、21267→8803、21268→8804、21269→8805（备用，当前未占用） |
| GPU | **RTX 4090 48G**（`vram_total` 47.4 GiB） |
| CPU / 内存 | 16 vCPU / **31 GB RAM（实测，非 50 GB）+ 8 GB swap（`/swap.img`）** —— 见 §4 坑 37：内存是首图/首镜慢的真正瓶颈 |
| OS | Ubuntu 24.04（主机名 `ubuntu24`） |
| 系统盘 | `/dev/vda1` 200 G（已用 ~164 G，余 ~23 G） |
| 数据盘 | `/dev/vdb1` ext4 **100 G** → **`/addDisk`**（2026-09-15 维护后挂载点变更；另建兼容软链 `/media/vipuser/addDisk → /addDisk` 以兼容既有软链；余 ~53 G） |
| 持久卷 | **`/opt/weaveora`**（所有模型/脚本/日志/产物，禁写 /tmp 与系统盘） |
| 已知瑕疵 | `nvidia-smi` NVML 版本错配（内核 595.71.05 vs 用户态 595.84）→ 计算正常；**显存以 ComfyUI `/system_stats` 为准** |

### 1.2 服务矩阵（systemd 单元 `weaveora-stack.service`，平台自启）

| 端口 | 服务 | 启动脚本 | 能力 |
|---|---|---|---|
| **8800** | `edge_proxy.py` | `/opt/weaveora/edge_proxy.py` | 单端口多路复用（公网只有 10558 一个口） |
| **8001** | ComfyUI **0.34.0** | `ComfyUI/main.py --listen 0.0.0.0 --port 8001 --disable-smart-memory --reserve-vram 0.5` | 出图 / 出片 / 对口型 / 配乐 的宿主 |
| **8091** | `tts_server.py` | `audio/tts_server.py 8091` | 配音 `/tts`、转写 `/transcribe`、`/load` `/unload` |
| 8092 | `music_server.py` | 默认**不起**（HTTP 兜底） | 配乐（主线走 ComfyUI 原生节点） |
| **8093** | `face_server.py --device cpu` | `face/face_server.py` | `/face/probe`（关键点/嘴张开度）、`/face/embed`（身份向量） |
| **8094** | `talk_server.py` | `talk/talk_server.py` | 整脸口型 `/talk`、`/talk_batch`（喊叫/吟唱镜） |

### 1.3 网关路由（`deploy/edge_proxy.py`）

| 公网路径 | 转发 | 覆盖 |
|---|---|---|
| `/audio/*` | `127.0.0.1:8091`（剥前缀） | 配音、转写 |
| `/bgm/*` | `127.0.0.1:8092`（剥前缀） | 配乐兜底 |
| `/face/*` | `127.0.0.1:8093` | 人脸探测 |
| `/talk*` | `127.0.0.1:8094` | 整脸口型（**待加**，见 §7） |
| `/__edge/health` | 网关自检 | 路由表 |
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

### 2.1 文生图 T3 —— Qwen-Image（主力，已上线）

| # | 文件（落盘） | 版本/作用 | 字节数 | sha256 | 下载地址 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/qwen_image_fp8_e4m3fn.safetensors` | **Qwen-Image** 20B 主模型 fp8；文生图/图生图 | 20,430,635,136 | `98763a127701eb6fb59096f7742cb3aa7d64ed510b9f4e882d8351f8176e3ce3` | ModelScope `Comfy-Org/Qwen-Image_ComfyUI` → `split_files/diffusion_models/qwen_image_fp8_e4m3fn.safetensors` |
| 2 | `text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors` | **Qwen2.5-VL 7B** 文本/多模态编码器 fp8（`type=qwen_image`） | 9,384,670,680 | `cb5636d852a0ea6a9075ab1bef496c0db7aef13c02350571e388aea959c5c0b4` | 同上 → `split_files/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors` |
| 3 | `vae/qwen_image_vae.safetensors` | Qwen-Image VAE（16 通道） | 253,806,246 | `a70580f0213e67967ee9c95f05bb400e8fb08307e017a924bf3441223e023d1f` | 同上 → `split_files/vae/qwen_image_vae.safetensors` |
| 4 | `loras/Qwen-Image-Lightning-8steps-V1.0.safetensors` | **Lightning 8 步蒸馏 LoRA**（把 20~50 步压到 8 步） | 1,698,951,104 | `07b5a999881437f63124979844ba1949ce2438f65b6220628a196a7d30a4fff9` | ModelScope `lightx2v/Qwen-Image-Lightning` → `Qwen-Image-Lightning-8steps-V1.0.safetensors` |
| | **小计** | | **≈ 31.7 GB** | | |

**工作流**（仓库内 API 格式，可被 worker 直接 `POST /prompt`）：
`deploy/comfy/qwen_image_txt2img_api.json`（文生图）、`deploy/comfy/qwen_image_img2img_api.json`（**关键帧当底图**，`LoadImage → VAEEncode → KSampler(denoise 0.65/0.85)`）。

### 2.2 一致性 / 改图 —— Qwen-Image-Edit（Phase 2，下载中）

| # | 文件 | 作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors` | **Qwen-Image-Edit**：参考图+指令改图（跨镜一致性、换场景/换光、保角色） | 20,430,635,136 | 待校验（下载中） | ModelScope `Comfy-Org/Qwen-Image-Edit_ComfyUI` → `split_files/diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors` |

> 复用 2.1 的 Qwen2.5-VL 文本编码器与 Qwen-Image VAE。**这就是"输入参考图"的正路**（相比 SDXL+IP-Adapter 更干净：Apache-2.0、无 dev 派生权重）。

### 2.3 文生图 T2 —— FLUX.1-schnell（Phase 3，排队中）

| # | 文件 | 作用 | 字节数 | sha256 | 地址 |
|---|---|---|---|---|---|
| 1 | `diffusion_models/flux1-schnell-fp8.safetensors` | **FLUX.1-schnell** 12B fp8（Apache-2.0，4 步出图） | 17,236,328,572 | 待校验 | ModelScope `Comfy-Org/flux1-schnell` → `flux1-schnell-fp8.safetensors` |
| 2 | `text_encoders/t5xxl_fp8_e4m3fn.safetensors` | T5-XXL 文本编码器 fp8 | 4,893,934,904 | 待校验 | ModelScope `AI-ModelScope/flux_text_encoders` |
| 3 | `text_encoders/clip_l.safetensors` | CLIP-L 文本编码器 | 246,144,152 | 待校验 | 同上 |
| 4 | `vae/ae.safetensors` | FLUX VAE | 335,304,388 | 待校验 | ModelScope `AI-ModelScope/FLUX.1-schnell` |
| | **小计** | | **≈ 22.7 GB** | | |

**工作流**：`deploy/comfy/flux_schnell_txt2img_api.json`。定位：**出图速度档 + 与 Qwen-Image-Edit 组成"跨模型一致性"链路（(a) 方案）**；不装 FLUX.1-dev 派生的 IP-Adapter/PuLID（非商用许可）。

### 2.4 图转视频（出片）—— Wan2.2 I2V-A14B 双专家

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

### 3.1 文生图 T3（Qwen-Image，ComfyUI）

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
**实测**：1344×768、8 步、**≈ 40 s/张**（模型已加载）。

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

档位预设（`worker/comfy_client.py`）：

| preset | steps | switch | cfg_high | cfg_low | lora_high | lora_low | shift |
|---|---|---|---|---|---|---|---|
| draft | 4 | 2 | 1.0 | 1.0 | 0.0 | 1.0 | 5.0 |
| balanced | 6 | 3 | 1.0 | 1.0 | 0.0 | 1.0 | 5.0 |
| motion | 8 | 4 | 1.5 | 1.0 | 0.0 | 1.0 | 5.0 |
| hero | 6 | 3 | 3.5 | 1.0 | 0.0 | 1.0 | 5.0 |
| full | 24 | 12 | 3.5 | 3.5 | 0.0 | 0.0 | 5.0 |

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

---

## 4. 部署过程中遇到的坑（现象 → 根因 → 处置）

### 4.1 网络与下载

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | `huggingface.co` 直连超时/被墙 | 出口网络 | **禁写死 huggingface.co**；改用 ModelScope API（`https://modelscope.cn/api/v1/models/<org>/<repo>/repo?Revision=master&FilePath=<urlencoded>`）或 aifasthub / `hf-mirror.com` |
| 2 | HF 镜像 API 一度 **403** | 镜像策略 | 切 ModelScope；用 `Range: bytes=0-0` 读 `Content-Range` **实测真实体积**再下 |
| 3 | **scp/sftp 到 GPU#2 被关** | 平台收敛 | 一律 **base64-over-ssh**：`echo <b64> \| base64 -d > 目标文件` |
| 4 | `FileNotFoundError: /tmp/xxx`（本地） | Windows 上 python 解析 `/tmp` 为 `D:\tmp` | 本地检查文件用 `C:/Users/<用户>/AppData/Local/Temp/...` 或直接跳过 |
| 5 | 下载慢/断流 | 单连接限速 | 统一走 `gpu_model_downloader.js`：**10 路 Range + `.meta.json` 断点续传 + `.done` 标记**；单实例 `flock` 锁 |
| 6 | `pkill -f xxx` **把自己的 ssh 命令也杀了**（两次） | `pkill -f` 匹配到自身命令行 | 用 `pkill -f '[x]xx'` 防自匹配，或先 `ps -eo pid,cmd` 列 PID 再按 PID 精确杀 |

### 4.2 磁盘与文件完整性

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 7 | 磁盘写满，下载中断 | 系统盘 196 G 被模型吃满 | ① 下载脚本加**磁盘闸门**（`NEED_GB` 不足直接退出）；② 新增数据盘 `/media/vipuser/addDisk`，**新增大件下到新盘 + 软链回 `models/`**（ComfyUI 逐请求重扫，**不重启**） |
| 8 | **字节数达标但文件其实没写完** | 下载器**稀疏预分配**（先按总长创建文件） | 判定"下完"必须 **`.done` 存在 + safetensors 头可解析**，不能只看 `stat` 字节数（曾因此在 LoRA 未写完时提交了出图任务） |
| 9 | `buffalo_l` 的 `1k3d68.onnx`/`w600k_r50.onnx` 只有 **32 MiB**（正常 143/174 MB） | 下载中断 + 旧脚本按"文件存在"跳过 | 重下并**自检形状**（`emb=512`、`lm=(106,2)`）；保留 `.bak.20260915-095658` 便于对比 |
| 10 | 模型与脚本混在 `/tmp`、容器重启就没了 | 平台容器会回收 | 一切落 **`/opt/weaveora`** 持久卷 |

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
| 21 | 24G 卡跑不动 Wan | 显存口径 | 24G 需 fp8 量化 + `weight_dtype=fp8_e4m3fn_scaled`；48G 可直接 `default` |
| 22 | 出图工作流里 `CLIPLoader.type` 填错 | Qwen-Image 必须 `type=qwen_image`；latent 用 `EmptySD3LatentImage`（16 通道）+ `ModelSamplingAuraFlow(shift=3.0)` | 已固化在 `deploy/comfy/qwen_image_*.json`（一次通过） |

### 4.5 服务与生产接线

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 23 | **绝不自行重启 ComfyUI** | 平台会回收/重置容器，重启即事故 | 只热改"工作流 JSON / 脚本"；ComfyUI 每次 `/prompt` 现读工作流；新增模型靠**扫目录**自动发现 |
| 24 | 平台只开一个公网端口 | 网关限制 | `edge_proxy.py` 单端口按路径多路复用（不用 nginx：容器重启会重置 overlay，apt 装的东西会丢） |
| 25 | GPU IP/端口变更后生产 404/拒连 | EngineSettings 里写死了旧地址 | 6 个服务 URL 从 `180.127.11.167:15264` → **`180.127.11.166:10558`**（备份 `engine_services_backup_20260915.json`）；现已改成**只配「GPU 服务器地址+端口」一处**，其余服务地址留空自动跟随 |
| 29 | 对口型 100% 失败：`PyAVPlugin.write() got an unexpected keyword argument 'macro_block_size'` | ComfyUI venv 被换过（`ImageIO 2.37 + av 18`，无 `imageio-ffmpeg`）→ `imageio` 选中 **pyav 插件**，而它不支持 `macro_block_size`（`torchvision.io.write_video` 内部正是这么调） | 改 `deploy/latentsync-node/files/nodes.py`：**优先用 PyAV 写输入帧**（本机已装 av，ComfyUI 原生 LoadVideo 也用它），torchvision 仅作回退。⚠️ 补丁必须**保留函数内 `import torchvision.io as io` 的局部绑定**，否则后面 `io.read_video` 会 `UnboundLocalError: cannot access local variable 'io'` |
| 30 | 节点一实例化就卡死，ComfyUI 队列（FIFO）被占 52 分钟 | ① venv 无 `pip` 且缺 `accelerate` → 节点自装依赖抛错；② 节点 `checkpoints/` 里 `latentsync_unet.pt` / `whisper/tiny.pt` 不见了 → 节点 `setup_models()` 转去 **huggingface.co 下 5 GB**（国内 ~0.6 MB/s） | ① 装回 `accelerate`（不动其它版本）；② 补节点自装标记 `~/.latentsync16_dependencies_installed`；③ **软链** `/opt/weaveora/latentsync/{latentsync_unet.pt,whisper/tiny.pt}` 回节点目录（零拷贝、不再触发下载）；④ `/etc/hosts` 加 `weaveora-hf-guard` 禁直连 HF/xet（§0.2 本就要求走 ModelScope/aifasthub） |
| 31 | 卡住的任务**无法从外部清除** | `/interrupt` 只在**节点之间**生效，节点内部（`setup_models` 下载）卡住时无效；`ss -K` 内核不支持（Invalid argument）；定向 iptables REJECT 也没断掉在途连接；删半成品文件后它仍写已删除的 inode | 只能**等它自己报错退出**或重启 ComfyUI；重启前务必确认 `/queue` 为空（否则打断生产任务）。本次实测：52 分 44 秒后以 `Model download failed` 自行退出，队列清空，**没重启** |
| 32 | 改节点/依赖后必须重启才生效 | ComfyUI 进程内 `sys.modules` 缓存：改 `.py` 或换包版本都不会热加载（`inference.py` 也是 `import_inference_script()` **进程内**导入） | 与 §4.5 #23 同款纪律：能靠“换工作流 JSON / 改配置”解决的绝不重启；确实要改节点代码才重启，且**先确认队列为空** |
| 33 | 维护/重建后“模型突然不可用”（Phase2/3 权重全部断链） | **数据盘挂载点变了**（`/media/vipuser/addDisk` → `/addDisk`），而 `/opt/weaveora/models/*` 里的软链仍指向旧路径 → 悬空 | 重建**兼容软链** `mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk`（一条命令让所有既有软链重新生效，比逐个重写安全）；并用 `deploy/gpu2_post_maint_check.sh` 逐项验可读 |
| 34 | 重启后**整脸口型（:8094）消失** | `services_up.sh` 里**没有 talk 启动块**（当初是手工 `start_talk.sh` 起的）→ 平台重启后就只剩 ComfyUI/TTS/Face/网关 | 已把 talk 启动块写进 `services_up.sh`（幂等 + 监听汇总含 8094）；**新增服务必须同时进启动脚本**，否则“重启即丢” |
| 35 | 网关路由配了却不生效：`/talk/health` 404 | 「去前缀」实现是 `path[len(prefix):] or "/"` —— 当**前缀本身就是完整路径**时，剥完是空 → 转发到 `/` → 上游 404 | 这类“路径就是全路径”的路由必须 **strip=False**（并让上游服务认该路径，如 talk 服务端同时认 `/health` 与 `/talk/health`）。另：`/talk_batch` 不匹配前缀 `/talk`（规则要求完全相等或前缀+/），必须单独列 |
| 37 | 重启后**首张图要 12 分钟**（ComfyUI 日志 `Prompt executed in 00:12:05`） | **RAM 不够**：机器只有 **31 GB** 内存，而 Qwen-Image 出图一套权重 = 20.4 G（主模型）+ 7.9 G（Qwen2.5-VL 文本编码器）≈ **28 GB**，加上 ComfyUI 自身开销直接超了 → `free` 显示 available ≈ 3 GB、**swap 已用 4.7 GB/8 GB**，加载过程在换页。实测**磁盘不是瓶颈**（顺序读 426–703 MB/s、4K 随机读也正常）、**显存也不是**（47.4 G 够） | 方案（按性价比排序）：① **平台侧把内存加到 64 GB**（最直接，12 min → ~1 min）；② **同类任务批处理**（整批出图/整批出片，把加载摊薄——talk 的 `/talk_batch` 就是这个思路）；③ **重启后预热一次**（极短 prompt 把权重读进 RAM/VRAM，让用户任务不付冷启动）；④ worker 侧**同 kind 优先调度**，避免 still→clip→still 反复换入换出；⑤ 换 **GGUF 量化**的 Qwen-Image+文本编码器（一套 ~15 GB，Q8 近无损）或草稿用 SDXL/FLUX-schnell、精稿才用 Qwen-Image；⑥ 架构级：做**常驻出图服务**（照抄 talk 的做法，进程内常驻 Qwen-Image，单张稳定 ~40 s），彻底绕开 ComfyUI 的换入换出 |
| 36 | `start_talk.sh` 报 “already running” 但它其实没跑 | `ps | grep "[t]alk_server.py"` 匹配到了**执行这条命令的 shell 自身**（命令行里含该字符串）→ 误判 | 用更严格的模式（两段式 `grep "[e]cho_mimic_v3" | grep "[t]alk_server"`）或直接 `pgrep -f 'envs/talk/bin/python .*talk_server.py'`；这就是 §4.1 #6 「`pkill -f` 自匹配」的同族坑 |
| 26 | 前端显示"方案有改动"但其实没改 | `prompt_revisions.schema_json` 是 **jsonb**（PG 重排对象键），前端 `JSON.stringify` 把**纯键序差异**当改动 | 新增 `canonicalJson()` 统一 dirty/pristine 比对；删掉 `startVoice/startBgm/startLipsync` 里会另存未确认版本的兜底保存 |
| 27 | 部署与生产任务互踩 | 同一张卡 | 纪律：**串行、不插队、不抢显存**；部署只在任务空隙；只做 ≤5 s 短查，禁止长轮询（单次等待 ≤60 s） |
| 28 | 许可风险 | FLUX.1-dev 系（含 IP-Adapter/PuLID 派生）**非商用** | 生产禁用 dev 派生权重；一致性改用 **Qwen-Image-Edit**（Apache-2.0）；T2 用 **FLUX.1-schnell**（Apache-2.0） |

---

## 5. 运维手册（常用命令）

```bash
# 机器
ssh root@180.127.11.166 -p 10512          # 别名 weaveora-gpu-a14b
df -h / /media/vipuser/addDisk            # 两块盘
curl -s http://127.0.0.1:8001/system_stats | python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"][0];print(round(d["vram_free"]/2**30,1),"G free")'

# 服务健康
for p in 8800 8001 8091 8093 8094; do printf "%s " $p; curl -s -o /dev/null -w "%{http_code}\n" -m 5 http://127.0.0.1:$p/; done
systemctl status weaveora-stack.service --no-pager | head -20

# 任务忙闲（部署/出图前必查）
curl -s http://127.0.0.1:8001/queue

# 下载（后台静默；日志即进度）
setsid nohup bash /opt/weaveora/dl_phase23.sh > /opt/weaveora/logs/dl_phase23.log 2>&1 < /dev/null &
tail -n 3 /opt/weaveora/logs/dl_phase23.log ; cat /opt/weaveora/logs/dl_phase23.pid   # 叫停：kill $(cat …)
df -h /media/vipuser/addDisk

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

## 6. 全量 sha256 表（本机实测，2026-09-15）

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

---

## 7. 待完成 / 后续

> 🚧 **2026-09-15 16:20 发现 GPU 服务器进入维护/重建态**：ssh 主机密钥变了、原密钥被拒（`Permission denied (publickey)`），
> 两个公网入口（10558/10588）均不可达（`000`）→ 依赖 GPU 的任务（出片/对口型/配音/配乐/本机出图）当前会失败。
> 维护结束后的回归清单（都已就绪，只差环境）：
> 1. 重新打通 ssh（新主机密钥已加入 `~/.ssh/known_hosts`；若新容器不认原公钥，需在平台侧重新注入）；
> 2. `bash /opt/weaveora/services_up.sh`（或确认 `weaveora-stack.service` 已启动）→ 校验 5 个端口 + 持久卷 `/opt/weaveora` 完整；
> 3. 部署网关新路由：推 `deploy/edge_proxy.py` 并**只重启网关进程**（不动 ComfyUI）→ `curl <网关>/talk/health` 应 200；
> 4. 配置页把「图像引擎」切成 **GPU 服务器**（`imageEngine=gpu`）→ 出图走本机 Qwen-Image；
> 5. 跑一遍第 5 镜三步链路（文生图 → motion → 对口型）+ 一个 `kind=talk` 任务（验证资产进「对口型」Tab）。

| 项 | 状态 | 说明 |
|---|---|---|
| **Qwen-Image-Edit（Phase 2）** | ⏳ 下载中（20.4 G） | 下完 → 写 `qwen_image_edit_api.json` → 用**第 5 镜关键帧**当参考图出一张对照 |
| **FLUX.1-schnell（T2，Phase 3）** | ⏳ 排队（22.7 G） | 走 `deploy/gpu2_dl_flux.sh`，同新盘 + 软链 |
| **kind=talk 生产接线** | 🟡 代码已入库（`8e9dd80`），未部署 | 需：① `edge_proxy.py` 加 `/talk` → `:8094`（数秒网关重启，挑任务空隙）② VPS worker env `WEAVEORA_TALK_URL=http://180.127.11.166:10558/talk` ③ 重启 `weaveora-gpu-worker` ④ 部署 API jar ⑤ 建 talk 任务验证资产进「对口型」Tab |
| **jaw_gain=1.25 增强档** | 🟡 参数已通 | 与 talk 接线一起验证 |
| **文生图切本地（imageEngine=gpu）** | ⏳ 待你定 | 目前 cloud 为主；Qwen-Image 已实测 40 s/张 |
| **旧模型迁到新盘** | ⏳ 可选 | 把 `diffusion_models/text_encoders/echo_mimic/checkpoints` 等旧大件"复制 → 校验 → 原子软链替换 → 删旧文件"迁到新盘，进一步松系统盘；**必须在任务空隙做** |
| **A14B worker 部署** | ⏳ 待部署 | 并发会话已提交 `4e93235`（Wan2.2 I2V-A14B 双专家 + TTS 显存让位），尚未上 VPS worker |
| **stable_syncnet.pt / ACE-Step split_files** | ⏳ 按需 | 推理不加载，磁盘紧张时再评估 |

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
