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

## 二、本机已排掉的 16 个坑（2026-09-13，都别踩）

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
| 9 | 提交后 **70ms 失败**、本机 GPU 全程 0% | 后端把 lipsync 路由到了**云 worker**（该用户 `video_engine=cloud`）。「自托管 kind 固定走 GPU」的规则只写在通用分支，而 lipsync 在那行之前就 return 了 | 抽 `routeForKind()` 统一 4 处（create / createLipsyncJobs / rerun / retry）：自托管 kind 恒 gpu + 3 例单测 |
| 10 | `expected a bytes-like object, tuple found` | `fetch_reference_bytes()` 返回 `(bytes, ctype)`，lipsync 分支直接当 bytes 用（上传、`_concat_voice` 两处） | 解包；`_concat_voice` 改用 `_ffmpeg_exe()`（原来调裸 `ffmpeg`，本机 PATH 里没有） |
| 11 | `comfy POST /prompt -> 400 no_prompt` | 导出的「API 格式」工作流是**裸节点图** `{"1":{...}}`，而 `/prompt` 要 `{"prompt": 图, "client_id":...}` | `_post_prompt({"prompt": graph, "client_id": client_id}, client_id)` |
| 12 | 任务 `succeeded`、mp4 也落盘，但**资源库看不到**；且该资产无宽高/时长 | ① 前端 `outputAssets` 白名单漏了 `lipsync`（`GAL_TABS` 里有 Tab → 那个 Tab 永远空）② worker 没回填 width/height/duration | ① 白名单补 `lipsync`（抽成 `OUTPUT_KINDS` 常量，注释写明「Tab 有的 kind 必须都在白名单」）；② worker 新增 `_probe_video_meta()`（ffmpeg -i 读回 512/512/5000ms）；已产出的那条资产已 SQL 回填 |
| 13 | 跑到第 **15 分钟**被判 `WORKER_STUCK 执行超时（worker 无心跳完成）`，但 worker 心跳正常、ComfyUI 已 `Doing inference 5/8` | 回收器 `reapStaleRunning()` 只比 `startedAt`、硬编码 `minusMinutes(15)`（注释还写着 30min），完全不看 worker 心跳——对口型一个 5s 镜实测 **25–30 分钟**，必被杀 | 改成「心跳感知 + 硬上限」：`running-timeout-minutes`（默认 60，可配）+ worker `lastSeenAt` 宽限 5min 内不回收 + `max(4×超时, 超时+60min)` 硬上限；回收改按 id 单条 |
| 14 | **任务 succeeded、资产也在，但画面是错的素材**（拿调试用的静帧片出了片，5s 音频配上我那张测试脸） | worker 把上传后的文件名注入到 `LoadVideo` **不认识的 `video` 键**，而真键 `file` 保留了工作流 JSON 里写死的旧文件名 → ComfyUI 静默加载旧文件。验尸方法：`GET /history` 看该 prompt 的图，`LoadVideo.inputs` 里两个键都会在那儿 | ① `_set_node_input` 改为**从节点 schema 推导键名**（`file`/`video`/`audio`，env 写错也回退）；② 删/清同一节点上其它候选文件名键；③ 上传文件名带**每任务唯一后缀**，从根上消掉重名复用；④ 注入后**全图自检**：还有指向其它 `.mp4/.wav` 的输入就拒跑；⑤ 工作流 JSON 的默认文件名改空串；⑥ supervisor 启动回显 lipsync env（本次就是靠它确认 `videoInput=file`） |
| 15 | **成片比配音短，末尾对白被截**（4.92s 配音 → 4.17s 成片） | 工作流把 `CreateVideo.fps` 接到了**源片 fps**（`GetVideoComponents.fps` = 30），而 LatentSync 按 config `video_fps: 25` 生成**定数帧**（帧数 ≈ 配音时长×25）→ 按 30fps 播放就快 25/30，**时长缩短 17%**。帧是定数的，改播放速度补不回内容 | ① 节点新增 `fps` 输入并透传给 pipeline；② 工作流把 `LatentSyncNode.fps` 与 `CreateVideo.fps` **接到同一个源片 fps**（生成帧率 = 播放帧率 = 源片帧率）；③ worker 新增 `_apply_fps_policy()`：默认 auto（把组装端对齐生成端），可用 `WEAVEORA_LIPSYNC_FPS=25` 强制回退到模型原生帧率；④ env 回显带上 fps |
| 16 | 资产库里记的分辨率与实际文件不符（记 1280×704，文件其实 832×464/1280×720）→ 让人误以为「对口型把分辨率改小了」 | `generate_motion` 把 payload 里**请求的** width/height 当结果上报，而 Wan 实际出图桶不同；`/internal/assets` 只是原样读文件，不转码 | `generate_motion` 改用 `_probe_video_meta(mp4)` 上报**真实**宽高/时长（与请求不一致时打日志）。注：对口型产物会原样保留源片分辨率 |

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
# 口型帧率策略：0/未设 = auto（生成帧率 = 播放帧率 = 源片 fps，推荐）；
# 设 25 = 强制用模型原生帧率（若非 25 的同步效果不满意可回退）
$env:WEAVEORA_LIPSYNC_FPS = "0"
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
2. 任务卡片点「**对口型**」→ 选镜弹窗：可切每镜的**底片**（`片段` / `静帧`）、点「指定人脸」→ 「按勾选生成」
3. 渲染成片时**自动优先使用对口型产物**（无对口型则回退普通 motion/关键帧）

