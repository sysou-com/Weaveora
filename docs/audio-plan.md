# 配音 / 配乐方案（2026-09-10 调研）

> 现状：`ConcatService` 渲染时插入的是 `anullsrc` 静音轨；方案里已有 `audio.music_mood / sfx[] / vo` 与逐镜 `narration` 字段，`edit_list.json` 已有空的 audio tracks —— **接入点已就绪，只差音源与混音**。

## 一、配乐：有没有"无需授权"的自动来源？
严格说没有"无需授权"，只有两类：
1. **免署名可商用曲库**（授权已含在素材许可里）：Pixabay Music / CC0（FreePD 等）。
   - ✅ 免费、可商用、多数免署名；❌ **没有稳定的音乐搜索 API**（Pixabay API 主要面向图片/视频，条款要求展示来源），自动"查找"需自建曲库。
2. **API 授权生成/发放**（推荐做自动化）：
   | 方案 | 授权口径 | 自动化 | 备注 |
   | --- | --- | --- | --- |
   | **Mubert API v3** | Mubert 持有版权 + Sync License，**可商用、免署名、可 app/UGC sublicense** | ✅ 官方 API：duration/format(mp3/wav)/bitrate/intensity/mode(track\|loop) | 套餐 $49 / $199 / $499 档；按情绪/时长生成，最贴合 music_mood |
   | **ElevenLabs Music API** | 训练于已授权数据，broad commercial use；self-serve **不含 film/TV/Studio Games**，输出带 C2PA | ✅ 需付费订阅 | 质量高、生态成熟 |
   | Jamendo API | 曲目多为 CC：**CC-BY 需署名**、CC0 免署名；商业授权另走 Jamendo Licensing | ✅ client_id API | 需按曲目许可处理署名，导出包要自动附 attribution |
   | MiniMax / Mureka 音乐 | 宣称可商用但训练数据/权利口径不透明 | ✅ | 合规存疑，谨慎 |
   | Suno / Google Lyria | 权利按创作时条款锁定 / SynthID 水印、未声明 licensed training | 部分 | 商用需逐案确认 |

**建议**：主用 **Mubert API**（自动、免署名、可商用、参数匹配情绪与时长）→ 自建 **Pixabay/CC0 精选曲库**做离线兜底（按 mood 打标签，零边际成本）→ Jamendo 作为可选扩展（自动生成 attribution 文本）。

## 二、配音：云 API vs 自托管
### 云 API（中文场景，2026 实测/官方价）
| 方案 | 中文自然度 | 国内延迟 | 价格 | 备注 |
| --- | --- | --- | --- | --- |
| **火山引擎 TTS（豆包语音合成 2.0）** | 9/10 | 120–400ms | 按量 **1.3 元/千字**；10 万字包 28 元（≈**0.28 元/千字**）；音色复刻 150 元/年/音色（5–10s 样本） | 国内开发者首选；SSML、异步长文本、Python/Java SDK |
| **MiniMax Speech 2.8 HD** | 榜单 #1（Artificial Analysis Arena） | 国内直连 | 按量（官网为准） | 质量最好，情感表现强 |
| **Azure Neural TTS** | 8.5/10 | ~120–150ms（eastasia） | **50 万字符/月免费**；$16/1M（Neural）、$22/1M（Neural HD，2026-03 起） | SSML 最完整；无声音复刻 |
| ElevenLabs | 9/10（英语最佳） | 需代理 | 订阅 | 生态最成熟，国内网络不稳 |
| OpenAI TTS | 7.5/10 | 需代理 | 低 | 中文一般 |

### 开源自托管（跑现有 GPU 机器，零 API 费）
| 模型 | 商用许可 | 结论 |
| --- | --- | --- |
| **CosyVoice 3（阿里）** | **Apache 2.0 ✅ 可商用** | **首选自托管**：中文强、zero-shot 克隆、跨语种 |
| IndexTTS2（B 站） | 代码 Apache 2.0，但**模型为 Bilibili Model License：商用需事先书面授权** | 需授权后可用，质量/时长控制很好 |
| Fish Speech / Fish Audio S2 | **Fish Audio Research License：商用需单独购买授权** | 不可直接商用（除非买授权） |
| GPT-SoVITS / Kokoro / Chatterbox | MIT / Apache | 可用，音质与稳定性略逊 |

