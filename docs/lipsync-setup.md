# 对口型（lipsync）安装与配置

> **为什么需要这一步**：图生视频模型（Wan 2.2 i2v、p-video 等）**没有音频输入通道** ——
> 它们只根据图片和文字生成运动，不可能知道台词在说什么，所以**嘴型不可能对上台词**。
> 要口型一致，必须再加一个「**音频驱动嘴型**」的后处理环节。

## 方案总览（按成本）

| 方案 | 成本 | 质量 | 说明 |
|---|---|---|---|
| **分镜规避**（已在导演 Prompt 里做） | 0 | 观众无感 | 有台词的镜头避免正脸大特写，改用过肩/侧脸/反应镜头 |
| **本地 LatentSync 1.6**（已落地） | 电费 | 高 | ComfyUI 节点 `ComfyUI-LatentSyncWrapper`；3070 Ti 8GB 实测 **~2.5 分钟/秒视频** |
| MuseTalk | 电费 | 中高 | 速度快，适合长片段 |
| Wav2Lip-GAN | 电费 | 中 | 最稳最快，画质一般 |
| 云端（Replicate `sync/lipsync` 等） | 按次/按秒 | 高 | 关键镜头再用，省本地时间 |

平台侧已经实现：`kind=lipsync` 任务（画面 + 配音 → 对口型视频）、渲染时**优先使用对口型产物**、
导演 Prompt 会在必要镜头写 `speaking, mouth moving` 并标记 `shots[].lip_sync`。

> **状态（2026-09-13）**：本机 3070 Ti 8GB **已跑通**。实测：512×512 / 25fps / 2.41s（60 帧）
> → **370 秒**出片，显存峰值 ~7.9 GiB。GPU 利用率 97–100%，非「跑不动」。

---

## 一、安装 ComfyUI-LatentSyncWrapper（本机 GPU）

```powershell
# 1) 克隆节点
cd D:\ComfyUI\custom_nodes
git clone https://github.com/ShmuelRonen/ComfyUI-LatentSyncWrapper.git
cd ComfyUI-LatentSyncWrapper

# 2) 依赖（用 ComfyUI 的 venv）。注意：requirements.txt 漏了 insightface 与 librosa！
& D:\ComfyUI\venv\Scripts\python.exe -m pip install -r requirements.txt
& D:\ComfyUI\venv\Scripts\python.exe -m pip install insightface librosa pytorch_lightning onnx
#    CUDA 12 环境（torch cu126）必须钉 onnxruntime-gpu<=1.22；1.23+ 是按 CUDA 13 编的
& D:\ComfyUI\venv\Scripts\python.exe -m pip uninstall -y onnxruntime
& D:\ComfyUI\venv\Scripts\python.exe -m pip install onnxruntime-gpu==1.22.0

# 3) 拉模型权重 —— 禁止用节点自带的 download_models.py / huggingface_hub！
#    按 Weaveora.md §0.2「大文件下载铁律」用 10 进程分片下载器（后台静默、可续传）：
#    powershell -NoProfile -Command "Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','D:\workspace\Weaveora\deploy\windows\dl_latentsync.ps1' -WindowStyle Hidden -RedirectStandardOutput 'D:\model\_dl\latentsync_dl.log' -RedirectStandardError 'D:\model\_dl\latentsync_dl.err.log'"
#    进度：type D:\model\_dl\latentsync_dl.log   （每 10s 一行：百分比 / 总大小 / 已下载 / 速度）
```

### 1.1 权重清单（LatentSync 1.6，推理必需）

实测可用镜像（2026-09-13）：**`https://aifasthub.com`**（Range 206，10 进程 ~40–80 MiB/s）。
`hf-mirror.com` 实测极不稳定（~0.08 MiB/s、并发 TLS 被掐），仅作最后备用。