### 6.1 底片（驱动嘴型的那份画面）—— A/B/C 三道保护

底片 = 交给 LatentSync 的画面（motion 片段 或 关键帧静帧）。LatentSync 的工作方式是
**把脸的下半部盖住重画一张嘴**：底片里嘴越干净（闭合/微张、一张脸、够大），出片越稳。

| 机制 | 行为 | 怎么用 / 怎么改 |
|---|---|---|
| **A 逐镜可选底片** | 选镜弹窗每行「底片：`片段` / `静帧`」；写方案 `shots[].lipsync_source`（不写=自动） | 对话近景建议 **静帧**（一张干净的脸）；需要保留运镜时才用片段 |
| **B 底片体检**（worker 出片前） | 硬拒（**嘴优先**）：① 嘴张开度 ≥ `WEAVEORA_LIPSYNC_MOUTH_MAX`（默认 **1.05**）→ **拒绝**并提示换底片；② 人脸宽度 < `WEAVEORA_LIPSYNC_FACE_MIN_PX`（默认 64px；无像素口径时退到占比 < `WEAVEORA_LIPSYNC_FACE_MIN_RATIO` 1.5%）→ **拒绝**（脸都糊了）。软提醒：嘴 ≥ `WARN`（默认 **0.90**）/ 脸宽 < `WEAVEORA_LIPSYNC_FACE_WARN_PX`（默认 96px）→ 只提醒不拦 | 阈值是**量出来的**（同项目实测 mouth_open：平静脸 0.44~0.66、略开可接受 0.59~0.78、真·大张 1.23）；指标来自人脸服务 / 本机 insightface（106 点），**拿不到指标就跳过**；嘴部点序号可用 `WEAVEORA_LIPSYNC_MOUTH_IDX` 覆盖（默认 `52-71`）；日志每跑一次都打印实测值 |
| **C 极端表情自动分流** | action/正词/台词含 喊叫/尖叫/失声/惊恐张口/scream/mouth wide… → ① 默认底片**自动改成静帧**（有静帧时）；② 出片时 `lips_expression` 从 1.5 降到 `WEAVEORA_LIPSYNC_EXPRESSION_RISK`（默认 **1.0**，即该节点允许的下限）减少嘴部形变；③ 选镜弹窗标「⚠ 大张口风险」 | 这是**文字口径**（保守，不看画面）：实测第 6 镜文字命中但嘴并不大张（0.59/0.78），所以只是「保守地也走静帧」；真正的硬拦交给 B。这类镜更好的做法是**改成旁白/画外音或侧脸** |

> ⚠️ **静帧底片的两个已知坑（已修，2026-09-14）**：① 原来把静帧 pad 成 **512×512 方形**（16:9 静帧变带黑边方片），
> 导致对口型产物也成方视频、成片画幅被破坏 —— 现在只做等比缩放（长边 ≤1280，`WEAVEORA_LIPSYNC_STILL_MAX`）；
> ② 静帧底片会让该镜**失去运镜**（一张静态脸按配音出片），要保留运镜就得用片段底片。

逃生门（明确要硬跑）：方案的 `shots[].lipsync_force = true`，或 worker 侧 `WEAVEORA_LIPSYNC_FORCE=1`。

