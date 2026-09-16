# Research: Qwen-Image-Edit（2509 / 2511）官方推荐用法与参数

> 结论基于一手材料：Qwen 官方仓库/HF 模型卡、ComfyUI 官方文档、ComfyUI 官方 workflow_templates 仓库的模板 JSON（逐节点解析）、以及 ComfyUI 核心源码（`comfy_extras/nodes_qwen.py`、`comfy/samplers.py`、`comfy/conds.py`、`comfy/ldm/qwen_image/model.py`、`comfy/model_base.py`）。
> 检索日期：本次会话。所有链接为可直接访问的原始来源。

---

## Summary

1. **参考图必须"双线接线"**：三套官方模板（Aug-Edit / 2509 / 2511）**都是同时**走 (A) 参考图 → 缩放 → `VAEEncode` → `KSampler.latent_image`，**和** (B) 同一张图 → `TextEncodeQwenImageEditPlus` 的 `image1` + `vae`（内部生成 `reference_latents`）。**官方模板里没有任何 `EmptySD3LatentImage`**。所谓"被参考图带偏"的根因就是 (A) 决定了输出画布尺寸/网格，而 (B) 负责外观语义；两者网格不一致时才会出现漂移/裁切/缩放。
2. 官方参数口径：Qwen 侧 = 40 steps + `true_cfg_scale` 4.0（2511/2509），ComfyUI 模板侧 = `euler` + `simple` + `denoise 1.0` + `ModelSamplingAuraFlow shift 3.1`（2511）/`3.0`（2509）；低步数走 Lightning LoRA 时 4 steps / CFG 1.0。
3. `ConditioningSetAreaPercentage` 包住带参考图的 conditioning → `IndexError` **是核心限制（core 不兼容），不是接线错误**：`CONDList`（reference latents 的载体）在 `process_cond()` 里完全忽略 `area`，而 `get_area_and_mult()` 却会把目标 latent 裁到区域尺寸，同时 `cond_cat()` 用 `cond.concat()` 合并 batch 却不校验 `can_concat()`。

---

## Findings

### 1. 官方推荐的 steps / cfg / sampler / scheduler / shift / denoise

**1.1 Qwen 官方（diffusers `QwenImageEditPlusPipeline`）**

| 模型 | num_inference_steps | true_cfg_scale | guidance_scale | negative_prompt |
|---|---|---|---|---|
| Qwen-Image-Edit-2511 | **40** | **4.0** | **1.0** | `" "` |
| Qwen-Image-Edit-2509 | **40** | **4.0** | **1.0** | `" "` |
| Qwen-Image-Edit（2025-08） | **50** | **4.0** | — | `" "` |
| Qwen-Image（T2I 基座） | **50** | **4.0** | — | — |

来源：QwenLM/Qwen-Image README（2511 段落 `num_inference_steps: 40, true_cfg_scale: 4.0, guidance_scale: 1.0, negative_prompt: " "`）<https://github.com/QwenLM/Qwen-Image> ；HF 模型卡同款代码 <https://huggingface.co/Qwen/Qwen-Image-Edit-2511/blob/main/README.md> ；2509 段落同参（40/4.0/1.0）<https://huggingface.co/Qwen/Qwen-Image-Edit-2509> 。
注意：`guidance_scale=1.0` 是"关闭蒸馏式 guidance"，真正生效的 CFG 是 `true_cfg_scale=4.0`。官方 2511/2509 落点 = **40 steps / CFG 4.0**。

**1.2 ComfyUI 官方模板（权威、可直接抄）**

模板自带的 `MarkdownNote` 表格（逐字）：

- `image_qwen_image_edit_2511.json`（node 157）：`Steps: Qwen 40 / Comfy 20`，`CFG: Qwen 4.0 / Comfy 4.0`
- `image_qwen_image_edit_2509.json`（node 444）：`| Parameters | Qwen Team | Comfy Original | with 4steps LoRA | | Steps | 50 | 20 | 4 | | CFG | 4.0 | 2.5 | 1.0 |`
- `image_qwen_image_edit.json`（Aug 版）：`Offical 50/4.0 | comfy 20/2.5 | fp8_e4m3fn + 4steps LoRA 4/1.0`

模板实际节点参数（逐节点解析 JSON 得到）：

| 模板 | sampler | scheduler | denoise | shift（ModelSamplingAuraFlow） | CFGNorm | KSampler 默认 steps/CFG | 加速 LoRA |
|---|---|---|---|---|---|---|---|
| `image_qwen_image_edit_2511.json` | euler | simple | 1 | **3.1** | 存在（strength 1, skip False） | **40 / 4**（switch 默认 off；开 turbo=4/1） | `Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16` |
| `image_qwen_image_edit_2509.json` | euler | simple | 1 | **3.0** | 存在 | 4 / 1（模板默认开 Lightning） | `Qwen-Image-Edit-2509-Lightning-4steps-V1.0-bf16` |
| `image_qwen_image_edit.json`（Aug） | euler | simple | 1 | 3.0 | 存在 | 4 / 1（默认走 Lightning 分支） | `Qwen-Image-Lightning-4steps-V1.0` |