| 目标路径（`checkpoints/` 下） | 大小 | 来源 |
|---|---|---|
| `latentsync_unet.pt` | 4.72 GiB | `aifasthub.com/ByteDance/LatentSync-1.6/resolve/main/latentsync_unet.pt` |
| `stable_syncnet.pt` | 1.50 GiB | 同仓库 `stable_syncnet.pt`（README 列为主模型；推理不加载，评估才用） |
| `whisper/tiny.pt` | 72.1 MiB | 同仓库 `whisper/tiny.pt` |
| `config.json` | 32 B | 同仓库 `config.json` |
| `vae/diffusion_pytorch_model.safetensors` | 319.1 MiB | `aifasthub.com/stabilityai/sd-vae-ft-mse/resolve/main/diffusion_pytorch_model.safetensors` |
| `vae/config.json` | 547 B | 同 VAE 仓库 `config.json` |
| `auxiliary/models/buffalo_l/*.onnx` | 335 MiB | `gh-proxy.com/https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip` |

合计 ≈ **6.9 GiB**。字节数校验（与 HF API 一致）：
`5,072,222,488 / 1,605,328,746 / 75,572,083 / 32 / 334,643,276 / 547`；buffalo_l.zip = `288,621,354`。

### 1.2 另外两件「必须手动放好」的东西

1. **whisper 的缓存副本**：`inference.py` 调 `Audio2Feature(model_path="tiny")`（传的是**名字**不是路径），
   whisper 会去 `%USERPROFILE%\.cache\whisper\tiny.pt` 找；找不到就**从 `openaipublic.azureedge.net`
   联网下载**。请把 `checkpoints/whisper/tiny.pt` 复制过去（sha256 必须等于
   `65147644a518d12f04e32d6f3b26facc3f8dd46e5390956a9424a650c0ce22b9`，不匹配会重新下载）。