> 典型案例（2026-09-14《那宝玉恍恍惚惚》第 5 镜，用户反馈「配口型时画面被破坏」的那一镜）：
> action「…抓住宝玉将他拖下溪去，宝玉**失声惊叫**」、正词 `his mouth open in a **terrified scream**`，
> 底片用的是 motion 片段（嘴全程大张）→ LatentSync 先把嘴合上再按配音重开 → **画面被破坏**。
> 同项目实测 `mouth_open`（这就是阈值的标尺）：平静脸 0.44~0.66（第1镜 0.53 / 第4镜 0.66 / 定妆照 0.44）；
> 第5镜底片 **1.232（静帧）/ 1.238（片段）** → 真·大张口，B 直接拒；第6镜 0.59/0.78 → 正常区间，B 不拦。
>
> **闭环实测（同一天现场做的）**：给第 5 镜重出一张「闭嘴近景」静帧当底片（脸宽 746px、mouth_open 0.409，
> 并在负词里加 `open mouth/parted lips/visible teeth`）→ 底片＝静帧重跑对口型 →
> 产物 1280×720/3.92s，mouth_open **0.443**（正常区间），脸宽 376px —— 对比坏的那版：
> 832×464、mouth_open **1.242**、脸宽 97px。C 仍会自动把底片定为静帧 + `lips_expression` 降到节点下限 1.0
> （工作流写死 1.5；**该节点 min 就是 1.0**，给 0.8 会被 ComfyUI 判 `value_smaller_than_min` → 任务秒失败；
> worker 现在会按节点 schema 夹一次区间，env 写错也不会再把任务打挂）。

### 6.2 底片体检查不出来的情况

- **老数据**（worker 部署前产的 still/clip）没有体检指标 → 只有「人脸有没有」那道门禁；可对该镜重出静帧或直接重跑对口型。
- **人脸服务是旧版**（未返回 `face_ratio`/`mouth_open`）→ 体检自动跳过，不报错。部署清单：GPU 机器上的 `deploy/face/face_server.py` 也要更新。

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

## 九、我们对节点的本地补丁（**重装/升级节点后必须重打**）

`ComfyUI-LatentSyncWrapper` 是第三方节点，下面是本地修过的文件（不在本仓库，无法通过 git 同步）：

| 文件 | 补丁 | 为什么 |
|---|---|---|
| `nodes.py` `pre_download_models()` | s3fd 预下载改为**默认跳过**（`WEAVEORA_ALLOW_S3FD_DOWNLOAD=1` 才下）；`download_model` 加 `timeout=(10,60)` | 首次实例化会去 huggingface.co 拉一个**推理根本不用**的文件，无 timeout 会挂死 |
| `nodes.py` `check_and_install_dependencies()` | 包名 `'ffmpeg-python'` → `'ffmpeg'` | 写包名时 `find_spec` 永远 False，每次实例化都白跑一次 pip |
| `nodes.py` `inference()` | 删除 `torch.cuda.set_per_process_memory_fraction(0.8)`（改为 `WEAVEORA_LATENTSYNC_MEM_FRACTION` 可选） | 8GiB 卡被硬限到 6.5GiB → 必 OOM |
| `nodes.py` `INPUT_TYPES` + `inference()` | **新增 `fps` 输入**（FLOAT，默认 25），写进 `args.video_fps`，临时视频也用它 | 不传时 pipeline 吃默认 25，与源片 fps 不一致时成片时长会错 |
| `scripts/inference.py` | `pipeline(..., video_fps=int(round(args.video_fps or 25)))`；构造后 `pipeline.enable_vae_slicing()`；3 处非 ASCII print 改 ASCII | ① 帧率透传；② 降峰值显存；③ `✓`/`⚠️` 在 GBK stdio 下会抛 UnicodeEncodeError |
| `latentsync/utils/face_detector.py` | aux 路径改为按 `__file__` 解析；**先 import torch 并 `os.add_dll_directory(torch/lib)`** 再 import insightface | ① ComfyUI 的 CWD=`D:\ComfyUI`，相对路径会去下 buffalo_l；② 否则 ORT 找不到 cublasLt64_12/cudnn64_9 而静默跑 CPU |
| `latentsync/utils/audio.py` | `audio_config_path` 改为按 `__file__` 解析 | `configs/audio.yaml` 相对 CWD，import 时就炸 |
| `latentsync/utils/image_processor.py` | `DEFAULT_MASK_PATH` 按 `__file__` 解析 | `latentsync/utils/mask.png` 相对 CWD → `cv2.imread` 返回 None |
| `latentsync/pipelines/lipsync_pipeline.py` | `mask_image_path` 默认值改用 `image_processor.DEFAULT_MASK_PATH` | 同上 |

