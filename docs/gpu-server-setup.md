# Weaveora GPU 服务器搭建手册（本机部署实录）

> 日期：2026-09-08 ｜ 目标机：Windows + **NVIDIA RTX 3070 Ti 8GB**（RAM 31.7GB）
> 用途：部署 Weaveora 的本地 ComfyUI 引擎（SDXL 出图 + Wan2.2 i2v 视频），供 weaveora-worker `WEAVEORA_WORKER_MODE=comfy` 使用。
> 对应代码引用：`worker/comfy_client.py`（模型默认文件名）、`deploy/gpu_provision.sh`、`deploy/gpu_comfy_standby.sh`。

---

## 0. 关键结论速览

| 项 | 结论 |
|---|---|
| 模型总量 | **27.68 GiB**（6 个权重文件，ModelScope 国内 CDN） |
| 出图模型 | SDXL base 1.0（ComfyUI checkpoint） |
| 视频模型 | Wan2.2 TI2V-5B FP16（8GB 卡**加载时转 fp8** 运行）+ UMT5-XXL **fp8** + Wan2.2 VAE |
| 一致性 | IP-Adapter SDXL ViT-H + CLIP ViT-H-14 |
| 网络 | github.com / huggingface.co / hf-mirror.com **直连不通**；ModelScope.cn、ghfast.top、python.org、download.pytorch.org、pypi.org 可达 |
| 一键重装 | `deploy/windows/install_gpu_stack.ps1`（幂等、可后台静默、断点续传） |

> ⚠️ 项目文档 v2.0 已把「本地视频引擎」表述升级为 Wan 2.6；但 **worker 代码仍按 Wan2.2-5B wrapper 工作流引用**（`wan2.2_ti2v_5B_fp16.safetensors` 等默认名），本手册以代码实际引用为准。

---

## 1. 网络通道探测结论（重要前提）

| 目标 | 状态 |
|---|---|
| `github.com` / `huggingface.co` / `hf-mirror.com` | ❌ TCP 拒连 / 超时（直连不可用） |
| `www.modelscope.cn` | ✅ 2.6s，CDN 命中 `cdn-lfs-cn-1.modelscope.cn`，**支持 Range（206）** |
| `ghfast.top` / `gh-proxy.com`（github 加速） | ✅（zip/文件下载走 ghfast，API 走 gh-proxy） |
| `download.pytorch.org/whl/cu126` | ✅（torch 轮子） |
| `pypi.org` / `pypi.tuna.tsinghua.edu.cn` | ✅（清华源偶发 DNS 抖动，失败换官方源） |
| `www.python.org` | ✅ |

**镜像选择策略**：大模型权重全走 **ModelScope**（国内且支持分片断点）；github 小文件（源码/节点 zip）走 **ghfast.top 前缀** `https://ghfast.top/https://github.com/...`；pip 用官方 pypi（清华备选）。

---

## 2. 模型清单（最终版，已含 fp8 调整）

目录结构 = ComfyUI models 布局，根：`D:\model`

| # | 文件（保存名） | 版本 / 说明 | ComfyUI 目录 | 大小 | 字节数 | ModelScope 下载地址（resolve/master） |
|---|---|---|---|---|---|---|
| 1 | `sd_xl_base_1.0.safetensors` | SDXL **base 1.0**（出图主模型） | `checkpoints/` | 6.46 GiB | 6,938,078,334 | `https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors` |
| 2 | `wan2.2_ti2v_5B_fp16.safetensors` | **Wan2.2 TI2V-5B FP16**（i2v 主模型；8GB 卡 loader 转 fp8 运行） | `diffusion_models/` | 9.31 GiB | 9,999,658,848 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors` |
| 3 | `umt5_xxl_fp8_e4m3fn_scaled.safetensors` | **UMT5-XXL FP8**（Wan 文本编码器，fp8 省显存） | `text_encoders/` | 6.27 GiB | 6,735,906,897 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` |
| 4 | `wan2.2_vae.safetensors` | **Wan2.2 VAE**（FP32 1.4GB） | `vae/` | 1.31 GiB | 1,409,400,960 | `https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/vae/wan2.2_vae.safetensors` |
| 5 | `ip-adapter_sdxl_vit-h.safetensors` | **IP-Adapter SDXL ViT-H**（h94，一致性锚定） | `ipadapter/` | 0.65 GiB | 698,391,064 | `https://www.modelscope.cn/models/AI-ModelScope/IP-Adapter/resolve/master/sdxl_models/ip-adapter_sdxl_vit-h.safetensors` |
| 6 | `CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors` | **CLIP ViT-H-14**（LAION2B，IP-Adapter 图像编码） | `clip_vision/` | 3.67 GiB | 3,944,552,236 | `https://www.modelscope.cn/models/AI-ModelScope/CLIP-ViT-H-14-laion2B-s32B-b79K/resolve/master/model.safetensors` |

