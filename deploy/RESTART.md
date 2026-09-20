# GPU 服务器 —— 开机恢复清单

> **本清单 2026-09-18 按新盒实测重写**（旧版写的是 `/home/dataset-local/weaveora` + 网关 8000 +
> 公网 30250，那台已不再使用）。
>
> 当前盒实测：`ubuntu24` / RTX 4090 **48G 显存** + **47G 内存** / 16 核 / 服务全部跑在 `/opt/weaveora`。
>
> ⚠️ **没验证过的一点**（换机前请先确认）：`/opt/weaveora` 在实例重启后是否持久。实测现象是
> 开机约 4 分钟服务已全部在跑、无需任何人手工介入 ⇒ 看起来是平台按镜像恢复的；
> 但 `node` 缺失（见 §6），说明它**不是** `/home/dataset-local` 那种逐文件持久的 PVC。
> 旧版文档曾写「除数据集挂载路径外一律重置」，与现象不符 —— 以实测为准，但换机后请照 §3 验一遍。

---

## 〇、换机 / 重启后**第一件事：确认公网端口，并同步两处地址**

> **2026-09-18 故障复盘**：GPU 盒换实例后**公网 IP 和端口都变了**（旧 `180.127.11.169:27458` →
> 新 `180.127.11.167:21270`），但生产侧两个地方还写着旧地址，结果所有生成任务都直接
> `urlopen error [Errno 111] Connection refused` 失败。**端口是平台按实例分配的，每次换机都可能变。**

```bash
# 1) 在 GPU 盒上确认网关端口（不同实例不一定是 8800）
ss -ltnp | grep edge_proxy
# 2) 从生产机确认公网可达（把 <公网IP:端口> 换成平台给的值）
curl -s -o /dev/null -w '%{http_code}\n' http://<公网IP:端口>/__edge/health   # 期望 200
```

两处地址**都要改**（缺一处就有一半功能不通）：

| # | 位置 | 内容 |
|---|---|---|
| 1 | 生产机 PG：`user_engine_settings.services` + `gpu_server_url`/`gpu_server_port` | 7 个服务：`tts` / `face` / `talk` / `image` / `music` / `lipsync` / `transcribe`（**随任务下发给 worker，这才是真正的生效处**） |
| 2 | 生产机 `/etc/weaveora/weaveora-gpu-worker.env` | `WEAVEORA_COMFY_URL` / `TTS_URL` / `MUSIC_URL` / `FACE_URL` |

> **位置 1 已有一键入口（2026-09-21 起）**：网页「生成引擎配置 → GPU 服务器」卡片里的
> **「一键同步 IP / 端口」**按钮（`POST /api/v1/me/engine-settings/sync-address`）。填入新 IP + 端口即可一次替换
> `gpu_server_url` 与 `services` 里**所有 host 命中旧地址**的 URL（路径保留），并回显**替换清单**与
> **改漏清单**（仍是 IP 字面量、既不是新主机也不是回环的 URL）。
> 起因：只改「端口」字段时 `services` 里的显式 URL 不会跟随 → 出图/参考图上传打到死地址，
> 报 `urlopen error [Errno 111] Connection refused`；且 worker 日志打的是另一个变量（COMFY），看着「地址已经对了」。
> **位置 2 仍然要手改**（它是 worker 的回退值，网页管不到）。

```bash
# 位置 1（先备份再改；把 OLD/NEW 换成实际值）
set -a; . /etc/weaveora/weaveora-api.env; set +a; export PGPASSWORD="$WEAVEORA_DB_PASSWORD"
psql -h 127.0.0.1 -U "$WEAVEORA_DB_USER" -d weaveora -c "
  update user_engine_settings
     set services = replace(services::text,'<旧IP:端口>','<新IP:端口>')::jsonb,
         gpu_server_url = replace(gpu_server_url,'<旧IP>','<新IP>'),
         gpu_server_port = <新端口>
   where services::text like '%<旧IP:端口>%';"

# 位置 2
cp -a /etc/weaveora/weaveora-gpu-worker.env /root/weaveora-backups/weaveora-gpu-worker.env.bak.$(date +%Y%m%d-%H%M%S)
sed -i 's|<旧IP:端口>|<新IP:端口>|g' /etc/weaveora/weaveora-gpu-worker.env
systemctl restart weaveora-gpu-worker
```

改完**必须看一眼 worker 日志里的实际下发地址**（这是唯一可信的判据）：

```bash
journalctl -u weaveora-gpu-worker --since -2min --no-pager | grep 服务地址
# 期望看到：[comfy] 服务地址：comfy=http://<新IP:端口> ...  /  [audio] 服务地址：tts=http://<新IP:端口> ...
```

---

## 一、服务恢复

```bash
bash /opt/weaveora/services_up.sh      # 幂等；实测这台机器平台会自启，一般不用手工跑
```

