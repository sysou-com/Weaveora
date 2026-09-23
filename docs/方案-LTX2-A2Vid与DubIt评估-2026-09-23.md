# 方案 · LTX-2.5 的 A2Vid（音频驱动出片）与 DubIt（重配音）评估

> 日期：2026-09-23（本机 `Invisible`）。
> 触发：`docs/交接快照-2026-09-23-LTX25生产接入.md` §5-6「建议顺手评估官方 A2Vid / DubIt 两条流水线（用同一批权重），可能替掉 LatentSync 环节」。
> 关联：`docs/交接快照-2026-09-23-LTX25生产接入.md`、`docs/方案-口型与配音一体化-开源模型调研-2026-09-22.md`、`docs/lipsync-setup.md`。

---

## 0. 一句话结论

**两条流水线都值得做，但不是同一件事**：
- **DubIt** = 「重配音」→ 直接替掉我们最痛的一环（LatentSync 对口型：嘴部糊斑/乱码、480p 底片天花板）。**流水线顺序不变**（出片 → 配音 → 重对口型），风险最小。
- **A2Vid** = 「音频驱动出片」→ 一次过同时解决运动 + 口型 + 原生音轨，**但要求把顺序倒过来**（先有配音，再出片）。收益最大、改动也最大。
- **两者都不是"下载权重就能用"**：本地 ComfyUI 没有官方 A2Vid/DubIt 工作流（现成的 `LtxApi25AudioToVideo` 是**调官方 API 的付费节点**），要我们自己做图（跟 LTX-2.5 I2V 摊平模板是同一类活，但更复杂）。
- **显存是硬约束**：现役 48G 4090 单跑 LTX-2.5 22B distilled int8 5s/1280×704 已占 **43.0 GiB（121 帧@24fps）/ 43.0 GiB（241 帧@48fps）**；A2Vid/DubIt 都是**两段式**（Stage1 半分辨率 → Stage2 ×2 上采样 + 精修），必须先量再定档。

---

## 1. 事实（全部标注来源与性质）

| # | 事实 | 来源 | 性质 |
|---|---|---|---|
| 1 | 官方有 `A2VidPipelineTwoStage`：Stage1 在**半分辨率**下带音频条件生成（视频去噪、**音频冻结**），Stage2 **×2 上采样**并用 **distilled LoRA** 精修视频与音频 | `Lightricks/LTX-2` `packages/ltx-pipelines/src/ltx_pipelines/a2vid_two_stage.py`、`docs/pipelines.md` | **官方源码** |
| 2 | 官方有 `DubItPipeline`：两段式，**IC-LoRA + distilled checkpoint**，参考片段提供**视频与音频参考 token**；`LipDubPipeline`/`ltx_pipelines.lipdub` 已被 DubIt 取代 | `.../src/ltx_pipelines/dubit.py`、`docs/pipelines.md` | **官方源码** |
| 3 | 两段式要求的组件：LTX-2 主干 + Gemma 文本编码器 + **Spatial Upscaler（两段式必需）** + **Distilled LoRA（非蒸馏主干的 Stage2 精修需要）** | 官方 `docs/installation.md` | **官方文档** |
| 4 | LTX-2 已进 ComfyUI 核心；ComfyUI 内置的「音频→视频」节点是 **`LtxApi25AudioToVideo`**（走 Lightricks **云 API**、按秒计费、2–20s、可选首帧图） | `docs.comfy.org/built-in-nodes/LtxApi25AudioToVideo` | **官方（ComfyUI）** |
| 5 | ComfyUI-LTXVideo 示例工作流主要挂在 **LTX-2.3** 名下（distilled / IC-LoRA 变体）；**未确认有官方的 LTX-2.5 配音/对口型示例工作流** | `lightricks/comfyui-ltxvideo` README | 官方仓库 · 结论为"未确认" |
| 6 | 「把自定义音频当**冻结音频 latent**（用 LTX-2.5 Audio VAE 编码、先裁到视频时长）而不是 LipDub/参考条件」 | HF `Lightricks/LTX-2.5` discussions #44 | ⚠️ **非官方 / 用户自行摸索，未核实** |
| 7 | **LTX-2.x Community License**（2026-08-11 版覆盖 LTX-2.5）：年营收 **< 1000 万美元**免费商用、自托管与微调均可；≥ 门槛需另签付费授权；门槛按**公司总营收**（含子公司/关联方）计 | `Lightricks/LTX-2` `LICENSE-2_x`、`ltx.io/model/license` | **官方许可**（近门槛的解释建议法务确认） |