**合计 27.68 GiB（29.72 GB）**。备注（2026-09-08 终版）：
- 运行时**主用 fp8_e4m3fn_scaled（#3）**（Comfy 原生 CLIPLoader type=wan 支持）；wrapper 回退路线需 fp16，**umt5_xxl_fp16.safetensors（11.37 GB）已重新下载作兜底保留**（磁盘充足不删）。
- ModelScope 同仓库 fp16 地址：`split_files/text_encoders/umt5_xxl_fp16.safetensors`。
- ModelScope 上 **没有 Wan2.2-5B 的 fp8 权重**（官方只发布了 14B 系 fp8_scaled）；5B 唯一官方权重就是 fp16 10GB。
- 官方仓库文件全览：`GET https://www.modelscope.cn/api/v1/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/repo/files?Revision=master&Recursive=true&PageSize=200`（52 个文件，含 14B t2v/i2v/fun 系列等）。

### fp8 运行方案（8GB 显存关键）

`WanVideoModelLoader` 原生支持 `quantization`（实测选项：`disabled / fp8_e4m3fn / fp8_e4m3fn_fast / fp8_e4m3fn_scaled / fp8_e4m3fn_scaled_fast / fp8_e5m2…`）。
→ **fp16 文件无需替换**，提交 workflow 时把 `quantization` 设为 `fp8_e4m3fn_scaled`，加载时实时转 fp8（主模型显存 ~5GB）。weaveora 的 worker 端在 `comfy_client.py::_motion_graph` 中该参数目前硬编码 `"disabled"`，8GB 卡联调时改传该值（Model Preset/params 透传）。

---

## 3. 模型下载工具（`deploy/windows/gpu_model_downloader.js`）

Node 原生实现（无第三方依赖），特性：
- **每文件 10 路 Range 并发分片**（等价格 aria2 `-x10`）
- **断点续传**：`<file>.meta.json` 记录每段进度；中断后重跑自动从断点继续
- **ModelScope 签名 URL 自动刷新**（403/401/416 时重新 resolve）
- 稀疏预分配目标文件（NTFS 瞬时），完成后写 `<file>.done` 标记（重跑秒跳）
- 复用方式：
  - 数组模式（内置 6 文件清单，改 `FILES`）：`node gpu_model_downloader.js`
  - **单文件模式**：`node gpu_model_downloader.js <ModelScope-resolve-URL> <输出绝对路径> 10`
- 实测速度：单文件 10 路 **~30-44 MiB/s**，27.7 GiB 全量约 **12-15 分钟**
- 命令行静默跑法：
  ```powershell
  Start-Process -FilePath 'C:\Program Files\nodejs\node.exe' -ArgumentList '...\gpu_model_downloader.js' `
    -WindowStyle Hidden -RedirectStandardOutput download.log -RedirectStandardError download.err.log
  ```
- 工作副本历史：`D:\model\_dl\`（含日志 `download.log`）

---

## 4. ComfyUI 环境部署步骤（`D:\ComfyUI`，源码版）

> 便携版官方 asset 在国内渠道找不到稳定镜像（Comfy-Org release API 404），故走「源码 + venv」路线。全部命令在 git-bash 验证通过。

前置：node（≥20）、7-Zip（`C:\Program Files\7-Zip\7z.exe`）、Python 3.13 launcher（py）、curl.exe。

```bash
mkdir -p /d/ComfyUI/_setup && cd /d/ComfyUI/_setup