**建议双轨**：
- **质量档（默认）**：火山引擎 TTS（中文首选、便宜、可复刻音色）或 MiniMax Speech 2.8 HD（追质量）；
- **成本档/私有化**：自有 GPU 部署 **CosyVoice 3（Apache 2.0）**，用角色参考音做 zero-shot 克隆；避免 Fish Speech / IndexTTS2 的商用限制。

## 三、建议落地（P7，按现有架构接入）
1. **配音**：逐镜 `narration` / `audio.vo` → TTS 合成 `voice` 资产（Asset.kind 可扩展新值）；时长对齐用 SSML prosody 语速或 ffmpeg `atempo` 微调，保证与镜时长一致。
2. **配乐**：`audio.music_mood`（+ 目标时长）→ Mubert 生成 `bgm` 资产；无 API key 时回退自建曲库（按 mood 标签匹配）。
3. **混音（ConcatService）**：把 `anullsrc` 换成 `amix`：voice 为主 + BGM 侧链压缩 ducking（`sidechaincompress`）或音量包络 + `sfx` 叠加；成片导出附 `attribution.txt`（CC-BY 场景）与 **AI 生成内容标识**（《AI 生成合成内容标识办法》2025-09 起）。
4. **合规**：音色克隆需用户授权声明（同真人肖像合规）；AI 音乐/语音在元数据与片尾加标识。
5. **成本参考**：15s 短片旁白约 40–60 字 → 火山约 **0.01–0.02 元/条**；Mubert 按套餐（$49–499/月）。

## 四、待你定的事
- 配乐走 **Mubert API**（需 API Key/套餐）还是先自建 Pixabay/CC0 曲库（0 成本，人工精选）？
- 配音默认走 **火山引擎**（需 AK/SK + 音色 ID）还是 **MiniMax**？是否也要 **CosyVoice 3 自托管**兜底？
- 选定后我按 P7-1..P7-4 实现（先做配音还是先做配乐）。

---

## 已实施（P7，2026-09-10 上线；GPU 唤醒后验证）
**决策：配音 + 配乐全部自建自托管（不用任何第三方云 API / 不抓取第三方曲库）。**

- 任务类型：`voice`（逐镜旁白）/ `bgm`（整片配乐）加入 job kind；固定 `engineRoute=gpu`（云节点不认领，只有 GPU worker 处理）。
- 后端：`JobService.createAudioJobs()`（voice 逐镜取 narration + target_sec + voice/speed；bgm 取 `audio.music_mood` + 时长）；payload 带 revision_no/prompt_md5（沿用 P3 审计）。
- worker：`worker/audio_client.py`（HTTP 调本机服务）；`stub_worker.execute_job` 新增 voice/bgm 分支；comfy 节点注册 `audio:true`。
- 自托管服务（`deploy/audio/`）：
  - `tts_server.py`：CosyVoice 3（Apache-2.0）——内置音色 SFT / 参考音频 zero-shot 克隆，`:8091 /tts`；
  - `music_server.py`：ACE-Step（Apache-2.0，默认）或 Stable Audio Open（Community License）`:8092 /music`；
  - `audio_standby.sh`：一键起 TTS + 音乐 + audio worker；`README.md` 含模型下载/许可/验证清单。
- 渲染混音（`ConcatService`）：静音轨 → **逐镜 voice（按镜起点 adelay，apad 补齐）+ bgm（volume 0.30，apad）**，`sidechaincompress` 说话段自动压低音乐（失败自动退化为固定低音量）；已在 VPS 用合成音频验证：`sidechain exit=0`，成片音轨时长与画面对齐（6.000s/6.000s）。
- 导出（`ExportService`）：`edit_list.json` audio 轨写入 `voice`（逐镜，含 timeline_start_sec）与 `bgm`（整片）；zip 内含音频文件。
- 前端：任务区新增「生成配音(voice)」「生成配乐(bgm)」；资产库支持 `voice/bgm` 展示与 **音频播放**；任务行类型标签含配音/配乐。

**待验证（GPU 唤醒后）**：`audio_standby.sh` → 两个 `/health` → Web 生成配音/配乐 → 渲染成片听混音 → 导出包查 edit_list.json 与音频文件。

---

## 已实施（P7.3 配乐，2026-09-11 落地；Windows 3070 Ti 本机 ComfyUI）

**决策：配乐改走 ComfyUI 原生节点，不再用 `deploy/audio/music_server.py`（该服务保留作可选兜底）。**

