# 新 GPU 服务器 · 第一批模型安装文档（实录）

> 目标机：`batchcom@36.103.182.217:30203`（平台容器，Ubuntu 20.04 + Kasm 桌面）
> 硬件：AMD EPYC 7542 128 核 / 503 GiB RAM / **RTX 4090 24 GB**（驱动 580.65.06 = CUDA 13.0）
> 完成日期：2026-09-13 ｜ 规范依据：`Weaveora.md` §0.2「大文件下载铁律」
> 方案前提：**文生图 / 图生视频已切云 API**，本机只承担 `voice` / `bgm` / `lipsync` 三类自托管任务

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 第一批总量 | **≈ 22.4 GiB** |
| 下载耗时 | **约 25 分钟**（ModelScope 10 并发实测 **27–30 MiB/s**） |
| ComfyUI | **0.34.0**（容器内 `0.0.0.0:8000` ←→ 公网 `36.103.182.217:30250`） |
| 验证状态 | **配音 / 配乐 / 对口型 三项均已端到端冒烟通过**（见 §5） |
| 持久化 | 仅 `/home/dataset-local`（K8s PVC）为持久路径，**其余重启即重置**（见 §6） |

---

## 1. 部署拓扑

```
持久盘 /home/dataset-local/weaveora/          （K8s PVC /dev/nvme1n1, 100G）
├── ComfyUI/                     ComfyUI v0.34.0 源码 + venv(torch 2.7.1+cu126)
├── models/                      → 软链接进 ComfyUI/models
│   ├── checkpoints/             ACE-Step 配乐权重
│   └── face_id/                 YuNet / SFace ONNX
├── latentsync/                  LatentSync 权重（含 vae / whisper / buffalo_l）
├── audio/
│   ├── CosyVoice/               仓库代码(含 Matcha-TTS) + pretrained_models/
│   ├── tts_server.py            配音服务
│   └── music_server.py          配乐兜底（主线走 ComfyUI 原生节点）
├── envs/cosy/                   CosyVoice 独立 conda 环境（python 3.10.21）
├── logs/                        全部服务日志
├── weaveora_boot.sh             容器启动后一键恢复（幂等）
├── audio_start.sh               音频服务启动
└── dl_first_batch.sh            第一批下载脚本
```

**端口映射**：容器内 `8000` ←→ 公网 `36.103.182.217:30250`（平台侧映射，需服务绑 `0.0.0.0`）。
注意容器内 `8080` 被 **Kasm Vnc** 占用，`2222` 为 sshd。

---

## 2. 第一批模型清单（名称 / 版本 / 大小 / 下载源 / 落盘路径）

### 2.1 配乐 —— ACE-Step（9.34 GiB）

| 项 | 值 |
|---|---|
| 文件名 | `ace_step_1.5_turbo_aio.safetensors` |
| 版本 | **ACE-Step 1.5 Turbo（all-in-one 全量包）** |
| 大小 | 10,025,478,736 B（9.34 GiB） |
| 下载源 | **ModelScope** `Comfy-Org/ace_step_1.5_ComfyUI_files` → `checkpoints/ace_step_1.5_turbo_aio.safetensors` |
| 落盘 | `models/checkpoints/ace_step_1.5_turbo_aio.safetensors` |
| 调用方式 | **ComfyUI 原生节点**：`CheckpointLoaderSimple` → `ModelSamplingAuraFlow(shift=3)` → `TextEncodeAceStepAudio1.5` → `EmptyAceStep1.5LatentAudio` → `KSampler(euler/simple, steps=8, cfg=1)` → `VAEDecodeAudio` → `SaveAudioMP3(320k)` |
| 说明 | aio 单文件自含全部组件；官方另有 `split_files/`（qwen 编码器 + vae，≈20 GiB）**仅原生分体工作流需要，本批未下** |

### 2.2 对口型 —— LatentSync 1.6（5.32 GiB）

| 文件 | 版本 | 大小(B) | 下载源 | 落盘路径 |
|---|---|---|---|---|
| `latentsync_unet.pt` | LatentSync 1.6 主模型 | 5,072,222,488 | **aifasthub** `ByteDance/LatentSync-1.6` | `latentsync/` |
| `vae/diffusion_pytorch_model.safetensors` | SD VAE ft-mse | 334,643,276 | **aifasthub** `stabilityai/sd-vae-ft-mse` | `latentsync/vae/` |
| `whisper/tiny.pt` | OpenAI Whisper tiny | 75,572,083 | aifasthub `ByteDance/LatentSync-1.6` | `latentsync/whisper/` |
| `auxiliary/models/buffalo_l/*.onnx` ×5 | insightface **buffalo_l v0.7** | 288,621,354 | **ghfast.top** → GitHub `deepinsight/insightface` release v0.7 `buffalo_l.zip` | 解压至 `latentsync/auxiliary/models/buffalo_l/` |
| `config.json` | LatentSync 1.6 | 32 | aifasthub | `latentsync/` |
| `vae/config.json` | sd-vae-ft-mse | 547 | aifasthub | `latentsync/vae/` |

buffalo_l 5 个 onnx（与本机基线逐字节一致）：`1k3d68.onnx`(143,607,619) `w600k_r50.onnx`(174,383,860) `det_10g.onnx`(16,923,827) `2d106det.onnx`(5,030,888) `genderage.onnx`(1,322,532)

> `stable_syncnet.pt`（1.50 GiB）**推理不加载**（仅评估用）→ 已**推后到第二批**。

### 2.3 配音 —— CosyVoice（6.0 GiB）