# 4.1 ComfyUI 源码（master；仓库已迁移 Comfy-Org/ComfyUI）
curl -sL -C - -o comfyui_master.zip "https://ghfast.top/https://github.com/Comfy-Org/ComfyUI/archive/refs/heads/master.zip"   # 12.7 MB
"/c/Program Files/7-Zip/7z.exe" x comfyui_master.zip -o"D:\ComfyUI\_setup\cx" -y
mv cx/ComfyUI-master/* /d/ComfyUI/ ; rm -rf cx          # 版本实测 0.34.0

# 4.2 venv（python 3.13）
"/c/Users/Administrator/AppData/Local/Programs/Python/Launcher/py.exe" -3.13 -m venv /d/ComfyUI/venv

# 4.3 torch cu126（官方源；2.7.1 支持 cp313 win；3070 Ti sm86 OK）
/d/ComfyUI/venv/Scripts/python.exe -m pip install \
  --index-url https://download.pytorch.org/whl/cu126 \
  torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1

# 4.4 ComfyUI 依赖（官方 pypi；清华 tuna 有偶发 DNS 抖动，失败就换官方源）
/d/ComfyUI/venv/Scripts/python.exe -m pip install -r /d/ComfyUI/requirements.txt --timeout 60 --retries 8

# 4.5 自定义节点（zip 经 ghfast）
curl -sL -o wanwrapper.zip "https://ghfast.top/https://github.com/kijai/ComfyUI-WanVideoWrapper/archive/refs/heads/main.zip"      # 19 MB
"/c/Program Files/7-Zip/7z.exe" x wanwrapper.zip -o"D:\ComfyUI\_setup\ww" -y
mv ww/ComfyUI-WanVideoWrapper-main /d/ComfyUI/custom_nodes/ComfyUI-WanVideoWrapper
/d/ComfyUI/venv/Scripts/python.exe -m pip install -r /d/ComfyUI/custom_nodes/ComfyUI-WanVideoWrapper/requirements.txt --timeout 60 --retries 8

curl -sL -o ipadapter.zip "https://ghfast.top/https://github.com/cubiq/ComfyUI_IPAdapter_plus/archive/refs/heads/main.zip"        # 0.3 MB（无 requirements）
"/c/Program Files/7-Zip/7z.exe" x ipadapter.zip -o"D:\ComfyUI\_setup\ipa" -y
mv ipa/ComfyUI_IPAdapter_plus-main /d/ComfyUI/custom_nodes/ComfyUI_IPAdapter_plus
```

### 4.6 模型路径映射（`D:\ComfyUI\extra_model_paths.yaml`）

```yaml
weaveora:
    base_path: D:/model
    checkpoints: checkpoints
    diffusion_models: diffusion_models
    text_encoders: text_encoders
    vae: vae
    ipadapter: ipadapter
    clip_vision: clip_vision
    loras: loras
    upscale_models: upscale_models
    embeddings: embeddings
```

### 4.7 启动服务（后台，:8188）

```powershell
cd /d/ComfyUI
Start-Process -FilePath 'D:\ComfyUI\venv\Scripts\python.exe' -ArgumentList 'main.py','--port','8188' `
  -WorkingDirectory 'D:\ComfyUI' -WindowStyle Hidden `
  -RedirectStandardOutput 'D:\ComfyUI\comfy.log' -RedirectStandardError 'D:\ComfyUI\comfy.err.log'
```

---

## 5. 验证清单

```bash
# 5.1 CUDA
/d/ComfyUI/venv/Scripts/python.exe -c "import torch;print(torch.cuda.is_available(),torch.cuda.get_device_name(0))"
# → True NVIDIA GeForce RTX 3070 Ti

# 5.2 服务健康 + 节点注册
curl -s http://127.0.0.1:8188/system_stats          # comfyui_version 0.34.0, devices:[cuda:0]
curl -s http://127.0.0.1:8188/object_info           # 1097 nodes；须含：
#   WanVideoModelLoader / LoadWanVideoT5TextEncoder / WanVideoVAELoader / WanVideoSampler
#   WanVideoDecode / WanVideoEncode / WanVideoTextEncode / WanVideoEmptyEmbeds  (Kijai wrapper)
#   IPAdapterUnifiedLoader / IPAdapterAdvanced                                    (cubiq)
#   CheckpointLoaderSimple

# 5.3 模型可见性（extra_model_paths 生效）
#   checkpoints: sd_xl_base_1.0.safetensors
#   diffusion_models: wan2.2_ti2v_5B_fp16.safetensors
#   text_encoders: umt5_xxl_fp8_e4m3fn_scaled.safetensors (+fp16)
#   vae: wan2.2_vae.safetensors
```

### 5.4 真实出图 smoke（SDXL txt2img）

用 `D:\ComfyUI\_setup\smoke_txt2img.py`（提交 768×768 → 轮询 → 存 PNG），首次含模型加载实测 **~105s**，输出 `smoke_out.png`（934KB）。

**ComfyUI v0.34 API 要点（踩坑记录）**：
- `POST /prompt` body 必须为 `{"prompt": {节点图}, "client_id": "xxx"}`（图不能裸放顶层）
- `KSampler` 需要显式 `denoise: 1.0`（缺了报 `required_input_missing`）
- 校验错误会以 400 返回，body 里有 `node_errors` 详情

---

## 6. 8GB 显存运行要点（3070 Ti）

| 模型 | 显存策略 |
|---|---|
| SDXL base（6.9GB fp16） | ComfyUI 自动 lowvram offload，768² 12 步实测 ~100s（含首载） |
| Wan2.2 5B（10GB fp16 文件） | `WanVideoModelLoader.quantization = fp8_e4m3fn_scaled` → ~5GB 上卡；配合 UMT5 **fp8** 编码器 + CPU offload |
| UMT5-XXL | 100% offload CPU/内存（31.7GB RAM 充裕），fp8 减半搬运 |
| Wan VAE 1.4GB | 常规 |

预期：8GB 卡 Wan i2v 512×512@16fps 单镜可跑，速度明显慢于 4090；**正式生产推荐 16GB+ 卡**（模型文件不变，去掉/降低量化）。

---

## 7. 一键脚本

**`deploy/windows/install_gpu_stack.ps1`**（与 `gpu_model_downloader.js` 同目录配套）——幂等，重复执行自动跳过已完成步骤；断点续传由下载器保证。

```powershell
# 只装/补模型（已有 .done 则秒过 + 字节校验）
powershell -ExecutionPolicy Bypass -File deploy/windows/install_gpu_stack.ps1 -SkipComfyUI
# 全栈（模型 + ComfyUI + 节点 + 服务）
powershell -ExecutionPolicy Bypass -File deploy/windows/install_gpu_stack.ps1
# 后台静默（立即返回，日志 D:\model\_dl\install.log）
powershell -ExecutionPolicy Bypass -File deploy/windows/install_gpu_stack.ps1 -Background
# 强制重下模型（忽略 .done）
... -Force
```

内置清单 = 上文 6 个模型（含大小字节期望值，下载后自动校验）。可选参数：`-ModelRoot`（默认 `D:\model`）、`-ComfyDir`（默认 `D:\ComfyUI`）。
**编码注意**：PowerShell 5.1 对无 BOM UTF-8 文件按 GBK 解码，脚本内避免中文可防解析错乱（本脚本已纯 ASCII）。

---

## 8. 踩坑与经验（复用时必读）

1. **MSYS 路径转换**：git-bash 调 7z/curl 传 `/d/...` 或 `-o/d/...` 会被转成 `C:/d/...`——用 Windows 风格 `"D:\..."` 引号包裹传参。
2. **下载器 `.done`/`.meta.json`**：`.done` 存在即视为完成；中断残留 `.meta.json` 无碍，重跑续传。
3. **ModelScope 签名 URL**：resolve 302 → `cdn-lfs-cn-1` 带 `auth_key`（约 1 天有效），脚本自动在 403 时重新 resolve。
4. **网络抖动**：清华 PyPI 偶发 DNS 解析失败——加 `--timeout 60 --retries 8` 或直接官方 pypi。
5. **github 加速代理**：文件/zip 下载走 `ghfast.top`；GitHub API 走 `gh-proxy.com`；`mirror.ghproxy.com` 超时不可用。
6. **ComfyUI 官方便携版**（`ComfyUI_windows_portable_nvidia.7z`）release asset 404/找不到稳定国内镜像，故弃用便携路线。
7. **worker 联调**：`WEAVEORA_WORKER_MODE=comfy` + `WEAVEORA_COMFY_URL=http://127.0.0.1:8188`；motion 的 Wan 节点集 = Kijai wrapper（非 ComfyUI 内置 native 节点名）。

---

## 9. 本机关键路径汇总

| 用途 | 路径 |
|---|---|
| 模型根 | `D:\model\`（checkpoints / diffusion_models / text_encoders / vae / ipadapter / clip_vision） |
| 下载器+日志 | `D:\model\_dl\` |
| ComfyUI 源码 | `D:\ComfyUI\`（venv、custom_nodes、extra_model_paths.yaml） |
| ComfyUI 服务日志 | `D:\ComfyUI\comfy.log` / `comfy.err.log` |
| 验收图 | `D:\ComfyUI\_setup\smoke_out.png` |
| 仓库归档 | `docs/gpu-server-setup.md`、`deploy/windows/install_gpu_stack.ps1`、`deploy/windows/gpu_model_downloader.js` |

---

## 10. 本机 GPU 引擎最终运行态（Windows + RTX 3070 Ti 8GB）—— 2026-09-08 定版

> 对前文的两处更新：① 运行时不再用 Kijai wrapper 做 motion（其对 fp8_scaled T5 不支持）；② `umt5_xxl_fp16.safetensors` 已重新下载保留作兜底。

### 10.1 运行架构（三层，全部常驻）

```
[本机 Windows]                                  [175.12.60.225 应用服务器 ecm-9a7b]
ComfyUI :8188  (SDXL / Wan2.2 5B, fp8)           weaveora-api.jar :8080 (systemd, nginx 80/443)
comfy worker (池节点 win-comfy-worker)           业务: 用户下单 → job queued → claim → 资产
      ↑ssh隧道×2 (计划任务 ComfyTunnel, 自愈)           │
      │   -R 127.0.0.1:18188:127.0.0.1:8188  (服务器 loopback 访问本机 Comfy)
      │   -L 127.0.0.1:18080:127.0.0.1:8080   (本机 worker 访问服务器 API 内网口)
      └───────────────────────────────────────┘
```

- **weaveora API 的 /internal 通道经 nginx 不对外暴露**（公网访问返回 403，属设计）→ worker 必须在服务器本机或经 18080 隧道访问 API。
- 业务侧仍正常走公网 HTTPS（nginx）；仅引擎 worker 用内部通道。

### 10.2 motion 引擎方案抉择（8GB 卡关键决策）

Wan2.2-5B（fp16 文件 10GB）在 8GB 显存**必须转 fp8 运行**，且注意两点：

1. **主模型**：fp16 文件 + Comfy 原生 `UNETLoader weight_dtype=fp8_e4m3fn`（加载实时转，~5GB）；wrapper 的 `WanVideoModelLoader quantization=fp8_e4m3fn_scaled` 亦可，但本机选原生 UNETLoader。
2. **T5 编码器**：Kijai wrapper 的 `LoadWanVideoT5TextEncoder` **不支持 fp8_scaled**（报 `Invalid T5 text encoder model, fp8 scaled is not supported`）→ 改用 **Comfy 原生 Wan2.2 节点**：`CLIPLoader type=wan` 原生支持 `umt5_xxl_fp8_e4m3fn_scaled.safetensors`（6.3GB）。

**原生 Wan2.2 i2v 工作流**（`worker/comfy_client.py::_motion_graph` 已实现；参考官方模板 `video_wan2_2_5B_ti2v.json`）：

```
UNETLoader(wan2.2_ti2v_5B_fp16.safetensors, weight_dtype=fp8_e4m3fn)
CLIPLoader(umt5_xxl_fp8_e4m3fn_scaled.safetensors, type=wan)
VAELoader(wan2.2_vae.safetensors)
ModelSamplingSD3(model, shift=8.0)
CLIPTextEncode(positive / negative) ×2  (clip=[clip,0])
LoadImage(关键帧) → Wan22ImageToVideoLatent(vae, start_image, width, height, length=4n+1, batch=1)
KSampler(model=shifted, pos, neg, latent, seed, steps≈20, cfg≈5, sampler_name=uni_pc, scheduler=simple, denoise=1.0)
VAEDecode → SaveImage(逐帧 PNG) → ffmpeg(libx264) 合成 mp4   （worker 现有合成路径，无需 VHS）
```

**踩坑**：① 原生 latent 节点图像参数名是 `start_image`（不是 `image`）；② latent 帧数须 `4n+1`（32 采样帧 → length 33）；③ v0.34 `KSampler` 必须显式 `denoise`；④ 尺寸默认 768²（方形档位，16 的倍数）。wrapper 全套节点（`WanVideoModelLoader` 等）仍随 ComfyUI 安装着，仅不再用于 motion。

### 10.3 常驻组件与脚本（本机 D:\ComfyUI\_setup）

| 组件 | 脚本 | 计划任务 | 说明 |
|---|---|---|---|
| SSH 隧道 ×2 | `tunnel_comfy.ps1` | `ComfyTunnel`(开机) + `ComfyTunnelHeartbeat`(每10min) | 断线 8s 自愈；mutex 单实例；日志 `tunnel.log` |
| comfy worker | `worker_win.ps1` | `ComfyWorker`(开机) + `ComfyWorkerHeartbeat`(每10min) | MODE=comfy, API=`http://127.0.0.1:18080`, Comfy=`http://127.0.0.1:8188`, NAME=`win-comfy-worker`；日志 `worker.log` |
| 部署任务注册 | `setup_tunnel_task.ps1` | — | 幂等重建上述任务 |

- 密钥：`~/.ssh/comfy_tunnel_ed25519`（已授权 175.12.60.225 root 的 authorized_keys）。
- worker 每次重启重新注册池节点（pool）；API 按 queued 先到先 claim（无 capability 过滤）。

### 10.4 验收记录（均实测 RESULT PASS）

| 场景 | 脚本（服务器 /opt/weaveora） | 结果 |
|---|---|---|
| txt2img(SDXL 768²) 引擎直调 | `comfy_client.generate` 探针 | 8.4s，938KB PNG |
| still 业务全链路 | `test_gpu_e2e_local.py` / `test_wait_win_worker.py` | succeeded，1.2-1.4MB |
| motion 业务全链路 | `test_motion_win_worker.py` | 4 still + 4 clip(mp4) 全 succeeded |
| 原生 Wan 本地探针 | `D:\ComfyUI\_setup\probe_native_wan.py` | 33 帧 PASS |

motion 单镜实测 ~3-5 分钟（768²×33 帧 fp8，含模型加载）；SDXL 首载 ~100s、热缓存 ~8s。

### 10.5 运维备忘

```powershell
# 更新引擎代码后（worker/comfy_client.py 等）：
#   1) 同步服务器副本  ssh 'cat > /opt/weaveora/comfy_client.py' < worker/comfy_client.py
#   2) 重启本机常驻 worker（新代码在下次启动生效）
schtasks /End /TN ComfyWorker ; schtasks /Run /TN ComfyWorker
# 查看状态/日志
type D:\ComfyUI\_setup\worker.log    # tunnel.log 同理
Get-ScheduledTask -TaskName 'ComfyTunnel*','ComfyWorker*'
```

```bash
# 服务器侧：清掉滞留 queued（防止历史任务被新 worker 抢先/拖慢验收）
sudo -u postgres psql -d weaveora -c "UPDATE generation_jobs SET cancel_requested=true WHERE state='queued' AND cancel_requested=false;"
# 服务器侧：长验收脚本用 nohup + 轮询；注意 python3 print 缓冲（进程结束才 flush，
#   期间可用 DB/本机 worker.log 观察进度）
cd /opt/weaveora && nohup python3 test_motion_win_worker.py > motion_e2e.log 2>&1 &
```

### 10.6 能力矩阵（当前）

| 能力 | 状态 | 备注 |
|---|---|---|
| txt2img 出图 | ✅ 验收通过 | SDXL base 1.0 |
| Wan i2v 视频 | ✅ 验收通过（4 clip mp4） | 原生节点 + fp8，768²×33 |
| IP-Adapter 一致性 | ⚠️ 节点已装未业务验收 | cubiq 节点 + ipadapter/clip_vision 模型就位 |
| motion 时长/并发优化 | ⏳ 可选 | 如升 16GB 卡可减量化/加帧数 |