### 为什么换
| 维度 | ComfyUI 蓝图 ✅ | WSL + `pip install acestep` |
| --- | --- | --- |
| 前置 | 只需权重 | 需 WSL 分发版 + conda + Linux CUDA |
| 链路复用 | 复用 `comfy_client` 的 workflow/轮询/取产物全套 | 新增 8092 服务 + 守护 |
| 开机自启 | 白送（ComfyUI 计划任务已存在） | 需再写一套常驻 |
| 8GB 显存 | 同进程串行 + ComfyUI 自带 offload | 两个 CUDA 上下文抢显存 |

### 权重（all-in-one，单文件）
- `D:\model\checkpoints\ace_step_1.5_turbo_aio.safetensors`，**9.34 GiB**；
- 来源 ModelScope 镜像 `Comfy-Org/ace_step_1.5_ComfyUI_files`（官方蓝图指向 HF，国内不通）；
- 该镜像另含分体版（`acestep_v1.5_turbo` 4.46G + `qwen_4b_ace15` 7.80G + `qwen_0.6b_ace15` 1.11G + `ace_1.5_vae` 0.31G ≈ 13.68G），蓝图原样需要这套；走 aio 图更简单。
- 下载器：`deploy/windows/gpu_model_downloader.js` 的 10 路 Range 在 ModelScope CDN 上**前 ~60s 几乎不动**（疑似并发预热），随后爬到 15 MiB/s；单路 `curl -C -` 反而稳在 ~50 MiB/s。

### 图谱（`worker/comfy_client.py::_music_graph`）
`CheckpointLoaderSimple(aio)` → `ModelSamplingAuraFlow(shift=3)` → `TextEncodeAceStepAudio1.5` +
`ConditioningZeroOut` → `EmptyAceStep1.5LatentAudio(seconds)` → `KSampler(euler/simple, steps=8, cfg=1, denoise=1)`
→ `VAEDecodeAudio` → `SaveAudioMP3(320k)`。参数全部对齐官方蓝图 `blueprints/Text to Audio (ACE-Step 1.5).json`。

### 踩坑：CUDA 13 内核 vs CUDA 12.7 驱动（关键）
`comfy_kitchen/backends/cuda/_C.abi3.pyd` 链接 `cublasLt64_13`（按 **CUDA 13** 构建），本机驱动 566.36
只到 CUDA 12.7 → 一启动 kernel 就 `CUDA driver version is insufficient for CUDA runtime version`。
而 `flash_attention.is_available()` 只查「扩展是否导入成功」，**没查驱动**，于是返回 True 骗过了
`llama.py::init_kv_cache()`，让 ACE-Step 的 Qwen3 AR 循环走 FixedKV → 崩在
`generate_audio_codes=True`（蓝图默认、也是音质档）。
- 绕过办法：`WEAVEORA_MUSIC_AUDIO_CODES=0`（关掉 LLM 音频码，有损音质，能出曲）。
- **正式修复**：`deploy/windows/patch_comfy_kitchen_cuda13.py` —— 用 `cuDriverGetVersion` 让探针如实回答，
  驱动 < 13.0 就返回 False → 自动退回普通 KV 缓存，**codes 保持开启、音质无损**。以后升级驱动/torch 自动放行。
  幂等可重跑；`pip install -U comfy_kitchen` 会冲掉，需重跑。

### 实测（RTX 3070 Ti 8GB，warm）
| 时长 | 耗时 | 产物 |
| --- | --- | --- |
| 30s | **22.3s** | 1.20 MB mp3 320k / 48kHz 立体声 |
| 120s | **62.6s** | 4.80 MB |

（冷启动另加 ~25-30s 模型加载；ComfyUI 节点缓存会让相同 seed+参数的重复请求秒回。）

### worker 接线
- `stub_worker.py::_bgm_media()`：`WEAVEORA_MUSIC_ENGINE=comfy`（默认）走 ComfyUI；`=http` 退回 `music_server.py`。
- `worker_win.ps1` 已置 `WEAVEORA_MUSIC_ENGINE=comfy` / `WEAVEORA_MUSIC_CKPT_NAME=...` / `WEAVEORA_TTS_URL=http://127.0.0.1:8091`。
- 冒烟脚本：`worker/test_music_comfy.py`（`TEST_DUR` / `TEST_SEED` / `TEST_PROMPT` 可调）。