来源：`https://raw.githubusercontent.com/Comfy-Org/workflow_templates/main/templates/image_qwen_image_edit_2511.json`、`.../image_qwen_image_edit_2509.json`、`.../image_qwen_image_edit.json`。

**推荐区间（由上述锚点外推，实测口径）**
- steps：**40**（质量档，官方 Qwen 值）；**20**（Comfy 折中档）；**4**（Lightning LoRA 档，必须 CFG 1.0）。
- CFG（=Comfy 的 `cfg`，对应 Qwen 的 `true_cfg_scale`）：**4.0** 主档；2.5 折中；**1.0 仅配 Lightning LoRA**。经验区间 2.5–4.5；>5 容易过饱和/失真。
- sampler / scheduler：**euler + simple**（三套官方模板完全一致；2509 HF 讨论区维护者/作者亦答 euler + simple，<https://huggingface.co/Qwen/Qwen-Image-Edit-2511/discussions/10>）。
- shift：**3.0（2509）/ 3.1（2511）**，均由 `ModelSamplingAuraFlow` 设置；换模型族不要照搬 Flux 的 1.15/3.0。
- denoise：**1.0**（官方模板固定值；Edit 任务不要降 denoise，降 denoise 会退化成"半保留原图 + 网格不匹配"）。

**1.3 「2511 相对 2509 的变化"**

模型侧（官方 2511 发布说明，逐字要点）：mitigate image drift（减轻漂移）、improved character consistency、**Multi-Person Consistency**（多人合照高保真融合）、**Integrated LoRA capabilities**（社区 LoRA 直接内建）、enhanced industrial design generation、strengthened geometric reasoning（可直接生成辅助构造线）。
来源：<https://qwen.ai/blog?id=qwen-image-edit-2511>、<https://huggingface.co/Qwen/Qwen-Image-Edit-2511>、<https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit-2511>。

ComfyUI 侧（模板 diff 实证）：
1. **shift 3.0 → 3.1**（`ModelSamplingAuraFlow` 唯一值变化）。
2. **新增 `FluxKontextMultiReferenceLatentMethod` 节点 ×2**，接在 positive/negative 的 `TextEncodeQwenImageEditPlus` 之后，`reference_latents_method = "index_timestep_zero"`；2509 / Aug 模板**没有**这个节点。这是 2511 模板最重要的结构差异（影响参考图 token 的 RoPE 索引与 timestep 拼接）。
   - 取值域（官方文档）：`offset` / `index` / `uxo/uno` / `index_timestep_zero`；`uxo|uso` 会被规范化为 `uxo`。<https://docs.comfy.org/built-in-nodes/FluxKontextMultiReferenceLatentMethod>
3. 默认档位不同：2511 模板默认走 **40/4** 全步数（turbo 开关默认 false），2509 模板默认走 **4/1** Lightning。
4. 权重文件不同：`qwen_image_edit_2511_fp8mixed.safetensors`（2511）vs `qwen_image_edit_2509_fp8_e4m3fn.safetensors`。
5. 2511 模板新增提示 Note：**"The 'Edit Model Reference Method' nodes above are not needed if you use Comfy files, but may be needed if you use repackaged ones from other people."**（用 Comfy-Org 官方权重可直连，用第三方重打包权重才需要这层）。
6. 与 2511 同时发布的还有 `Qwen-Image-Layered`（分层 RGBA）。<https://blog.comfy.org/p/qwen-image-edit-2511-and-qwen-image>

> 不确定项（medium confidence）：ComfyUI issue #10849 指出 2509 模板里存在一个 **"Raw latent version"** 分组，其 `ReferenceLatent（Subgraph）` 有一个输出未接线，Comfy-Org 回复 "this is a real bug — fixing now"。我在当前 `main` 分支的 2509 模板 JSON 中 **找不到** 任何 `ReferenceLatent` 节点，推测该分组随后被移除或重做。<https://github.com/Comfy-Org/ComfyUI/issues/10849>

---

### 2. 分辨率口径

**2.1「总像素 ≈1MP、长边 ≈1328–1536、尺寸为 8/16 倍数」——基本成立，但数字要按来源区分**

- **Qwen 官方训练桶（T2I 基座 README 的 `aspect_ratios` 字典，逐字）**：
  `1:1 → 1328×1328`、`16:9 → 1664×928`、`9:16 → 928×1664`、`4:3 → 1472×1104`、`3:4 → 1104×1472`、`3:2 → 1584×1056`、`2:3 → 1056×1584`。
  → 每个都是 **≈1.0–1.55MP**、**全部为 16 的倍数**、**长边 928–1664**。所以"长边 ≈1328–1536"只对 1:1/4:3 类成立，**16:9 的长边是 1664**。
  来源：<https://huggingface.co/Qwen/Qwen-Image/blob/main/README.md>、<https://github.com/QwenLM/Qwen-Image>
- **ComfyUI 官方口径（Edit 教程逐字）**："The `Scale Image to Total Pixels` node will scale your input image to a total of one million pixels … mainly to avoid quality loss in output images caused by oversized input images such as 2048x2048"。
  来源：<https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit>
  → **~1MP 是 ComfyUI 的官方推荐口径**。
