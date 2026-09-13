# 新 GPU 服务器（RTX 4090 24G）模型与权重清单

> 目标机：`batchcom@36.103.182.217:30203`（平台容器，Ubuntu 20.04，Kasm 桌面）
> 硬件：AMD EPYC 7542 128 核 / 503 GiB RAM / **RTX 4090 24 GB**（驱动 580.65.06，CUDA 13.0）
> 编制日期：2026-09-13 ｜ 对应规范：`Weaveora.md` §0.2「大文件下载铁律」
> 用途：把 Weaveora 的 GPU 引擎（ComfyUI 出图/视频 + 配音 + 配乐 + 对口型）整体迁移到 Linux

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 新服务器现状 | ComfyUI **v0.27.0 纯净 clone**（135 MB），`models/` **56 KB 全空**，`custom_nodes/` 空，无 venv |
| torch | anaconda3(Py3.12) 自带 **torch 2.5.0，CUDA 可用 ✅** |
| 需下载总量 | **≈ 50 GiB**（不含可选兜底 10.6 GiB） |
| 下载位置 | ✅ **直接在新服务器上下载**（见 §3 实测依据），**不要**本机下载再上传 |
| 上传带宽 | ❌ 本机 → 新服务器仅 **0.23 ~ 1.37 MB/s**（50 GiB 需 **≈ 14 小时**） |
| 下载带宽 | ✅ 新服务器 → ModelScope 单连 3.95 MB/s，**10 并发聚合 8.3 MB/s**（50 GiB ≈ **1.7 小时**） |

---

## 1. 新服务器网络实测（2026-09-13）

| 下载源 | 新服务器可达性 | 实测速度（Range 20 MB） |
|---|---|---|
| `www.modelscope.cn`（主站 + API + CDN） | ✅ 206 | **3.24 – 3.95 MB/s** |
| ModelScope 10 并发聚合 | ✅ 7/10 成功 | **8.30 MB/s** |
| `aifasthub.com`（LatentSync 权重） | ✅ 206 | **4.14 MB/s** |
| `hf-mirror.com` | ✅ 206 | 1.21 MB/s（慢，仅备用） |
| `ghfast.top`（github 加速） | ✅ 200/206 | zip 小文件可用；buffalo_l 0.71 MB/s |
| `openaipublic.azureedge.net`（whisper 官方） | ✅ 206 | 0.56 MB/s |
| `registry.npmmirror.com`（Node 二进制） | ✅ 206 | 2.46 MB/s |
| `pypi.org` / `download.pytorch.org` | ✅ | 正常 |
| `github.com` / `huggingface.co` | ❌ 直连不通 | 走 `ghfast.top` 前缀 |
| 生产服务器（175.12.60.225 / sysou.com） | ❌ **双向封禁** | 仅本机 Windows 可同时连通两边 |

> **上传方向实测**：100 MB→429 s（0.23 MB/s）；50 MB→57 s（0.88 MB/s）；200 MB(ssh 管道)→146 s（1.37 MB/s）。
> 结论：**上传不可行**，模型一律在服务器本地下载。

---

## 2. 模型清单（按用途分组）

路径根：`MODELS=/home/batchcom/comfyui/models`（或软链到持久盘，见 §4）

### A. ComfyUI 核心 —— 出图 / 图生视频（**必装，27.68 GiB**）

| # | 目标路径 | 大小 | 字节数 | 下载地址 |
|---|---|---|---|---|
| A1 | `checkpoints/sd_xl_base_1.0.safetensors` | 6.46 GiB | 6,938,078,334 | `https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors` |
| A2 | `diffusion_models/wan2.2_ti2v_5B_fp16.safetensors` | 9.31 GiB | 9,999,658,848 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors` |
| A3 | `text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` | 6.27 GiB | 6,735,906,897 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` |
| A4 | `vae/wan2.2_vae.safetensors` | 1.31 GiB | 1,409,400,960 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/vae/wan2.2_vae.safetensors` |
| A5 | `ipadapter/ip-adapter_sdxl_vit-h.safetensors` | 0.65 GiB | 698,391,064 | `https://www.modelscope.cn/models/AI-ModelScope/IP-Adapter/resolve/master/sdxl_models/ip-adapter_sdxl_vit-h.safetensors` |
| A6 | `clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors` | 3.67 GiB | 3,944,552,236 | `https://www.modelscope.cn/models/AI-ModelScope/CLIP-ViT-H-14-laion2B-s32B-b79K/resolve/master/model.safetensors` |