### 配音（voice）已落地：WSL2 + CosyVoice2-0.5B（2026-09-11）

CosyVoice 依赖 Linux 专属轮子 → 只能走 WSL2。落地细节：

| 项 | 值 |
| --- | --- |
| 发行版 | Ubuntu 26.04.1 LTS；备用 rootfs tarball 在 `D:\wsl\ubuntu-24.04-wsl.rootfs.tar.gz` |
| Python | `/opt/miniconda/envs/cosy/bin/python` = 3.10.21（CosyVoice 要求 3.10） |
| 代码 | `/data/audio/CosyVoice`（ghfast 代理拉 zip；**submodule `third_party/Matcha-TTS` 必须单独拉**，zip 里是空目录） |
| 权重 | `/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B`（12 文件 / 3.8G，ModelScope `iic/CosyVoice2-0.5B`） |
| 服务 | `tts_server.py :8091`；Windows worker 经 WSL2 localhost 转发直连 `http://127.0.0.1:8091` |
| 常驻 | Windows 计划任务 `ComfyTTS` + `ComfyTTSHeartbeat` → `wsl_tts_win.ps1` → `wsl_run_tts.sh`（pidfile 锁 + 崩溃重拉） |

**实测**：9 字文本 → 2.7s 语音 / 耗时 2.6s（热）；Windows worker 侧 `audio_client.tts()` 端到端 3.1s 拿 3.7s wav。

**踩坑（全部已修）**
1. **pip 在大轮子上卡死**：`nvidia-cudnn-cu12` 731MB 下载 0 B/s 且不触发超时；同一 URL 用 `curl -r` 实测 36MB/s。
   → `wsl_predownload_wheels.sh` 用 curl 并行预下 12 个 nvidia/triton 轮子到 `/data/audio/wheels`（1.9G / 29s），pip 加 `--find-links`。
2. **`openai-whisper==20231117` 构建失败**：其 `setup.py` 顶层 `import pkg_resources`，setuptools>=81 已移除；
   `PIP_CONSTRAINT` 对 build isolation **不生效** → 先装 `setuptools<81`，再 `pip install --no-build-isolation`。
3. **不需要 pynini**：新版 CosyVoice 文本正则化用 `wetext`（纯 Python），旧文档的 conda `pynini` 已过时。
4. **`inference_zero_shot` 第 3 参是 wav 文件路径，不是张量**：内部 `frontend_*` → `load_wav` → `torchaudio.load`，
   传张量报 `Invalid file: tensor([...])`。原 `tts_server.py` 传了 4 个位置参数（把张量喂给 `zero_shot_spk_id`），已修正。
5. **`_tensors_to_wav` 实际收到 dict**：CosyVoice 的 tts 生成器 yield `{'tts_speech': tensor}` 而非裸张量，
   原写法在 `np.clip` 报 `'>=' not supported between instances of 'dict' and 'float'`，已兼容两种。
6. **CosyVoice2-0.5B 是 zero-shot 模型（无 spk2info）**：`inference_sft('中文女')` 必然 KeyError。
   → 已通过引入 **CosyVoice-300M-SFT** 解决（见下节），它自带 7 个内置音色。
7. onnxruntime 的 CUDA EP 因 `libcublasLt.so.11` 缺失（本机是 CUDA 12 系 .so.12）退回 CPU —— 功能正常，只是那几个
   ONNX（campplus / speech tokenizer）在 CPU 上跑。

### 配音多音色：双模型路由（P7.5，2026-09-11）

**问题**：CosyVoice2-0.5B 没有内置音色，UI 的 7 个预设会全部回退成同一个参考音色。

**方案**：再部署 **CosyVoice-300M-SFT**（v1 结构，`spk2info.pt` 自带 7 个音色），
`tts_server.py` 按 voice 路由；两模型在 8GB 卡上**同时只保留一个**（切换先卸载 + `empty_cache`）。

| voice 取值 | 路由 | 模型 |
| --- | --- | --- |
| 存在的 wav 路径 | `inference_zero_shot` | CosyVoice2-0.5B（24kHz） |
| 内置音色名（7 个） | `inference_sft` | CosyVoice-300M-SFT（22kHz） |
| 其他 | 回退 `asset/zero_shot_prompt.wav` | CosyVoice2-0.5B |

