# 对口型（lipsync）安装与配置

> **为什么需要这一步**：图生视频模型（Wan 2.2 i2v、p-video 等）**没有音频输入通道** ——
> 它们只根据图片和文字生成运动，不可能知道台词在说什么，所以**嘴型不可能对上台词**。
> 要口型一致，必须再加一个「**音频驱动嘴型**」的后处理环节。

## 方案总览（按成本）

| 方案 | 成本 | 质量 | 说明 |
|---|---|---|---|
| **分镜规避**（已在导演 Prompt 里做） | 0 | 观众无感 | 有台词的镜头避免正脸大特写，改用过肩/侧脸/反应镜头 |
| **本地 LatentSync**（推荐） | 电费 | 高 | ComfyUI 节点 `ComfyUI-LatentSyncWrapper`，3070Ti 可跑 4–5s 短片段 |
| MuseTalk | 电费 | 中高 | 速度快，适合长片段 |
| Wav2Lip-GAN | 电费 | 中 | 最稳最快，画质一般 |
| 云端（Replicate `sync/lipsync` 等） | 按次/按秒 | 高 | 关键镜头再用，省本地时间 |

平台侧已经实现：`kind=lipsync` 任务（画面 + 配音 → 对口型视频）、渲染时**优先使用对口型产物**、
导演 Prompt 会在必要镜头写 `speaking, mouth moving` 并标记 `shots[].lip_sync`。

---

## 一、安装 ComfyUI-LatentSyncWrapper（本机 GPU）

```powershell
# 1) 克隆节点
cd D:\ComfyUI\custom_nodes
git clone https://github.com/ShmuelRonen/ComfyUI-LatentSyncWrapper.git
cd ComfyUI-LatentSyncWrapper

# 2) 装依赖（用 ComfyUI 的 venv，别用系统 python）
& D:\ComfyUI\venv\Scripts\python.exe -m pip install -r requirements.txt

# 3) 拉模型权重（LatentSync 权重约 5GB；脚本会自动下到 models/ 下）
& D:\ComfyUI\venv\Scripts\python.exe scripts\download_models.py
#    若脚本报错，也可手动放置：
#      checkpoints\latentsync_unet.pt
#      checkpoints\whisper\tiny.pt
#      checkpoints\auxiliary\vgg.pth  等（见节点 README）

# 4) 重启 ComfyUI（节点才会被加载）
```

验证节点已加载：

```powershell
Invoke-RestMethod "http://127.0.0.1:8188/object_info" |
  ConvertTo-Json -Depth 4 | Select-String -Pattern "LatentSync" -SimpleMatch
```

## 二、导出「API 格式」工作流

1. ComfyUI 里搭好一条**最小口型链路**（节点 README 里有示例）：
   `LoadVideo → LatentSyncNode → SaveVideo`（节点名以实际为准）
2. **把承载视频的节点标题改成 `video`，承载音频的节点标题改成 `audio`**
   （右键节点 → Title；或改 env `WEAVEORA_LIPSYNC_VIDEO_TITLE` / `WEAVEORA_LIPSYNC_AUDIO_TITLE`）
3. 菜单 **Workflow → Export (API)** 导出 JSON，例如存到
   `D:\ComfyUI\_setup\lipsync_workflow_api.json`

## 三、配置 worker 环境

`D:\ComfyUI\_setup\worker_win.ps1` 里加上：

```powershell
$env:WEAVEORA_LIPSYNC_WORKFLOW = "D:\ComfyUI\_setup\lipsync_workflow_api.json"
# 可选：节点标题不是 video/audio 时改这两个
# $env:WEAVEORA_LIPSYNC_VIDEO_TITLE = "video"
# $env:WEAVEORA_LIPSYNC_AUDIO_TITLE = "audio"
# 可选：超时（默认 1800s；3070Ti 上 4s 片段约 1–3 分钟）
# $env:WEAVEORA_LIPSYNC_TIMEOUT = "1800"
```

然后重启 worker（任务计划或 `worker_win.ps1`）。

## 四、使用

1. 该镜**已有 motion/关键帧** + **已生成配音**
2. 任务卡片点「**对口型**」→ 生成对口型片段
3. 渲染成片时**自动优先使用对口型产物**（无对口型则回退普通 motion/关键帧）

## 五、成本与效果提示

- **只对有台词的镜头跑**，且优先 `特写/近景 + 正脸`；远景/背影跑了也看不出效果
- 时长对齐：口型工具要求音视频基本等长 → 对话镜建议把「时长模式」设成 **`audio_first`**（镜长=配音长），
  或用默认 `shot_fixed` + 少量溢出（口型末尾会有 1–2 帧不同步，通常可接受）
- 镜头**运动越剧烈**口型越难对；对话镜建议用轻微运镜（`camera_move` 选缓推/固定）

## 六、未安装时的行为

任务会**明确失败**并给出原因（不会静默产出无口型结果）：

```
LIPSYNC_ERROR  未配置对口型工作流：请按 docs/lipsync-setup.md 安装（ComfyUI-LatentSyncWrapper 等）
               并把导出的 API 格式工作流路径填到 WEAVEORA_LIPSYNC_WORKFLOW
```