- **编辑类模板实际用的缩放器不是 `ImageScaleToTotalPixels`，而是 `FluxKontextImageScale`**（2509/2511 模板均如此），其内部 `PREFERRED_KONTEXT_RESOLUTIONS` 是 17 个固定桶：`(672,1568) (688,1504) (720,1456) (752,1392) (800,1328) (832,1248) (880,1184) (944,1104) (1024,1024) (1104,944) (1184,880) (1248,832) (1328,800) (1392,752) (1456,720) (1504,688) (1568,672)`——**每个都 ≈1.05MP、每个都是 16 的倍数**，按输入宽高比就近吸附。
  来源：<https://github.com/Comfy-Org/ComfyUI/blob/285a9894/comfy_extras/nodes_flux.py>、<https://docs.comfy.org/built-in-nodes/FluxKontextImageScale>
- **倍数要求（分层解释，实测口径）**：
  - **8 的倍数**是硬约束（Qwen VAE 空间压缩 8×；ComfyUI 源码里 `TextEncodeQwenImageEditPlus` 明确 `width = round(x*scale/8.0)*8`）。
  - **16 的倍数**是 DiT `patch_size=2` 的建议值（`comfy/ldm/qwen_image/model.py::process_img` 用 `(h + patch_size//2)//patch_size` 计算 token 网格；非 16 倍数会触发 `pad_to_patch_size`，是漂移来源之一）。
  - **32 的倍数**是 Qwen 自家 diffusers pipeline 的 resize 口径（社区引述官方说明："不超过 1024×1024，32 倍数"；LockPixel 也按 32px 网格 padding）。<https://github.com/QwenLM/Qwen-Image/issues/160>、<https://github.com/tori29umai0123/ComfyUI-QwenImageEdit-LockPixel>

**2.2 1280×704（0.90MP）是否在推荐区间？**

- 数值上：1280/16=80、704/16=44 → **是 16 的倍数**；但 **704 不是 32 的倍数**（704/32=22，其实是 32 的倍数！704 = 32×22 ✅；1280 = 32×40 ✅）。→ **两者都是 32 的倍数**（我重新核算：1280=32×40，704=32×22），所以网格对齐没问题。
- 像素数：901,120 px = **0.901 MP**，比官方 ~1.0–1.05MP 预算**低约 12%**，比 Kontext 桶（1,053,696 px）低约 14%。宽高比 20:11 ≈ 1.818，**不落在任何官方桶里**（最接近的 Kontext 桶是 1392×752 ≈ 1.851）。
- **结论**：
  - 可以跑，网格安全（32 的倍数），不属于"非法尺寸"；
  - 但**不在官方推荐桶内**，属于"低于 1MP 预算"的尺寸，细节/文字渲染会略软于 1.0–1.05MP；
  - 如果这条线经过官方模板的 `FluxKontextImageScale`，**它会被吸附到 ~1.05MP 的 Kontext 桶**（20:11 → 最近的 1392×752），输出尺寸与你填的 1280×704 **不一致**。想严格锁 1280×704，必须绕开 `FluxKontextImageScale`（用 `ImageScale`/`ImageScaleToTotalPixels` 或直连 VAEEncode）。
- **Edit 类任务推荐尺寸**：把**输出画布**设成"参考图缩放后的尺寸"，即 ~1.0–1.05MP、16/32 的倍数；1:1 用 1024×1024 或 1328×1328，16:9 用 1664×928（官方桶）或 Kontext 桶 1328×800/1392×752。口诀：**reference latent 的网格 == sampler latent 的网格**。
  MonAI 的实测口径（"输入 768×960 → 输出 896×1160，因为 Qwen 按 4:5 就近吸附到 ~1MP 且可被 8 整除的尺寸"）与此一致：<https://wiki.monai.art/en/models/qwen_image_edit>

---

### 3. 【最关键】参考图到底怎么接线

**3.1 官方模板的确切节点连法（`image_qwen_image_edit_2511.json`，逐节点解析）**

```
LoadImage(41, leather_sofa.png)  ──┐
LoadImage(83, texture_fur.png)   ──┤ (subgraph)"Image Edit (Qwen-Image 2511)"
                                   │   image1, image2, image3
UNETLoader(161) → ModelSamplingAuraFlow(145, shift=3.1) → CFGNorm(152) ──┬→ SwitchModel(163) on_false
                                                                          └→ LoraLoaderModelOnly(153, 2511-Lightning-4steps) → SwitchModel on_true
PrimitiveBoolean(168 "Enable 4steps LoRA?", false) → 163/164/167 switches
PrimitiveInt(166=40) → SwitchSteps(167) ; PrimitiveFloat(154=4) → SwitchCFG(164)

[image1] → FluxKontextImageScale(160) ──┬─────────────────────────────→ ① TextEncodeQwenImageEditPlus(151 "Positive").image1
                                        ├─────────────────────────────→ ② TextEncodeQwenImageEditPlus(149, prompt="").image1   ← 负词节点
                                        └─────────────────────────────→ ③ VAEEncode(156).pixels
[image2] ───────────────────────────────┴→ ①/② 的 image2
[image3] ───────────────────────────────┴→ ①/② 的 image3

CLIPLoader(162) ──→ ①/② 的 clip
VAELoader(146)  ──→ ①/② 的 vae（★ 关键）以及 ③ VAEEncode.vae、VAEDecode(158).vae

①(151) → FluxKontextMultiReferenceLatentMethod(148, "index_timestep_zero") → KSampler(169).positive
②(149) → FluxKontextMultiReferenceLatentMethod(147, "index_timestep_zero") → KSampler(169).negative
③ VAEEncode(156) → KSampler(169).latent_image          ★ 这就是"输出画布尺寸"的来源
KSampler(169): euler / simple / steps 40 / cfg 4 / denoise 1
KSampler → VAEDecode(158) → SaveImage(9)
```

