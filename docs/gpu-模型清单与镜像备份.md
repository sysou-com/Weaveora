# GPU 盒 · 全部模型/权重清单与镜像备份

> 用途：**环境一变（平台重开容器）就能"重新上传即恢复"**。
> 扫描脚本：`deploy/gpu_manifest.sh`（在盒子上 `bash gpu_manifest.sh > /tmp/gpu_manifest.txt`）
> 实测快照：`.scratch/gpu_manifest_20260919.txt`（2026-09-19，实例 `ubuntu24`）
> 相关：`GPU服务器能力搭建指南.md`、`CLAUDE.md §四`（重连协议）、`docs/notes/运维铁律-GPU与生产.md`

---

## 0. 为什么必须做镜像（2026-09-19 亲历两次）

| 现象 | 实测 |
|---|---|
| 外网 HTTP 端口变 | `21270` → `12476` → `15276` |
| SSH 端口变 | `12424` → `31012` → `15224` |
| **同一镜像 → 主机键不变** | ED25519 `SHA256:WEeKFd90sfhAztcvbci9bH98kReJlkMNgvlkskxTnJA`（**换了实例也一样，不能当"是不是同一台"的判据**） |
| **`/root` 会被重置** | `authorized_keys` 与 root 密码**双双失效** → 每次换实例都要重装公钥 |
| 内存会变 | 上一实例 **47 GiB**（swap 用掉 6.7G、`pressure/memory full avg10=33%`）；当前实例 **62 GiB**（swap 未用、pressure 0） |
| `/addDisk`（`/dev/vdb1`） | **持久**：4 个大模型在它上面，容器换了还在 |
| 系统盘 `/opt/weaveora` | 随镜像走；**另有 5 个模型真文件在这里** |

---

## 1. 目录与软链布局（★ 打镜像的关键）

```
/opt/weaveora/                                   ≈128 GiB（系统盘 /dev/vda1，196G/已用168G）
├── ComfyUI/                     12G   ComfyUI 0.34.0（git 12d5279, 2026-08-25）+ venv
│   ├── models -> /opt/weaveora/models                    ★软链（ComfyUI 的模型根）
│   ├── custom_nodes/ComfyUI-LatentSyncWrapper/           对口型节点 + 内嵌人脸模型
│   └── cx/models/frame_interpolation/                    ★RIFE 真文件在这（不是 models/）
├── models/                      96G   ↓ 见 §2.1
├── workflows/                          API 格式工作流 JSON（随代码部署，别漏）
├── envs/                       7.2G   venv: cosy（TTS）/ talk（EchoMimicV3）
├── audio/                      6.1G   CosyVoice 代码 + pretrained_models（两套）
├── latentsync/                 5.4G   LatentSync 1.6 权重（对口型）
├── echo_mimic_v3/              150M   EchoMimicV3 代码（models -> models/echo_mimic）
├── _dl/                        651M   下载残留（可清）
├── opt-node/                   168M   自带 node（给 gpu_model_downloader.js）
├── services_up.sh  warmup.sh  edge_proxy.py  stub_worker.py  comfy_client.py  audio_client.py
└── face/ talk/ logs/                 服务代码与日志

/addDisk/weaveora/models/        ~78G（名义）  数据盘 /dev/vdb1（98G）
├── diffusion_models/  4 个大模型（见 §2.1 标「addDisk」的行）
├── text_encoders/     clip_l.safetensors, t5xxl_fp8_e4m3fn.safetensors
├── vae/               ae.safetensors
├── loras/  checkpoints/   （空）
/media/vipuser/addDisk -> /addDisk                ★兼容软链（坑 33）
```

**为什么必须区分真文件 / 软链**：`/opt/weaveora/models/*/` 里两者混住。打镜像时软链是"薄壳"，
**只打系统盘 → 丢 addDisk 那 4 个大模型；解引用（`tar -h`）→ 把 78G 拷回已达 90% 的系统盘，直接爆盘。**

---

## 2. 模型清单（按能力，2026-09-19 实测字节数）

### 2.1 出图 / 出片（ComfyUI `diffusion_models`）

