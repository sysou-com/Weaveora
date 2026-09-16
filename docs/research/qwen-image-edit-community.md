# Research: Qwen-Image-Edit 出「电影感关键帧」的社区实测优化技巧

Scope: r/StableDiffusion / r/comfyui / r/QwenImageGen, Civitai/CivArchive 文章与评论, 知乎/CSDN/B站/GitCode, GitHub issues (Comfy-Org/ComfyUI, QwenLM/Qwen-Image), Hugging Face discussions, 官方文档 (comfy.org / QwenLM / 阿里云百炼).

> **抓取限制（先读）**：Reddit 在本环境**无法直接抓取正文**（fetch 被拦截），所有 Reddit 内容均来自搜索引擎返回的**片段/摘录**，属于二手引用。HF discussion 页面也部分被拦截（靠片段）。CSDN 上大量「保姆级教程」是 AI 生成的内容农场文（出现「Character Anchor Module」「92% 保持率」等无出处数字），已降权或剔除。

---

## Summary

1. **白底/证件照参考图带偏是真实存在的、有机制解释的问题**：Qwen-Image-Edit 把输入图**同时**喂给 Qwen2.5-VL（语义）和 VAE Encoder（外观 latent），所以参考图的构图、白底、头肩比例本身就是一个很强的条件信号；再叠加「内部重缩放（VAE ×16 vs VL patch 14）+ TextEncodeQwenImageEditPlus 内部统一缩到 1MP」导致的 zoom/平移漂移，输出就会残留白底/证件照构图。**最可靠的对策不是提示词，而是 mask + Inpaint Crop & Stitch（或 ReferenceLatent 链）**，提示词只作为辅助。
2. **人脸相似度**：2509 才真正可用，2511 更强（且内置 LoRA），但 2511 有「比 2509 更差的」反向报告，需按工作流实测。社区主流配方 = **Qwen Edit + Lightning LoRA（4/8 步）+ dx8152 多角度/Fusion 类 Qwen LoRA + FaceDetailer 收尾 + SeedVR2 放大**。**PuLID / InstantID / IPAdapter-FaceID 不适用于 Qwen**（它们是 SDXL/Flux 的 attention patch），社区明确说新 i2i 模型让 IPAdapter 在保脸场景「基本过时」。
3. **多主体同框**：Qwen 系**没有**社区可用的 regional-prompting/attention-couple 方案（Regional Prompter 只支持 SD1.5/SDXL）。可行路径按成功率排序：**(B) 先抠图摆位 + Fusion LoRA 一次统一光影 > (C) 分主体 mask + Crop&Stitch 逐次 Edit > (A) 原生多图 + "image 1 在左/image 2 在右" 提示词 > (D) 红/蓝画笔色块 mask**。
4. **负面词在 Qwen 上基本无效**（这是本报告里证据最硬的一条：CFG 1→7 网格实验完全无效，架构上也没做 CFG 训练），社区共识是「正向描述替代负词」。电影感靠**光学术语结构性重建**（镜头焦段/光位/大气），而不是胶片滤镜词。
5. **关键帧构图**：主体占画面 **30%–60%**、沿运动方向留白、画幅在生成阶段就定死（9:16 / 16:9）、避免文字与复杂手部、元素复杂度 ≤2 个主体。白色背景残留要用「抠图 → 摆位合成 → 一次 Fusion/relight 统一 → Crop&Stitch 保证 mask 外零改动」的流水线消除，而不是靠提示词「不要白背景」。

---

## Findings

### Q1. 白底/纯色背景定妆照当参考图，如何不被「白底 + 证件照构图」带偏

**结论（置信度：机制高 / 提示词对策中 / mask 对策高）**