2. **ffmpeg**：节点的 `check_ffmpeg()` 只认名为 `ffmpeg.exe` 的可执行文件（PATH 或
   `<节点>/ffmpeg/bin/`）。ComfyUI venv 里 `imageio_ffmpeg` 自带一个，直接复制改名即可：
   ```powershell
   New-Item -ItemType Directory -Force D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper\ffmpeg\bin
   Copy-Item (D:\ComfyUI\venv\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-*.exe) `
             D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper\ffmpeg\bin\ffmpeg.exe
   ```
3. **训练/评估用的 `auxiliary/*`**（i3d / koniq / vgg16 / vit_g / syncnet_v2 / sfd_face，~2.8 GiB）
   推理链路不需要，**不用下**。

验证节点已加载：

```powershell
Invoke-RestMethod "http://127.0.0.1:8188/object_info" |
  ConvertTo-Json -Depth 4 | Select-String -Pattern "LatentSyncNode" -SimpleMatch
```

---

## 二、本机已排掉的 8 个坑（2026-09-13，都别踩）

这些坑的表现都是「**ComfyUI 卡住 / 队列永远 running=1 / GPU 0%**」，实际是软件问题，不是显卡不够。

| # | 现象 | 根因 | 已做的修法 |
|---|---|---|---|
| 1 | 节点**导入即挂死**，整轮会话堵死 | 上游 `snapshot_download` 长连接 + `huggingface.co` 不可达 | 禁用 snapshot/hf_hub，改用 `deploy/windows/gpu_model_downloader.js`（10 进程分片 + 断点续传 + 后台）；见 Weaveora.md §0.2 |
| 2 | 节点**首次实例化**就挂死 | `nodes.py::pre_download_models()` 无条件去 `huggingface.co` 拉 s3fd，`requests.get` **无 timeout**；而该文件推理根本不用 | 默认跳过；确需时设 `WEAVEORA_ALLOW_S3FD_DOWNLOAD=1`；并给 `download_model` 加 `timeout=(10,60)` |
| 3 | 找不到模型 / 想联网下 buffalo_l | 上游用 **CWD 相对路径**（ComfyUI 的 CWD=`D:\ComfyUI`）：`checkpoints/auxiliary`、`configs/audio.yaml`、`latentsync/utils/mask.png` | 全部改为按 `__file__` 解析（`face_detector.py` / `audio.py` / `image_processor.py` / `lipsync_pipeline.py`） |
| 4 | 任务瞬间失败，之后**所有**任务都不再执行 | Windows Python stdio 默认 **GBK**，`scripts/inference.py` 打印 `✓` 触发 `UnicodeEncodeError`；异常处理里 `traceback.print_exc()` 又因同一个 `✓` 再次崩溃，**打死 prompt_worker 线程** | ① launcher `comfy_win.ps1` 加 `$env:PYTHONUTF8="1"`；② 3 处非 ASCII print 改 ASCII |
| 5 | 429/瞬间 OOM，`Reserved 6553 MiB` | `nodes.py` 有 `torch.cuda.set_per_process_memory_fraction(0.8)`，把进程死限在 8GB×0.8=6.5GB | 默认**不再设上限**；需要时用 `WEAVEORA_LATENTSYNC_MEM_FRACTION` 显式指定 |
| 6 | 峰值显存紧、易 OOM | 上游从未开 VAE 分片 | `scripts/inference.py` 构造 pipeline 后调用 `pipeline.enable_vae_slicing()` |
| 7 | face detector 静默退回 CPU | `insightface` 的 CUDA EP 找不到 `cublasLt64_12.dll`/`cudnn64_9.dll`（它们在 `torch\lib`） | `face_detector.py` **先 import torch 并 `os.add_dll_directory(torch/lib)`**，再 import insightface |
| 8 | 注入输入失败 / 一跑就 OOM | ① 原生 `LoadVideo` 的输入键是 **`file`**（不是 `video`）；② ComfyUI 缓存着 SDXL/Wan 占显存 | ① worker 新增 `WEAVEORA_LIPSYNC_VIDEO_INPUT`/`_AUDIO_INPUT`；② 跑 lipsync 前先 `POST /free`（`unload_models+free_memory`） |

---

## 三、API 格式工作流

已生成：**`D:\ComfyUI\_setup\lipsync_workflow_api.json`**（原生节点，无需装 VideoHelperSuite）：

```
1 LoadVideo(标题 "video", 输入键 file)  ─┐
2 GetVideoComponents                    ├─► 4 LatentSyncNode(images, audio, seed, lips_expression, inference_steps)
3 LoadAudio(标题 "audio", 输入键 audio) ─┘        └─► 5 CreateVideo(images, audio, fps) ─► 6 SaveVideo(filename_prefix)
```

- worker 按 **节点标题** 找节点：标题 `video` / `audio`（可用 env 改名）；
  再按 **输入键** 注入上传后的文件名（默认视频 `video`、音频 `audio`，原生节点要用 `file`）。
- `SaveVideo` 的输出在 `/history` 里以 `outputs[node]["images"]` 上报，worker 的
  `_download_outputs()` 已能正确取回 mp4。

在 ComfyUI 界面里自己搭也可以：菜单 **Workflow → Export (API)** 导出，覆盖上面这个文件即可
（记得把两个加载节点标题改成 `video` / `audio`）。

## 四、配置 worker 环境

`deploy/windows/worker_win.ps1`（本机实际运行副本：`D:\ComfyUI\_setup\worker_win.ps1`）：

```powershell
$env:WEAVEORA_LIPSYNC_WORKFLOW   = "D:\ComfyUI\_setup\lipsync_workflow_api.json"
$env:WEAVEORA_LIPSYNC_VIDEO_INPUT = "file"   # 原生 LoadVideo 的输入键；VHS_LoadVideo 才是 "video"
$env:WEAVEORA_LIPSYNC_TIMEOUT     = "1800"   # 3070Ti：≈2.5 分钟/秒视频，1800s 够 5s 片段
# 可选：节点标题不是 video/audio 时
# $env:WEAVEORA_LIPSYNC_VIDEO_TITLE = "video"
# $env:WEAVEORA_LIPSYNC_AUDIO_TITLE = "audio"
```

改完**重启 worker supervisor**（任务计划 `ComfyWorker`）才生效：

```powershell
Get-CimInstance Win32_Process | ? { $_.CommandLine -like "*worker_win*" } | % { Stop-Process $_.ProcessId -Force }
Get-CimInstance Win32_Process -Filter "Name='python.exe'" | ? { $_.CommandLine -like "*stub_worker*" } | % { Stop-Process $_.ProcessId -Force }
Start-ScheduledTask -TaskName ComfyWorker
```

## 五、实测性能（RTX 3070 Ti 8GB）

| 项 | 实测值 |
|---|---|
| 分辨率 / 帧率 | 512×512 / 25fps（LatentSync 1.6 固定 512） |
| 单块 | 16 帧一块，约 **75 秒**（20 steps，DeepCache + VAE slicing，~1.5–3 s/step） |
| 2.41 秒视频（60 帧 = 4 块） | **370 秒**（6.2 分钟） |
| 折算 | **≈ 2.5 分钟 / 秒视频** → 4–5s 片段约 10–13 分钟 |
| 显存峰值 | ~7.9 GiB / 8192 MiB（**很紧**，所以必须先 `/free` 卸掉 ComfyUI 缓存模型） |
| 失败重跑 | 重跑同镜即可；不会静默产出无口型结果 |

> 想更快：① 只对有台词的正脸镜跑；② 对话镜时长模式用 `audio_first`（镜长=配音长）避免白跑；
> ③ `inference_steps` 20 → 12 左右（质量略降）；④ 关键镜头改走云端（Replicate `sync/lipsync`）。

### 5.1 并发与排队（重要）

对口型在 8GiB 卡上会把显存吃到 ~7.9GiB，**同一时刻不能再有其它 GPU 任务**：

- 本机 worker（`stub_worker.py`）是**单线程取任务**，天然串行；但如果在其它节点/云端并发处理，会 OOM。
- API 侧已在 lipsync 任务的 `payload` 里带上 `gpuExclusive: true` 与 `gpuHint`（人话提示），
  并在创建时打 `WARN` 日志；前端「对口型」按钮/Tab 的 tooltip 也写了这条。
- 排队建议：把对口型放到其它 GPU 任务都跑完之后再提交；一个 4–5s 对话镜约 10–13 分钟，
  别和关键帧/motion 批量任务混在一起排。

```json
// GET /api/jobs 里 lipsync 任务的 payload 片段
{ "kind": "lipsync", "gpuExclusive": true,
  "gpuHint": "对口型会独占本机显存（~7.9/8GiB），同一时刻不要同时排其它 GPU 任务；实测约 2.5 分钟/秒视频（4–5s 对话镜约 10–13 分钟）" }
```

## 六、使用

1. 该镜**已有 motion/关键帧** + **已生成配音**
2. 任务卡片点「**对口型**」→ 生成对口型片段
3. 渲染成片时**自动优先使用对口型产物**（无对口型则回退普通 motion/关键帧）

## 七、成本与效果提示

- **只对有台词的镜头跑**，且优先 `特写/近景 + 正脸`；远景/背影跑了也看不出效果
- 时长对齐：口型工具要求音视频基本等长 → 对话镜建议把「时长模式」设成 **`audio_first`**（镜长=配音长），
  或用默认 `shot_fixed` + 少量溢出（口型末尾会有 1–2 帧不同步，通常可接受）
- 镜头**运动越剧烈**口型越难对；对话镜建议用轻微运镜（`camera_move` 选缓推/固定）

## 八、出问题先查这四处

```powershell
# 1) 队列是否被卡死（running=1 但 GPU 0% → 之前有任务把 worker 线程打死了）
curl.exe -s http://127.0.0.1:8188/queue
# 2) 真正的报错（GBK/Unicode、OOM、缺模型都在这里）
Get-Content D:\ComfyUI\comfy.err.log -Tail 60
# 3) 显存
nvidia-smi --query-gpu=utilization.gpu,memory.used,power.draw --format=csv
# 4) 队列卡死时的急救：重启 ComfyUI（会清空队列）
powershell -File D:\ComfyUI\_setup\stop_comfyui_for_install.ps1
powershell -File D:\ComfyUI\_setup\start_comfyui.ps1
```

## 九、未安装时的行为

任务会**明确失败**并给出原因（不会静默产出无口型结果）：

```
LIPSYNC_ERROR  未配置对口型工作流：请按 docs/lipsync-setup.md 安装（ComfyUI-LatentSyncWrapper 等）
               并把导出的 API 格式工作流路径填到 WEAVEORA_LIPSYNC_WORKFLOW
```