一键核对（应该都有输出）：

```bash
cd /d/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper
grep -n "WEAVEORA_ALLOW_S3FD_DOWNLOAD" nodes.py
grep -n "WEAVEORA_LATENTSYNC_MEM_FRACTION" nodes.py
grep -n "video_fps" scripts/inference.py
grep -n "add_dll_directory" latentsync/utils/face_detector.py
grep -n "_NODE_ROOT" latentsync/utils/audio.py latentsync/utils/image_processor.py
curl -s localhost:8188/object_info/LatentSyncNode | grep -o '"fps"'
```

### 9.2 轨迹锁人 / 收紧贴回遮罩 / 版本握手（2026-09-14，补丁版本 `2026-09-14.1`）

这一批解决**效果与安全**（节点侧已改用 `deploy/latentsync-node/` 管理，GPU 服务器用 `apply.sh` 覆盖，
不再靠人工记）：

| 文件 | 补丁 | 为什么 |
|---|---|---|
| `latentsync/utils/face_detector.py` | **轨迹锁人**：点选/定妆照只用于**播种**，之后按「上一帧框 + 轨迹自累积人脸特征(EMA)」逐帧关联；**质量闸门**：跟丢 / 脸太窄(<64px) / 侧脸代理>0.55 → `last_driven=False`；点选最大距离 0.35；输出 `last_driven`/`last_reason`/`stats_line()` | 用户实测反馈：「一个视频里画面不停变，选定人脸的帧可能对，动作幅度大就不对了」——静态点选逐帧找最近脸，在走位/交错/出画时会**跳到别人脸上**（实测第4镜帧130 驱动了宝玉而不是警幻）。改成轨迹后：宝玉出画又回画，轨迹仍稳锁警幻（实测 165/165 帧，x≈534–548） |
| `latentsync/utils/image_processor.py` | `build_paste_mask()`：把训练用遮罩**收紧到中央竖带 + 外扩 10px + 羽化 9px** 作为**贴回**遮罩；模型输入仍用原遮罩；`affine_transform` 无脸时不再抛 `Face not detected`；新增 `last_driven`/`last_paste_masks` | 原版 mask.png 的「重绘区」是整个下半脸+两侧脸颊（U 形，宽 80%×高 63%）——**贴回**时把脸颊一起覆盖，对齐稍偏就糊脸、甚至糊到隔壁那张脸（两张脸只隔 180px 时必然互相污染） |
| `latentsync/pipelines/lipsync_pipeline.py` | 逐帧 `driven` 贯通（不驱动 → `restore_video` 直接输出原帧）；贴回改用 `last_paste_masks`；`write_video(..., fps=video_fps)`；`_debug_mark()` 调试画框 | 「宁可这帧嘴不动，也不要把画面搞坏」；中间片帧率口径对齐源片 |
| `nodes.py` | `WEAVEORA_NODE_VERSION` / `WEAVEORA_NODE_FEATURES` + **`GET /weaveora/version`**；节点日志打印收到的锁定规格（内联 JSON 还是文件路径） | worker 与节点分处两台机，曾因 GPU 机是旧副本（不认内联 JSON → 退回「取最大脸」）而悄悄把两段台词配到同一张脸。现在 worker 跑前强校验，缺能力**直接失败**并给修复指引 |
| `scripts/inference.py` | 锁定规格支持**内联 JSON**；解析 `debugBox` | 跨机不能传文件（节点侧那个临时路径根本不存在） |

**改完必须重打**（详见 `deploy/latentsync-node/README.md`）：

```bash
# API 服务器（VPS）上托管补丁包：
bash deploy/latentsync-node/pack.sh
# GPU 服务器上执行：
curl -fsSL https://sysou.com/weaveora-node/latentsync-node-patch.tar.gz -o /tmp/p.tar.gz \
  && tar xzf /tmp/p.tar.gz -C /tmp && bash /tmp/latentsync-node/apply.sh   # 打完记得重启 ComfyUI
bash /tmp/latentsync-node/verify.sh http://127.0.0.1:8001                    # 自检版本+能力
```

一键核对（节点侧）：

```bash
curl -s http://127.0.0.1:8001/weaveora/version     # 应有 version=2026-09-22.1 与 8 项能力
```

### 9.3 调试画框坐标系勘误（2026-09-22，补丁版本 `2026-09-22.1`）

