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

---

## 十、未安装时的行为

任务会**明确失败**并给出原因（不会静默产出无口型结果）：

```
LIPSYNC_ERROR  未配置对口型工作流：请按 docs/lipsync-setup.md 安装（ComfyUI-LatentSyncWrapper 等）
               并把导出的 API 格式工作流路径填到 WEAVEORA_LIPSYNC_WORKFLOW
```