**答案：官方同时用 (A) 和 (B)，一个都不少。**
- **(A)** `FluxKontextImageScale → VAEEncode → KSampler.latent_image`：决定**输出画布尺寸与 latent 网格**。
- **(B)** 同一张图 → `TextEncodeQwenImageEditPlus` 的 `image1`，且 **`vae` 必须接上**（源码里 `if vae is not None: ref_latents.append(vae.encode(...))`，否则只进 VL、不进 reference latents）。
- 外加 `FluxKontextMultiReferenceLatentMethod("index_timestep_zero")` 修饰 positve/negative conditioning（2511）。
- **模板中不存在 `EmptySD3LatentImage`**（2509 / 2511 / Aug 三套模板均已 `findText` 验证为 no match）。

同一结构在 2509 模板（node 117/88/3/110/111）与 Aug 模板（node 88 VAEEncode → KSampler node 3；`TextEncodeQwenImageEdit` node 76/77 的 `vae` 已接）完全一致。

**3.2 (A) 与 (B) 对"构图被参考图带偏"的影响差别**

- **(A) 是"尺寸/构图被带偏"的直接原因，而且是唯一原因。** `KSampler.denoise = 1.0` + `ModelSamplingAuraFlow`（flow matching）下，`ModelSamplingDiscreteFlow.noise_scaling(sigma, noise, latent)` 在 σ=1 时返回纯噪声 → **latent_image 的像素内容在 denoise=1.0 时被完全丢弃，只有它的 shape 生效**。（源码：`comfy/model_sampling.py` 的 flow noise_scaling；模板 denoise 固定 1.0。）
  所以：**你在 `latent_image` 里放多大的 latent，输出就是多大**。参考图经过 `FluxKontextImageScale` 被吸附到 Kontext 桶（~1.05MP，比例被改写），再 VAEEncode 进 KSampler → 输出画布 = 参考图吸附后的尺寸/比例。这正是"输出被参考图带偏/裁切/缩放"的根因。
  社区/官方 bug 记录一致：ComfyUI #9481「TextEncodeQwenImageEdit 强制 ~1MP，导致 latent 与 KSampler latent 尺寸不同时会出现非预期 zoom」；#10198「1328×1328 的 conditioning image 被内部压到 1024×1024，导致输出与 conditioning 不对齐」。<https://github.com/Comfy-Org/ComfyUI/issues/9481>、<https://github.com/comfyanonymous/ComfyUI/issues/10198>
- **(B) 是"外观/身份被参考图带偏"的原因，也是我们要的效果来源。** `reference_latents` 会被 `QwenImage.extra_conds` 包装成 `comfy.conds.CONDList` → `out['ref_latents']`，在 DiT 里按 `ref_latents_method` 把参考图的 latent token 直接拼到 image token 序列上（`comfy/ldm/qwen_image/model.py::_forward`：`hidden_states = torch.cat([hidden_states, kontext], dim=1)`，并记录 `ref_num_tokens` / `timestep_zero_index`）。参考图越多、分辨率越大，注入越强，主体越容易被"钉"住。
- **只做 (A) 不做 (B)**：外观/身份一致性大幅下降（只剩 Qwen2.5-VL 的语义描述），且**不会有警告**——这是最容易踩的坑（VAE 忘了接到 TextEncode 上）。
- **只做 (B) 不做 (A)**（用 `EmptySD3LatentImage` 当 latent_image）：输出画布由你指定，看起来"更自由"，但目标网格与参考网格不一致 → **像素漂移/轻微 zoom/边缘错位**。ComfyUI #9481 与 LockPixel 的核心结论就是这条：**reference latent grid == sampler latent grid**。<https://github.com/tori29umai0123/ComfyUI-QwenImageEdit-LockPixel>
- **正确姿势**：让 (A) 的 latent 和 (B) 的参考 latent **来自同一张、已缩放好的图**，并把 `latent_image` 的尺寸当作"我想要输出多大"来主动设定（不要指望 `EmptySD3LatentImage` 自动对齐）。

---

### 4. `TextEncodeQwenImageEditPlus` 的行为细节

源码（ComfyUI master，`comfy_extras/nodes_qwen.py`，class `TextEncodeQwenImageEditPlus`）：