**可选兜底（10.58 GiB，24G 卡非必需）**：`text_encoders/umt5_xxl_fp16.safetensors`
`…/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/text_encoders/umt5_xxl_fp16.safetensors`

> 24G 显存下 **无需 fp8 量化**：`UNETLoader.weight_dtype` 可设 `default`(fp16/bf16)，`WanVideoModelLoader.quantization` 设 `disabled`。
> （本地 3070Ti 8G 才需要 `fp8_e4m3fn_scaled`。）

### B. 配乐 —— ACE-Step（**必装，9.34 GiB**）

| # | 目标路径 | 大小 | 字节数 | 下载地址 |
|---|---|---|---|---|
| B1 | `checkpoints/ace_step_1.5_turbo_aio.safetensors` | 9.34 GiB | 10,025,478,736 | `https://www.modelscope.cn/models/Comfy-Org/ace_step_1.5_ComfyUI_files/resolve/master/checkpoints/ace_step_1.5_turbo_aio.safetensors` |

- 服务代码：`pip install acestep`（pypi 可达，无需搬运）
- 该仓库另有 `split_files/`（qwen_0.6b/1.7b/4b 文本编码器 + ace_1.5_vae，共 ~20 GiB）：**仅原生节点工作流需要，AIO 全量包已自含，暂不下**。

### C. 配音 —— CosyVoice（**必装，6.0 GiB**）

以**本机 WSL 实测跑通的子集**为准（仓库全量含 fp16/fp32 zip 变体，用不到）：

**C1. CosyVoice2-0.5B（3.8 GiB，12 文件）** → `pretrained_models/CosyVoice2-0.5B/`
基址 `https://www.modelscope.cn/models/iic/CosyVoice2-0.5B/resolve/master/`

| 文件 | 大小 |
|---|---|
| `llm.pt` | 1.88 GiB |
| `CosyVoice-BlankEN/model.safetensors` | 942.3 MB |
| `speech_tokenizer_v2.onnx` | 473.1 MB |
| `flow.pt` | 429.7 MB |
| `hift.pt` | 79.5 MB |
| `campplus.onnx` | 27.0 MB |
| `CosyVoice-BlankEN/vocab.json` | 2.65 MB |
| `CosyVoice-BlankEN/merges.txt` | 1.34 MB |
| `cosyvoice2.yaml` | 7.3 KB |
| `CosyVoice-BlankEN/tokenizer_config.json` | 1.3 KB |
| `CosyVoice-BlankEN/config.json` | 659 B |
| `CosyVoice-BlankEN/generation_config.json` | 242 B |

**C2. CosyVoice-300M-SFT（2.2 GiB，7 文件）** → `pretrained_models/CosyVoice-300M-SFT/`
基址 `https://www.modelscope.cn/models/iic/CosyVoice-300M-SFT/resolve/master/`

| 文件 | 大小 |
|---|---|
| `llm.pt` | 1.16 GiB |
| `speech_tokenizer_v1.onnx` | 498.4 MB |
| `flow.pt` | 400.4 MB |
| `hift.pt` | 78.1 MB |
| `campplus.onnx` | 27.0 MB |
| `spk2info.pt` | 7.8 KB（7 个内置音色） |
| `cosyvoice.yaml` | 6.4 KB |

**C3. CosyVoice 仓库代码**：`ghfast.top/https://github.com/QwenAudio/CosyVoice/archive/refs/heads/main.zip`
（或直接上传本机 WSL 的 `/data/audio/CosyVoice/cv.zip`，1.6 MB，更稳）

### D. 对口型 —— LatentSync 1.6（**必装，6.9 GiB**）

**D1. 权重**（基址 `https://aifasthub.com/`，实测 4.14 MB/s）→ 节点 `checkpoints/`