| 模型 | 版本 | 文件数 | 大小 | 下载源 | 落盘 |
|---|---|---|---|---|---|
| CosyVoice2-0.5B | zero-shot 音色克隆（24kHz） | 12 | ≈3.8 GiB | **ModelScope** `iic/CosyVoice2-0.5B` | `audio/CosyVoice/pretrained_models/CosyVoice2-0.5B/` |
| CosyVoice-300M-SFT | v1 结构，**自带 7 个内置音色** | 7 | ≈2.2 GiB | **ModelScope** `iic/CosyVoice-300M-SFT` | `audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT/` |

**CosyVoice2-0.5B 实际所需 12 文件**（按本机 WSL 实测跑通的子集，仓库里的 fp16/fp32 zip 变体用不到）：
`llm.pt`(2,023,316,821) `CosyVoice-BlankEN/model.safetensors`(988,097,824) `speech_tokenizer_v2.onnx`(496,082,973) `flow.pt`(450,575,567) `hift.pt`(83,390,254) `campplus.onnx`(28,303,423) `CosyVoice-BlankEN/{vocab.json, merges.txt, tokenizer_config.json, config.json, generation_config.json}` `cosyvoice2.yaml`

**CosyVoice-300M-SFT 实际所需 7 文件**：
`llm.pt`(1,242,994,835) `speech_tokenizer_v1.onnx`(522,624,269) `flow.pt`(419,900,943) `hift.pt`(81,896,716) `campplus.onnx`(28,303,423) `spk2info.pt`(7,772，含 7 音色) `cosyvoice.yaml`

**内置音色（实测 `/health` 返回）**：`中文女` `中文男` `日语男` `粤语女` `英文女` `英文男` `韩语女`

> 仓库代码 `CosyVoice`（含 `third_party/Matcha-TTS` 子模块）由**本机 WSL 已验证版本打包上传**，避免版本漂移。
> `tts_server.py` 内部已用 `sys.path` 注入 Matcha-TTS（不依赖 CWD）。

### 2.4 人脸（可选，18 MB）