1. **VL 分支会缩放到固定面积 384×384（≈147k px），保持宽高比、不裁切**（`total = int(384 * 384)` + `common_upscale(..., "area", "disabled")`）。
2. **VAE/reference-latent 分支会缩放到固定面积 1024×1024（=1MP），并把宽高 round 到 8 的倍数**：
   `width = round(x*scale/8.0)*8 ; height = round(y*scale/8.0)*8`，再 `vae.encode(...)`。
   → **是的，它内部一定把参考图缩到 ~1MP**（这是 #10198 / #9481 的争议点，也是 LockPixel 之类修复节点存在的原因）。
   官方文档同口径（逐字）："Images are scaled to a target area of 384x384 pixels (aspect ratio preserved) for vision-language processing, and to dimensions divisible by 8 (with a target area of 1024x1024 pixels) for VAE encoding."
   <https://docs.comfy.org/built-in-nodes/TextEncodeQwenImageEditPlus>
3. **image1/image2/image3 严格按输入顺序对应提示词里的 "Picture 1/2/3"**：源码里
   `image_prompt += "Picture {}: <|vision_start|><|image_pad|><|vision_end|>".format(i + 1)`，
   按 `i = 0,1,2` 遍历 `[image1, image2, image3]`，最后 `clip.tokenize(image_prompt + prompt, images=images_vl, llama_template=llama_template)`。
   → 顺序 = 接线顺序，**提示词里要说 "image 1 / image 2 / image 3"（或 "Picture 1/2/3"）**。
   ⚠️ 注意 `i+1` 用的是**槽位序号而不是"已填图片的序号"**：若 `image1` 悬空而只接 `image2`，模型侧仍会看到 "Picture 2"，且它只拿到 1 张 VL 图 → 位置错配。**从 image1 开始连续接。**
4. **`vae` 可选但等价于开关**：不接 vae → 只有 VL 语义，**没有 reference latents**；接了 vae → `conditioning_set_values(..., {"reference_latents": ref_latents}, append=True)`。
5. **负词节点：官方用的是 `TextEncodeQwenImageEditPlus`（prompt 留空 + 同样接 image1/2/3 + 接 vae），不是普通 `CLIPTextEncode`。**
   证据：2511 模板 node 149 = `TextEncodeQwenImageEditPlus`，`widgets_values: [""]`，接 clip/vae/image1/image2/image3，输出到 negative；2509 模板 node 110 同理；Aug 模板 node 77 = `TextEncodeQwenImageEdit`（另一个单图版节点）`widgets_values: [""]`，也接了 vae。
   → **用普通 `CLIPTextEncode` 做负词与官方不一致**：负词分支会缺 `ref_latents`，`cond_cat()` 合并 batch 时正/负两支的 cond key 集合不同，是潜在的 batch/cond 不一致来源。实践中很多人这么用也能出图，但**若出现诡异报错或结果异常，第一个该改的就是这里**。
6. 官方 llama_template（system prompt）是 Qwen 官方 `prompt_template_encode` 的同款文本（"Describe the key features of the input image (color, shape, size, texture, objects, background), then explain how the user's text instruction should alter or modify the image…"），与 diffusers/官方仓库一致。<https://github.com/tsiendragon/qwen-image-finetune/blob/main/docs/spec/models/qwen_image_edit_plus.md>
7. ⚠️ 上游已知问题：`TextEncodeQwenImageEditPlus` 的 1MP 强缩放会让 ControlNet 条件图（如 depth）与输出不对齐（#10198）；官方至今未提供"不缩放"开关。社区用 `Comfyui-QwenEditUtils` 的 `TextEncodeQwenImageEditPlusAdvance_lrzjason`（分离 VL resize 与 latent resize，可选 target size / crop method）绕过。

---

### 5. 多参考图（多角色）与区域条件

**5.1 官方支持：同一次采样注入多张参考图 —— 支持，官方就是这么设计的**

- Qwen 官方：2509 起原生多图编辑，"supports various combinations such as person+person, person+product, person+scene. **Optimal performance is currently achieved with 1 to 3 input images.**"
  <https://github.com/QwenLM/Qwen-Image/blob/main/Qwen-Image-Edit-2509.md>
- 2511 官方增强项直接写了 **"Multi-Person Consistency: High-fidelity fusion of multiple person images into coherent group shots"**，ComfyUI 博客亦写 "Significantly improved character consistency, especially in multi-person group photos"。实测可一次融合 **最多 3 个角色**。
  <https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit-2511>、<https://blog.comfy.org/p/qwen-image-edit-2511-and-qwen-image>、<https://wiki.monai.art/en/models/qwen_image_edit>
- ComfyUI 节点侧：`TextEncodeQwenImageEditPlus` 支持 3 张；`ReferenceLatent` 官方文档明确 "you can chain multiple ReferenceLatent nodes to set multiple reference images" —— 即 `reference_latents` 本来就是**列表（CONDList）**，多张同时生效。
  <https://docs.comfy.org/built-in-nodes/ReferenceLatent>