- **不加载模型就能判路由**：直接 `torch.load(spk2info.pt)`（7.7KB）读音色名，避免为判定白切一次模型。
- 7 个内置音色（实测豆异，7/7 唯一 sha）：**中文女 / 中文男 / 英文女 / 英文男 / 日语男 / 韩语女 / 粤语女**
  - ⚠️ 模型里是「**日语男**」，而旧 UI 预设写的「日语女」→ 已改 `web/src/utils/audio.ts`
- 权重：`/data/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT`（7 文件 / 2.2G，字节级校验通过）
- `/health` 新增 `kind` / `spks` / `sft_spks` 便于运维查看
- **切换代价**：若同时用预设又用克隆路径，每次切模型约 20-40s（同项目 voice 单一值，实际不会频繁切）
- 顺带实现了 **target_sec 时长对齐**（配音贴合镜头）：首次结果偏差 >25% 就按比例修语速重合成一次；
  语速限幅 `[0.5, 2.0]`，所以 4s 的句子拉不到 10s（这是有意限制，避免失真）。

实测（同一句文本）：中文女 1.87s/质心1900Hz、中文男 2.53s/1571Hz、英文男 2.33s/1081Hz、粤语女 2.95s/2216Hz —— 男女声差异明显。

### P8 音频时间轴与角色音色（2026-09-11）

**问题**：只能全片 1 个音色 + 1 段配乐、强度硬编码；一镜只能段旁白。

#### Schema 扩展（`packages/schemas/director.schema.json`，均可选、向后兼容）
```jsonc
// shots[]
"narrations": [ { "at_sec": 0, "text": "…", "kind": "narration|dialogue",
                 "subject": "关羽", "voice": "中文男", "speed": 1.0 } ]
// audio
"voiceBindings": [ { "subject": "关羽", "voice": "中文男", "speed": 1.0 } ]
"music": [ { "id":"chase", "start_sec":16, "end_sec":24, "mood":"紧张悬疑",
            "gain_db": -8, "fade_in_sec":0.5, "fade_out_sec":2, "loop":true, "duck":true } ]
```
- `gain_db`：**0dB=原始**；默认 **-10.5dB ≈ 线性 0.30**（与 P7 硬编码 `volume=0.30` 等价，老方案听感不变）；
  **“一半” ≈ -16.5dB**（线性 0.15）
- 解析口径：`narrations` 优先于 `narration`；`music[]` 优先于 `music_mood`（后者退化为整片一段）

#### 后端
| 位置 | 改动 |
| --- | --- |
| `AudioPlan`（新） | 纯函数解析层：lines / musicCues / voiceFor / speedFor / distinctMoods / generateDurationFor。**混音与导出共用，避免口径漂移** |
| `JobService` | 逐语音段生成 voice 任务（payload 带 `at_sec`/`line_index`/`line_kind`/`subject`/`voice`/`speed`）；bgm 按 **mood 去重**生成，`duration`=该 mood 最长段 |
| `Asset`/`AssetService` | `createOutput` 增 `prompt_snapshot` 参数（= 产生它的 job payload） |
| `AudioAssetLookup`（新） | 资产→cue：读 `prompt_snapshot.at_sec/line_index`；同 `line_index` 只取最新（防重点生成叠音） |
| `ConcatService` | 语音摆到 `镜头起点 + at_sec`；配乐逐段 `atrim→volume(gain_db)→afade→adelay`，`loop` 时 `-stream_loop -1` 填满区间；多段合为 `[bgmraw]` 后统一 ducking |
| `ExportService` | `edit_list.json` 输出完整段表（voice 带 `line_index`/`at_sec`/`timeline_start_sec`；bgm 带 `id`/`mood`/`gain_db`/`fade_*`/`loop`/`duck`） |

> **为何要 `prompt_snapshot`**：多个配音任务**并发完成、完成顺序不确定**，混音无法用 `createdAt` 推断“这是第几段”。

#### 前端（方案 B：时间轴）
- `NarrationTimeline.vue`：镜内时间轴，语音块**横向拖拽改 at_sec**（吸附 0.1s），选中可改文本/类型/说话人/音色/语速
- `MusicTimeline.vue`：全局配乐时间轴，**拖块移动 / 拖右边缘改结束**；每段独立 mood / gain_db / 淡入淡出 / 循环 / duck
- `VoiceBindingsTable.vue`：角色→音色→语速，角色名来自「绑定表 ∪ 参考图主体 ∪ 分镜说话人」
- ⚠️ `npm run build` **只跑 vite、不做类型检查**；改前端必须单独跑 `npx vue-tsc --noEmit`

