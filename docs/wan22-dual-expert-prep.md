# Wan2.2 双专家（I2V-A14B）落地前分析 —— 改造清单

> 目的：把「图生视频动态不足」的修法（高噪声专家少蒸馏/不蒸馏）真正落地。
> **v2 修订 2026-09-14 晚** —— 按另一会话的实际进展修正（权重已下、latent 已修、sweep 已启动）。
> 对应提交：`4e93235`（双专家图）、`22065d5`（latent 修通 + GPU#2 脚本）
> 目标机：**GPU #2** `180.127.11.166:10532`（Ubuntu 24.04，**RTX 4090 48G**）
> 对照机：GPU #1 `36.103.182.217`（Ubuntu 20.04，4090 24G）——**已关机**

---

## ⚡ v3 状态（2026-09-14 深夜）—— 代码层已无阻塞

并行会话已参照本文档落地 **P0 / P1 / P2 / §3.1**（commit `7ce9bf7`，采用的正是本文推荐**方案 b**），
并额外修了**前端视频参数面板只在「云 API」时显示**（`e0a2df3`）；
提示词源头也做了动态强化（`7b7d71f`，见 §8）。

| 维度 | 状态 | 落地位置 |
|---|---|---|
| 图代码 / latent / 权重 / LoRA 名 | ✅ | `4e93235` · `22065d5` |
| **P0 档位下发** | ✅ **已修** | `servicesWithDefaults()` 新增 `motion` 段（白名单 + camelCase→snake_case）→ `apply_services` 读 `MOTION_OVERRIDES` → `_motion_params()`（**每镜 payload 显式优先**） |
| **§3.1 sanitize 隐患** | ✅ **已修** | `keepMotionKeys()`：`engine=gpu` 时把白名单键从原值补回 |
| **P1 dual 误判** | ✅ **已修** | 只认 `mode=single`/`dual=false`/本地 `*.safetensors`，否则忽略 `params.model` |
| **P2 显存口径** | ✅ **已修且更激进** | `MOTION_MIN_FREE_GB=30` / `MOTION_MIN_TOTAL_GB=44`，<44GiB 出片前**快速失败**并提示路由到 48G GPU#2 |
| **前端档位面板** | ✅ **已修** | `e0a2df3` |
| **提示词动态（源头）** | ✅ **已落地** | `7b7d71f`（见 §8） |
| sweep 结论 / 实机验收 | ⏳ **待做** | 见 §6 |

> **结论：代码/配置层已无待改项，剩下只有「看 sweep 结论 → 实机全链路验收」。**
> 下文 §1–§7 保留 v2 时期的排查记录（部分已标注 "已修"），可作为**回归参考**。

### 两个层次的协同（提示词源头 ↔ 引擎）

| 层 | 修什么 | 提交 |
|---|---|---|
| **提示词源头** | 要求模型**动多少**：5 类动态要素（主体动作/表情变化/眼神/**次级运动**/多主体互动）至少覆盖 3 类 + clip 负词确定性抑制静止 | `7b7d71f` |
| **引擎** | 引擎**有多能动**：高噪声专家不蒸馏/低强度 → 运动幅度不被压扁 | `4e93235` · `22065d5` |
| **链路** | 让档位/参真能下发到 worker、UI 真能显示与保存 | `7ce9bf7` · `e0a2df3` |

---

## 0. 结论速览（v2）

| 维度 | 现状 | 缺口 |
|---|---|---|
| 双专家**图代码** | ✅ 已实现（`4e93235`） | — |
| **latent 节点** | ✅ 已修（`22065d5` 改原生 `WanImageToVideo`） | — |
| **权重** | ✅ **6 个已全部落盘 + 双校验**（含 umt5「字节对内容坏」的坑） | — |
| **LoRA 文件名** | ✅ **代码默认名即 Kijai 仓库实际文件名**，无歧义 | — |
| **P0 参数下发链路** | 🔴 **仍然断着**（sweep 直连 `_motion_graph`，绕过了它） | **生产要按镜/按项目调档位必须先修** |
| 单/双专家判定 | 🟡 `dual = not params.model`，易误退化 | P1 |
| 显存阈值 | 🟡 按 24G 卡调的（48G 上偏保守） | P2 |
| `sanitizeParams` 过滤 | ✅ 当前不拦（无 schema） | 见 §3.1 风险 |