- **"各自定位"**：模型侧不用坐标，靠三件事——① 提示词里的 "image 1 / image 2 / image 3" 绑定；② 参考图在序列里的拼接顺序；③ `FluxKontextMultiReferenceLatentMethod` 的 `reference_latents_method`（`index` / `index_timestep_zero` / `offset` / `uxo`）决定参考 token 的 RoPE index 与 timestep 拼接方式。这正是"图 1 的长边比例决定输出比例"的机制（MonAI 明确写了 **"the proportion of the first image will dictate the proportion of the final image"**）。
- 也有可选的社区强化节点（非官方，仅供参考）：`lrzjason/Comfyui-QwenEditUtils`（每图独立 target size / vl_resize / 权重）、`ComfyUI_VNCCS` 的 `NCCS_QWEN_Encoder`（每图权重 0–1、可 `use_ref` 关闭某图的 latent 只保留 VL 影响）、`LockPixel`（锁定 latent 网格）。<https://github.com/lrzjason/Comfyui-QwenEditUtils>、<https://github.com/AHEKOT/ComfyUI_VNCCS/blob/main/nodes/vnccs_qwen_encoder.py>

**5.2 「ConditioningSetArea + Edit 参考图」有没有官方支持？——没有**

- ComfyUI 官方文档、三套官方模板、Qwen 官方仓库，**都没有**任何 `ConditioningSetArea` / `ConditioningSetAreaPercentage` 与 `TextEncodeQwenImageEditPlus` / `ReferenceLatent` 组合的示例。
- 官方模板里 2509/2511 的"多角色"就是"3 张图 + 自然语言描述位置"，**没有区域条件**。

**5.3 为什么 `ConditioningSetAreaPercentage` 包住带参考图的 conditioning 会 `IndexError: tuple index out of range`？**

**结论：是核心实现层面的已知不兼容（core limitation），不是接线错误。**

逐层机制（源码实证）：

1. `ConditioningSetAreaPercentage` 只是往 conditioning dict 里写 `area` / `strength` / `set_area_to_bounds`。
   源码：`comfy_extras/nodes_conditioning.py` / `nodes.py`，历史上是 `n[1]['area'] = (height // 8, width // 8, y // 8, x // 8)`，后重构为 `node_helpers.conditioning_set_values(conditioning, {"area": (...), "strength": ..., "set_area_to_bounds": False})`。
   <https://github.com/pengjianhong/ComfyUI/commit/80bda6c16393e7af7934e791b1babedb2cf4896a>
2. 采样侧 `comfy/samplers.py::get_area_and_mult()` 拿到 `area` 后会做两件事：
   - **把目标 latent 裁到区域尺寸**：`area[i] = min(input_x.shape[i+2] - area[len(dims)+i], area[i])`，`input_x = input_x.narrow(i+2, ...)`；
   - 对 batch 里**每一个** `model_conds[k]` 调用 `process_cond(batch_size=x_in.shape[0], area=area)`。
3. **但 `reference_latents` 的载体 `comfy.conds.CONDList` 完全忽略 `area`**：
   ```python
   class CONDList(CONDRegular):
       def process_cond(self, batch_size, **kwargs):   # ← area 被 **kwargs 吃掉
           out = []
           for c in self.cond:
               out.append(comfy.utils.repeat_to_batch_size(c, batch_size))
           return self._copy_with(out)
   ```
   → 参考图 latent **保持整幅画布尺寸**，目标 latent 变成"只有区域大小"，两者网格不再一致。
4. `_calc_cond_batch()` 随后 `c = cond_cat(c_list)`，而 `cond_cat()` **没有先调 `can_concat()` 就直接 `conds[0].concat(conds[1:])`**；`CONDList.concat()` 的实现是逐元素索引：
   ```python
   def concat(self, others):
       out = []
       for i in range(len(self.cond)):
           o = [self.cond[i]]
           for x in others:
               o.append(x.cond[i])     # ← 不同 chunk 的参考图数量不一致时直接 IndexError
           out.append(torch.cat(o))
       return out
   ```
   而区域路径会经 `create_cond_with_same_area_if_none()` 复制/新增 cond 条目、产生"有 area / 无 area"混合的 batch chunk，正是让正负两支（或不同 area 的 chunk）的 `ref_latents` 数量/形状不一致的场景。
   注意：`CONDList.can_concat()` 存在且会返回 False，但 `cond_cat()` 从不调用它 —— **所以本应被安全降级的路径变成了硬报错**。
5. 同时 `comfy/model_base.py::QwenImage.extra_conds` / `extra_conds_shapes` 与 `comfy/ldm/qwen_image/model.py::_forward` 里的 `timestep_zero_index = num_embeds`、`transformer_options["reference_image_num_tokens"]` 都是**基于未裁切的目标 token 数**算出来的；目标 latent 被裁后，Qwen-Image 的 `process_img()` 会用 `(h + patch_size//2)//patch_size` 重算 token 网格，`h_len * w_len` 与实际 padded token 数可能不再相等，`img_ids` 与 `kontext_ids` 的 `torch.cat` 以及最后的 `hidden_states[:, :num_embeds].view(orig_shape...)` 都会失配 —— 这是报 `IndexError: tuple index out of range`（`torch.Size` 是 tuple 子类，越界即报此消息）的直接来源。

**旁证**：另一个同样使用 `reference_latents` + 区域条件的 MMDiT 模型（circlestone-labs/Anima）在 HF discussion 里被明确记录：`Conditioning (Set Area with Percentage)` + `Conditioning (Combine)` → **KSampler 报 `IndexError: tuple index out of range`**，与 Qwen 的失败模式一致。
<https://huggingface.co/circlestone-labs/Anima/discussions/76>