| # | 目标路径 | 大小 | 字节数 | 地址 |
|---|---|---|---|---|
| D1 | `checkpoints/latentsync_unet.pt` | 4.72 GiB | 5,072,222,488 | `ByteDance/LatentSync-1.6/resolve/main/latentsync_unet.pt` |
| D2 | `checkpoints/stable_syncnet.pt` | 1.50 GiB | 1,605,328,746 | `ByteDance/LatentSync-1.6/resolve/main/stable_syncnet.pt` |
| D3 | `checkpoints/whisper/tiny.pt` | 72.1 MiB | 75,572,083 | `ByteDance/LatentSync-1.6/resolve/main/whisper/tiny.pt` |
| D4 | `checkpoints/config.json` | 32 B | 32 | `ByteDance/LatentSync-1.6/resolve/main/config.json` |
| D5 | `checkpoints/vae/diffusion_pytorch_model.safetensors` | 319.1 MiB | 334,643,276 | `stabilityai/sd-vae-ft-mse/resolve/main/diffusion_pytorch_model.safetensors` |
| D6 | `checkpoints/vae/config.json` | 547 B | 547 | `stabilityai/sd-vae-ft-mse/resolve/main/config.json` |
| D7 | `checkpoints/auxiliary/models/buffalo_l/*.onnx` | 288 MB | 288,621,354 | `https://ghfast.top/https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip`（解压到该目录） |

> `auxiliary/*` 下的 i3d / koniq / vgg16 / vit_g / syncnet_v2 / sfd_face（~2.8 GiB）**推理不需要，不要下**。

**D2. 节点代码**：`ComfyUI-LatentSyncWrapper`
⚠️ **必须用本机已打补丁的版本，不要 fresh clone** —— 本机版已修 8 个坑（禁用 hf_hub 自动下载、`__file__` 相对路径、UTF8、显存上限、VAE slicing、CUDA EP DLL 目录等，详见 `docs/lipsync-setup.md` §二）。

### E. 转写 —— Whisper（**0.14 GiB**）

| # | 目标 | 大小 | 地址 |
|---|---|---|---|
| E1 | `~/.cache/whisper/base.pt` | 138.5 MiB | `https://openaipublic.azureedge.net/main/whisper/models/ed3a0b6b1c0edf879ad9b11b1af5a0e6ab5db9205f891f668f8b0e6c6326e34e/base.pt`（已含在 D3 的 `tiny.pt` 之上，按需） |

- 替代源（更稳）：ModelScope 上的 whisper 镜像。
- `WEAVEORA_WHISPER_MODEL=small` 会再拉 ~460 MB。

### F. 人脸识别 face_id（可选，18 MB）

| # | 目标路径 | 大小 |
|---|---|---|
| F1 | `models/face_id/face_detection_yunet_2023mar.onnx` | 227 KB |
| F2 | `models/face_id/face_recognition_sface_2021dec.onnx` | 17.9 MB |

（本机已有，属小文件，直接 scp 上传即可。）

### G. 服务代码 / custom_nodes

| # | 组件 | 来源 | 说明 |
|---|---|---|---|
| G1 | `ComfyUI_IPAdapter_plus` | `ghfast.top/https://github.com/cubiq/ComfyUI_IPAdapter_plus/archive/refs/heads/main.zip` | IP-Adapter 一致性 |
| G2 | `ComfyUI-LatentSyncWrapper` | **上传本机已打补丁版** | 对口型 |
| G3 | `ComfyUI-WanVideoWrapper` | `ghfast.top/…/kijai/ComfyUI-WanVideoWrapper/…` | 可选（现运行时用原生 Wan 节点） |
| G4 | ComfyUI 本体 | 已有 v0.27.0 | ⚠️ 本机生产版为 **v0.34.0**，见 §5 |

### H. Python 环境

- `pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu126`（pytorch 源可达；anaconda 自带 2.5.0 亦可直接用）
- `pip install -r ComfyUI/requirements.txt`（pypi 可达）
- 音频：`pip install acestep`；CosyVoice 按仓库 `requirements.txt`
- 对口型额外：`insightface librosa pytorch_lightning onnx` + **`onnxruntime-gpu==1.22.0`**（CUDA 12 系必须 ≤1.22）