**事故**：2026-09-22 上一场次排「对口型嘴部乱码」时，拿调试绿框的 bbox 当「锁定/回贴区域」，
量出 `x 0.000–0.505 / y 0.000–0.996`（正好是裁剪尺寸），于是推出「贴回半个画面 ⇒ 乱码」。
**这个结论是错的**：`_debug_mark()` 收到的 `box` 来自 `ImageProcessor.affine_transform()`，
恒为 `[0, 0, 裁剪宽, 裁剪高]` —— `AlignRestore.align_warp_face()` 把脸对齐到固定模板，
裁剪图左上角就是 `(0,0)`，所以这个 box **只编码「裁剪图有多大」，与脸在原帧的哪个位置无关**。
直接当原帧坐标画 → 绿框永远贴在画面左上角 ⇒ 「框住谁 / 框在不在嘴上」的目视判断全部无效。

**修法**：`affine_matrix` 是「原帧 → 裁剪」的 2x3（`restore_img()` 正是用它的**逆**把脸贴回原帧），
把裁剪矩形的四角乘上它的逆矩阵即可得到原帧上的四边形（带旋转，故画多边形而非 bbox）。
新增模块级 `_crop_box_to_frame_quad(box, affine_matrix)`，只有它失败或拿不到矩阵时才退回旧画法；
纯调试代码，任何异常都只打一行日志、不影响业务。

| 文件 | 补丁 | 为什么 |
|---|---|---|
| `latentsync/pipelines/lipsync_pipeline.py` | 新增 `_crop_box_to_frame_quad()`；`_debug_mark(..., affine_matrix=None)` 用 `cv2.polylines` 画**原帧坐标**下的四边形；`restore_video()` 把 `affine_matrices[index]` 传进去 | 绿框此前恒锚在左上角（只反映裁剪尺寸），据此判「点选是否跑偏」无效 |
| `nodes.py` | 版本 `2026-09-14.1 → 2026-09-22.1`；能力表加 `debug_box_frame_coords` | worker 只校验必需能力（多一项不破），但版本号便于远端核对线上到底跑的是哪版 |

**离线自检**（不需要 GPU，不加载 diffusers）：

```bash
python deploy/latentsync-node/test_debug_box_quad.py          # GPU 盒上：/opt/weaveora/ComfyUI/venv/bin/python
```

覆盖：纯平移的已知答案、缩放+旋转的往返一致性（`max_err≈3e-14`）、torch `(1,2,3)` half 张量
（线上实际传入的形态）、非法 box / 奇异矩阵返回 `None` 且不抛。

> ⚠️ 复跑验收时的口径：绿框现在框的是**回贴区域**（整张脸的对齐裁剪），不是嘴；
> 判「点选是否跑偏」看框有没有盖住目标那张脸即可。

### 9.4 「嘴部乱码 + 画面卡顿」两个真因（2026-09-22，补丁版本 `2026-09-22.3`）

排查对象：第1镜 13:12 的对口型产物（asset `…c78767cb0001`，832×464 / 5.000s / 160 帧 @32fps），
源片 = 12:08 的 clip（同尺寸，32fps，RIFE ×2）。方法：**逐帧数值对齐 + 节点日志**，不靠肉眼。

**① 帧率时基打架（嘴不同步 + 段间硬跳）**

`latentsync/utils/util.py:read_video()` 的 `change_fps` 默认 `True`，里面是 **`ffmpeg -i in -r 25`**（硬编码 25fps）；
而 pipeline 第 464 行原来是 `read_video(video_path, use_decord=False)` —— 没传 fps ⇒ **解码后帧率永远是 25**；
可同一趟里 `feature2chunks(fps=video_fps)` / `write_video(fps=video_fps)` 用的是**源片 fps（32）**。两套时基打架，实测后果：

| 证据 | 数值 |
|---|---|
| 节点实际吃进的帧数 | 第1段 **39** 帧 / 第2段 **49** 帧，而 worker 按时间轴切的是 **50 / 62** 帧（39/50 = 25/32） |
| 段内画面相对时间轴的漂移 | 输出帧 20≈源片 24（+4）、30≈38（+8）、39≈48（+9）；段外（110/120）偏移 **+0** |
| 段首/段尾硬跳（全局帧差） | 帧46 = **9.71**（同帧源片自身运动 1.19 ⇒ **8.1×**）、帧108 = **11.90**（源片 1.02 ⇒ **11.6×**） |