**判责结论（可直接写进 issue）**：
- ❌ **不是你的接线错误**：节点连法没有"正确版本"可用；`TextEncodeQwenImageEditPlus` + `ConditioningSetAreaPercentage` 在 core 层就没有被支持/测试过。
- ✅ **是 core limitation**：`comfy/conds.py::CONDList.process_cond` 不透传 `area`，`comfy/samplers.py::cond_cat` 不做 `can_concat` 校验。
- 需要的修复方向（供提 issue）：① `CONDList` 支持按 area 裁切参考 latent；或 ② `cond_cat` 在 concat 前调用 `can_concat()` 并走 `create_cond_with_same_area_if_none` 类的对齐；或 ③ 文档层面在 conditioning 节点上直接禁止 `ref_latents` + `area` 组合。
- 可行的替代方案：**不要用 area 做多角色分区**。改用 (i) 一次注入 2–3 张参考图 + 提示词里写 "image 1/2/3" 的位置关系（官方做法）；(ii) 分两步生成再合成；(iii) 用社区节点做 mask 级控制后再局部重绘。

---

## Sources

**Kept**
- ComfyUI 官方文档：Qwen-Image-Edit-2511 教程 <https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit-2511> — 官方模板说明、模型清单、2511 增强项。
- ComfyUI 官方文档：Qwen-Image-Edit 教程 <https://docs.comfy.org/tutorials/image/qwen/qwen-image-edit> — "Scale Image to Total Pixels … one million pixels" 官方 1MP 口径。
- ComfyUI 官方文档：`TextEncodeQwenImageEditPlus` <https://docs.comfy.org/built-in-nodes/TextEncodeQwenImageEditPlus> — inputs 列表 + 384²/1MP 缩放口径，直接回答 Q4。
- ComfyUI 官方文档：`FluxKontextMultiReferenceLatentMethod` <https://docs.comfy.org/built-in-nodes/FluxKontextMultiReferenceLatentMethod> — 取值域，2511 模板新增节点的语义。
- ComfyUI 官方文档：`ReferenceLatent` <https://docs.comfy.org/built-in-nodes/ReferenceLatent> — "可链式设置多张参考图"官方表述。
- ComfyUI 官方文档：`FluxKontextImageScale` <https://docs.comfy.org/built-in-nodes/FluxKontextImageScale> — 按宽高比吸附到训练尺寸。
- ComfyUI 官方模板 JSON（逐节点解析）：`image_qwen_image_edit_2511.json` / `image_qwen_image_edit_2509.json` / `image_qwen_image_edit.json`，`https://raw.githubusercontent.com/Comfy-Org/workflow_templates/main/templates/…` — **Q3 的权威证据**（latent_image 由 VAEEncode 供给、vae 接到 TextEncode、无 EmptySD3LatentImage）。
- ComfyUI 核心源码：`comfy_extras/nodes_qwen.py`（`comfy/../ComfyUI` master）— 节点 schema 与 384²/1MP 缩放、`Picture N:` 拼接、`reference_latents` 写入。
- ComfyUI 核心源码：`comfy/samplers.py`（`get_area_and_mult`、`cond_cat`、`_calc_cond_batch`）— Q5 报错机制。
- ComfyUI 核心源码：`comfy/conds.py`（`CONDList.process_cond` / `can_concat` / `concat`）— Q5 报错机制。
- ComfyUI 核心源码：`comfy/ldm/qwen_image/model.py`（`process_img` / `_forward` 的 `ref_latents`、`timestep_zero_index`）与 `comfy/model_base.py`（`QwenImage.extra_conds` → `CONDList`）— 参考图注入机制与 token 记账。
- ComfyUI 核心源码：`comfy_extras/nodes_flux.py`（`PREFERRED_KONTEXT_RESOLUTIONS`、`FluxKontextImageScale`）— 1.05MP×17 桶。
- Qwen 官方：QwenLM/Qwen-Image README + 2509 文档 <https://github.com/QwenLM/Qwen-Image>、<https://github.com/QwenLM/Qwen-Image/blob/main/Qwen-Image-Edit-2509.md> — 官方 inference 参数与分辨率桶。
- Qwen 官方：HF 模型卡 2511 / 2509 <https://huggingface.co/Qwen/Qwen-Image-Edit-2511/blob/main/README.md>、<https://huggingface.co/Qwen/Qwen-Image-Edit-2509> — `num_inference_steps=40, true_cfg_scale=4.0, guidance_scale=1.0`；"1–3 input images"。
- Qwen 官方博客 <https://qwen.ai/blog?id=qwen-image-edit-2511> — 2511 相对 2509 的变化清单。
- ComfyUI 官方博客 <https://blog.comfy.org/p/qwen-image-edit-2511-and-qwen-image> — 2511 集成、多人一致性、模板下载。
- ComfyUI issues：#9481 / #10198 / #10849 <https://github.com/Comfy-Org/ComfyUI/issues/9481>、<https://github.com/comfyanonymous/ComfyUI/issues/10198>、<https://github.com/Comfy-Org/ComfyUI/issues/10849> — 漂移/缩放争议、2509 "Raw latent version" 模板 bug。
- circlestone-labs/Anima HF discussion #76 <https://huggingface.co/circlestone-labs/Anima/discussions/76> — 同类 `IndexError: tuple index out of range` 复现旁证。
- tori29umai0123/ComfyUI-QwenImageEdit-LockPixel <https://github.com/tori29umai0123/ComfyUI-QwenImageEdit-LockPixel> — "reference latent grid == sampler latent grid" 规则与 32px 网格实践。
- wiki.monai.art <https://wiki.monai.art/en/models/qwen_image_edit> — 2511 实测 guidance 区间、3 角色融合、第一张图决定输出比例。