| 文件 | 大小 | 存放 | 真/链 | 能力 |
|---|---|---|---|---|
| `qwen_image_fp8_e4m3fn.safetensors` | 20,430,635,136 | **系统盘** | 真文件 | **文生图主力**（0.34 上只它稳，09-17 定位） |
| `qwen_image_edit_2511_fp8mixed.safetensors` | 20,533,762,817 | **/addDisk** | 软链 | **改图 / 关键帧（edit 通路在用）**，带 `_quantization_metadata` |
| `qwen_image_edit_fp8_e4m3fn.safetensors` | 20,430,635,136 | **/addDisk** | 软链 | 基础版 Edit（备选） |
| `qwen_image_2512_fp8_e4m3fn.safetensors` | 20,430,679,144 | **/addDisk** | 软链 | 2512 档（0.34 出噪声，**慎用**） |
| `flux1-schnell-fp8.safetensors` | 17,236,328,572 | **/addDisk** | 软链 | 备选文生图（T2） |
| `wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors` | 14,294,742,832 | **系统盘** | 真文件 | **图生视频 · 高噪专家** |
| `wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors` | 14,294,742,832 | **系统盘** | 真文件 | **图生视频 · 低噪专家** |

> `models/diffusion_models/` 合计 46G（= 5 个真文件；4 个软链不计入）。

### 2.2 文本编码器 / VAE / LoRA

| 文件 | 大小 | 存放 | 用途 |
|---|---|---|---|
| `text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors` | 9,384,670,680 | **系统盘** | Qwen 出图链路文本编码器 |
| `text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors` | 6,735,906,897 | **系统盘** | Wan2.2 / lipsync 文本编码器 |
| `text_encoders/t5xxl_fp8_e4m3fn.safetensors` | 4,893,934,904 | **/addDisk** | FLUX |
| `text_encoders/clip_l.safetensors` | 246,144,152 | **/addDisk** | FLUX |
| `vae/qwen_image_vae.safetensors` | 253,806,246 | **系统盘** | 出图 / 改图 |
| `vae/wan_2.1_vae.safetensors` | 253,815,318 | **系统盘** | 图生视频 |
| `vae/ae.safetensors` | 335,304,388 | **/addDisk** | FLUX |
| `loras/Qwen-Image-Lightning-8steps-V1.0.safetensors` | 1,698,951,104 | 系统盘 | 8 步蒸馏（快档；cfg1.0 时负词失效） |
| `loras/Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | 630,695,648 | 系统盘 | 高噪 4 步蒸馏（**生产 motion 档用 0**） |
| `loras/Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | 630,695,648 | 系统盘 | 低噪 4 步蒸馏 |

> text_encoders 16G / loras 2.8G / vae 485M。每个模型旁都有 `<file>.done` 哨兵，一并保留。

### 2.3 整脸口型 talk = EchoMimicV3（`models/echo_mimic/`，**22G**）

| 文件 | 大小 |
|---|---|
| `Wan2.1-Fun-V1.1-1.3B-InP/models_t5_umt5-xxl-enc-bf16.pth` | 11,361,920,418 |
| `Wan2.1-Fun-V1.1-1.3B-InP/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth` | 4,772,359,047 |
| `transformer/diffusion_pytorch_model.safetensors` | 3,414,541,616 |
| `Wan2.1-Fun-V1.1-1.3B-InP/diffusion_pytorch_model.safetensors` | 3,128,957,992 |
| `Wan2.1-Fun-V1.1-1.3B-InP/Wan2.1_VAE.pth` | 507,609,880 |
| `wav2vec2-base-960h/model.safetensors` | 377,607,901 |

> 依赖口径（**重建必须对齐**）：`transformers==4.57.6`（5.x 会让 diffusers import 失败）+ `diffusers 0.35.1` + `torch 2.7.1+cu126`；
> umt5 必须用 **bf16 版**（与 ComfyUI 的 fp8_scaled 版**不可互换**）；见 `notes/对口型-经验与坑.md`。

### 2.4 对口型 lipsync = LatentSync 1.6（`/opt/weaveora/latentsync/`，5.4G）

| 文件 | 大小 |
|---|---|
| `latentsync_unet.pt` | 5,072,222,488 |
| `vae/diffusion_pytorch_model.safetensors` | 334,643,276 |
| `whisper/tiny.pt` | 75,572,083 |
| `_dl/buffalo_l.zip` | 288,621,354 |