> 旧版的 `/home/dataset-local/weaveora/weaveora_boot.sh`（装系统依赖 + 覆盖 `/home/start.sh`
> 顶掉平台自带 ComfyUI）**在新盒上已不存在**，`/home/dataset-local/weaveora/` 也只剩残迹。
> 若平台又出现了自带的旧 ComfyUI 抢端口，再临时处理。

---

## 二、服务与端口（新盒实测）

| 端口 | 服务 | 说明 |
|---|---|---|
| **8800** | `edge_proxy.py` 边缘网关 | **本实例**平台映射的公网端口 = **21270**，唯一入口 |
| 8001 | ComfyUI 0.34.0（torch 2.7.1+cu126） | 配乐 / 对口型 / 出图宿主 |
| 8091 | `tts_server.py` | 配音 `/tts` + 转写 `/transcribe` |
| 8093 | `face_server.py` | 人脸 |
| 8094 | `talk_server.py` | 对口型 |
| 8092 | `music_server.py` | 配乐 HTTP 兜底（默认不起；配乐引擎是 `comfy`） |

公网入口 `http://<公网IP:端口>` 的路由（取自 `/__edge/health`）：
`/` → ComfyUI 8001 ｜ `/audio` → 8091 ｜ `/face` → 8093 ｜ `/talk`·`/talk_batch` → 8094 ｜ `/bgm` → 8092

---

## 三、验收

```bash
GW=http://127.0.0.1:8800        # 或公网 http://<公网IP:21270>
curl -s -o /dev/null -w '%{http_code}\n' $GW/system_stats    # 200 = ComfyUI 通
curl -s $GW/__edge/health                                     # 路由表 + ok:true
curl -s $GW/audio/health                                      # TTS：7 个内置音色
curl -s $GW/talk/health                                       # 对口型：cuda + 空闲显存/内存
ffmpeg -version | head -1                                     # 出图缩略图要用它
nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader
```

**生产能力验收（推荐，一次就能看出地址有没有改对）**：随便出一次图（关键帧），
盯 worker 日志应出现「服务地址 = 新地址」+「工作流出图：<workflow>(txt2img|edit|img2img) size=… steps=…」。
2026-09-18 实测基线：Qwen-Image 2560×1408 / 20 steps / 4090 ≈ **2 分 40 秒**，显存 ≈ **22G**。

---

## 四、生产机侧（别忘了）

```bash
sudo systemctl enable --now weaveora-gpu-worker
systemctl status weaveora-gpu-worker --no-pager
# 日志里应看到: [stub] node 0a000003 registered (workspace=pool)
```

| 表单项（引擎配置 → 服务地址） | 值 |
|---|---|
| 对口型工作流 | `/opt/weaveora/lipsync_workflow_api.json` |
| 对口型 ComfyUI 地址 | `http://<公网IP:端口>` |
| 配音（TTS）服务地址 | `http://<公网IP:端口>` |
| 转写服务地址 | `http://<公网IP:端口>` |
| 人脸服务地址 | `http://<公网IP:端口>` |
| 配乐引擎 / 权重名 | `comfy` / `ace_step_1.5_turbo_aio.safetensors` |

> 注意与旧版的差别：**这些值必须随实例改**（旧版文档写「不用改」是错的，见 §0 复盘）。
> 另外「模型架构/预设」探测（`image_model_schema`）只服务**云端**模型样例，与本地 GPU 网关无关，换机不必重探。

---

## 五、常用运维

```bash
# 重启全部服务（幂等）
bash /opt/weaveora/services_up.sh

# 看日志（新盒日志在 /opt/weaveora/logs/）
tail -f /opt/weaveora/logs/edge_proxy.log
tail -f /opt/weaveora/logs/audio_tts.log
tail -f /opt/weaveora/logs/talk_server.log
tail -f /opt/weaveora/logs/face.log

# worker 侧（生产机）
journalctl -u weaveora-gpu-worker -f
```

---

## 六、目录速查（新盒）

```
/opt/weaveora/
├── ComfyUI/                 0.34.0 + venv(torch 2.7.1+cu126) + custom_nodes
├── models/                  ACE-Step 权重 / face_id onnx
├── latentsync/              LatentSync 1.6 权重（unet/vae/whisper/buffalo_l）
├── audio/CosyVoice/         仓库代码(含 Matcha-TTS) + 2 个预训练模型
├── face/face_server.py      人脸服务
├── talk/talk_server.py      对口型服务
├── envs/cosy/               CosyVoice 独立 conda 环境
├── logs/                    全部日志
├── edge_proxy.py            单端口网关（本实例起在 8800）
├── services_up.sh           服务编排（幂等）
└── *.sh / *.js              安装与下载脚本
```

> ⚠️ `node` **缺失**（2026-09-18 实测）：`/opt/weaveora` 里那些 `dl_*.sh` / 下载器脚本需要它。
> 要下模型前先补：`apt-get install -y nodejs`（或装到持久目录后软链）。