**Dropped**
- runcomfy / fal.ai / runware / localaimaster / thundercompute / comfyui-wiki 等二手教程 — 参数多为平台默认值（如 fal 28 steps / 4.5 CFG），与 Qwen 官方口径冲突且有营销成分。
- comfy.icu 上的第三方节点（ZOEY/XB/TS MultiReference）— 非官方，仅作备选参考。
- `unpkg.com/comfyui-mcp` 的 SKILL.md — 非一手来源，但其中的 Lightning 4-step（4/1.0/euler/simple）与官方模板一致，可交叉印证。
- GitHub/HF 上 "tuple index out of range" 的无关命中（IPAdapter / AnimateDiff / ImpactPack / ai-toolkit LoRA 训练）— 错误字面相同但根因不同。

---

## Gaps

1. **2509 模板 "Raw latent version" 现状未定**：issue #10849 说明它曾有未接线的 `ReferenceLatent`，Comfy-Org 表示在修；当前 `main` 的 2509 JSON 里我找不到任何 `ReferenceLatent` 节点。→ 需要直接看 ComfyUI 桌面端模板库或 git history 确认是"修好了"还是"删掉了"。**建议**：`git log -p --follow templates/image_qwen_image_edit_2509.json`（Comfy-Org/workflow_templates）。
2. **`FluxKontextImageScale` 的精确选择公式**未取得逐字源码（raw 抓取失败，仅有 GitHub blob 片段的常量表 + 文档描述"based on the input image's aspect ratio"）。→ 1280×704 会被吸附到具体哪个桶（我推算最接近的是 1392×752）**未 100% 验证**。
3. **`IndexError` 的确切抛出行**未复现：我给出的是完整因果链（`CONDList` 忽略 area → `cond_cat` 无 `can_concat` → token 记账失配），但未在本地跑出 traceback 定位到具体 `.py:line`。→ **建议**：跑一次最小工作流（`TextEncodeQwenImageEditPlus`（带 vae+image1）→ `ConditioningSetAreaPercentage(0.5,0.5,0,0)` → `KSampler`）并抓完整 traceback；若在 `comfy/conds.py::CONDList.concat` 抛错即为 `list index out of range`，若在 `comfy/ldm/qwen_image/model.py::process_img`/`_forward` 抛错即为 `tuple index out of range`（与 parent 描述一致）。
4. **分辨率"必须是 8 / 16 / 32 的倍数"缺少一句 Qwen 官方逐字声明**：8× 来自 Qwen VAE 架构事实，16× 来自 patch_size=2，32× 来自社区引述官方 pipeline resize 策略（Qwen-Image issue #160）。→ 目前属"高置信的工程共识"而非官方原文。
5. **`ModelSamplingAuraFlow` shift 3.1 是否为 2511 的官方建议值**未在 Qwen 侧文档中找到对应（Qwen/diffusers 侧无 shift 概念）。Comfy 官方模板用 3.1 是唯一权威依据。
6. **denoise=1.0 时 latent_image 内容被完全丢弃**这一结论基于 `comfy/model_sampling.py` 的 flow `noise_scaling`（σ=1 → σ·noise + 0·latent）。未逐字抓取该函数源码，属**高置信推论**。→ 建议 `grep -n "def noise_scaling" -A3 comfy/model_sampling.py`。

---

## Supervisor coordination

无需决策，未阻塞；按约定直接返回研究简报。

---

## 给 parent 的落地清单（可直接照做）

1. **接线**（核心 5 行）：
   `LoadImage → (可选 ImageScaleToTotalPixels @1.0MP 或 FluxKontextImageScale) →` 分三路：
   ①`VAEEncode → KSampler.latent_image`；②`TextEncodeQwenImageEditPlus(image1=该图, vae=VAELoader, clip=Qwen2.5-VL) → KSampler.positive`；③同样的 Plus 节点（prompt 留空）`→ KSampler.negative`。
   **不要用 `EmptySD3LatentImage`**；**不要忘了把 VAE 接进 TextEncode**。
2. **参数**：`euler / simple / denoise 1.0 / shift 3.1（2511）`，`steps 40 / cfg 4.0`；要快就 `Lightning LoRA + steps 4 / cfg 1.0`。
3. **尺寸**：先决定输出画布，再让参考图缩放到同一尺寸。**1280×704 合法但低于 1MP 预算**；走官方模板会被 `FluxKontextImageScale` 吸附走样，需绕开。多参考图时**第一张图的比例决定输出比例**。
4. **多角色**：官方只支持"多图 + 提示词位置描述"，**不要用 `ConditioningSetAreaPercentage` 分区**（core 不支持，会 `IndexError`；这是 core limitation，不是你接错线）。