⇒ 一句话：**段内被拉慢 1.28×（嘴比声音慢），段边界再硬切回原帧** —— 这就是「嘴对不上/像乱码 + 画面卡顿」。

修法（pipeline，`2026-09-22.3`）：`read_video(video_path, change_fps=False, use_decord=False)`，
并打一行 `帧率口径：解码 N 帧（change_fps=False）｜whisper 分块 fps=… → M 块（差 ±K 帧）` ——
**这两个数不一致就是这类时基 bug，以后一眼能看出来**。

**② 质量闸门脸宽下限 64px > 本镜第二角色实际脸宽**

同一镜是**近景双人**。节点同款 insightface 在源片关键帧实测：

| 角色 | 实测脸心（归一化）/ 脸宽 | 点选 hint |
|---|---|---|
| 宝玉 | (0.50–0.52, 0.34–0.36) / **76–84 px** | (0.5099, 0.3792) ✓ 命中 |
| 可卿 | (0.55, 0.39–0.45) / **53–64 px** | (0.5457, 0.439) ✓ 命中 |

⇒ **点选坐标是对的**（这也是第一次用数据证伪「点选跑偏」H1）。但 `MIN_FACE_W = 64` ⇒ 节点日志
`第2段：选脸统计 不驱动:脸太小×42，驱动×4`——可卿 49 帧里只有 4 帧被驱动 ⇒ 她说话时嘴几乎不动、
偶尔几帧猛动一下，观感就是「乱码」。第1段 `36/39 驱动（侧脸×3）`，那 3 帧的驱/不驱交替同样显脏。

修法（face_detector，`2026-09-22.2`，**默认值不变**）：三个门槛改为环境变量，并新增抖动抑制：

```
WEAVEORA_LIPSYNC_MIN_FACE=48      # 脸宽下限 px（默认 64）
WEAVEORA_LIPSYNC_MAX_YAW=0.55     # 侧脸代理上限（默认 0.55）
WEAVEORA_LIPSYNC_HOLD_FRAMES=2    # 连续不达标 <N 帧且上一帧在驱动 → 继续驱动（默认 0=关）
```

节点启动时打印实际生效值：`质量闸门：脸宽>=48px 侧脸<=0.55 抖动抑制=2帧`。
⚠️ **调低是折中**：LatentSync 把对齐图放大到 256×256，55px 的脸放大约 4.6×，贴回必然发虚；
真正的质量解是「对话镜用更近的景别」。A/B 用：GPU 盒 drop-in
`/etc/systemd/system/weaveora-stack.service.d/weaveora-lipsync-gate.conf`（删文件 + daemon-reload + restart 即撤销）。

**复测脚本**（在能读到视频的机器上跑，纯数值、不需要肉眼）：

```bash
python3 /tmp/jump.py   <对口型产物> <源片>   # 全局逐帧运动：段边界不应再出现 8–12× 的孤立尖峰
python3 /tmp/align.py  <对口型产物> <源片>   # 逐帧对齐：段内偏移应回到 +0（不再 +4…+9 漂移）
```

### 9.5 A 方案：对口型前放大底片（2026-09-22，worker 侧开关）

**动机**（用户实测「说话时嘴部都是马赛克」）：motion 阶段出片是 **480p**（`video_params.resolution=480p`，832×464），
本镜说话人只有 **53–84px** 的脸；而 LatentSync **已经是官方最高档 1.6 / 512×512**
（日志 `Using LatentSync 1.6 config (512x512)`，权重 5.07GB）⇒ 模型侧没有余地，
是**喂进去的像素太少**：512 配置把脸对齐放大到 420×560，嘴部几乎没有真实像素支撑。

**做法**：对口型之前，先把底片放大 2× 再送 LatentSync（静帧底片本来就是 1664×928，自动跳过）。

```
LoadVideo → GetVideoComponents → UpscaleModelLoader → ImageUpscaleWithModel
          → ImageScaleBy(0.5,lanczos) → CreateVideo(fps=源片, audio=原音轨) → SaveVideo
```

- 权重：`realesr-general-x4v3.pth`（官方 Real-ESRGAN 发布物，**BSD-3**，4.9MB，x4 后缩回一半）。
  ⚠️ 官方 release 里**没有** `RealESRGAN_x4plus.pth`（会 404），别照旧博客抄 URL。