---

## 3. 下载方式（遵守 §0.2 大文件下载铁律）

- **≥200 MiB**：必须 `gpu_model_downloader.js`（10 路 Range + `.meta.json` 断点 + `.done` 标记 + 每 10s 进度）
- **<200 MiB**：`curl -L -C -` 即可
- **一律后台静默**，禁止前台阻塞；日志每 10 s 一行含 百分比/总量/已下载/速度

新服务器无 Node，需先装（npmmirror 可达，2.46 MB/s）：

```bash
curl -L -C - -o /tmp/node.tar.xz \
  https://registry.npmmirror.com/-/binary/node/v20.18.0/node-v20.18.0-linux-x64.tar.xz
mkdir -p ~/opt && tar -xJf /tmp/node.tar.xz -C ~/opt
export PATH=~/opt/node-v20.18.0-linux-x64/bin:$PATH
node -v   # v20.18.0
```

---

## 4. 数据落盘位置（重要）

| 挂载点 | 容量 | 可用 | 持久性 |
|---|---|---|---|
| `/home/batchcom`（overlay） | 1.9 T | 428 G | ⚠️ **容器重建即丢** |
| `/home/dataset-local`（`/dev/nvme1n1`） | 100 G | 100 G | ✅ 实例内存活（真实挂载） |
| `/home/dataset-bc-pub`（NFS） | 493 G | 436 G | 只读 |

**建议**：模型（≈50 GiB）放 `/home/dataset-local/models`，再用软链挂到 ComfyUI：
```bash
mkdir -p /home/dataset-local/models
ln -sfn /home/dataset-local/models /home/batchcom/comfyui/models   # 注意先清空原 models/ 内空目录
```
预算：50 GiB 模型 / 100 G 盘 → 剩 ~50 G，够；若还下 wan fp16 兜底(10.6G)+ACE 原生组件(20G) 则需升配或改用 overlay。

---

## 5. 版本差异与整改建议

| 组件 | 新服务器 | 本机生产（基线） | 建议 |
|---|---|---|---|
| ComfyUI | **v0.27.0**（1377a2f7, 2026-07-10） | **v0.34.0** | ⚠️ **升级到 v0.34.0**：worker 代码按 v0.34 API 写（`KSampler` 必填 `denoise`、`POST /prompt` 需 `{"prompt":{...},"client_id"}`、`Wan22ImageToVideoLatent.start_image`、latent 帧数 4n+1） |
| torch | 2.5.0（anaconda） | 2.7.1+cu126 | 建议统一 cu126/cu121 与驱动匹配（驱动 580 支持 CUDA 13，向下兼容） |
| 显存策略 | — | fp8_e4m3fn_scaled（8G 卡） | 24G 卡**改 `disabled`/fp16**，质量更好、速度更快 |
| 采样 | — | — | 24G 可提高 `steps`、加帧数（33→81）、720p |

---

## 6. 验收清单（部署后）

```bash
# 1) GPU 可见
/opt/anaconda3/bin/python -c "import torch;print(torch.cuda.get_device_name(0))"   # RTX 4090
# 2) ComfyUI 健康
curl -s http://127.0.0.1:8000/system_stats     # 注意：对外端口 30250 → 容器内 8000
# 3) 节点注册
curl -s http://127.0.0.1:8000/object_info | grep -o 'IPAdapterUnifiedLoader\|LatentSyncNode\|CheckpointLoaderSimple' | sort -u
# 4) 模型可见
curl -s http://127.0.0.1:8000/object_info/CheckpointLoaderSimple   # 应见 sd_xl_base_1.0 / ace_step
# 5) 音频服务
curl -s localhost:8091/health ; curl -s localhost:8092/health      # {"ok":true,...}
```

**端口映射已确认**：公网 `36.103.182.217:30250` → 容器内 **8000**（需服务绑 `0.0.0.0:8000`；`8080` 被 Kasm Vnc 占用）。
白名单已放行 `175.12.60.225`；注意实测**本机非白名单 IP 也能访问**，如需收紧请在平台侧确认。

