# 新 GPU 服务器 —— 开机恢复清单

> 平台说明：**关闭开发环境后，实例内除「数据集挂载路径」以外的文件和路径将被重置。**
> 唯一持久路径 = `/home/dataset-local`（K8s PVC）。
> 也就是说：容器重启后，`/home/start.sh`、`/opt/anaconda3/envs/*`、`~/.cache/*`、
> apt 装的 curl/build-essential/ffmpeg 都会丢——但本目录下的东西**全在**。

---

## 一、重启后只做一件事

```bash
bash /home/dataset-local/weaveora/weaveora_boot.sh
```

它会自动（幂等，约 1–3 分钟）：

1. 补装 `curl` + `build-essential` + `ffmpeg`
2. 重建软链 `/opt/anaconda3/envs/cosy` → 持久环境
3. 恢复 whisper 缓存 `~/.cache/whisper/tiny.pt`
4. 覆盖 `/home/start.sh`（平台默认会拉起自带的旧 ComfyUI 0.27.0 占 8000，必须盖掉）
5. 停掉抢 8000 端口的平台自带 ComfyUI
6. 调 `services_up.sh` 拉起全部服务

---

## 二、服务与端口

| 端口 | 服务 | 说明 |
|---|---|---|
| **8000** | `edge_proxy.py` 边缘网关 | 平台映射的公网端口 **30250**，唯一入口 |
| 8001 | ComfyUI 0.34.0 | 配乐 / 对口型 / 出图宿主 |
| 8091 | `tts_server.py` | 配音 `/tts` + 转写 `/transcribe` |
| 8093 | `face_server.py` | 人脸 `/face/probe` `/face/embed` |
| 8092 | `music_server.py` | 配乐 HTTP 兜底（默认不起） |

公网入口：`http://36.103.182.217:30250`
- `/` → ComfyUI
- `/audio/*` → 配音 + 转写
- `/face/*` → 人脸
- `/bgm/*` → 配乐兜底

---

## 三、验收

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8000/system_stats   # 200
curl -s http://127.0.0.1:8091/health                                          # loaded=true, 7 音色
curl -s http://127.0.0.1:8000/__edge/health                                   # 路由表
```

---

## 四、生产机侧（别忘了）

GPU 服务器关机前，生产机的 GPU worker 已被**停用**（避免任务失败）。
GPU 服务器恢复、上面验收通过后，在生产机重新启用：

```bash
sudo systemctl enable --now weaveora-gpu-worker
systemctl status weaveora-gpu-worker --no-pager
# 日志里应看到: [stub] node 0a000003 registered (workspace=pool)
```

**「生成引擎配置 → ③ 服务地址」里的值不用改**（都指向 `36.103.182.217:30250`，随任务下发，保存过就一直有效）。

| 表单项 | 值 |
|---|---|
| 对口型工作流 | `/opt/weaveora/lipsync_workflow_api.json` |
| 对口型 ComfyUI 地址 | `http://36.103.182.217:30250` |
| 配音（TTS）服务地址 | `http://36.103.182.217:30250/audio` |
| 转写服务地址 | `http://36.103.182.217:30250/audio` |
| 人脸服务地址 | `http://36.103.182.217:30250` |
| 配乐引擎 / 权重名 | `comfy` / `ace_step_1.5_turbo_aio.safetensors` |

---

## 五、常用运维

```bash
# 重启全部服务（幂等）
bash /home/dataset-local/weaveora/services_up.sh

# 看日志
tail -f /home/dataset-local/weaveora/logs/comfyui.log
tail -f /home/dataset-local/weaveora/logs/audio_tts.log
tail -f /home/dataset-local/weaveora/logs/edge_proxy.log

# 关键环境变量（TTS 用）
#   MODELSCOPE_CACHE=/home/dataset-local/weaveora/cache/modelscope  （wetext FST，避免重下 52MB）
```

---

## 六、目录速查

```
/home/dataset-local/weaveora/
├── ComfyUI/                 0.34.0 + venv(torch 2.7.1+cu126) + custom_nodes
├── models/                  ACE-Step 权重 / face_id onnx
├── latentsync/              LatentSync 1.6 权重（unet/vae/whisper/buffalo_l）
├── audio/CosyVoice/         仓库代码(含 Matcha-TTS) + 2 个预训练模型
├── face/face_server.py      人脸服务
├── envs/cosy/               CosyVoice 独立 conda 环境（python 3.10.21）
├── cache/                   whisper tiny.pt + modelscope/wetext FST
├── opt/node-v20.18.0-*      下载器用的 Node
├── logs/                    全部日志
├── edge_proxy.py            单端口网关
├── services_up.sh           服务编排（幂等）
├── weaveora_boot.sh         开机恢复（含系统依赖）
├── audio_start.sh           音频服务
└── *.sh / *.js              安装与下载脚本
```