- 开关（worker env）：`WEAVEORA_LIPSYNC_PRE_UPSCALE=2`（0/1=关）、`WEAVEORA_LIPSYNC_PRE_UPSCALE_MODEL=realesr-general-x4v3.pth`。
- 实测（4090/47G，160 帧 832×464→1664×928）：**50–72 秒**，无 OOM；失败自动退回原底片（不阻断任务）。
- 副产品：交付分辨率对齐定妆照（**1664×928**，`assets.width/height` 也按新值落库）。

**A/B 实测**（第1镜 V80，同一条 prompt/配音，唯一变量=放大开关）：

| 指标 | 旧（480p 底片） | 新（放大 2× 底片） |
|---|---|---|
| 段尾「啪」的帧差（帧108，源片自身=1.02） | 5.08（**5.0×**） | **1.03（1.0×）** ← 段间淡入淡出生效 |
| 全局最大逐帧运动 | 11.90（源片最大 4.76） | **4.44**（低于源片自身最大值） |
| 可卿段驱动帧 | 4/49（脸太小×42） | **54/62** |
| 同观看尺寸下嘴/脸区拉普拉斯锐度 | 5.36 | **8.97（1.67×）** |
| 块效应（8px 对齐/非对齐） | 1.00 | 1.00（无块状伪影） |

复测脚本：`jump.py`（段边界）、`align.py`（段内对齐）、`sharp_ab.py`（同尺寸锐度）。

**顺带修的真 bug**：`GetVideoComponents` 的输出序号是 **(images, audio, fps)**，而
`_interp_video_on_box()`（对口型后插帧）一直把 `["2", 2]`（FLOAT=fps）接到 `CreateVideo.audio`
⇒ ComfyUI 直接 `prompt_outputs_failed_validation`。因为插帧开关默认关、且异常被 try/except 吞成一行
WARN，所以**从来没被验证出来过**。现已改为索引 1。

**变体 1 vs 变体 2 的实测结论（2026-09-22 深夜，用户反馈 + LLM 看图）**

用户反馈：「先放大再对口型」那版**整帧画面明显更好，但嘴部像一块糊斑盖住嘴**。
用项目自己的 LLM（`deepseek-v4-flash`，支持 `image_url`，见 `WEAVEORA_LLM_*`）看三版并排对照图，
逐版结论（左=无放大 / 中=先放大后对口型 / 右=先对口型后统一放大）：

| | 嘴部结构（唇缝/牙齿） | 与周围皮肤的质感一致性 |
|---|---|---|
| 左 无放大 | 彩色噪点覆盖，只剩极淡的唇缝走向，牙齿不可辨 | 有差距（像局部补丁），但比中版轻 |
| 中 先放大后口型 | **糊成一片**，唇缝/牙齿全看不出，只剩斑块 | **明显不一致，像一块独立贴片，边缘不融** |
| 右 先口型后放大 | 隐约可见唇缝轮廓（比中版可辨） | **最接近一致**（整帧走同一套放大，噪点尺度匹配皮肤） |

⇒ **结论：变体 2（先对口型 → 再统一放大）胜出**，配置 = `WEAVEORA_LIPSYNC_PRE_UPSCALE=0` +
`WEAVEORA_LIPSYNC_POST_UPSCALE=2`（已在 VPS worker env 生效）。
机理：变体 1 里整帧是 Real-ESRGAN 的锐化纹理、只有嘴部贴回区是 LatentSync 的软输出 ⇒ 反差被放大；
变体 2 让嘴部与整帧走同一条放大链，贴片感消失。

⚠️ **天花板仍在**：三版的嘴部都没有清晰牙齿 —— 口型始终是在 **480p** 底片上生成的（放大只改善
纹理一致性、不新增嘴部信息）。要再上一档只有两条路：① motion 直接出 720p（§4.4 显存账已标定）；
② 新增「只裁人脸区放大 3–4× 再贴回」的前处理（比整帧放大更聚焦）。

📷 三版对照图/视频（临时公开，可随时删）：`https://sysou.com/weaveora/_ab_liplsync_20260922/`
（`three_face_1.0.png` / `three_face_3.2.png` / `three_face.mp4`；左→右 = 无放大/先放大/后放大）

> 看图办法（助手自身模型无视觉能力时）：把图的**公网 URL** 塞进 `image_url` 调项目自己的 LLM——
> 一次约 2k prompt token；注意 `max_tokens` 要给够（该模型会先输出 reasoning，给小了 content 会是空的）。
> 现成脚本：`deploy/diag/ask_image.py <公网图片URL> "<问题>"`（在 API 机上跑；key 只从
> `/etc/weaveora/weaveora-api.env` 读，**不打印不落盘**）。默认 `ASK_MAX_TOKENS=12000`。