> 节点侧通过软链取用（**必须重建**，否则节点会去 huggingface 下 5GB，见指南坑 30）：
> `ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/latentsync_unet.pt -> /opt/weaveora/latentsync/latentsync_unet.pt`
> `ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/whisper/tiny.pt -> /opt/weaveora/latentsync/whisper/tiny.pt`

### 2.5 人脸 = insightface `buffalo_l`

| 文件 | 大小 | 位置 |
|---|---|---|
| `w600k_r50.onnx` | 174,383,860 | `ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/auxiliary/models/buffalo_l/` |
| `1k3d68.onnx` | 143,607,619 | 同上 |
| `buffalo_l.zip` | 288,621,354 | 同上 + `latentsync/_dl/` |
| `models/face_aux/` | 17M | 辅助 |
| `models/face_id/` | 4.0K（**空**） | — |

> ⚠️ 同目录下有两个 **`.bak.20260915-095658`，字节数恰好 33,554,432（32 MiB）= 坑库记的"截断下载"残留**，打镜像前删掉。

### 2.6 配乐 bgm = ACE-Step（`models/checkpoints/`，9.4G）

| 文件 | 大小 |
|---|---|
| `ace_step_1.5_turbo_aio.safetensors` | 10,025,478,736 |

### 2.7 配音 TTS = CosyVoice（`/opt/weaveora/audio/`，6.1G，**两套都在**）

| 模型 | 文件（大小） |
|---|---|
| `pretrained_models/CosyVoice-300M-SFT/` | `llm.pt` 1,242,994,835 ｜ `flow.pt` 419,900,943 ｜ `hift.pt` 81,896,716 ｜ `speech_tokenizer_v1.onnx` 522,624,269 ｜ `campplus.onnx` 28,303,423 |
| `pretrained_models/CosyVoice2-0.5B/` | `llm.pt` 2,023,316,821 ｜ `flow.pt` 450,575,567 ｜ `hift.pt` 83,390,254 ｜ `speech_tokenizer_v2.onnx` 496,082,973 ｜ `campplus.onnx` 28,303,423 ｜ `CosyVoice-BlankEN/model.safetensors` 988,097,824 |

> 生产用 `CosyVoice-300M-SFT`（22 kHz，见 `Weaveora.md §7`）。venv 在 `envs/cosy/`。

### 2.8 补帧 RIFE（真文件在 `ComfyUI/cx/models/frame_interpolation/`）

| 文件 | 大小 |
|---|---|
| `film_net_fp16.safetensors` | 68,882,302 |
| `rife_v4.26_heavy.safetensors` | 22,908,216 |
| `rife_v4.26.safetensors` | 22,674,688 |

> `models/frame_interpolation/*` 是**指向上面这三个文件的软链**（`models/frame_interpolation/` 本身只有 16K）—— 漏了软链就补不了帧。

### 2.9 放大 = **无**

`upscale_models` 为空。要出 2K 成片必须先补 ESRGAN 类权重（`4x-UltraSharp.pth` 之类）→ 放 `models/upscale_models/`。

### 2.10 同名校验结论 ✅

多副本检测只命中两个**通用文件名**，都是**不同模型的正常同名**，**不是遮蔽冲突**：
```
diffusion_pytorch_model.safetensors ×3 = echo_mimic/transformer + echo_mimic/Wan2.1-Fun + latentsync/vae
model.safetensors                   ×2 = echo_mimic/wav2vec2-base-960h + CosyVoice-BlankEN
```
⇒ 权重没有"指错文件"的问题（2026-09-19 那次出图异常最终定位到 `steps/cfg`，与权重无关）。

---

## 3. 打镜像要点

1. **两块盘都要进镜像**；**保留软链不要解引用**（`tar`/`rsync` 别加 `-h`/`-L`）。
   软链清单用 `deploy/gpu_manifest.sh` 的 C 段导出，恢复时按清单逐条重建。
2. **`/media/vipuser/addDisk -> /addDisk` 这条兼容软链必须重建**（坑 33）：
   `mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk`
