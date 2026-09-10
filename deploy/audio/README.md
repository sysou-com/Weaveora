# Weaveora 自托管音频（配音 / 配乐）部署说明

> 目标：**不依赖任何外部云 API**，在自有 GPU 机器上跑配音与配乐，产物落到 Weaveora 资产库并混进成片。

## 1. 组件与端口（GPU 机器）
| 服务 | 端口 | 脚本 | 用途 |
| --- | --- | --- | --- |
| TTS（配音） | 8091 | `tts_server.py` | CosyVoice 3（Apache-2.0，可商用）逐镜旁白、zero-shot 音色克隆 |
| 音乐（配乐） | 8092 | `music_server.py` | ACE-Step（Apache-2.0，默认）或 Stable Audio Open（Community License） |
| Worker（audio） | — | `../../worker/stub_worker.py` | 认领 `voice`/`bgm` 任务 → 调上面两个服务 → 上传资产 |

worker 环境变量（GPU 机器）：
```
export WEAVEORA_WORKER_MODE=comfy
export WEAVEORA_TTS_URL=http://127.0.0.1:8091
export WEAVEORA_MUSIC_URL=http://127.0.0.1:8092
```
任务路由：`voice`/`bgm` 任务的 `engineRoute=gpu`，只有 GPU 节点会认领（云节点 engine=cloud 不认领）。

## 2. 模型准备
### 配音 CosyVoice 3（Apache-2.0，可商用）
```
mkdir -p /data/audio && cd /data/audio
git clone --depth=1 https://github.com/QwenAudio/CosyVoice.git
cd CosyVoice && pip install -r requirements.txt
# 权重（ModelScope 国内可达）
python3 -c "from modelscope import snapshot_download; snapshot_download('iic/CosyVoice2-0.5B', local_dir='pretrained_models/CosyVoice2-0.5B')"
export WEAVEORA_COSYVOICE_DIR=/data/audio/CosyVoice
export WEAVEORA_COSYVOICE_MODEL=pretrained_models/CosyVoice2-0.5B
python3 tts_server.py 8091
```
- 内置音色：`中文女` / `中文男` 等（取决于模型 `spk2info`）；
- **角色音色克隆**：把 5–15s 干净人声 wav 放到 GPU 机器，任务里 `voice` 填该 wav 路径即可 zero-shot 克隆（注意：须有该音色的合法授权）。

### 配乐（默认 ACE-Step，Apache-2.0）
```
cd /data/audio
pip install acestep
# 权重放到 /data/audio/ACE-Step/checkpoints（见 ACE-Step 官方 README，ModelScope 有镜像）
export WEAVEORA_MUSIC_ENGINE=ace-step
export WEAVEORA_MUSIC_CKPT=/data/audio/ACE-Step/checkpoints
python3 music_server.py 8092
```
备选：`WEAVEORA_MUSIC_ENGINE=stable-audio` + `pip install stable-audio-tools`，
权重 `stabilityai/stable-audio-open-1.0`（Community License：**年营收 <$1M 可商用**；超过需向 Stability 申请）。

## 3. 一键待命
```bash
# 在能 ssh GPU 机处（把本目录与 worker 一起同步过去）
bash audio_standby.sh
# 日志：/data/weaveora/audio_tts.log / audio_music.log / gpu_worker.log
```

## 4. 用法（Web）
1. 项目为 **视频**，分镜里填好「旁白 narration」→ 任务区点 **「生成配音(voice)」**（逐镜生成）；无旁白的镜头会跳过；
2. 点 **「生成配乐(bgm)」** → 按 `audio.music_mood` 生成整片 BGM；
3. 「渲染成片」→ 自动混音：配音为主线，BGM 低音量 + **sidechain ducking**（说话时自动压低音乐）；
4. 「导出成片包」→ `edit_list.json` 的 audio 轨含 `voice`/`bgm` 片段，zip 内含音频文件。

## 5. 许可与合规（重要）
| 组件 | 许可 | 商用 |
| --- | --- | --- |
| CosyVoice 3 | Apache-2.0 | ✅ 可商用 |
| ACE-Step | Apache-2.0 | ✅ 可商用 |
| Stable Audio Open 1.0 | Stability Community License | ⚠️ 年营收 <$1M 可商用 |
| IndexTTS2 | 代码 Apache-2.0 / **模型 Bilibili License（商用需书面授权）** | ⚠️ 需授权 |
| Fish Speech / Fish Audio S2 | Fish Audio Research License | ❌ 商用需单独购买授权 |
- 音色克隆需被克隆人授权（同肖像合规）；AI 生成的音/视频按《AI 生成合成内容标识办法》加标识。
- 本方案不抓取第三方音乐库，全部为**本地生成**，来源清晰、可审计。

## 6. 验证清单（GPU 唤醒后）
1. `curl -s localhost:8091/health`、`curl -s localhost:8092/health` → `{"ok":true}`；
2. Web 点「生成配音」→ 任务成功 → 资产库出现 `voice`（可播放）；
3. 点「生成配乐」→ 出现 `bgm`；
4. 「渲染成片」→ 成片有配音，说话段 BGM 自动压低；
5. 「导出成片包」→ `edit_list.json` audio 轨有 voice/bgm 条目且 zip 内有对应音频文件。

## 7. 预热（首次任务不再等加载）
两个服务默认**启动即在后台加载模型**（`/health` 返回 `loaded/preload/warm`）：
```
WEAVEORA_TTS_PRELOAD=1        # 默认开；加载后还会跑一次小样推理预热（warm=true）
WEAVEORA_MUSIC_PRELOAD=1      # 默认开；只加载模型
WEAVEORA_MUSIC_WARM_GEN=0     # =1 时再跑一次 5s 生成预热卷积核（更慢但首次生成更快）
```
查看：`curl -s localhost:8091/health` → `{"ok":true,"loaded":true,"warm":true,...}`。

## 8. 配音试听 / 换音色（Web）
- 分镜卡（有旁白时）出现 **「试听配音」**；视频方案音频区有 **「配音音色 voice」** 输入 + **「试听配音（第一个有旁白的镜头）」**；
- 换音色：改 `voice`（内置名如 `中文女`/`中文男`，或 GPU 机器上的参考音频路径做 zero-shot 克隆）→ 再点试听；
- 试听会创建一个一次性的 `voice` 任务，完成后在页面顶部出现播放条（可关闭）。