**一句话**：图和权重都通了、**sweep 能跑**；但「**UI 上调档位生效**」这条**生产链路仍然断着**（§2 P0）。
sweep 验证的是「图对不对 / 显存够不够 / 速度可接受」，**不等于**生产能调参。

---

## 1. 权重清单 —— ✅ 已完成（保留供查/复核）

落地脚本：`deploy/gpu2_wan_a14b_download.sh`（+ `deploy/gpu2_wan_a14b_rest.sh`）
特性：**单实例 flock** + **每文件双校验**（字节数 + sha256/结构）+ 校验失败删残片重下。

| # | 文件（落盘名） | 目录 | 字节数（已校验） | 来源 |
|---|---|---|---|---|
| 1 | `wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors` | `diffusion_models/` | 14,294,742,832 | ModelScope `Comfy-Org/Wan_2.2_ComfyUI_Repackaged` |
| 2 | `wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors` | `diffusion_models/` | 14,294,742,832 | 同上 |
| 3 | `umt5_xxl_fp8_e4m3fn_scaled.safetensors` | `text_encoders/` | 6,735,906,897 | 同上（sha256 `c3355d30…`） |
| 4 | `wan_2.1_vae.safetensors` | `vae/` | 253,815,318 | 同上 |
| 5 | `Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | `loras/` | 630,695,648 | **`Kijai/WanVideo_comfy` → `LoRAs/Wan22_Lightx2v/`** |
| 6 | `Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors` | `loras/` | 630,695,648 | 同上 |

> ⚠️ **VAE 是坑**：I2V-A14B 必须用 **`wan_2.1_vae.safetensors`**；
> `wan2.2_vae.safetensors` 是 **TI2V-5B** 的 —— 旧清单 `docs/gpu-newserver-models.md` 写的就是它，**对本方案不适用**。

### 1.2 LoRA 文件名 —— ✅ 无歧义（原「三选一」作废）

代码默认常量（`worker/comfy_client.py`）**就是实际文件名**，位于
**`Kijai/WanVideo_comfy` 的 `LoRAs/Wan22_Lightx2v/`**：

```
MOTION_LORA_HIGH = "Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
MOTION_LORA_LOW  = "Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
```

> 注：同仓库还有个 `LoRAs/Lightx2v/` 子目录（`lightx2v_I2V_14B_480p_cfg_step_distill_rank*`）是**另一套**，别混。
> 先前列的 Comfy-Org / Wan2.2-Distill-Loras / Seko-V1 三套仍可作**对比实验**备选，但**不需要改代码**。

### 1.3 下载/校验口径修正（实测教训，建议回写全局规范）

1. **ModelScope 限流是按路径/出口 IP 的，不是全局**：
   - GPU #1：`resolve` 端点正常（27–30 MiB/s）
   - GPU #2：`resolve` 端点**被限到 0.38 MB/s 且会中途挂死**；换 **API 端点**后 6.9 MB/s（单连）/ **28–53 MiB/s**（10 路）→ 约 **18 倍**
   - API 端点：`https://modelscope.cn/api/v1/models/{o}/{r}/repo?Revision=master&FilePath={urlencode(path)}`
2. **「字节数对 ≠ 内容对」**：umt5 曾出现**字节完全正确但 sha256 不符**（`871298ad` ≠ `c3355d30`）—— 污染/不完整副本。
   → 大文件**不能只看字节数**；至少 safetensors 头结构校验，关键文件全量 sha256。
3. **校验强度折中**（GPU CPU 共享超卖：cgroup 14 核 / load ~20）：
   - 13 GiB 大件 → `quick`（字节数 + safetensors 头结构，能抓裁断/不完整）
   - 小件（LoRA/VAE）→ `full`（全量 sha256）

---

## 2. 代码层面改造

### ✅ 已就绪（无需改）