3. **★ 启动 flag 按当前实例内存重算**（`services_up.sh` / ComfyUI 启动行）：
   `--disable-smart-memory --cache-ram 32 --reserve-vram 0.5` 是按 **62 GiB** 定的。
   实测在 **47 GiB** 实例上 `--cache-ram 32` + UNET 19.5G + TE 7.9G 超内存 →
   `Swap 6.7Gi/8Gi`、`pressure/memory full avg10=33%`、单张出图 186s~646s 抖动。
   **做法**：新实例先 `free -h` + `cat /proc/pressure/memory`，`--cache-ram` ≤ 内存 − 模型常驻 − talk常驻。
   当前 62 GiB 实例：`free` 显示 available 46Gi、pressure 0 → 维持 32 即可。
4. **参数口径不许改**：`services.image.steps = 40`、`cfg = 4.0`（官方口径）。
   实测 **20/3.5 → 出图近全黑（平均亮度 0–21）**；40/4.0 → 正常（71–93）。
   这是 2026-09-18 为"2K 快点出"调下来造成的，**降步数的代价是整幅退化，不是"稍软"**。
5. **确认 8001 上是我们的 ComfyUI**（跳过它就会用错模型路径）：
   ```bash
   curl -s http://127.0.0.1:8001/system_stats | python3 -c 'import json,sys;print(json.load(sys.stdin)["system"]["argv"])'
   # 必须是 ['/opt/weaveora/ComfyUI/main.py', ...]
   ```
   ⚠️ 盒子上另有一个 **`/root/ComfyUI`（0.27.0，系统自带）**，别打进去、别让它占 8001。
6. **可清理项**（打镜像前决定，能省 ~1G 且去掉已知坏件）：
   `LatentSyncWrapper/.../buffalo_l/*.bak.20260915-095658`（两个 32MiB 截断残留）、
   `/opt/weaveora/_dl/`（651M）、`latentsync/_dl/buffalo_l.zip`（289M）、
   `services_up.sh.bak*`（7 个）、`edge_proxy.py.bak.*`、`models/face_id`（空）。
7. **容量规划的坑**：数据盘是**超卖的持久卷**，`df` 的数字不可信（铁律⑧：超卖下全量 sha256 能拖 7–10 分钟像卡死）。

---

## 4. 恢复到新实例的核对顺序

```
① 两盘挂载            df -h / /addDisk
② 兼容软链            mkdir -p /media/vipuser && ln -sfn /addDisk /media/vipuser/addDisk
③ 重装公钥            echo '<ssh-ed25519 AAAA…gpu-server-20260913>' >> /root/.ssh/authorized_keys
④ 起服务              bash /opt/weaveora/services_up.sh   （systemctl enable --now weaveora-stack）
⑤ 5 个端口            for p in 8800 8001 8091 8093 8094; do curl -s -o /dev/null -w "$p %{http_code}\n" -m 5 http://127.0.0.1:$p/; done
⑥ 模型自检            bash /opt/weaveora/post_maint_check.sh  ＋  bash deploy/gpu_manifest.sh > /tmp/after.txt
⑦ 平台侧端口同步       「生成引擎配置 → GPU 服务器地址+端口」改新端口 → **保存后回读确认**
                      ssh root@sysou.com "sudo -u postgres psql -d weaveora -At -c \"select gpu_server_port, services from user_engine_settings where gpu_server_url is not null;\""
                      ⚠️ 配置页会把旧快照写回（已发生：把 12476 洗回失效的 21270）
⑧ 与镜像里的 manifest diff（重点比 B 段真文件集合、C 段软链、A 段 pathIndex）
```

---

## 5. 尚未覆盖

- [ ] `envs/cosy`、`envs/talk`、`ComfyUI/venv` 的 `pip freeze`（版本敏感，建议随镜像一起存一份 requirements.txt）
- [ ] `models/face_aux/`（17M）与 `ComfyUI-LatentSyncWrapper/checkpoints/auxiliary/` 的完整内容
- [ ] `workflows/` 下 8 个 JSON 的逐文件 md5（换实例后要确认没被旧副本覆盖）
- [ ] 节点补丁（`REQUIRED_NODE_FEATURES`：`point_lock`/`inline_spec`/`track_lock`/`quality_gate`/`paste_mask`/`fps_pin`/`debug_box`）—— 补丁文件清单与 diff 基线，**旧副本会静默降级**（铁律⑤）
- [ ] 镜像产物的存放位置与命名/回滚规范（平台侧）
