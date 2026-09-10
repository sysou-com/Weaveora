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
   现在非路径音色统一回退到仓库自带 `asset/zero_shot_prompt.wav` 做 zero-shot。
   ⚠️ 因此 **UI 里的 7 个音色预设目前音色相同**；要真正多音色需给 `voice` 传参考 wav 绝对路径
   （或用 CosyVoice-300M-SFT 那类带内置 spk 的模型，见"待办"）。
7. onnxruntime 的 CUDA EP 因 `libcublasLt.so.11` 缺失（本机是 CUDA 12 系 .so.12）退回 CPU —— 功能正常，只是那几个
   ONNX（campplus / speech tokenizer）在 CPU 上跑。

### 待办（P7 收尾）
- [ ] UI 音色预设：换成真正的多音色（CosyVoice-300M-SFT 内置 spk，或给每个预设配一个参考 wav）
- [ ] WSL `.wslconfig` 限内存（`[wsl2] memory=10GB`），避免与 ComfyUI 抢 RAM
- [ ] 验收：Web → 视频项目 → 分镜填旁白 → 生成配音 → 资产库可播放 → 渲染成片听混音 → 导出包查 edit_list.json
- [ ] `deploy/audio/README.md` 里的 `music_server.py` 路线已被 ComfyUI 路线取代，择机精简