| 项 | 说明 |
|---|---|
| 双专家图 | `UNETLoader×2 → LoraLoaderModelOnly×2 → ModelSamplingSD3 → KSamplerAdvanced×2`（高噪声 `0..switch` + `add_noise=enable` + `return_leftover=enable`；低噪声 `switch..end` + disable/disable） |
| 档位 preset | `draft(4步) / balanced(6步·默认) / motion(8步) / hero(高噪声不蒸馏+CFG3.5) / full(24步不蒸馏)`；切分=总步数折半 |
| **原生 latent 节点** | ✅ `22065d5` 改用 **`WanImageToVideo`**（patch_size=2，输出 positive/negative/LATENT 三路）。旧 `Wan22ImageToVideoLatent` 报 `tensor (52) vs (104)` |
| weight_dtype | fp8_scaled 权重强制 `default`（忽略旧 `quantization=fp8_e4m3fn`） |
| 素材存在性校验 | `_need()` 对 object_info 校验，缺文件直接点名 |
| 显存让位 | `stub_worker._yield_vram_for_video()` → TTS `POST /unload` |
| mp4 合成 | ffmpeg 合成（不依赖 VHS） |
| **验证脚本** | `deploy/verify_wan_i2v_motion.py`（`--sweep draft,balanced,hero`、`--preset`/`--lora-high`/`--cfg-high`，采真实显存峰值，输出 `WANMOTION {json}`） |

### 🔴 P0 —— motion 参数到不了 worker（**v2：仍然存在**）

**证据链（四处断裂，均已复核）**：

1. `JobService.videoShotPayload()`：clip 的 params **硬编码**
   ```java
   payload.set("params", mapper().createObjectNode().put("width", dd[0]).put("height", dd[1]));
   ```
2. `JobService.claim()`：只下发 `{job, services}`；`servicesWithDefaults()` 只有
   `tts / music / lipsync / transcribe / face` —— **无 motion/video**
3. worker 的 `GET /internal/users/{uid}/cloud-config` 含 `video`（model/apiKey/params/schemaParams），
   但 `stub_worker` **只在 `MODE == "cloud"` 时才拉**
4. `comfy_client.apply_services()` 只读 `lipsync` / `face`，**不读 `video`**

**后果**：`_motion_graph` 里 `params = payload.get("params")` 只拿到 `{width, height}` →
`preset / steps / switch_step / lora_high / lora_low / cfg_high / cfg_low / model_high / model_low …`
**全部无法从「生成引擎配置」下发**，只能靠 worker 环境变量 `WEAVEORA_MOTION_PRESET`（默认 `balanced`）。

**为什么 sweep 跑得通、生产却不行**：`deploy/verify_wan_i2v_motion.py` 是**直连建图函数**——

```python
params = {"preset": preset}                       # CLI 直接塞参数
prompt = cc._motion_graph("verify-motion", payload, POS, NEG, name, ...)   # 绕过 payload/引擎配置
```

它验证的是**图/显存/性能**，**不覆盖参数下发链路**。所以「sweep 通过」≠「UI 能调档位」。

**三个修法（按推荐度）**：

| 方案 | 改动点 | 评价 |
|---|---|---|
| **(b) 后端 +claim 下发 `motion` 段** ⭐ | `servicesWithDefaults()` 增加 `motion`（preset/steps/loraHigh/loraLow/… 与 lipsync/face 并列）；`comfy_client.apply_services()` 读 `g("motion", …)` | **最干净**、与既有「服务地址」模式同构、改 UI 即生效、无需重启 worker |
| (a) worker 侧在 comfy 模式也拉 `cloud-config` 并合并 `video.params` | 只改 `stub_worker` | 最小改动，但该接口语义叫 cloud-config，会变味 |
| (c) `videoShotPayload` 直接合并 `videoParams` 进 `params` | 改 `JobService` | 分段时 deepCopy，语义混淆，不推荐 |

**另有一条务实路径（P0-lite）**：若**不需要按镜/按项目差异化**调档，只要全局统一档位，可以
**把 sweep 得出的最优档位设为 worker 的默认**（`WEAVEORA_MOTION_PRESET=hero` 或改 `MOTION_PRESETS` 的 `balanced` 基值）——
零后端改动即可生效。**只有当需要按镜/按项目调参时才必须修 P0。**

### 🟡 P1 —— `dual` 判定易误退化