#### 样例（`packages/fixtures/guan-yu-vs-lvbu.plan.json`，24s / 6 镜）
旁白(中文女) + 关羽(中文男) + 吕布(日语男)，共 11 段；配乐 `open` 0-16s 史诗磅礴 **-16.5dB** →
`chase` 16-24s 紧张悬疑 **-8dB**（追赶转急促，无缝衔接）。
> 现实限制：内置音色只有 **2 个男声**（中文男 / 日语男），三个以上男角色需上参考音频零样本克隆。

#### 验证
- 单测 34 个全绿（`AudioPlanTest` 15 / `AudioPlanGuanYuFixtureTest` 5 / `ConcatMusicFilterTest` 4 / 原有 10）
- `deploy/verify_mix_filter.sh` 真跑 ffmpeg：滤镜图 rc=0；成片 **24.00s**；
  开场 9-15s=**-41.0dB** vs 追赶 17-21s=**-32.5dB**（差 8.5dB，与设计值一致）；语音准确落在 6.0s；
  源曲 6s 循环铺满 16s 区间
- JSON Schema 校验 0 错误；§10.3 运行时校验通过；前端 `vue-tsc` 零错
- ⚠️ **界面手感未经人眼确认**（本机无浏览器），且本机无 PG/API，端到端未跑

### 待办（P8 收尾）
- [ ] 逐段字幕定时（现在同一镜的多段字幕是拼接后整镜显示）
- [ ] 段落级重新生成（现在只能整镜/全片重生成）
- [ ] Web 部署（`web/dist` 已构建，sysou.com 的 web 根目录待确认）
- [ ] 验收：Web → 视频项目 → 拖拽语音/配乐 → 生成配音/配乐 → 渲染成片听混音 → 导出包查 edit_list.json

### P8.7 配音结束点 + 逐段字幕定时（2026-09-11）

**问题**：一镜内每段语音只能设起点，无法限定结束；字幕还是“整镜一句话”（两条台词会同时压在屏幕上）。

#### `narrations[].end_sec`（新，可选）
```jsonc
"narrations": [ { "at_sec": 2.0, "end_sec": 3.6, "text": "谁敢与我决一死战！", "subject": "吕布" } ]
```
- 设了 → 混音时先 `atrim=0:(end-at)` **剪掉超出部分**，再 `adelay` 摆到起点；job 的 `target_sec` 也用这个窗口
- 留空 → 用配音自然长度（旧行为）
- 非法值（`end<=at`）自动忽略；窗口超出镜长会被截断
- UI：时间轴上**拖块右缘**直接设结束点（比字数估算准）；面板里有「结束(s)」输入 +
  「清结束点」（回到自然长）+「按估算设结束」；块宽在设了结束点后变成精确值（无结束点的带 `~` 标记为估算）