> 检索日期：**2026-09-23**。许可与管线命名会随版本变（例：`lipdub` → `dubit` 就是一次改名），落地前按 CLAUDE.md 二次核对官方仓库/许可页。

---

## 2. 与我们现役链路的关系

### 2.1 现役（LTX-2.5 出片 + LatentSync 对口型）

```
关键帧 →[motion] LTX-2.5 或 Wan2.2 I2V → clip(无音轨)
                    ↓ 配音 TTS
                 LatentSync 按段驱动（点选锁人 + 轨迹）
                    ↓ RIFE/放大
                 交付片段 → 导出成片
```
已知痛点（`docs/lipsync-setup.md`）：嘴部结构上限低（底片 480p / 24fps 与 LatentSync 25fps 口径差）、段间接缝、贴回区质感不一致、点选在「目标脸检不到」的帧会落到别人脸上。

### 2.2 A2Vid 路径（收益最大：一步出「有口型 + 有音轨」的镜头）

```
关键帧 + 该镜配音(音频) →[A2Vid 两段式] → 带口型与音轨的 clip → 导出
```
- 直接删掉 LatentSync 与插帧两步；口型由**音频条件**决定，天生同步，没有"贴回区"这种东西。
- ⚠️ **流程倒置**：现在是「先出片再配音」，A2Vid 要「先配音再出片」。要确认我们的编排能不能在 motion 之前拿到每段配音（TTS 已经是独立服务，顺序上是可调的，但**镜头切分/段时长依赖配音时长**，现在正是用配音时长反推段边界的）。
- ⚠️ 音频驱动出片意味着**动作也要重新服从音频**（2–20s 由音频长度决定），与现役「帧数 = 时长 × 原生 fps」的口径需要重新对齐。

### 2.3 DubIt 路径（风险最小：只换对口型那一步）

```
关键帧 →[motion]（不变）→ clip(无音轨)
                    ↓ 配音 TTS（不变）
                 DubIt（IC-LoRA，参考片段给视频+音频 token）
                    ↓
                 交付片段 → 导出（不变）
```
- 保留现役出片质量与顺序，只把 `_run_lipsync_graph`（LatentSync）换成 DubIt。
- 期望收益：口型由**生成模型**给出（不是贴回），消除贴片感/接缝；且它原生吃 24fps，可与 LTX 出片口径一致（不再有 24 vs 25 的时基打架）。
- 代价：DubIt 是 22B 级模型的**两段式**，单镜 GPU 时间大概率远高于 LatentSync（后者只有 5 亿参数级别）。**必须先量时间**再决定是否替换。

---

## 3. 落地清单（资产 / 缺口）

| 组件 | 现役盒上是否已有 | 说明 |
|---|---|---|
| `ltx-2.5-22b-distilled-transformer-comfy-int8-convrot.safetensors` | ✅ 已有（20.5 GB，`/addDisk`） | 现役 I2V 用的就是它 |
| `gemma4-12b-with-proj-ltx-2.5-comfy-int8-convrot` | ✅ 已有 | 文本编码器 |
| `ltx-2.5-video-vae-bf16` / `ltx-2.5-audio-vae-bf16` | ✅ 已有 | **Audio VAE 已在**（A2Vid/DubIt 都要用） |
| `ltx-2.5-latent-spatial-upscaler-x2-bf16` | ✅ 已有（995 MB） | 两段式必需 |
| `ltx-2.5-latent-temporal-upscaler-x2-bf16` | ✅ 已有（262 MB） | 48fps 用的（P4 已实测可用） |
| **Distilled LoRA（Stage2 精修）** | ❌ **缺** | A2Vid 两段式需要 |
| **DubIt IC-LoRA** | ❌ **缺** | DubIt 两条 stage 都要用它 |
| **本地 A2Vid/DubIt 的 ComfyUI 工作流** | ❌ **没有** | 官方只给了 Python 管线；ComfyUI 现成的是"调云 API"的付费节点 |