```python
dual = not params.get("model")   # 只有显式给单模型才退单专家
```
若「video 引擎配置」的模型名被塞进 `params.model`（如云模型 `minimax/video-01`），
worker 会走**单专家**路径并用该名去 `UNETLoader` 找文件 → 报「扩散模型不存在」。
**建议**：自托管路径**忽略** `params.model`，改为显式开关（如仅 `params.mode == "single"` 才退单专家）。

### 🟢 P2 —— 显存阈值按 24G 卡调的

- `_vram_note()`：`free < 15.0` 打 WARN
- `_yield_vram_for_video(need_gb=15.0)`

GPU #2 是 **48G**：双专家 13.31×2 = 26.6 GB + TTS ~7 GB ≈ **33.6 GB**，余量充足。
→ 阈值可提到 ~28 GB，减少无谓的 TTS 卸载（卸载后首次配音要重加载）。
> 让位机制本身无副作用（只在不够时才卸），保持也可。

---

## 3. 配置层面

### 3.1 `sanitizeParams` 过滤（**已核实当前不拦**）

`EngineSettingsService` 保存 videoParams 时：
```java
if (schema == null || !schema.path("params").isArray() || schema.path("params").isEmpty())
    return userParams.deepCopy();          // ← 无 schema：原样放行 ✅
// 有 schema：只留 schema 里 userEditable 且类型匹配的键 → 其余**静默丢弃**
```

**实测数据库**：所有 `user_engine_settings.video_model_schema` 均为 **NULL** → 走原样放行 ✅
（唯一 `video_engine='gpu'` 且有 `gpu_server_url` 的用户是 `eng.…@weaveora.dev`，填的还是占位 `http://my-gpu:8188`）

**风险**：一旦有人在「生成引擎配置」刷新过**云视频模型**的 schema，`video_model_schema` 被写入后，
`preset/switch_step/lora_*` 等未知键会被**静默丢弃**（表现为「UI 上配了没效果」）。
**对策**：把 motion 键登记进 schema（`userEditable=true`），或让 `engine=gpu` 路径**跳过 sanitize**。

### 3.2 「生成引擎配置」需要填的值（P0 修好后才有意义）

| 表单项 | 值 |
|---|---|
| 视频引擎 | **GPU 服务器（自有引擎）** |
| GPU 服务器 URL / 端口 | GPU #2 的公网入口（或经网关的地址，视部署） |
| 视频参数 videoParams | `{"preset": "motion"}`（先跑通再按 sweep 结论调） |

---

## 4. Worker 层面

| 项 | 说明 |
|---|---|
| **目标机差异** | GPU #2 是 **Ubuntu 24.04**（GPU #1 是 20.04）→ 部署脚本/apt 包名/python 版本需适配 |
| **worker 放哪** | 若放 GPU #2：需在生产机 nginx `/weaveora/internal/` 白名单**放行 GPU #2 的出口 IP**（复用 `deploy/nginx_allow_gpu_internal.py`） |
| **依赖** | `cv2`+`numpy`（关键帧解码）、`imageio-ffmpeg`（`_encode_frames_mp4` **硬依赖**）、`PIL`（关键帧缩放，缺则降级不缩放）、**ffmpeg ≥ 4.3**（`-fps_mode`） |
| **档位默认** | `WEAVEORA_MOTION_PRESET` 缺省 `balanced`；动态优先应设 `motion`/`hero` |
| **显存互斥** | 与 TTS 共用一张卡；48G 够用，但注意 ComfyUI offload 策略（GPU #1 上 `--disable-async-offload` 是**必需**的） |
| **torch 版本** | GPU #1 曾因 **torch 2.14 + DynamicVRAM 死锁**降级到 `2.7.1+cu126`；GPU #2 若装新版需同样验证 |

---

## 5. 建议落地顺序（v2）

1. ~~定 LoRA 来源 + 下权重~~ ✅ **已完成**（6 个，已双校验）
2. **看 sweep 结论**：确定哪个档位（draft/balanced/motion/hero/full）+ LoRA 强度能治好慢动作
3. **决定要不要修 P0**：
   - 只需全局统一档位 → **P0-lite**：把最优档设为 worker 默认（零后端改动）
   - 需要按镜/按项目调参 → **修 P0**（推荐方案 b：`services.motion`）