#### 逐段字幕定时
原先：整镜拼接成一句，一条 ASS Dialogue 从 0.4s 到镜尾 → 多段时两句话重叠。
现在：每段各一条 Dialogue，`start=at_sec`，`end=end_sec` 或下一段起点或镜尾；
新增 `assTs()`/`assDialogue()`/`assHeader()`（时间戳 `H:MM:SS.cc`，`{}` 与 `\` 转义），
删除已无用的 `MediaClip.narration` 与 `subtitleText()`。

#### 验证
- 单测 **44 个全绿**（新增 `ConcatSubtitleTest` 6 + `AudioPlanTest` 结束点 4）
- `deploy/verify_voice_window.sh` 真跑 ffmpeg：
  * 配音窗口 2.0→3.6s：1.0-1.8s 静音、2.2-3.4s **-24.1dB**、4.0s 后**静音**；
    不设结束点的对照组在 4.0s 仍是 -24.1dB → 证明剪裁真的生效
  * 一镜两段字幕：libass 接受（rc=0），0.8s 帧有字幕（14KB）/ 3.9s 帧无字幕（1.5KB）→ 逐段定时生效

### P9 克隆音色 / 录音配音（2026-09-11）

**目标**：让用户自己能提供声音，解决"内置只有 2 个男声"的限制。两种用途都做：

| | A. 克隆音色（录一次跑全片） | B. 真人配音（一段一条） |
| --- | --- | --- |
| 入口 | 音频区「配音音色」旁：**🎙 克隆音色** | 每条语音面板里的 **克隆配音** |
| 结果 | 写入 `audio.voicePresets`，下拉里出现 `clone:<id>` | 直接落成本行的 `voice` 资产 |

#### 数据
```jsonc
"audio": {
  "voicePresets": [ { "id":"guanyu", "name":"关羽", "assetId":"…",
                      "promptText":"吾乃关云长…", "durationSec":12.4 } ],
  "voiceBindings": [ { "subject":"关羽", "voice":"clone:guanyu" } ]
}
```
`voice` 现在可能是：内置音色名 / GPU 机上的 wav 路径 / **`clone:<id>`**

#### 后端
| 位置 | 改动 |
| --- | --- |
| `AudioProcessService`（新） | ffmpeg 处理链：单声道+24kHz（必做）→（可选）降噪 `afftdn` → 去静音 `silenceremove` → 响度归一 `loudnorm=-16` → 音高 `asetrate+atempo` → 裁长。**先降噪再测静音/响度**，否则测的是噪底 |
| `VoicePresetService`（新） | 存两份资产：`voice_preset_src`（原件，供 A/B 对比）+ `voice_preset`（处理后的参考音） |
| `POST /projects/{id}/voice-presets` | multipart：file + 处理选项 → 返回 `{rawAssetId, presetAssetId, durationSec, warnings}` |
| `POST /voice-presets/{assetId}/transcribe` | 调 GPU 机 whisper 转写，并把文本回写快照 |
| `POST /shots/{no}/lines/{i}/voice-from-sample` | B 用途：**服务端复制**样本成该行 voice 资产（前端不用重传） |
| `JobService` | `voice` 为 `clone:<id>` 时解析出 `refAssetId`/`refPromptText` 写进 payload |
| `worker` | 有 `refAssetId` → 用现成的 `fetch_reference_bytes()` 把参考音拉到临时 wav → 把 `voice` 换成**本地路径** + `prompt_text` |
| `tts_server.py` | `/tts` 增 `prompt_text`；新增 `/transcribe`（whisper，默认 base、默认跑 CPU 以免抢显存） |
| `tunnel_comfy.ps1` | 新增 `-R 127.0.0.1:18091:127.0.0.1:8091`，供 API 调转写；`weaveora.tts-url` 默认指向它 |

#### 前端
- `VoiceCloneDialog.vue`：取声源（浏览器录音 MediaRecorder / 上传）→ 处理选项 → **原声 vs 处理后 A/B 试听** → 自动转写（可手改）→ 「设为角色音色」/「直接用这段作为本行配音」
- 浏览器不支持时给出提示：**Chrome / Edge / Firefox，或 Safari 14.1+**，并引导改用上传
- 录音容器自适应：优先 `audio/webm;codecs=opus`，Safari 回落 `audio/mp4`

#### 实测
- 单测 **57 个全绿**（新增 `AudioProcessServiceTest` 7 + 克隆解析 6）
- `deploy/verify_voice_clone.sh` 真跑 ffmpeg：4.80s 的“差样本”（首尾静音+底噪+低电平）
  → 3.39s / **24kHz 单声道** / 响度 -30dB → **-15.2dB**（接近目标 -16 LUFS）
- whisper 转写链路实测通（服务器经隧道 18091 → GPU 机）：合成一句 → 转写回文本，3s（模型已缓存）
  * 精度提醒：whisper-base 中文仍有同音错字（实测“虎牢关前…”→“胡牢关前…”），
    所以 UI 里转写文本**可手改**；要更准可设 `WEAVEORA_WHISPER_MODEL=small`（首次下载 ~460MB）

### 待办（P7 收尾）
- [ ] Web 端产出已构建（`web/dist`），但**部署到 sysou.com 的目标路径未在 175.12.60.225 的 nginx 配置里**，需确认后再发
- [ ] 验收：Web → 视频项目 → 分镜填旁白 → 生成配音 → 资产库可播放 → 渲染成片听混音 → 导出包查 edit_list.json
- [ ] `deploy/audio/README.md` 里的 `music_server.py` 路线已被 ComfyUI 路线取代；TTS 段落仍写着 CosyVoice 3 + pynini，待重写