---

## 7. 总量汇总（全量）

| 分组 | 大小 |
|---|---|
| A ComfyUI 核心 | 27.68 GiB |
| B 配乐 ACE-Step | 9.34 GiB |
| C 配音 CosyVoice（2 模型） | 6.00 GiB |
| D 对口型 LatentSync | 6.90 GiB |
| E Whisper | 0.14 GiB |
| F face_id | 0.02 GiB |
| **合计** | **≈ 50.1 GiB** |
| 可选：UMT5 fp16 兜底 | +10.58 GiB |
| 可选：ACE-Step 原生组件 | +20.0 GiB |

---

## 8. 【当前方案】第一批次清单 —— 出图/视频已切云 API

> **前提（2026-09-13 用户确认）**：文生图（still）与图生视频（clip）改用**云 API**，本机不再承担。
> 依据 `worker/stub_worker.py` 路由：`still`/`clip` → `cloud`；**`voice`/`bgm`/`lipsync` → 固定 `engineRoute=gpu`，必须自托管**。
> 因此 **A 组（ComfyUI 核心模型 27.68 GiB + fp16 兜底 10.58 GiB）整体后置**，第一批不做。

### 8.1 第一批总量：**≈ 22.4 GiB**（其中大文件 21.9 GiB）

| 分组 | 内容 | 大小 | 是否必需 |
|---|---|---|---|
| **G 基础设施** | ComfyUI 升级 0.27.0→0.34.0 + venv + torch + requirements | ~2 GiB（pip） | ✅ 必需 |
| **B 配乐** | `ace_step_1.5_turbo_aio.safetensors` | **9.34 GiB** | ✅ 必需 |
| **C 配音** | CosyVoice2-0.5B + CosyVoice-300M-SFT + 仓库代码 | **6.00 GiB** | ✅ 必需 |
| **D 对口型** | latentsync_unet + vae + buffalo_l + tiny.pt + configs | **5.32 GiB** | ✅ 必需 |
| **E 转写** | Whisper `base.pt` | 0.14 GiB | ⭕ 按需（字幕/转写） |
| **F 人脸** | face_id onnx ×2 | 0.02 GiB | ⭕ 可选（小，建议带上） |
| **D-可选** | `stable_syncnet.pt` | 1.50 GiB | ❌ 推理不加载，可后置 |

### 8.2 必须下载的大文件（≥200 MiB，走 10 路 Range）

| # | 目标路径（相对 `MODELS/`） | 大小 | 源 |
|---|---|---|---|
| 1 | `checkpoints/ace_step_1.5_turbo_aio.safetensors` | 9.34 GiB | ModelScope `Comfy-Org/ace_step_1.5_ComfyUI_files` |
| 2 | `latentsync/latentsync_unet.pt` | 4.72 GiB | aifasthub `ByteDance/LatentSync-1.6` |
| 3 | `latentsync/vae/diffusion_pytorch_model.safetensors` | 319.1 MiB | aifasthub `stabilityai/sd-vae-ft-mse` |
| 4 | `latentsync/auxiliary/models/buffalo_l/*.onnx` | 288 MB | ghfast `deepinsight/insightface` v0.7 `buffalo_l.zip` |
| 5 | `latentsync/whisper/tiny.pt` | 72.1 MiB | aifasthub `ByteDance/LatentSync-1.6` |
| 6 | CosyVoice2-0.5B `llm.pt` | 1.88 GiB | ModelScope `iic/CosyVoice2-0.5B` |
| 7 | CosyVoice2-0.5B `CosyVoice-BlankEN/model.safetensors` | 942.3 MB | 同上 |
| 8 | CosyVoice2-0.5B `speech_tokenizer_v2.onnx` | 473.1 MB | 同上 |
| 9 | CosyVoice2-0.5B `flow.pt` | 429.7 MB | 同上 |
| 10 | CosyVoice-300M-SFT `llm.pt` | 1.16 GiB | ModelScope `iic/CosyVoice-300M-SFT` |
| 11 | CosyVoice-300M-SFT `speech_tokenizer_v1.onnx` | 498.4 MB | 同上 |
| 12 | CosyVoice-300M-SFT `flow.pt` | 400.4 MB | 同上 |
| ⭕ | Whisper `base.pt` | 138.5 MiB | azureedge 官方 |
| ⭕ | `latentsync/stable_syncnet.pt` | 1.50 GiB | aifasthub（推理不加载，可后置） |