> ⚠️ 权重文件名/大小以官方仓库为准，本表只记"有没有"；下载一律走 `deploy/windows/gpu_model_downloader.js`（多路 Range + 断点续传，§0.2），并先出清单等确认，**不允许**由助手自行下载覆盖。

---

## 4. 建议的有界验证（不承诺落地）

**Step 0（0 GPU 成本，先做）**：确认「先配音再出片」在编排上是否可行（每镜音频时长 vs 镜头切分/段边界的依赖关系）。**这一步不过，A2Vid 免谈。**

**Step 1（窄口径裸跑，半天量级）**：
1. 下载 **Distilled LoRA** 与 **DubIt IC-LoRA**（先出清单 + 体积 + 恢复路径，逐项确认后走并行下载器）；
2. 拿现成的 `weaveora_ltx25_e54cba_00001_.mp4` 那类产物，按官方 `a2vid_two_stage.py` / `dubit.py` 起**最小脚本**（先不上 ComfyUI，纯 Python 管线验证可行性），20–40 帧、短时长；
3. 量三个数：**单镜耗时 / 峰值显存 / 口型主观质量**（照 §4 的"LLM 看图"办法：`deploy/diag/ask_image.py` 把图发公网 URL → 项目自己的 LLM）。

**Step 2（决策规则，先写死再看数字）**：
| 场景 | 采用条件 |
|---|---|
| **DubIt 替 LatentSync** | 单镜耗时 ≤ LatentSync 的 3× **且** 口型主观判定明显更好（逐版对照）**且** 48G 能跑（峰值 ≤ 44 GiB） |
| **A2Vid 替 出片+对口型** | Step 0 通过 **且** 单镜端到端 ≤ 现役两者之和（LTX I2V ≈3.5 分钟 + LatentSync ≈? ）**且** 口型/音画同步明显更好 |
| **都不满足** | 维持现状，把结论写回本文件（同 2026-09-22 口型调研那样给出量化结论） |

---

## 5. 风险与坑（预先写死，免得踩了不认）

1. **不是官方 ComfyUI 工作流**：`LtxApi25AudioToVideo` 是**云 API 付费节点**，不能当本地能力用；本地要自己做图（我们摊平 LTX-2.5 官方模板时已经踩过类型不匹配（`CreateVideo.fps` 收 FLOAT，模板给的是 `PrimitiveInt`）—— 两段式图更复杂，必须逐节点核类型）。
2. **两段式显存**：Stage2 是 ×2 上采样 + 精修，**峰值大概率高于现役 I2V**（现役 5s/121 帧已 43.0 GiB）。48G 卡可能只能做 480p 级或更短时长；要 720p 两段式很可能需要 ≥80 GiB 卡。
3. **DubIt 的"参考片段"语义**：它吃**视频 + 音频参考 token**，要求参考片段里人物的口型/音色关系合理；我们现役素材是"AI 生成的 480p/720p 片段 + TTS 音色"，与真人参考分布不同，效果需实测，别照搬论文结论。
4. **顺序倒置的连锁影响**（A2Vid）：段边界现在按配音时长平铺（`_lipsync_seg_windows`）；改成"先配音"后，镜头时长/切分逻辑、帧数上限（121 帧 = 5.04s @24fps）都要重新对齐。
5. **许可**：LTX-2.x Community License 年营收 < 1000 万美元免费商用；接近门槛要法务确认（官方明确口径是"公司总营收，含关联方"）。
6. **非官方资料降权**：本文件 §1 第 6 条（冻结音频 latent 的做法）来自 HF 讨论帖，**属于用户摸索**，只能当线索，不能当依据。

---

## 6. 现在不建议做的

- ❌ 现在就动手接 A2Vid（Step 0 未过 + 权重未下 + 无本地图）—— 会把 LTX 出片这条刚验收的通路搅乱。
- ❌ 用「云 API 节点」做生产（按秒计费 + 数据出网 + 与 §0-2「GPU 推理只在自己盒上」的纪律冲突）。
- ✅ 建议**先做 Step 0 + Step 1 的纯 Python 裸跑**，拿到"耗时/显存/质量"三个数再谈接不接。