**1.1 为什么会带偏（机制层，硬证据）**
- Qwen-Image-Edit 的架构是**双通道条件**：输入图同时进 Qwen2.5-VL（视觉语义控制）**和** VAE Encoder（视觉外观控制）。[官方 ComfyUI 文档](https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit) · [Qwen 官方博客](https://qwenlm.github.io/blog/qwen-image-edit/) → 参考图的「外观」是被当作 latent 参考注入的，白底+头肩构图天然是强条件。
- 实测症状被反复报告：只改衣服颜色/背景颜色，**脸也变了**（[QwenLM/Qwen-Image#88](https://github.com/QwenLM/Qwen-Image/issues/88)）；ComfyUI 离线版（Q8_GGUF/FP8）**几乎整张重绘**，而在线 demo 只改必要像素（[Comfy-Org/ComfyUI#9455](https://github.com/comfyanonymous/ComfyUI/issues/9455)）；本地跑出「背景不一致」（[Qwen-Image-Edit discussion #11](https://huggingface.co/Qwen/Qwen-Image-Edit/discussions/11)）；多图工作流下**背景细节被摧毁/模糊、原图残影透出**（[r/StableDiffusion 1nxwrd1](https://www.reddit.com/r/StableDiffusion/comments/1nxwrd1/qwen_2509_background_details_destroyed_and/)）。
- **额外的构图漂移源**：VAE 维度是 16 的倍数、Qwen2.5-VL patch 是 14 的倍数 → 输入宽高取 **112 的倍数（LCM(16,14)）** 可基本消除内部重缩放的 zoom 效果（[r/StableDiffusion 1myr9al](https://www.reddit.com/r/StableDiffusion/comments/1myr9al/use_a_multiple_of_112_to_get_rid_of_the_zoom/)；[官方 issue #229](https://github.com/QwenLM/Qwen-Image/issues/229)）；中文侧同样结论（112 / 56 倍数，[B站要点总结](https://www.bilibili.com/opus/1103473928432517125)）。
- **TextEncodeQwenImageEditPlus 会内部把所有输入图统一缩到约 1MP**（nodes_qwen.py 第 89–96 行），所以「自己加 resize 节点」和「不加」行为不同（[r/StableDiffusion 1otityx](https://www.reddit.com/r/StableDiffusion/comments/1otityx/the_simplest_workflow_for_qwenimageedit2509_that/)），官方模板默认带一个 `Scale Image to Total Pixels` 节点（1MP），注释说明是为了避免 2048×2048 这类大图掉画质（[官方文档](https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit)）。
- **放大到 1.5–2MP 会出现新的漂移副作用**，社区实测 1MP 最稳（[r/StableDiffusion 1nrfd2j](https://www.reddit.com/r/StableDiffusion/comments/1nrfd2j/using_qwen_edit_no_matter_what_settings_i_have/)）。

**1.2 对策清单（按可靠性排序）**

| 手段 | 具体怎么做（ComfyUI 接线） | 可靠性 |
|---|---|---|
| **① Mask + Inpaint Crop & Stitch**（最高可靠） | 装 `lquesada/ComfyUI-Inpaint-CropAndStitch`：`Load Image →(mask)→ ✂️ Inpaint Crop →(cropped image+mask)→ TextEncodeQwenImageEditPlus/KSampler → ✂️ Inpaint Stitch`，把结果缝回原图。**mask 外的像素物理上不被改动**，从根本上杜绝白底/证件照构图残留与整体漂移。官方模板就有 `Qwen-Image-Edit + Crop&Stitch + Fusion LoRA`（"Relight Composited Product"）。 | 高 |
| **② ReferenceLatent 链（latent reference chaining）** | 断开 `TextEncodeQwenImageEditPlus` 上的 VAE 与 image 输入，改成每张输入图 → `VAE Encode` → `ReferenceLatent` 节点（可多路链接）。社区 PSA 称能大幅降低 2509/2511 的像素漂移；ComfyUI 作者也在 commit 里说明「TextEncodeQwenImageEdit 会顺带 set ref latent，若不想这样就把 VAE 断开、自己用 ReferenceLatent」。 | 中高 |
| **③ 遮罩保护脸部 / 只重绘目标区** | 编辑人物时若脸会变，**用 mask 把脸部圈出来保护**（[B站要点总结](https://www.bilibili.com/opus/1103473928432517125)）。ComfyUI 侧用 `MaskEditor` + `InpaintModelConditioning`，denoise 0.6–0.75 修五官、0.8–0.92 重构背景（[GitCode 单图工作流攻略](https://blog.gitcode.com/0ebe82d34cbcee93fccddda8ac7cf660.html)）。 | 高 |
| **④ 参考图预处理：别喂整张证件照** | 换成 **①紧凑脸部特写（identitiy）+ ②目标体态/服装参考（头部区域用纯色块盖住）+ ③骨架图**；提示词第 1 句固定为模板，如 `"Remove the black color patch. A portrait of the woman in image 1, wearing the dress in image 2 with the pose in image 3."` + 第二段才是场景描述。这是 MyAIForce 的「100% 人脸一致性」工作流原话。**参考图角度/表情会强烈影响输出**。 | 中高 |
| **⑤ 先抠图再合成，不要指望 Edit 自己换背景** | 白底定妆照 → 背景移除（RMBG/BiRefNet/SAM）→ 放到目标画布上按目标比例/位置贴好 → 再用 Qwen Edit/Fusion LoRA 统一光影。电商流程文章给的就是这套五步法（去背景→统一画幅/位置/留白→加背景色→统一光影色温→人工审核边缘文字）。 | 中高 |
| **⑥ 输入前先做一次 resize 到受支持尺寸** | Civitai 评论原话：字符细节丢失常来自「不支持的图片尺寸」，**在让模型看到之前加一个 resize 节点**，并配合调 shift。官方推荐尺寸 1328×1328 / 1664×928 / 928×1664 / 1472×1140 / 1140×1472（≈1MP 级）。 | 中高 |
| **⑦ 提示词：显式保背景 + 保身份 + 保构图** | 社区共识三句「保命句」：`Keep everything else unchanged.` / `Preserve face and clothing features.` / `Keep the composition unchanged, keep the original aspect ratio and object positions; do not zoom or crop the frame.`（[Qwen-Image-Edit Prompt Guide: The Complete Playbook](https://www.reddit.com/r/StableDiffusion/comments/1n1n81o/qwenimageedit_prompt_guide_the_complete_playbook/) — 中译版同样可搜到）。`Keep the original background.` 是 Reddit 对「只换主体」问题的高赞解法。**但社区也提醒：加「保持其他不变」有帮助，却不能完全避免裁剪/缩放**（同贴中译版原话）。 | 中 |
| **⑧ 提示词要写成「操作指令」而非「画面描述」** | Edit 模型吃的是「对已有图做什么」：`Replace only the background… Keep the bottle exactly as in the original – same angle, same lighting on the glass, same reflections.`（[deAPI 提示词指南](https://deapi.ai/blog/qwen-image-edit-plus-prompting-guide-how-to-write-edit-instructions-that-actually-work)）。Qwen 官方给的 system prompt 也要求「精炼、可执行、矛盾时优先合理解读」。 | 中 |
| **⑨ Shift 参数用来「让模型听话」** | Civitai 评论经验：**Shift 1–3 = 模型会「改写你的提示词、自作主张」；Shift 5–8 = 严格照做**。想强行保背景就往 5–8 调。 | 中（单源经验） |
| **⑩ CFG 别开高** | 2511 社区经验 CFG 4–5 最佳，更高出 artifacts；官方模板 2509/2511 fp8 用 CFG 4 / 20 步、bf16 CFG 4 / 40 步、Lightning 4 步用 CFG 1.0。编辑模型 CFG 越高越倾向于「按提示词大改」。 | 中高 |
| **⑪ 色块 mask 占位（零额外节点）** | 在 MaskEditor 里用**画笔涂纯色（红/蓝）**，然后提示词写「replace the red area with X, the blue area with Y」，无需传统 inpaint 工作流即可做局部替换/换脸雏形（[r/comfyui 1resmvu](https://www.reddit.com/r/comfyui/comments/1resmvu/qwen_image_edit_2511_easy_inpainting_and_face/)）。 | 中（简单场景） |

**1.3 已确认的坑**
- 「一键去背景」后出现**白边**是已知痛点；解法是羽化 1–2px 抠掉并强制输出 PNG/Alpha 归一化（[CSDN 透明通道文](https://adg.csdn.net/697067e1437a6b40336a2026.html)、[零基础入门](https://www.limyvps.com/news/26_80659.html)）。
- 多图输入时**输出比例由最后一张图决定**（阿里云 API 文档），而社区经验是**第一张图的 proportion 决定最终比例**（[MonAI wiki](https://wiki.monai.art/en/tutorials/qwen-reference-images)）→ 两端说法冲突，**实操建议：自己把画布尺寸定死并统一输入尺寸**，不要依赖模型决定。
- **不要用「不要白背景」这类否定句**：Qwen 系对否定语义基本无响应，甚至反向（见 Q4）。

---

### Q2. 人脸相似度提升手段

**结论（置信度：版本结论中高 / 节点结论高 / 分辨率结论中 / 「先低分再放大」高）**

**2.1 版本差异（必须先对齐版本）**
- **2509**：官方明写「Improved Person Editing Consistency：更好保留面部身份，支持各种肖像风格与姿势迁移」，新增多图（person+person / person+product / person+scene，1–3 张最优）。[HF 2509](https://huggingface.co/Qwen/Qwen-Image-Edit-2509) · [官方 md](https://github.com/qwenlm/qwen-image/blob/main/Qwen-Image-Edit-2509.md)
- **2511**：官方四项升级里有两项直接相关——**Mitigate Image Drift**、**Improved Character Consistency**、**Multi-Person Consistency（把多张单人图融成合影）**、**内置 LoRA**。[ComfyUI 官方说明](https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit-2511) · [Qwen 博客](https://qwen.ai/blog?id=qwen-image-edit-2511)
- **反向证据（重要）**：CivArchive 2511 页面评论里有人称「一致性甚至比 2509 差很多」，另一人称「不加任何 LoRA 完全不出效果、2509 能用的提示词在这没反应」；且有 **2511 人脸崩坏/重影**的 HF discussion（需 diffusers 0.36.0 + `zero_cond_t`，旧版 diffusers 会坏）。→ **结论：2511 不是无条件更好，必须用你自己的参考图对比 2509/2511 再定版。**
- 另有第三方横评称 2511 双人合照「相较 2509 质变，但仍不及 nano-banana-pro-2k」（[aibyte](https://www.aileading.cn/docs/comfyui/qwen-image-edit-2511.html)）。

**2.2 参考图数量与选择**
- 官方：**1–3 张最优**（超过 3 张不是训练分布）。
- 社区最佳实践（两套可落地配方）：
  - **两图**：图1 = 目标场景/构图（含背景，可以是「主体只占小部分」的场景图；甚至有工作流把同一张图同时接到 image1 和 image3，用来强化保真）；图2 = 主体人物抠图/角色参考。
  - **三图**：图1 = 脸部特写（identity），图2 = 全身/服装/姿态（**头部区域涂成纯色块**，让模型去「补头」），图3 = 姿态骨架。
- **同一人多张参考是否更好**：会**小幅提升**，但不是越多越好——每多一张参考都会带来「属性串味」风险，且官方训练分布是 1–3 张。实践上 **1 张高质量紧凑脸部特写 > 3 张低质全身照**。**该参考图的角度和表情会强烈决定输出**，所以选参考图时优先选与目标镜头角度接近的那张。

**2.3 需要配什么节点 / 兼容性（关键决策）**
- ✅ **FaceDetailer（Impact Pack）**：几乎所有「100% 人脸一致性」工作流都以它收尾——检测→裁剪→重绘→贴回，用来修眼神、五官对齐、皮肤细节。MyAIForce 的配方就是 `Qwen Edit → (face LoRA ≈0.2 strength) → FaceDetailer（调 LoRA strength + denoise）→ SeedVR2 放大`。脸漂了就把 LoRA/denoise 调高，过熟就调低。
- ✅ **SeedVR2（ComfyUI-SeedVR2_VideoUpscaler）或普通放大**：**建议先低分出图再放大**，理由是「避免全程跑大分辨率」（效率），且最终脸不会被放大过程破坏——放大放在最后一步。
- ✅ **Qwen 原生 LoRA**：`dx8152/Qwen-Edit-2509-Multiple-angles`（单图→多角度、跨角度保持身份；作者 2025/11/2 还因一致性不稳重训并重新上传，说明这类 LoRA 一致性本身就是不稳定的）、`dx8152/Qwen-Image-Edit-2509-Fusion`（融合/摆位/统一光影，配 Lightning 使用）、`lightx2v/Qwen-Image-Edit-2511-Lightning-4steps`（加速）。2511 **内置**了若干社区常用 LoRA（含光照/视角）。
- ❌ **PuLID / InstantID / IPAdapter-FaceID 与 Qwen 不兼容**：它们都是 InsightFace + SDXL/Flux attention patch 方案（InstantID 明确「Works ONLY with SDXL checkpoints」；PuLID_ComfyUI / PuLID-Flux 分别对 SDXL / Flux）。社区共识：**新的 i2i 模型（Flux Kontext / Qwen Image Edit）在保脸场景已让 IPAdapter 基本过时**。
- ⚠️ **Qwen 系自己的换脸路子**：有社区教程用 `Qwen face-to-person` + `qwen consistent edit` 两个 LoRA + inpaint 节点做换脸（nunchaku 低显存版），走的是「人脸区域 mask + 双 LoRA」而非 InsightFace。可作为备选，但依赖非官方 LoRA。

**2.4 分辨率与「脸部像素」**
- 硬事实：官方 API 要求单图宽高在 **384–3072**；ComfyUI 模板把输入统一到 **1MP**；官方推荐 1328×1328 / 1664×928 / 928×1664 / 1472×1140 / 1140×1472。
- 推论（**中等置信度**，无社区定量实验支持）：在 1MP 下若主体是全身，脸部只占几十到一两百像素，身份特征信息量不足 → 这就是「全身图脸不像、脸部特写放大才像」的根因。因此推荐流水线是：**用脸部特写参考 + 1MP 出图 → FaceDetailer 修脸 → 放大到 2–4MP**，而不是一步出 2K 大图。
- 另有社区经验：**1.5–2MP 输入会出现额外副作用**，1MP 最稳；想保细节靠 FaceDetailer + 放大，而不是靠提高 Edit 分辨率。

**2.5 链式编辑的漂移**
- 多轮链式 Edit 会累积漂移；2511 专门「减轻 image drift」就是针对这个。实践上：**尽量少轮**，每轮都重新注入参考图/参考 latent，或用 Crop&Stitch 限定改动范围。

---

### Q3. 多主体同框且各自定位（ComfyUI 可接线方案 + 代价/成功率）

**结论（置信度：中高；「Qwen 没有 regional prompting」为中，属消极证据）**

**先说会被浪费时间的结论：**
- **Regional Prompter / Attention Couple 类只在 SD1.5/SDXL 上工作**（「works with SD1.5/SDXL (not Flux)」），ComfyUI 的 `ConditioningSetArea` / `RegionalPrompt` 也是为 UNet/Flux 生态做的。Qwen 是 20B MMDiT + VLM 文本编码器，本次调研**没有找到任何 Qwen 原生的 regional attention 补丁**（Krea-2、Flux 有对应 pack，Qwen 没有）。→ **不要把 regional prompting 当作 Qwen 的第一方案**；它的实际用法退化成「生成阶段不用，合成阶段用」。

---

**方案 A：原生多图融合 + 编号提示词（最省事）**
- 接线：`LoadImage×2~3 → TextEncodeQwenImageEditPlus(prompt 用 "Image 1"/"Image 2" 引用) → KSampler(Qwen Edit 2509/2511) → VAE Decode`。
- 定位靠语言：官方示例原文 `"The magician bear is on the left, the alchemist bear is on the right, facing each other in the central park square"`；社区写法 `"Use image A and image B. Merge them into one photo where both people sit on a park bench. Preserve face identity and natural lighting."`、`"Make the three girls sit together on a bench, maintaining natural proportions."`
- 代价：**1 次采样**，最便宜。成功率：2 人可用；**2511 明显优于 2509**（2509 双人会出现「张冠李戴/长相趋同」）；3 人以上可靠性下降；与 nano-banana-pro-2k 仍有差距。**附加坑**：1MP 画布下两人各半，脸像素减半 → 需搭配 FaceDetailer 或更大画布。
- 参考：[官方 2511 HF 示例](https://huggingface.co/Qwen/Qwen-Image-Edit-2511) · [Civitai 多图合成教程](https://civitai.com/articles/30625/combine-multiple-images-into-one-scene-with-qwen-image-edit-2509-in-comfyui) · [Civitai 2511 多图多参考](https://civitai.com/articles/29885/combine-multiple-reference-images-into-one-edit-with-qwen-image-edit-2511-lightning)

**方案 B：手工摆位（Compositor）+ Fusion LoRA 统一光影（定位最准，推荐做关键帧）**
- 接线：主体逐个抠图（RMBG/SAM）→ `ComfyUI Compositor`（可交互放置，社区演示最多 7 个对象）摆到目标像素位置 → 合成图 + 各主体参考图 → `TextEncodeQwenImageEditPlus` → Qwen Edit + `Qwen-Image-Edit-2509-Fusion` LoRA（dx8152，建议强度 ≈0.8）+ Lightning → 输出。
- **关键区分两种 LoRA**（作者本人解释）：**Fusion = 不动背景，只改主体的受光/反射/阴影/透视**；**Relight = 改全局光照**。做关键帧想保留你排好的构图，用 Fusion 而不是 Relight。
- 代价：多一个合成步骤 + 1 次采样；LoRA 权重需调（默认设置会改变色彩平衡与位置，需按 GitHub 项目说明自行绕过）。
- 成功率：**位置 100% 可控**（是你排的），失败点转移到「光影融合不自然 / 边缘白边 / 透视不匹配」。
- 参考：[dx8152 Fusion README](https://huggingface.co/dx8152/Qwen-Image-Edit-2509-Fusion/blob/bacd972972e1006e4241284e10b0ce6ebdd087eb/README.md) · [社区 FAQ：Fusion vs Relight](https://www.reddit.com/r/comfyui/comments/1ohf8f4/qwenedit2509_image_fusion_lora/) · [QWEN-AI-Compositing](https://github.com/rik-python/QWEN-AI-Compositing)

**方案 C：分主体逐次 Edit + mask + Crop&Stitch（保真最高，最慢）**
- 接线：对底图跑 1 次/主体；每次 `Load Image → MaskEditor 圈出该主体区域（可用 SAM2/SAM3 自动分割）→ ✂️ Inpaint Crop → Qwen Edit（提示词只描述这一个主体）→ ✂️ Inpaint Stitch → 存图作为下一轮输入`。循环 N 次。
- 代价：N 次采样；每次都有缝/光影不连续风险，需要最后再做 1 次低 denoise 全局 pass 或 relight。
- 成功率：单个主体可控性最高；主体间的光照/透视一致性最差。已有成型工作流：[QWEN image editing with mask & reference(Improved)](https://www.reddit.com/r/comfyui/comments/1nyfqdv/qwen_image_editing_with_mask_referenceimproved/)（仓库 https://github.com/ashish-aesthisia/comfyui-workflows/tree/main/qwen-edit-with-mask）、[Qwen 2511 Edit Segment Inpaint workflow（含 SAM2/SAM3）](https://www.reddit.com/r/StableDiffusion/comments/1px2jl6/released_qwen_2511_edit_segment_inpaint_workflow/)、[Qwen Edit 2509 Crop & Stitch](https://www.reddit.com/r/comfyui/comments/1nq82om/qwen_edit_2509_crop_stitch/)。

**方案 D：色块 mask（色笔）**
- 接线：MaskEditor 里用不同颜色涂不同区域 → 提示词 `replace the red area with …, the blue area with …`。
- 代价：**零额外节点**；成功率仅限「简单区域替换」，复杂主体/多主体易混淆。

**方案 E：ControlNet 几何约束（钉住剪影/姿态）**
- 2509/2511 **原生支持 ControlNet**（depth / edge / keypoint / soft-edge）；ComfyUI 侧用 `Qwen-Image ControlNet` 模型 patch（canny/depth/inpaint 三种），社区推荐 **InstantX Union ControlNet** 作为通用选择。
- 用途：配合 A/B/C 使用，**把每个主体的轮廓/姿态钉死**（尤其「同一张图里两个人位置不能互换」的场景）。参考图在 2509 起可以喂**姿态骨架图**做姿势迁移。
- 局限：ControlNet 只管结构，**不分配身份**；要身份就得配参考图/LoRA。代价：+VRAM，+调参。
- 参考：[2509 官方 md（Native ControlNet）](https://github.com/qwenlm/qwen-image/blob/main/Qwen-Image-Edit-2509.md) · [ComfyUI ControlNet 教程](https://kombitz.com/2025/10/03/how-to-use-controlnet-with-qwen-image-edit-2509-in-comfyui/) · [InstantX Qwen-Image-ControlNet-Inpainting](https://huggingface.co/InstantX/Qwen-Image-ControlNet-Inpainting)

**方案 F：区域条件 / Regional Prompting — 判定：不适用**
- 只有 SD1.5/SDXL 生态的 `ConditioningSetArea` + `ConditioningCombine`、`RegionalPrompt`/`RegionalSampler`；且文档明确说「regional prompts 只限制 conditioning 生效范围，**不会自动解决姿态或身份一致性**」。Qwen 侧未见可用实现。
- 参考：[Easton Dev 多主体区域提示](https://eastondev.com/blog/en/posts/ai/20260828-comfyui-regional-prompting-multi-subject/) · [Apatero Regional Prompter 指南](https://apatero.com/blog/regional-prompter-comfyui-complete-guide-2025)

**成功率汇总（社区口径）**：B（摆位+Fusion）位置最准、最适合关键帧；C 保真最高但一致性最差；A 最省事、2511 可用、3 人以上靠运气；E 作为 A/B/C 的补充约束；F 放弃。

---

### Q4. 电影感 / 影视质感的提示词与参数 + 负面词共识

**结论（置信度：负面词高 / 参数中高 / 光学词中高 / 胶片颗粒低）**

**4.1 负面词：社区共识 = 基本无用，别浪费时间（本报告证据最硬的一条）**
- 网格实验：positive 变体 × CFG 1.0/2.0/…/7.0，negative 固定为 `child` → **CFG 全区间零效果**；把否定写进正向（`… Not a child.`）反而**更糟**，因为「child」这个词进了正向 prompt。官方所有示例里 `negative_prompt = " "`（一个空格）。HF discussion #75「负面文本编码相关问题」、diffusers #12175（唯一结论：**整个省略 negative_prompt 会导致画质崩坏**，所以必须传一个空串/占位）。技术报告里根本没提 CFG/negative。→ **架构上 Qwen-Image 不是按 SD/Flux 那样做 CFG 训练的。**
- 来源：[The Mystery of Qwen-Image's Ignored Negative Prompts（含网格图）](https://blog.promptmaster.pro/posts/qwen-image-negative-prompts/) · [diffusers#12175 引用](https://github.com/huggingface/diffusers/issues/12175)
- 社区仍在流传的负词清单（**属低价值 cargo cult，仅作参考**）：`blurry, low quality, extra fingers, warped face, deformed, wrong outfit details, inconsistent lighting, text, watermark, logo`（[Civitai Qwen Image Edit 2511 Ultimate](https://civitai.com/models/2301814/qwen-image-edit-2511-ultimate)）；中文侧 `模糊 失真 噪点`（CSDN 教程，多源引用）。第三方指南的折中说法：Qwen 接受 negative（≤500 字符）「但效果比生成模型微妙得多，只适合避免具体 artifacts，不要用来做大概念修改」。
- **实操建议**：负词只在 `CFG > 1`（即不用 Lightning 的 true_cfg=1）时有一点作用；**主力靠正向描述**（要「纯色背景」就写 `plain seamless studio backdrop`，不要写 `no white background`）。

**4.2 电影感提示词：Qwen 把摄影术语当「物理过程」而非滤镜**
- 实测结论（Qwen-Image FP8 + Lightning 4 步的光学修饰词对照实验）：**镜头/光照/大气线索触发最大的结构性变化；胶片/扩散类词主要只改变影调与对比度**。→ 想「有电影感」，优先写**光位 + 焦段 + 大气**，而不是写「film grain」。[r/QwenImageGen 光学修饰词实验](https://www.reddit.com/r/QwenImageGen/comments/1ooijqu/optical_modifiers_with_qwenimage_fp8_lightning/)
- Reddit Playbook 里被验证的句式（直接抄）：
  - 光：`Relight the scene with a warm key light from the right and cool rim light from the back. Keep pose and background unchanged.`
  - 镜头：`Render with a 35 mm lens, shallow depth of field, focus on subject's face. Preserve environment blur.`
  - 结构保真补丁：`Keep the composition unchanged. Keep the original aspect ratio and object positions, change only … do not zoom or crop the frame.`（中译版被高频引用）
- 元素级词汇表（官方/阿里云文档给的分类）：主体 / 场景（时间、天气）/ 风格（写实、电影摄影）/ 构图镜头 / 光照 / 色调；阿里云示例 prompt 里出现过 `背景呈现自然的景深虚化效果，色彩以大地色、灰色和暖白色为主调`。
- 官方「万能后缀」：`positive_magic = ", Ultra HD, 4K, cinematic composition."`（中文：`, 超清，4K，电影级构图.`）——这是官方 README 里的真实字段。[README](https://github.com/qwenlm/qwen-image/blob/main/README.md)
- **Edit 模型下的写法**：短、具体、动词导向。`"Add a red scarf"` 优于 `"add a beautiful red silk scarf wrapped elegantly around the neck"`；一次只改一件事（提示词里多一个 `and` 就可能整张脸被重生成——中文社区原话）。
- **机位/景深是 Qwen Edit 的强项**：2509 起可做「换机位」（低角度/荷兰倾斜/反打、模拟 35mm 广角），2511 增强几何推理；2509 需配 `dx8152/Qwen-Edit-2509-Multiple-angles` LoRA，2511 直出更好（但仍有用户报告「旋转相机」被理解成整图旋转 90°，角度控制不稳定 → 关键帧要出多机位时，**建议仍走多角度 LoRA 或先出多张再挑**）。
- **系列一致性**：有 `next-scene-qwen-image-lora-2509` 这类 LoRA 做分镜序列（LoRA 强度 0.7–0.8、前缀 `Next Scene…`），以及官方生态的 `Qwen Image Edit + Wan 2.2` 连贯场景工作流（同步光照/机位/构图跨镜头）。

**4.3 参数**
| 变体 | CFG | steps | 备注 |
|---|---|---|---|
| bf16 | 4 | 40 | 质量优先 |
| fp8 | 4 | 20 | 常用 |
| fp8 + Lightning（4 步） | **1.0** | 4 | 此时 negative 完全失效 |
| Lightx2v 8 步 | - | 8 | 更稳，4 步更飘 |
- 2511 社区经验：CFG **4–5** 最佳，更高出 artifacts。
- **Shift**：1–3 = 模型「改写」你的提示词；5–8 = 严格照做。（单源经验，但与「Qwen 是 VLM 不是 T5」的机制一致）
- 分辨率：1MP 工作、mod-112 输入、约 1MP 输入最稳；≥1.5MP 有额外副作用。
- LoRA 强度经验：Fusion ≈0.8；人脸一致性 LoRA ≈0.2 再交给 FaceDetailer 收尾；next-scene LoRA 0.7–0.8。

**4.4 胶片感（胶噪/颗粒）— 证据薄弱**
- 只找到「film/diffusion 类词主要只改动影调与对比」这一条间接证据（光学修饰词实验）。**没有**找到「film grain / 35mm grain / halation / anamorphic flare」在 Qwen Edit 上的可控实测。→ **建议颗粒、halation、色彩查找表（LUT）放后期**（ComfyUI 里用 film-grain/LUT 节点做），别指望 prompt。

---

### Q5. 关键帧（作为图生视频首帧）的构图最佳实践

**结论（置信度：构图规则中高；Qwen 侧的像素对齐高）**

**5.1 构图规则（社区共识，多源一致）**
- **主体占画面 30%–60%**；不要填满画面。[4sAPI 图生视频实战](https://blog.4sapi.com/zh/blog/image-to-video-brand-commercial-guide)
- **预留运动空间**：想让镜头右移就在右侧留白；想让人往前走就留出前进方向的空间。画面太满，模型无处可动。[Seedance 首帧/分镜指南](https://seedance-2ai.org/zh/blog/ai-image-generator-for-video-creators)
- **主体清晰、光线充足、与背景分离良好**；避免严重逆光（除非刻意剪影）。
- **前景/中景/背景要有层次**（利于视差与自然运镜）；**扁平、无层次的构图**会让深度感不自然。[首帧与尾帧指南](https://seedance-2ai.org/zh/blog/ai-video-first-last-frame-guide)
- **复杂度控制**：1–2 个主体 + 连贯背景，远好于「几十个运动元素」的群像。
- **隐含物理线索**：被风吹起的头发、受重力下垂的布料 → 这些线索会引导运动模型。
- **稳定元素要清晰界定**：该不动的区域（背景）不能杂乱模糊。
- **画幅在生成阶段就定**（9:16 竖屏 / 16:9 横屏），不要事后裁切；1:1 人像硬转 9:16 会逼模型补大量上下空间 → 主体漂移。
- **少文字/细 Logo**（要就后期加）；**避免复杂手部**。
- 首帧设计四支柱：带运动空间的构图 / 明确光影方向 / 清晰的空间层级 / 与视频模型能力匹配的复杂度。

**5.2 「避免白色背景残留」的具体做法（可执行的 ComfyUI 流水线）**
1. **不要靠提示词**。`"keep the original background"` 有帮助但不保险（社区原话：不能完全避免裁剪/缩放），而且白底残留往往来自**外观 latent 注入 + 内部重缩放**，不是语义层能指挥的。
2. **先把白底干掉**：RMBG / BiRefNet / SAM 抠出主体 → RGBA PNG（检查边缘无白边；有白边就羽化 1–2px 抠掉）。
3. **在目标画布上摆位**：按 9:16 或 16:9 建画布，把主体放在**目标占比 30%–60%**、并按运动方向留白；背景用你想要的场景板（可以是另一张参考图或先 T2I 生成的场景）。
4. **一次 Qwen Edit/Fusion pass 统一光影**：用 **Fusion LoRA**（不动背景、只改主体受光/反射/阴影）把主体「焊」进场景；如果用 Relight LoRA 会改全局光照（可能又把你排好的背景改了）。同时提示词写成「只在主体上调整光照，保留背景不变」。
5. **像素级保证外部零改动**：用 `✂️ Inpaint Crop & Stitch` 把改动限制在主体 mask 内（或直接把主体透明合成、只让 Edit 处理主体区域）——这是唯一能保证「白底不会以任何形式回流」的做法。
6. **对齐/漂移纪律（对首帧尤其重要）**：社区明确指出「如果你打算把 Qwen 的输出当作视频的下一帧/关键帧，这个偏移就很烦人」；对策是 **mod-112 尺寸 + latent reference chaining（ReferenceLatent）**，并避免用默认 `TextEncodeQwenImageEditPlus` 自动注入 ref latent。
   - [r/comfyui 1p2motm（raw latent 版对关键帧的意义）](https://www.reddit.com/r/comfyui/comments/1p2motm/qwen_image_edit_2509_raw_latent_version_workflow/)
   - [PSA: latent reference chaining 消除像素漂移](https://www.reddit.com/r/StableDiffusion/comments/1pv96a2/psa_eliminate_or_greatly_reduce_qwen_edit/)
   - [unzooming 修复工作流](https://www.reddit.com/r/StableDiffusion/comments/1o01e6i/totally_fixed_the_qwenimageedit2509_unzooming/)
7. **首末帧对（FLF2V）**：两张图要**同宽高比同尺寸**、主体比例与机位轴接近，否则过渡会不可信；提示词只描述「动作 + 运镜 + 约束」，不要重复描述外观（外观由图定义）。

---

## Sources

### Kept
| 来源 | 为什么留 |
|---|---|
| [docs.comfy.org — Qwen-Image-Edit 原生工作流](https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit) | 一手：官方接线、1MP Scale 节点、模型文件、架构双通道说明 |
| [docs.comfy.org — Qwen-Image-Edit-2511 原生工作流](https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit-2511) | 一手：2511 五项升级 + Lightning 4 步模型 |
| [QwenLM/Qwen-Image — 2509 md](https://github.com/qwenlm/qwen-image/blob/main/Qwen-Image-Edit-2509.md) | 一手：多图训练方式、person+person 组合、Native ControlNet、1–3 图最优 |
| [HF — Qwen-Image-Edit-2509](https://huggingface.co/Qwen/Qwen-Image-Edit-2509) / [2511](https://huggingface.co/Qwen/Qwen-Image-Edit-2511) | 一手：官方能力描述 + 2511 多图 prompt 示例 |
| [QwenLM/Qwen-Image issue #88](https://github.com/QwenLM/Qwen-Image/issues/88) | 一手 bug 报告：「改衣服颜色脸也变」——本报告 Q1 的核心症状证据 |
| [QwenLM/Qwen-Image issue #229](https://github.com/QwenLM/Qwen-Image/issues/229) | 一手：输入输出空间错位/zoom |
| [Comfy-Org/ComfyUI issue #9455](https://github.com/comfyanonymous/ComfyUI/issues/9455) | 一手：离线（GGUF/FP8）与在线 demo 行为差异=整图重绘 |
| [HF discussion Qwen-Image-Edit #11](https://huggingface.co/Qwen/Qwen-Image-Edit/discussions/11) | 一手：本地跑背景不一致 |
| [HF discussion 2511 #30](https://huggingface.co/Qwen/Qwen-Image-Edit-2511/discussions/30) | 一手：3 参考图 + true CFG 出 artifacts、Lightning 正常（重要实操警告） |
| [HF discussion 2511 #11](https://huggingface.co/Qwen/Qwen-Image-Edit-2511/discussions/11) | 一手：2511 人脸崩坏/重影 + diffusers 版本要求 |
| [HF — dx8152/Qwen-Edit-2509-Multiple-angles](https://huggingface.co/dx8152/Qwen-Edit-2509-Multiple-angles) | 一手：多角度一致性 LoRA + 作者自述一致性不稳/重训 |
| [HF — dx8152/Qwen-Image-Edit-2509-Fusion](https://huggingface.co/dx8152/Qwen-Image-Edit-2509-Fusion/blob/bacd972972e1006e4241284e10b0ce6ebdd087eb/README.md) | 一手：Fusion LoRA 用法与依赖节点 |
| [HF — InstantX/Qwen-Image-ControlNet-Inpainting](https://huggingface.co/InstantX/Qwen-Image-ControlNet-Inpainting) | 一手：Qwen ControlNet 训练细节与支持任务 |
| [ComfyUI-Inpaint-CropAndStitch](https://github.com/lquesada/ComfyUI-Inpaint-CropAndStitch) | 一手：①号对策的节点语义（裁剪/上下文/羽化/resize） |
| [comfy.org workflow — Relight Composited Product](https://comfy.org/workflows/templates-qwen_image_edit-crop_and_stitch-fusion-ae12a241076c/) | 一手：官方模板把 Qwen Edit + Crop&Stitch + Fusion 串起来 |
| [NVIDIA-GenAI-Creator-Toolkit — targeted inpainting](https://github.com/NVIDIA/NVIDIA-GenAI-Creator-Toolkit/blob/main/workflows/03-targeted-inpainting/README.md) | 第三方权威：edit 模型「即使只想小改也会动整张图」的独立确认 |
| [promptmaster.pro — 负词实验](https://blog.promptmaster.pro/posts/qwen-image-negative-prompts/) | 唯一做了 CFG 网格对照实验的负词证据 |
| [阿里云百炼 Qwen-Image-Edit 指南](https://help.aliyun.com/zh/model-studio/qwen-image-edit-guide) | 一手：1–3 图、尺寸 384–3072、Image 1/2/3 引用语法、输出比例由最后一张图决定 |
| [MonAI wiki — Qwen Reference Images](https://wiki.monai.art/en/tutorials/qwen-reference-images) | 社区教程：多图岗位分工写法（含 lighting blend 句子） |
| [B站 — Qwen Image Edit 使用要点总结](https://www.bilibili.com/opus/1103473928432517125) | 中文社区少见的**具体**操作要点：1MP、mod-112/56、遮罩护脸、简单拼接做多图 |
| [r/StableDiffusion — Prompt Guide Playbook](https://www.reddit.com/r/StableDiffusion/comments/1n1n81o/qwenimageedit_prompt_guide_the_complete_playbook/) | 社区提示词模板源（保背景/保身份/保构图三句） |
| [r/StableDiffusion — mod 112 消除 zoom](https://www.reddit.com/r/StableDiffusion/comments/1myr9al/use_a_multiple_of_112_to_get_rid_of_the_zoom/) | 分辨率纪律的原始出处 |
| [r/StableDiffusion — latent reference chaining PSA](https://www.reddit.com/r/StableDiffusion/comments/1pv96a2/psa_eliminate_or_greatly_reduce_qwen_edit/) | ②号对策的原始出处（含节点级做法） |
| [r/StableDiffusion — 1nxwrd1 背景被毁/残影](https://www.reddit.com/r/StableDiffusion/comments/1nxwrd1/qwen_2509_background_details_destroyed_and/) | 多图编辑时背景崩坏的一手症状 |
| [r/comfyui — Fusion LoRA FAQ](https://www.reddit.com/r/comfyui/comments/1ohf8f4/qwenedit2509_image_fusion_lora/) | Fusion vs Relight 的语义区分（做关键帧的关键决策） |
| [r/comfyui — 2511 色笔 inpaint 技巧](https://www.reddit.com/r/comfyui/comments/1resmvu/qwen_image_edit_2511_easy_inpainting_and_face/) | 零节点成本 mask 技巧 |
| [r/comfyui — mask & reference 工作流](https://www.reddit.com/r/comfyui/comments/1nyfqdv/qwen_image_editing_with_mask_referenceimproved/) | C 方案可复用工作流 |
| [r/StableDiffusion — 2511 Segment Inpaint](https://www.reddit.com/r/StableDiffusion/comments/1px2jl6/released_qwen_2511_edit_segment_inpaint_workflow/) | SAM2/SAM3 自动分割 + inpaint 的成型方案 |
| [MyAIForce — 100% Face Consistency](https://myaiforce.com/face-consistency-qwen-edit/) | 唯一给出完整「脸部特写+涂色块遮头+骨架」三图配方与 LoRA 0.2 / FaceDetailer / SeedVR2 参数的文章 |
| [Civitai — 多图合成一场景](https://civitai.com/articles/30625/combine-multiple-images-into-one-scene-with-qwen-image-edit-2509-in-comfyui) | 多图编号提示词的社区写法 |
| [Civitai/CivArchive — 2511 FP8 页评论](https://civarchive.com/models/2247803?modelVersionId=2532694) | ① Shift 语义、② resize 节点建议、③ 2511 一致性反例（都是很具体的实操） |
| [stable-diffusion-tutorials — 2511 工作流参数](https://www.stablediffusiontutorials.com/2025/12/qwen-image-edit-2511.html) | bf16/fp8/Lightning 三套 CFG+steps 组合 |
| [r/QwenImageGen — 光学修饰词实验](https://www.reddit.com/r/QwenImageGen/comments/1ooijqu/optical_modifiers_with_qwenimagefp8_lightning/) | 电影感词为什么会/不会生效的唯一对照实验 |
| [Seedance — 首帧/尾帧指南](https://seedance-2ai.org/zh/blog/ai-video-first-last-frame-guide) | 首帧设计四支柱、运动空间、隐含物理线索 |
| [4sAPI — 图生视频实战](https://blog.4sapi.com/zh/blog/image-to-video-brand-commercial-guide) | 「主体占 30%–60%」「首帧图条件清单」的具体数值 |
| [SegmentFault — 产品图去背景与风格统一](https://segmentfault.com/a/1190000048026569) | 白底图处理的五步流程 + 白边/悬浮阴影检查项 |
| [Easton Dev](https://eastondev.com/blog/en/posts/ai/20260828-comfyui-regional-prompting-multi-subject/) / [Apatero Regional Prompter](https://apatero.com/blog/regional-prompter-comfyui-complete-guide-2025) | 区域提示的真实适用边界（用于判定 F 方案不适用） |
| [thediffusionart/Next Diffusion/Nano 等教程站] | 仅用作交叉验证模型下载与节点名，未采纳其结论性数字 |

### Dropped
- **CSDN/GitCode「保姆级教程」系列的绝大多数文章**（如带 `Character Anchor Module（CAM）`、`92% 案例保持五官比例`、`96.4% 角色辨识度` 的那批）：无实验方法、无出处、数字前后矛盾，判定为 AI 生成内容农场，仅保留其**可验证的操作性描述**（denoise 区间、mask 步骤）。
- `framerc.cn` / `aileading.cn` 等转载站：内容为二次搬运，仅在无法找到一手来源时作为旁证（2511 双人合照对比结论）。
- `facebook.com/groups/comfyuiph` 帖（"Qwen Image Edit ID Preservation without IPAdapters"）：登录墙，无法核验正文，仅取搜索摘要中的观点并标注为弱证据。
- `myaiforce.com/pulid-vs-instantid-vs-faceid`：与 Qwen 无关（纯 SDXL 对比），仅用于说明这些技术栈的底座。
- MonAI / pixeldojo / jaiportal / runcomfy 等平台页：SEO 导向，参数与官方重复度高。
- Reddit 原帖正文：**抓取被拦截，无法作为一手证据**（保留 URL 与搜索摘要）。

---

## Gaps
1. **Reddit / HF 正文无法抓取** → Q1/Q2/Q3 中所有 Reddit 论据是**搜索摘要转述**，未能看到完整评论串与顶踩数。建议：在可访问 Reddit 的环境（或旧版镜像）逐帖复核 1n1n81o、1muiozf、1myr9al、1pv96a2、1otityx、1nrfd2j 六个帖的正文与高赞回复。
2. **「定妆照（character look-test / white-bg 全身转面图）」这个具体输入类型没有直接命中的社区案例**。Q1 的结论是从「白底产品图/影棚图」+「全局重绘/漂移」两类证据推演出来的。建议做一次自测对照：同一张白底定妆照，(a) 全图直喂 + 提示词保背景，(b) 抠图后合成再 Edit，(c) mask 主体 + Crop&Stitch，(d) 脸部特写当参考 —— 记录四种输出的白底残留率与脸相似度。
3. **「参考图中有白底」是否比「参考图有复杂背景」更容易带偏**：没有找到对照数据。这是一个可以在 1 小时内自证的小实验（同一人物，白底 vs 实景背景各 10 次 seed，统计输出背景为白底的比例）。
4. **脸部像素数与相似度的定量关系**：无社区实验。建议自测：同一参考图，输出 canvas 从 1MP 到 4MP、主体占比 20%/40%/60%，用 face-similarity（InsightFace/ArcFace cosine）量化。
5. **Qwen 是否有可用的 regional attention 补丁**：本次未找到，但也没有穷尽（未检索 `Qwen` + `attention couple` / `RegionalPrompts` 的 issue 与 PR）。建议直接查 `ComfyUI-RegionalPrompt`、`attention-couple-comfyui` 的 issue 列表里是否有人问过 Qwen/MMDiT。
6. **胶片感（grain/halation/LUT）在 Qwen Edit 上的可控性**：证据不足，暂按「后期做」的建议处理。
7. **2511 是否真的优于 2509**：存在直接冲突的证据（官方 + 多数第三方 vs CivArchive 两条负面评论 + HF #11 崩坏报告）。**不能凭文档定版**，必须在自己的参考图上 A/B。
8. **`D:\workspace\Weaveora` 仓库内未做本地检索**：本次为纯外部调研，未读取项目里已有的 Qwen 工作流/提示词文件；如项目已有既定 prompt 模板，Q1/Q4 的结论应与其对齐后再落地。

---

## Supervisor coordination
无需决策或阻塞项，已按指派范围完成调研并写入指定路径。