---

## 九·补、2026-09-23 两项改动（P8 / P9）

### P8：段间 4 帧重叠已收掉（"归属窗口"与"推理窗口"分家）

**原来**：`a = round(t_cum*fps)`、`b = a + round(dur*fps) + 4` —— 上一段的尾巴（含那 4 帧余量）
与下一段的头**重叠**（线上实测日志 `宝玉 0-50帧(1.44s)` / `可卿 46-108帧(1.80s)` ⇒ 46–50 重叠），
同几帧被两段各推理一次，写回时（`_splice`）后一段再淡入盖在前一段上。

**现在**（`worker/comfy_client.py::_lipsync_seg_windows`，可单测）：
- **归属窗口** `own_b`：段与段**首尾相接、不重叠**，起点按帧数累加（不每次四舍五入时间，顺带消掉 ±1 帧漂移）；
- **推理窗口** `b = own_b + 4`：那 4 帧余量仍然多切给 LatentSync（否则音频比视频长会触发
  节点 `loop_video` 正放+倒放凑帧），但 **`_splice` 只写 [a, own_b)，余量不写回**。
- 回归：`python worker/test_lipsync_segments.py`（12 项，含"用线上真实数字复现旧公式确实重叠"）。
- 判据日志：`段间重叠：无` + 每段 `归属 a-b 帧（推理窗口 a-b 共 N 帧，产出 M 帧）`。
- 段尾淡入淡出（`WEAVEORA_LIPSYNC_SEAM_BLEND`，默认 4）保留不改：现在它在**相邻两段之间**
  混的是"驱动画面 ↔ 原帧"，仍负责消除段尾"啪"的一下；嫌接缝处驱动被削弱可调小/设 0 做 A/B。

### P9：调试绿框坐标系已验证 + 点选的**真实残余风险**

坐标系修复（`_crop_box_to_frame_quad`）已**验证生效**：用节点自己的代码在真实底片上复现
（`deploy/diag/diag_lipsync_point_lock.py`，**纯 CPU，不占 GPU**），输出标注图并打印判据：

| 帧 | 点选（faceHints） | 命中 | 到各候选脸中心的距离 ÷ 画面对角线 |
|---|---|---|---|
| f0（第1镜底片 1664×928） | 可卿 (0.546, 0.439) | **右侧低处那张脸**（w=102） | 0.0305（另一张 0.0558） |
| f0 | 宝玉 (0.510, 0.379) | **左侧高处那张脸**（w=151） | 0.0257（另一张 0.0484） |
| f25 | 可卿 | 画面里**只剩一张**通过上游过滤的脸（w=157） | 0.0632 |

- 修复后的四边形（仿射逆变换）**确实落在被选中的那张脸上**（f0 可卿：归一化 x 0.494–0.581 /
  y 0.308–0.482，与候选框 `[889,279,991,438]` 基本重合）；修复前的画法恒贴画面左上角。
- LLM 目视复核（`ask_image.py`）："2 张脸，左高右低；蓝框=左侧男子，绿框=右侧女子，黄四边形是同一张脸（右侧女子）；
  灰色细矩形在画面左侧；红点在两张脸中间、离右侧女子最近" ⇒ 与数值一致。
- ⚠️ **H1 的残余风险有了判据**：**一镜中段只剩一张通过过滤的脸时，另一个人的点选会落到这张脸上**
  （距离 0.0632 仍 < `POINT_MAX_DIST=0.35` ⇒ 不会被拒绝）。点选只在**播种/轨迹重置**时用，
  所以表现为"跟丢后重新锁错人"。另外首帧实测可卿的种子脸被判 `不驱动:侧脸`（质量闸门），轨迹起步即丢失。
  → 真要根治得在"按点播种"时加一条"本帧候选里是否存在更靠近该点的**另一段轨迹**"的互斥，或把点选升级为
  "首 N 帧平均点位 + 只在候选数 ≥2 时按点选"；**当前不改**，先按待办排期。

---

## 十、未安装时的行为

任务会**明确失败**并给出原因（不会静默产出无口型结果）：

```
LIPSYNC_ERROR  未配置对口型工作流：请按 docs/lipsync-setup.md 安装（ComfyUI-LatentSyncWrapper 等）
               并把导出的 API 格式工作流路径填到 WEAVEORA_LIPSYNC_WORKFLOW
```