其余为 <200 MiB 小文件（`curl -L -C -` 即可）：`hift.pt`(79.5 MB)、`campplus.onnx`(27 MB)、`spk2info.pt`、`cosyvoice*.yaml`、`config.json`、`vocab.json`、`merges.txt`、face_id onnx 等。

### 8.3 只需上传的小文件（走 `D:\ComfyUI Ubuntu` 中转，合计 ~几 MB）

| 项 | 说明 |
|---|---|
| `ComfyUI-LatentSyncWrapper` 节点代码 | ⚠️ **必须用本机已打 8 补丁的版本**（禁用 hf_hub 自动下载、`__file__` 路径、UTF8、显存上限、VAE slicing、CUDA EP DLL 目录等） |
| `tts_server.py` / `music_server.py` / `audio_standby.sh` | 来自 `deploy/audio/` |
| `worker/` 全套 + `deploy/gpu_provision.sh`、`gpu_comfy_standby.sh` | 部署脚本 |
| CosyVoice 仓库 `cv.zip`（1.6 MB） | 本机 WSL `/data/audio/CosyVoice/cv.zip`，**需另补 `third_party/Matcha-TTS` 子模块** |
| face_id onnx ×2（18 MB） | `D:\model\face_id\` |

> 这些合计仅几 MB ~ 18 MB，上传无压力，**符合 `D:\ComfyUI Ubuntu` 的中转定位**。

### 8.4 第一批可以不做（云 API 已接管 / 可选）

| 项 | 大小 | 理由 |
|---|---|---|
| `sd_xl_base_1.0.safetensors` | 6.46 GiB | 出图走云 |
| `wan2.2_ti2v_5B_fp16.safetensors` | 9.31 GiB | 视频走云 |
| `umt5_xxl_fp8_e4m3fn_scaled.safetensors` | 6.27 GiB | 同上 |
| `umt5_xxl_fp16.safetensors` | 10.58 GiB | 同上（兜底） |
| `wan2.2_vae.safetensors` | 1.31 GiB | 同上 |
| `ip-adapter_sdxl_vit-h.safetensors` | 0.65 GiB | 出图一致性，云侧解决 |
| `CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors` | 3.67 GiB | 同上 |
| `custom_nodes/ComfyUI_IPAdapter_plus` | 代码 | 同上 |
| `custom_nodes/ComfyUI-WanVideoWrapper` | 代码 | 视频走云，原生节点已覆盖 |
| `stable_syncnet.pt` | 1.50 GiB | LatentSync 推理不加载 |
| ACE-Step `split_files/*`（qwen 编码器 + vae） | ~20 GiB | aio 全量包已自含 |
| `acestep` pip（`music_server.py` 兜底路径） | — | 主线走 ComfyUI 原生节点 |
| `patch_comfy_kitchen_cuda13.py` | — | 本机是驱动 566(CUDA12.7) 才需打；**新服务器驱动 580 = CUDA 13.0，原生可用，无需打补丁** |

### 8.5 第一批执行顺序（建议）

1. 装 Node（npmmirror）→ 取得合规下载器
2. 建目录与软链：模型放 `/home/dataset-local/models`，软链到 `comfyui/models`
3. **起 10 路并发下载 B + C + D（≈21.9 GiB，约 50 分钟 @8.3 MB/s）**，后台静默、日志每 10s 一行
4. 并行：`pip install` ComfyUI 依赖（pypi 直连）+ 建 CosyVoice 的 py3.10 conda 环境
5. 上传小文件（打补丁的节点代码、服务脚本、face_id）
6. 升级 ComfyUI → 0.34.0，启动绑 `0.0.0.0:8000`，跑 §6 验收