4. 修 P1（`dual` 判定）、按需调 P2（显存阈值）
5. 配「生成引擎配置」→ 全链路（`clip` 走 gpu 路由）→ 渲染成片

---

## 6. sweep 结果该怎么读（提醒）

`deploy/verify_wan_i2v_motion.py` 明确写了：
> 脚本用的是合成测试图（渐变+圆），只能验证**链路/性能/显存**，
> **运动幅度必须用真实关键帧肉眼或取帧比对来判**（见 `--keep` 输出目录）。

所以 sweep 的 `WANMOTION {json}` 主要看 **耗时 / 显存峰值 / 是否成功**；
**「动态够不够」必须拿真实关键帧各档出一版对比**（这正是要治的病）。

---

## 7. 需你决策的两件事（v2，原三件已收敛）

1. ~~修不修 P0~~ → ✅ 已修（`7ce9bf7`，方案 b）
2. **worker 放 GPU #2 还是生产机**？放 GPU #2 需放行其出口 IP 到 `/internal` 白名单

---

## 8. 提示词源头治「缺动态」（`7b7d71f`）

### 8.1 关键前提

> **引擎只吃 `positive_prompt`**（`clip` 取 shot 的、`still` 取 keyframe 的），
> **`action` 字段不进引擎**。→ 动态描述必须写进 `positive_prompt`，写进 `action` 等于没写。

### 8.2 两层改动（软规则 + 硬兜底）

**一、LLM 提示词规则（源头）**

| 文件 | 改动 |
|---|---|
| `director_video_system.md` | 新增「**动态写作规则（P-motion）**」：**5 类动态要素至少覆盖 3 类** —— ① 主体动作（含幅度/速度，用现在分词）② 表情的**变化过程**（不是静态表情词）③ **眼神/视线**（从哪看向哪）④ **次级运动**（衣物/头发/烛火/尘埃；**每镜至少 1 个**）⑤ **多主体互动**（谁对谁做什么）。禁静态构图；质量词 ≤3 不变，**把词数花在动作上**；附 3 组正/反例 |
| `director_image_system.md` | 关键帧要「**动作中段**」而非静止摆拍（`mid-turn, hair still swinging` > `facing camera, arms at rest`）——关键帧的静态姿态就是运动的方向盘 |
| `director_rewrite_shot.md` | 单镜重写必须保留并**强化**动态 |
| `DirectorService.SYSTEM_FALLBACK[video]` | 兜底文案同步第 ④ 条 |

**二、确定性兜底（不依赖 LLM 听话）**

| 位置 | 改动 |
|---|---|
| `DirectorPlanValidator.MOTION_NEGATIVE` | `static, motionless, frozen, still photo, no movement, freeze frame` |
| `JobService.videoShotPayload` | **仅 clip** 追加该负面词（关键帧 still 不受影响，避免污染出图；这些词只抑制「整帧冻结」，不会压掉微表情/眼神等小幅运动） |

### 8.3 验证建议

1. 重新生成一版方案 → 检查新 `shots[].positive_prompt` 是否带动作/眼神/次级运动
2. **同一档位**下新旧两版对比动态（肉眼或取帧）
3. 若**动过头**（本该安静的镜头也在动）→ 收窄 `MOTION_NEGATIVE`，或在模板里加「按叙事控制幅度」的例外

---

## 9. 还未做的（交接清单）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **看 sweep 结论** | 定下哪个档位（draft/balanced/motion/hero/full）+ LoRA 强度能治好慢动作（注意：合成图只能验链路/性能/显存，**幅度必须真实关键帧对比**） |
| 2 | **worker 落位** | GPU #2 或生产机；若 GPU #2 需 `/internal` 白名单放行其出口 IP |
| 3 | **实机全链路验收** | 「生成引擎配置」填 `preset` → `clip` 走 gpu 路由 → 渲染成片 |
| 4 | **回写下载铁律** | §1.3 三条教训（resolve/API 端点限流差异、「字节对≠内容对」、校验强度折中）建议写入 `Weaveora.md §0.2` 与 `docs/gpu-newserver-batch1.md §4` |