| 文件 | 版本 | 大小(B) | 来源 | 落盘 |
|---|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | OpenCV YuNet 2023mar | 232,589 | 本机 `D:\model\face_id\` 上传 | `models/face_id/` |
| `face_recognition_sface_2021dec.onnx` | OpenCV SFace 2021dec | 18,761,056 | 同上 | `models/face_id/` |

### 2.5 软件与运行环境

> ⚠️ **同步提醒**：上传节点代码时要确保用的是**当时最新**的本机版本。本次首次同步 `nodes.py` 时用了一小时前的旧副本（缺 `fps` 参数），导致对口型报 `unexpected keyword argument 'fps'`，重新整目录同步后解决。

| 组件 | 版本 | 来源 / 说明 |
|---|---|---|
| ComfyUI | **0.34.0**（tag） | `ghfast.top` → GitHub `Comfy-Org/ComfyUI` `refs/tags/v0.34.0.zip`（13 MB）。平台自带 0.27.0 **未使用** |
| ComfyUI venv | Python 3.12.7 | `/opt/anaconda3/bin/python -m venv`；torch **2.7.1+cu126** + requirements |
| LatentSync 节点 | ShmuelRonen/ComfyUI-LatentSyncWrapper | **本机已打 8 个补丁的版本上传**（禁用 hf_hub 自动下载、`__file__` 相对路径、UTF8、显存上限、VAE slicing、CUDA EP DLL 目录等，见 `docs/lipsync-setup.md` §二）；**本机又追加第 9 个补丁**（`latentsync/utils/util.py` 显式 `format="FFMPEG"`，见 §4 坑 13） |
| 节点额外依赖 | — | `omegaconf diffusers einops opencv-python mediapipe decord face-alignment DeepCache safetensors soundfile` + `insightface librosa pytorch_lightning onnx` + `onnxruntime-gpu==1.30.0` |
| CosyVoice 环境 | conda env，Python **3.10.21** | `envs/cosy`；torch **2.3.1+cu121**（与 ComfyUI 的 2.7.1 冲突，故独立环境） |
| 音频依赖 | — | 跳过与推理无关的 `deepspeed / tensorrt-cu12* / gradio / fastapi / uvicorn / tensorboard`；`openai-whisper==20231117` 需 `setuptools<81` + `--no-build-isolation` |
| node（下载器运行） | v20.18.0 | `registry.npmmirror.com` 二进制（2.46 MB/s） |

---

## 3. 下载方式（遵守 §0.2 铁律）

- **≥200 MiB**：`gpu_model_downloader.js` —— 10 路 Range 分片 + `.meta.json` 断点续传 + `.done` 标记 + 每 10s 进度日志，**后台静默**
- **<200 MiB**：`curl -L -C -`
- 脚本：`deploy/download_linux_first_batch.sh`（本仓库）→ 部署到 `/home/dataset-local/weaveora/dl_first_batch.sh`

**实测源速（新服务器）**

| 源 | 单连速度 | 10 并发聚合 | 覆盖 |
|---|---|---|---|
| ModelScope | 3.24 – 3.95 MB/s | **27 – 30 MiB/s** | ACE-Step、CosyVoice |
| aifasthub | 4.14 MB/s | — | LatentSync |
| ghfast.top | 0.07 – 0.71 MB/s | — | buffalo_l、GitHub zip |
| 清华 pypi | 2.77 MB/s | — | Python 依赖 |
| npmmirror | 2.46 MB/s | — | Node 二进制 |
| ~~github / huggingface~~ | ❌ 直连不通 | — | 走 ghfast/hf-mirror |

---

## 4. 踩坑实录（复用时必读）

| # | 现象 | 根因 | 解决 |
|---|---|---|---|
| 1 | **pip 装 torch 卡死 40 分钟**，连接 CLOSE-WAIT，不报错不超时 | pip 自带下载器在 700 MB+ 大轮子上会停滞（WSL 侧同样踩过：cudnn 731 MB 0 B/s） | **改用 `uv`**：同样内容几分钟装完。uv 是本次的关键工具 |
| 2 | uv 也卡在 pypi 上（`151.101.x.x` Fastly CDN） | 官方 pypi 在本机吞吐不稳定 | 设 `UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple`（实测 2.77 MB/s） |
| 3 | `pyworld==0.3.4` 编译失败 `No such file or directory: 'g++'` | 容器缺 C++ 编译器 | `apt-get install -y build-essential` |
| 4 | **配乐任务永久卡死**：AR 循环停 `Model Initializing... 0/150`，CPU 0%、GPU 0%、全线程 `futex_wait`，堆栈落在 `comfy_aimdo/host_buffer.py::read_file_slice`（native `hostbuf_read_file_slice` 不返回） | uv 自动解析到 **torch 2.14.0+cu130**（过新），与 ComfyUI 0.34 的 DynamicVRAM / comfy-aimdo 0.4.15 不兼容 | **钉回基线 `torch==2.7.1+cu126`**。此后启动日志自动打印：`Unsupported Pytorch detected. DynamicVRAM support requires Pytorch version 2.8 or later. Falling back to legacy ModelPatcher` → 走已验证的旧路径。（`--disable-async-offload` / `--disable-dynamic-vram` **均无效**） |
| 5 | ComfyUI 启动极慢（3–5 分钟才监听 8000） | 容器存储为虚拟化后端，**冷顺序读实测仅 ≈54 MB/s**（`dd` 顺序读 9.34 GiB 用了 186 s；企业级 NVMe 标称 3 GB/s，慢约 55×） | 属平台固有特性。**不要**误判为卡死——判断依据：`/proc/diskstats` 的 nvme1n1 有读数即是在加载。缓解：服务常驻、避免重启 |
| 6 | 用 `oflag=direct` 测盘只有 7.7 MB/s，一度误判为磁盘瓶颈 | **direct I/O 在这套虚拟化存储上是慢路径**（测试方法伪影） | 用 buffered 顺序读测真实速度（≈54 MB/s 冷 / 296 MB/s 命中 cache） |
| 7 | `LatentSyncNode` 未注册，`IMPORT FAILED` | venv 缺 `omegaconf` 等节点依赖 | 按 §2.5 补装节点 requirements + insightface/librosa/pytorch_lightning/onnx/onnxruntime-gpu |
| 8 | **重启后环境全丢** | 平台说明：除**数据集挂载路径**外实例内容重置。实测唯一持久路径 = `/home/dataset-local`（K8s PVC）；`/`(overlay)、`/opt/anaconda3/envs/*`、`/home/start.sh` **均会重置** | ① 模型/代码/环境/日志**全部**放 `/home/dataset-local/weaveora/`；② conda 环境用 `conda create --prefix`（**不要**用指向 `/opt` 的 venv，重启后 python 会断）；③ 用 `weaveora_boot.sh` 恢复临时层（见 §6） |
| 9 | ComfyUI 0.27.0 与 worker 代码不匹配 | worker 按 v0.34 API 写（`KSampler` 必填 `denoise`、`POST /prompt` 需 `{"prompt":{…},"client_id"}`、`Wan22ImageToVideoLatent.start_image`、latent 帧数 4n+1） | 升级到 **0.34.0** |
| 10 | 平台自带 ComfyUI（0.27.0）重启后会抢 8000 端口 | `/home/start.sh` 默认拉起 `/home/batchcom/comfyui` | bootstrap 覆盖 `/home/start.sh` 指向自有实例，并 kill 旧进程 |
| 11 | 「上传到服务器」方案不可行 | 本机 → 服务器上传实测仅 **0.23–1.37 MB/s**，50 GiB 需 ≈14 小时；而同源下载 8–30 MB/s | **一律在服务器本地下载**，仅上传几 MB 的补丁代码 |
| 12 | 后台脚本日志长时间无输出，误判卡死 | 脚本里 `cmd 2>&1 \| tail -N` 会**缓冲到结束才输出** | 输出**直写日志文件**（不走管道），或加 `--no-progress` 逐行输出 |
| 13 | **对口型推理跑完但最终报错**：`TypeError: PyAVPlugin.write() got an unexpected keyword argument 'macro_block_size'` | LatentSync 节点 `latentsync/utils/util.py::write_video` 给 `imageio.get_writer` 传了 `macro_block_size`，而 **Linux 上 imageio 会优先选 PyAV 插件**（venv 里有 `av 18.1.0`），PyAV 不接受该参数。（Windows 侧 imageio/imageio-ffmpeg/av 版本与本机**完全相同**、`util.py` md5 也一致，但走的是 FFMPEG 插件，故不报错——属平台差异） | **给 `util.py` 加第 9 个补丁**：显式 `format="FFMPEG"` 指定后端（注意参数名是 `format=`，**不是 `plugin=`**——后者会被透传给 `_open()` 报 `unexpected keyword argument 'plugin'`）。已备份原文件为 `util.py.orig` |
| 14 | 对口型首次运行卡在下载并可能超时 | LatentSync 的 `Audio2Feature("tiny")` 传的是**模型名而非路径**，whisper 会去 `~/.cache/whisper/tiny.pt` 找，找不到就联网下（`openaipublic.azureedge.net`，本机实测可达 4.7 MiB/s，但换环境可能被墙） | 把 `latentsync/whisper/tiny.pt` 复制到 `~/.cache/whisper/tiny.pt`；并已存一份到持久盘 `cache/whisper/tiny.pt`，由 `weaveora_boot.sh` 自动恢复（`~/.cache` 在 overlay 会被重置） |
| 15 | 测试素材无脸导致 `RuntimeError: Face not detected` | 用的静态图（`example.png`）不含人脸 | 测试需用含清晰人脸的素材；本机用 `scikit-image` 的 `data.astronaut()`（512×512 含正脸）即可，无需外网 |
| 16 | ComfyUI 启动耗时 8–11 分钟 | 启动时导入 `transformers` 等会扫描 site-packages 全部元数据（`packages_distributions`），叠加本盘**小文件随机读极慢** | 实测**没有任何并发下载抢 I/O**（各盘读写 ≈ 0），属自身导入开销。判断“是否卡死”看 `/proc/diskstats` 是否有读数。服务常驻、避免频繁重启 |
| 17 | **克隆音色任务卡在 `running` 数分钟**，看起来像“任务没成功” | 实际是**首次运行开销**，而非失败：① CosyVoice 的文本正则化用 **wetext**，其 FST 模型由 `modelscope.snapshot_download("pengzhendong/wetext")` **在运行期联网拉取**（38 文件 / 52 MB）；② 请求 `clone:*` 音色会从 SFT 切换加载 **v2（CosyVoice2-0.5B）** 模型（单卡互斥，~1–2 min）。合计 3–4 分钟后 `succeeded` | ① 把 `MODELSCOPE_CACHE` 指向持久盘（`services_up.sh` 已设 `$ROOT/cache/modelscope`，已预置 38 个 fst），避开 ~/.cache 重启即丢；② 模型切换开销属设计固有（单卡只保留一个模型），UI 上可提示“克隆音色首个任务较慢” |
| 18 | Windows 上用 `schtasks /End /TN X` 报 `无效参数 'C:/Program Files/Git/End'` | Git Bash 的 **MSYS 路径转换**把 `/End`、`/TN` 当成路径 | 改用 PowerShell：`Disable-ScheduledTask -TaskName X`（或 `MSYS_NO_PATHCONV=1`） |
| 19 | 对口型任务失败：`LIPSYNC_ERROR: No module named 'cv2'`；补装后又 `imageio_ffmpeg 不可用` | 生产机（CentOS 8 / **Python 3.6.8**）原本无 cv2/numpy。lipsync 要**在 worker 侧**做视频分段处理（`_frame_count` / `_decode_frames` / `_encode_frames` / `_source_fps` / `_splice`），而 `_encode_frames_mp4` 里 `import imageio_ffmpeg` 失败会**直接抛错**（`_ffmpeg_exe()` 才回退 PATH） | 装 py3.6 兼容版：`pip3 install "numpy<1.20" "opencv-python-headless<4.7" "imageio-ffmpeg<0.5"`（实测 `numpy 1.19.5` / `cv2 4.6.0` / `imageio-ffmpeg 0.4.9`）。（`PIL` 只在已切云 API 的 motion 路径用且包在 try/except 里，可不管） |
| 20 | 对口型继续失败：`TypeError: __init__() got an unexpected keyword argument 'capture_output'` | `subprocess.run(capture_output=…, text=…)` 是 **Python 3.7+** API，而生产机是 3.6.8。`comfy_client.py` 里有 **6 处** | **改仓库代码**（提升 py3.6–3.13 通用性）：`capture_output=True, text=True` → `stdout=<alias>.PIPE, stderr=<alias>.PIPE, universal_newlines=True`（**必须分离 stdout/stderr**，代码会分别读 `r.stderr`；注意 line 504 的局部别名是 `_sp`）。同步后**重启 worker** 才生效 |
| 21 | 每次重启后第一个**克隆音色**任务都要等 3–4 分钟 | ① wetext FST 由 `modelscope.snapshot_download` 运行期拉取（38 文件/52MB）；② `clone:*` 会从 SFT 切到 v2（单卡互斥） | `MODELSCOPE_CACHE` 指向持久盘 `$ROOT/cache/modelscope`（已预置）；模型切换开销属设计固有 |
| 22 | 对口型继续失败：`ffmpeg 失败: Unrecognized option 'fps_mode'` | `-fps_mode` 需 **ffmpeg >= 4.3**，而 `_ffmpeg_exe()` 优先用 `imageio-ffmpeg` 自带的二进制；Python 3.6 上 imageio-ffmpeg 只能装到 **0.4.9（ffmpeg 4.2.2）**。系统 `/usr/local/bin/ffmpeg` 是 7.0.2 但没被优先选 | **改仓库代码**：① `_ffmpeg_exe()` 新增 `WEAVEORA_FFMPEG` 环境变量优先（文件里有**两处**同名定义，后者生效，两处都要改）；② `_encode_frames_mp4` 原本直接 `import imageio_ffmpeg` 且失败就抛错（不回退），改为统一走 `_ffmpeg_exe()`。生产机 env 加 `WEAVEORA_FFMPEG=/usr/local/bin/ffmpeg` |

---

## 5. 验收清单与当前状态

| # | 检查项 | 命令 | 状态 |
|---|---|---|---|
| 1 | GPU 可见 | `venv/bin/python -c "import torch;print(torch.cuda.get_device_name(0))"` | ✅ RTX 4090 |
| 2 | ComfyUI 版本 | `curl -s localhost:8000/system_stats` | ✅ 0.34.0 |
| 3 | 公网可达 | 生产机 `curl http://36.103.182.217:30250/system_stats` | ✅ HTTP 200（0.2s） |
| 4 | ACE-Step 节点 | `object_info` 含 `TextEncodeAceStepAudio1.5` / `EmptyAceStep1.5LatentAudio` / `VAEDecodeAudio` / `SaveAudioMP3` | ✅ 全部注册 |
| 5 | LatentSync 节点 | `object_info` 含 `LatentSyncNode` | ✅ 已注册（902 节点） |
| 6 | 视频/音频基础节点 | `LoadVideo` `GetVideoComponents` `CreateVideo` `SaveVideo` `LoadAudio` | ✅ |
| 7 | 模型可见 | `object_info/CheckpointLoaderSimple` → `ckpt_name` | ✅ `ace_step_1.5_turbo_aio.safetensors` |
| 8 | **配音端到端** | `POST localhost:8091/tts {"text":"浮生若梦…","voice":"中文女"}` | ✅ HTTP 200，4.05 s 音频 / 175 KB WAV；**热启动 2 s**（冷启动 381 s = 读盘） |
| 9 | 内置音色 | `curl -s localhost:8091/health` | ✅ 7 个音色 |
| 10 | **配乐端到端** | `worker/test_music_comfy.py`（`WEAVEORA_COMFY_URL=http://127.0.0.1:8000`） | ✅ `status: success`，产出 30 s / 1.2 MB / **320 kbps 48 kHz 立体声** MP3；**热调用 10.1 s** |
| 11 | **对口型端到端** | 工作流 `lipsync_workflow_api.json` + 512²/25fps 人脸视频 | ✅ `status: success`，产出 **h264 512×512@25fps + AAC 音轨** MP4，2.4 s 视频耗时 **112.55 s**（3070Ti 为 370 s，**快 3.3×**）；显存峰值 ≈ **24 GB**（跑满 4090） |
| 12 | 持久环境可用 | TTS 改用 `envs/cosy/bin/python` 重启 | ✅ `/health` → `loaded: true, warm: true` |

---

## 6. 重启恢复（重要）

平台每次重启会重置 overlay。恢复步骤：

```bash
bash /home/dataset-local/weaveora/weaveora_boot.sh
```

`weaveora_boot.sh`（幂等，已放持久盘）会：
1. 补装 `curl` + `build-essential`（overlay 会丢）
2. 重建软链 `/opt/anaconda3/envs/cosy` → 持久环境
3. 覆盖 `/home/start.sh`，指向自有 ComfyUI v0.34.0
4. 停掉抢 8000 的平台自带 ComfyUI
5. 拉起 ComfyUI（已在监听则跳过）与 TTS `:8091`（幂等）

> **建议**：把平台控制台的「**启动命令**」设为
> `bash /home/dataset-local/weaveora/weaveora_boot.sh`
> 这样每次开机自动恢复，无需人工干预。

---

## 8. 单端口入口网关（公网只有 30250）

平台只开放 `36.103.182.217:30250` → 容器 `8000`，而本机有 4 个服务，**必须在容器内做按路径多路复用**。

### 8.1 端口规划

| 端口 | 服务 | 说明 |
|---|---|---|
| **8000** | `edge_proxy.py`（边缘网关） | 平台映射的端口；**对外唯一入口** |
| 8001 | ComfyUI | 配乐 / 对口型 / 出图的宿主 |
| 8091 | `tts_server.py` | 配音 `/tts` + **转写** `/transcribe` |
| 8092 | `music_server.py` | 配乐 HTTP 兜底（默认不起，主线走 ComfyUI） |
| 8093 | `face_server.py` | 人脸 `/face/probe`、`/face/embed` |

### 8.2 路由表（`deploy/edge_proxy.py`）

| 公网路径 | 转发目标 | 是否剥前缀 | 覆盖能力 |
|---|---|---|---|
| `/audio/*` | `127.0.0.1:8091` | ✅ 剥 | **配音** `/audio/tts`、**转写** `/audio/transcribe`、`/audio/health` |
| `/bgm/*` | `127.0.0.1:8092` | ✅ 剥 | 配乐 HTTP 兜底（主线经 ComfyUI，不走这里） |
| `/face/*` | `127.0.0.1:8093` | ❌ 保留 | **人脸** `/face/probe`、`/face/embed` |
| `/*` | `127.0.0.1:8001` | — | **ComfyUI**（`/prompt`、`/object_info`、`/history`、`/view`、`/upload/image`、**`/ws` WebSocket**） |
| `/__edge/health` | 网关自检 | — | 返回路由表 |

> 为什么不用 nginx：**平台每次重启会重置 overlay**，nginx 得重新 apt 安装；而网关是**单文件放在持久盘**，且 `aiohttp 3.14.3` ComfyUI venv 自带，免安装。

### 8.3 worker 侧只需改环境变量（**无需改任何代码**）

```bash
export WEAVEORA_COMFY_URL=http://36.103.182.217:30250
export WEAVEORA_TTS_URL=http://36.103.182.217:30250/audio
export WEAVEORA_MUSIC_URL=http://36.103.182.217:30250/bgm
export WEAVEORA_FACE_URL=http://36.103.182.217:30250
```

依据（worker 代码里的拼接方式）：
- `comfy_client.py` → `COMFY + "/prompt"`、`FACE_URL + "/face/probe"`、`FACE_URL + "/face/embed"`
- `audio_client.py` → `TTS_URL + "/tts"`、`MUSIC_URL + "/music"`、转写 `TTS_URL + "/transcribe"`

### 8.4 实测（2026-09-13）

| 项 | 经网关本机 | 经公网（生产机发起） |
|---|---|---|
| ComfyUI `/system_stats` | HTTP 200，0.34.0 / RTX 4090 | **HTTP 200，0.20s** |
| 配音 `/audio/health` | HTTP 200，7 音色 | **HTTP 200，0.22s** |
| 配音 `/audio/tts` | HTTP 200，111 KB WAV | — |
| 人脸 `/face/embed` | HTTP 200，512 维（热 6.8s / 冷 42.6s） | — |
| 网关 `/__edge/health` | HTTP 200，路由表正确 | **HTTP 200，0.19s** |
| 配乐 / 对口型节点 | `TextEncodeAceStepAudio1.5`、`LatentSyncNode`、`SaveAudioMP3` 均 OK | 同左（走 ComfyUI 路由） |

### 8.5 启动方式

```bash
bash /home/dataset-local/weaveora/services_up.sh     # 幂等拉起全部服务
bash /home/dataset-local/weaveora/weaveora_boot.sh   # 重启后一键恢复（含系统依赖）
```

`/home/start.sh`（bootstrap 写入）已改为调用 `services_up.sh`，Kasm 桌面 Chrome 指向 `127.0.0.1:8000`（经网关看 ComfyUI 界面）。

---

## 9. 第二批下载清单（推后项）

推后原因：**文生图 / 图生视频已切云 API**，本机不再承担 `still` / `clip`。

### 7.1 权重（合计 ≈ 33.3 GiB）

| # | 文件 | 用途 | 大小 | 下载源 |
|---|---|---|---|---|
| 1 | `sd_xl_base_1.0.safetensors` | SDXL base 1.0 出图 | 6.46 GiB | ModelScope `AI-ModelScope/stable-diffusion-xl-base-1.0` |
| 2 | `wan2.2_ti2v_5B_fp16.safetensors` | Wan2.2 TI2V-5B 图生视频 | 9.31 GiB | ModelScope `Comfy-Org/Wan_2.2_ComfyUI_Repackaged` |
| 3 | `umt5_xxl_fp8_e4m3fn_scaled.safetensors` | Wan 文本编码器（fp8） | 6.27 GiB | 同上 |
| 4 | `wan2.2_vae.safetensors` | Wan2.2 VAE | 1.31 GiB | 同上 |
| 5 | `ip-adapter_sdxl_vit-h.safetensors` | IP-Adapter 一致性 | 0.65 GiB | ModelScope `AI-ModelScope/IP-Adapter` |
| 6 | `CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors` | IP-Adapter 图像编码 | 3.67 GiB | ModelScope `AI-ModelScope/CLIP-ViT-H-14-laion2B-s32B-b79K` |
| 7 | **`stable_syncnet.pt`** | LatentSync 评估用（**推理不加载**） | 1.50 GiB | aifasthub `ByteDance/LatentSync-1.6` |
| 8 | **Whisper `base.pt`** | 配音时长对齐 / 转写字幕 | 0.14 GiB | `openaipublic.azureedge.net`（或 ModelScope 镜像） |
| 9 | `umt5_xxl_fp16.safetensors`（可选兜底） | 8G 卡老路线备选，24G 卡不需要 | 10.58 GiB | ModelScope（同 #2 仓库） |

### 7.2 代码 / 节点（≈ 几十 MB）

| # | 组件 | 来源 |
|---|---|---|
| 1 | `custom_nodes/ComfyUI_IPAdapter_plus` | `ghfast.top/https://github.com/cubiq/ComfyUI_IPAdapter_plus/archive/refs/heads/main.zip` |
| 2 | `custom_nodes/ComfyUI-WanVideoWrapper` | `ghfast.top/https://github.com/kijai/ComfyUI-WanVideoWrapper/…`（现运行时用原生 Wan 节点，非必需） |

### 7.3 可选（视需求）

| # | 内容 | 大小 | 何时需要 |
|---|---|---|---|
| 1 | ACE-Step `split_files/*`（`acestep_v1.5_turbo` + `qwen_1.7b/0.6b_ace15` + `ace_1.5_vae`） | ≈20 GiB | 需要**分体原生工作流**（更低显存/更高可控性）时；aio 全量包已够用 |
| 2 | `pip install acestep`（`music_server.py` 兜底路径） | ~ | 只在不用 ComfyUI 原生节点、改走 8092 HTTP 服务时 |
| 3 | `WEAVEORA_WHISPER_MODEL=small` | +460 MB | 转写精度不够时 |

### 7.4 第二批**不需要**的（已由云 API 承担）

- 分镜/关键帧一致性相关的本地模型（云侧解决）
- Kijai wrapper 的 motion 专用权重

---

## 11. Worker 部署（生产机，无 Windows 依赖）

### 11.1 为什么不用 Windows

对口型工作流路径是 **worker 进程本地读取**（`open(LIPSYNC_WORKFLOW)`），所以历史上 GPU worker 必在 Windows，导致：① 依赖 Windows 开机；② 工作流路径得填 Windows 路径。

**实测结论：生产机本身就是常驻的（跑着 API），GPU worker 完全可以放在它上面。**

### 11.2 连通性实测

| 方向 | 结果 |
|---|---|
| 新 GPU 服务器 → 生产机 `:443/:80`（公网） | ✅ OPEN |
| 新 GPU 服务器 → 生产机 `:8080`（API 内网口）/ `:22` | ❌ BLOCKED |
| 生产机 → 新 GPU `:30250`（网关） | ✅ 全部端点 HTTP 200 |
| 新 GPU 出口 IP | `36.103.182.60`（入口是 `36.103.182.217`）—— 两个独立源核实 |

### 11.3 生产机 nginx 放行（若 worker 改放 GPU 服务器则需要）

`/etc/nginx/nginx.conf` 中两个 `location ^~ /weaveora/internal/` 块原本有：
```nginx
allow 127.0.0.1; allow 222.211.217.183; allow 183.36.43.35; deny all;
```
用 `deploy/nginx_allow_gpu_internal.py` 幂等追加 `allow 36.103.182.60;`（自带备份 + `nginx -t` + 失败回滚）。
实测：新 GPU 服务器由 **403 → 401**（已过白名单，待鉴权），其它 IP 仍 403。

### 11.4 本方案：GPU worker 跑在生产机（推荐）

| 项 | 值 |
|---|---|
| systemd 单元 | `/etc/systemd/system/weaveora-gpu-worker.service` |
| 环境文件 | `/etc/weaveora/weaveora-gpu-worker.env`（`WEAVEORA_WORKER_MODE=comfy`） |
| 工作流文件 | **`/opt/weaveora/lipsync_workflow_api.json`** |
| 引擎地址 | 均走公网网关 `http://36.103.182.217:30250` |
| API | `http://127.0.0.1:8080`（本机，零依赖） |

**部署步骤**（已执行）：
1. 同步 worker 代码 `worker/{stub_worker,comfy_client,audio_client}.py` → `/opt/weaveora/`（**旧版缺 `apply_services`/`FACE_URL`，即「生成引擎配置」不会生效**）
2. 建 env + unit，`systemctl enable --now weaveora-gpu-worker`
3. 重启 `weaveora-cloud-worker` 让它加载新代码
4. Windows 侧：`Disable-ScheduledTask ComfyWorker` + `ComfyWorkerHeartbeat`，并 kill 残留进程

**注册结果**：
```
cloud-worker     engine=cloud  gpu=cloud                     ✅ 常驻
win-comfy-worker engine=gpu    （已停心跳，age > 80s）        ❌ 已退役
gpu-worker-new   engine=gpu    audio=true gpu=comfy         ✅ 新
                 workflows=[sdxl_txt2img, wan_i2v, ipadapter, cosyvoice_tts, ace_step_music]
```

### 11.5 依赖差异（重要）

`worker/comfy_client.py` 里的 `cv2` / `numpy` 只在**函数内**（第 891 行的 `_FACE_CHECK` 子进程脚本）导入，**顶层 import 全是 stdlib**。因此生产机的 **Python 3.6.8 无需 cv2/numpy/insightface** —— 只要把「人脸服务地址」配好，`_face_probe` 会**优先走远端** `FACE_URL + "/face/probe"`，失败才回退本机子进程：
```python
if FACE_URL:
    ... _post_json(FACE_URL + "/face/probe", ...)   # 走新 GPU 服务器 8093
```

### 11.6 「生成引擎配置 → ③ 服务地址」最终取值（方案 A）

| 表单项（UI label） | 配置键 | 值 |
|---|---|---|
| **对口型工作流** | `services.lipsync.workflow` | **`/opt/weaveora/lipsync_workflow_api.json`** |
| 对口型 ComfyUI 地址 | `services.lipsync.comfyUrl` | `http://36.103.182.217:30250` |
| 对口型超时（秒） | `services.lipsync.timeout` | `1800` |
| 输出帧率 | `services.lipsync.fps` | `0` |
| 配音（TTS）服务地址 | `services.tts.url` | `http://36.103.182.217:30250/audio` |
| 配乐引擎 | `services.music.engine` | `comfy` |
| 配乐权重名 | `services.music.ckpt` | `ace_step_1.5_turbo_aio.safetensors` |
| 转写服务地址 | `services.transcribe.url` | `http://36.103.182.217:30250/audio`（**API 侧调用**，非 worker） |
| 人脸服务地址 | `services.face.url` | `http://36.103.182.217:30250` |
| 人脸/LatentSync 节点目录 | `services.face.latentsyncDir` | 留空（用远端人脸服务） |

### 11.7 worker 的完整环境变量支持清单

```
WEAVEORA_API_BASE          WEAVEORA_WORKER_MODE        WEAVEORA_WORKER_NAME
WEAVEORA_WORKER_TOKEN      WEAVEORA_WORKER_WORKSPACE   WEAVEORA_COMFY_URL
WEAVEORA_COMFY_FALLBACK_TXT2IMG                        WEAVEORA_TTS_URL
WEAVEORA_TTS_TIMEOUT       WEAVEORA_MUSIC_URL          WEAVEORA_MUSIC_ENGINE
WEAVEORA_MUSIC_CKPT_NAME   WEAVEORA_MUSIC_STEPS/CFG/SHIFT/SAMPLER/SCHEDULER
WEAVEORA_MUSIC_AUDIO_CODES WEAVEORA_MUSIC_BPM/KEYSCALE/LANGUAGE/QUALITY/TAGS_CFG
WEAVEORA_MUSIC_TEMPERATURE WEAVEORA_MUSIC_TIMESIG/MAX_SEC/TIMEOUT/SAVE_NODE
WEAVEORA_FACE_URL          WEAVEORA_LATENTSYNC_DIR     WEAVEORA_LIPSYNC_WORKFLOW
WEAVEORA_LIPSYNC_TIMEOUT   WEAVEORA_LIPSYNC_FPS        WEAVEORA_LIPSYNC_NODE_CLASS
WEAVEORA_LIPSYNC_VIDEO_TITLE / VIDEO_INPUT / AUDIO_TITLE / AUDIO_INPUT
```

---

## 12. 相关文件索引

| 用途 | 位置 |
|---|---|
| **单端口网关** | `deploy/edge_proxy.py` → `/home/dataset-local/weaveora/edge_proxy.py` |
| **服务编排（幂等）** | `deploy/services_up.sh` → `/home/dataset-local/weaveora/services_up.sh` |
| 人脸服务 | `deploy/face/face_server.py` → `/home/dataset-local/weaveora/face/face_server.py` |
| 本机（Windows）下载脚本 | `deploy/download_linux_first_batch.sh` |
| 本机（Windows）ComfyUI 依赖安装 | `deploy/install_comfyui_deps.sh` |
| 本机（Windows）CosyVoice 依赖安装 | `deploy/install_cosyvoice_deps.sh` |
| LatentSync 节点依赖安装 | `deploy/install_latentsync_deps.sh` |
| 启动脚本（ComfyUI） | `deploy/start_sh_newserver.sh` |
| 启动脚本（音频服务） | `deploy/audio_start_newserver.sh` |
| 重启恢复 bootstrap | `deploy/weaveora_boot.sh` |
| **nginx 放行 GPU 出口 IP** | `deploy/nginx_allow_gpu_internal.py` |
| **GPU worker unit**（生产机） | `/etc/systemd/system/weaveora-gpu-worker.service` |
| **GPU worker env**（生产机） | `/etc/weaveora/weaveora-gpu-worker.env` |
| **对口型工作流**（生产机） | `/opt/weaveora/lipsync_workflow_api.json` |
| 服务器端路径 | `/home/dataset-local/weaveora/` |
| 日志 | `/home/dataset-local/weaveora/logs/`（`comfyui.log` / `audio_tts.log` / `face.log` / `edge_proxy.log` / `services_up.log` / `boot.log`） |
| 早期全量清单（云 API 前的 50 GiB 版本） | `docs/gpu-newserver-models.md` |
| 对口型安装细节与 8 个坑 | `docs/lipsync-setup.md` |

---

## 13. Windows 侧退役（2026-09-14）

引擎全部迁到新 GPU 服务器后，原 Windows 机器（`WIN-20240101MQK`，RTX 3070 Ti）不再承担生产任务，已**停止服务并禁用开机自启**。

### 13.1 已停用 + 已禁用自启（计划任务全部 `Disabled`）

| 计划任务 | 作用 | 对应服务/端口 |
|---|---|---|
| `ComfyUI` / `ComfyUIHeartbeat` | 出图 / 视频 / **配乐**（ACE-Step 原生节点） | `main.py --port 8188` |
| `ComfyTTS` / `ComfyTTSHeartbeat` | **配音**（WSL CosyVoice） | `tts_server.py` `:8091` |
| `ComfyWorker` / `ComfyWorkerHeartbeat` | GPU worker | `stub_worker.py` |
| `ComfyTunnel` / `ComfyTunnelHeartbeat` | SSH 反向隧道（把本机服务暴露给生产机） | `tunnel_comfy.ps1` |

另：**人脸服务**（`deploy/face/face_server.py` `:8093`）已停——它**没有自启入口**（计划任务 / 启动文件夹 / 注册表 Run / Windows 服务 均查过），属手工启动的孤儿进程。

停用后核对：`8188 / 8091 / 8092 / 8093` 全部空闲，WSL 内 `tts_server` 进程 0 个，静默 90 秒无复活。

### 13.2 坑：两个容易“停不干净”的点

1. **守护脚本会“复活”服务**：`D:\ComfyUI\_setup\comfy_win.ps1` 是 `while($true)` 循环（服务退出后 10 秒重启，靠全局互斥锁保单实例）。
   → 必须**先杀守护（`comfy_win.ps1`），再杀服务（`main.py`）**；只杀服务会被立刻拉回。同理 `tunnel_comfy.ps1`、`wsl_tts_win.ps1`、`worker_win.ps1`。
2. **心跳任务必须一起禁用**：`*Heartbeat` 任务每 10 分钟跑一次同样的守护脚本（确保存活）。
   → 只禁用主任务而不禁心跳，服务仍会被拉回。且**禁用不会终止已排队/在跑的实例**，所以禁用后仍可能被拉起一次，需再杀一遍。

### 13.3 另一发现：定时唤醒从未生效（BIOS 层）

- Windows 侧配置完全正确：`RTCWAKE`（允许唤醒计时器）AC/DC 均为 `0x1`、`WeaveWakeAuto` 任务 `WakeToRun=True`、`powercfg /waketimers` 能查到登记的计时器。
- 但 `Power-Troubleshooter` 历史显示 **8 次唤醒全部是「电源按钮」，没有一次定时器唤醒**（`wake.log` 里的 `woken-by-WeaveWakeAuto` 都是人按电源键后任务靠 `StartWhenAvailable` 补跑产生的假象）。
- 结论：**ACPI RTC 闹钟没有被固件兑现**。机器是 ASUS 主板 + AMI BIOS 2212（物理机）。
- 修复方向：BIOS → `Advanced → APM Configuration`：
  - **`ErP Ready` = Disabled**（最常见的元凶，启用它会切断 S3/S5 所有唤醒源）
  - **`Power On By RTC` = Enabled**
  - `Deep Sleep Control`（如有）= Disabled；`Fast Boot` 建议 Disabled
- 备用方案：拿同局域网内一台常开设备发 WoL 魔法包（本机 `192.168.0.112`，MAC `C8-7F-54-A9-8B-09`，支持 `WakeOnMagicPacket`）。
  ⚠️ **生产机不能当发送方**：它在 `10.0.0.3/24`，ping `192.168.0.112` 100% 丢包，不在同一局域网。
- 另有：睡眠前记得清掉**已过期的 `WakeToRun` 残留任务**（曾用 `WeaveWake2h/3h/Test`），否则系统一挂起就被立刻唤醒去“补跑”，表现为“睡下 4 秒就醒”。
