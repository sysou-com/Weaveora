#!/usr/bin/env python3
"""回归：FLUX.2 [dev] 通路的**参数/文字/尺寸/参考槽**注入（2026-09-23 新增）。

背景（为什么必须有这套测试）：
  ComfyUI 的 FLUX.2 官方结构**没有 KSampler** —— 采样是 `SamplerCustomAdvanced` + `BasicGuider`，
  步数在 `Flux2Scheduler`、引导在 `FluxGuidance`（**不是 cfg**）、种子在 `RandomNoise`，
  参考图靠 `ReferenceLatent` **串链**。现网那套注入器是按 `KSampler` / `EmptySD3LatentImage` /
  `LoadImage` 写的 —— 不改就会出现「UI 改了步数/引导但毫无变化」的**静默失效**（四段坑 3 的翻版）。

本测试锁四件事：
  1) **识别**：Flux2 工作流被判为 Flux2；Qwen 工作流**不被误判**（否则会走错注入分支）；
  2) **参数落点**：steps→Flux2Scheduler.steps、cfg→FluxGuidance.guidance、seed→RandomNoise、
     denoise→SplitSigmasDenoise；尺寸**同时**写 EmptyFlux2LatentImage 与 Flux2Scheduler
     （只写一个 ⇒ 排程按旧尺寸算 ⇒ 出图发黑/退化）；LoRA 插在 UNETLoader 后且 BasicGuider 改指它；
  3) **文字**：正词写到 FluxGuidance 上游那个 CLIPTextEncode；Flux2 **没有负词位**（返回 None）；
  4) **空槽摘除**：少于 3 张参考图时，摘掉该槽 ReferenceLatent 的 `latent` 入参 + 整条支链，
     且 **conditioning 串链不能断**（断链 = ComfyUI 400 `prompt_outputs_failed_validation`）。

用法：`python worker/test_flux2_injection.py`（纯本地，不需要 ComfyUI / 网络 / GPU）
"""
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import comfy_client as c  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
WF_DIR = os.path.join(os.path.dirname(HERE), "deploy", "comfy")

OK = [0]
FAIL = []


def check(cond, msg):
    if cond:
        OK[0] += 1
        print("  ✓ %s" % msg)
    else:
        FAIL.append(msg)
        print("  ✗ %s" % msg)


def load(name):
    with open(os.path.join(WF_DIR, name), encoding="utf-8") as fh:
        g = json.load(fh)
    g.pop("_comment", None)
    return g


def main():
    # ── 1. txt2img ──────────────────────────────────────────────────────────
    print("\n[1] flux2_dev_txt2img_api.json")
    g = load("flux2_dev_txt2img_api.json")
    check(c._wf_is_flux2(g), "识别为 FLUX.2")
    c._wf_inject_size(g, 1280, 704)
    check(g["41"]["inputs"]["width"] == 1280 and g["41"]["inputs"]["height"] == 704,
          "EmptyFlux2LatentImage 尺寸已注入")
    check(g["42"]["inputs"]["width"] == 1280 and g["42"]["inputs"]["height"] == 704,
          "Flux2Scheduler 的 w/h **同步**注入（缺这条 ⇒ 排程错、图发黑）")
    pid, nid = c._wf_inject_text(g, "POS", "NEG")
    check(pid == "6" and g["6"]["inputs"]["text"] == "POS", "正词写到 FluxGuidance 上游节点")
    check(nid is None, "FLUX.2 返回 neg=None（无 uncond 分支，负词只能折进正词）")
    c._wf_inject_sampler(g, 98765, 8, 4.0, 1.0)
    check(g["42"]["inputs"]["steps"] == 8, "steps → Flux2Scheduler.steps")
    check(abs(g["7"]["inputs"]["guidance"] - 4.0) < 1e-9, "cfg → FluxGuidance.guidance（语义映射）")
    check(g["44"]["inputs"]["noise_seed"] == 98765, "seed → RandomNoise.noise_seed")
    check(c._wf_latent_is_img2img(g) is False, "txt2img 不被误判成 img2img（否则 denoise 会改错）")
    c._wf_inject_lora(g, "Flux_2-Turbo-LoRA_comfyui.safetensors", 1.0)
    check(g.get("lora_main", {}).get("class_type") == "LoraLoaderModelOnly", "Turbo LoRA 节点已插入")
    check(g["40"]["inputs"]["model"] == ["lora_main", 0], "BasicGuider.model 已改指 LoRA 输出")

    # ── 2. 负词折进正词 ─────────────────────────────────────────────────────
    print("\n[2] 负词折进正词（产品 2026-09-23 裁定）+ 参考槽措辞改写")
    # ★ 参考槽措辞：拿**生产真实正词**里那句当样本（Java 侧拼出来的中文口径）
    prod = ("参考图映射（按送入顺序；Picture N 与 imageN 指同一张图）："
            "Picture 1 (image1) = 宝玉[性别 男 male；年龄 13]；Picture 2 (image2) = 可卿；"
            "Picture 3 (image3) = 警幻。请严格按这个对应关系。")
    rw = c._flux2_slot_rewrite(prod)
    check("Picture 1 (image1)" not in rw and "参考图 1" in rw, "中文：Picture N (imageN) → 参考图 N")
    check("image2" not in rw and "参考图 2" in rw, "中文：残留的 imageN 也改掉")
    check("宝玉" in rw and "警幻" in rw and "性别 男" in rw, "中文：只改槽号，身份描述一字不动")
    en = c._flux2_slot_rewrite("The reference image(s) are Picture 1 and Picture 2; keep the face of image1.")
    check("Reference Image 1" in en and "Picture" not in en, "英文：→ Reference Image N（官方模板口径）")
    check(c._flux2_slot_rewrite("") == "" and c._flux2_slot_rewrite("no slots here") == "no slots here",
          "无槽号时原文返回（不误伤）")
    check(c._flux2_slot_rewrite("ImageMagick image processing") == "ImageMagick image processing",
          "不误伤普通单词（ImageMagick / image processing）")
    tail = c._flux2_slot_rewrite("参考图映射（按送入顺序；Picture N 与 imageN 指同一张图）：Picture 1 (image1) = 宝玉")
    check("Picture" not in tail and "参考图 1 = 宝玉" in tail and "（按送入顺序）" in tail,
          "陈旧解释句被清掉（不再自相矛盾）")
    # ★ 2026-09-24：字面 N（imageN，没数字）的残留
    lit = c._flux2_slot_rewrite("不同 imageN 是**不同的人**：禁止互换面孔、发型与服饰。")
    check("imageN" not in lit and "Picture" not in lit and "不同的参考图对应" in lit,
          "中文：字面 N 也改掉（不同 imageN 是 → 不同的参考图对应）")
    lit_en = c._flux2_slot_rewrite("Different imageN are **different people**.")
    check("imageN" not in lit_en and "Different reference images are" in lit_en,
          "英文：字面 N 同样改写")
    check(c._flux2_slot_rewrite("参考图映射（按送入顺序）：imageN") == "参考图映射（按送入顺序）：参考图"
          and c._flux2_slot_rewrite("ImageNet is a dataset") == "ImageNet is a dataset",
          "字面 N 兜底改写（中文篇→「参考图」），且不误伤 ImageNet")

    # ★ 2026-09-24 回归（关键）：组装顺序。错序 ⇒ 前缀刚写好的官方口径被再吃一遍，
    #   正词里只剩「Reference 参考图 1 …」残句 —— 官方口径从未进过模型。
    final, _neg = c._image_edit_prompt(prod, "", ["a.png", "b.png", "c.png"], True)
    check("Reference 参考图" not in final,
          "★ 组装顺序：前缀里的官方口径不会再被槽位改写吃掉（无「Reference 参考图」残句）")
    check(final.count("Reference Image 1") == 1 and "Reference Image 3" in final,
          "★ 官方口径 Reference Image 1/2/3 真的进了最终正词")
    check("参考图 1" in final and "宝玉" in final and "警幻" in final,
          "Java 侧那句映射仍翻成「参考图 N」，身份描述一字不动")
    qwen_pos, _ = c._image_edit_prompt("Picture 1 (image1) = 宝玉", "neg", ["a.png"], False)
    check("Picture 1 (image1)" in qwen_pos, "Qwen 通路正词**不被**改写（口径不变）")

    zh_id = c._flux2_fold_negative("庭院里的女子", "模糊, 换脸, 身份混淆", True)
    check("同一张脸不得在画面里重复出现" in zh_id, "负词里的身份组折成**正向**约束句")
    zh_cov = c._flux2_fold_negative("禁止互换面孔、发型与服饰，禁止把两位画成同一张脸。",
                                    "换脸, 身份混淆", True)
    check("同一张脸不得在画面里重复出现" not in zh_cov,
          "正词已写同类约束时不重复补句（提示词不白变长）")
    en_id = c._flux2_fold_negative("A woman in a garden", "same face, face swap", False)
    check("never appears twice in frame" in en_id, "英文：身份组同样折算成正向句")

    folded = c._flux2_fold_negative("A woman in a garden", "white background, 3d render", False)
    check("photorealistic" in folded and "A woman in a garden" in folded,
          "英文：原文保留 + 追加**正向**约束句（不是照抄负词）")
    check("white background" not in folded, "英文：负词原句没有被原样塞进正词")
    zh = c._flux2_fold_negative("庭院里的女子", "白色背景, 3d渲染", True)
    check("写实摄影" in zh and "庭院里的女子" in zh, "中文：跟随提示词语言")
    check(c._flux2_fold_negative("x", "", False) == "x", "无负词时原样返回")

    # ── 3. edit：3 / 2 / 1 张参考图 ─────────────────────────────────────────
    print("\n[3] flux2_dev_edit_api.json 参考槽")
    g = load("flux2_dev_edit_api.json")
    check(c._wf_is_flux2(g), "识别为 FLUX.2")
    c._wf_set_image(g, ["r1.png", "r2.png", "r3.png"])
    check([g[i]["inputs"]["image"] for i in ("12", "15", "18")] == ["r1.png", "r2.png", "r3.png"],
          "3 张参考图按槽位 1/2/3 写入")
    check(all(g[i]["class_type"] == "ImageScaleToTotalPixels"
              and abs(g[i]["inputs"]["megapixels"] - 1.0) < 1e-9 for i in ("13", "16", "19")),
          "参考图按**官方口径**缩到 1MP（ImageScaleToTotalPixels，**保长宽比** ⇒ 1:1 定妆照不会被拉成 16:9）")
    c._wf_inject_size(g, 1664, 928)
    check(all("width" not in g[i]["inputs"] and g[i]["inputs"]["megapixels"] == 1.0
              for i in ("13", "16", "19")),
          "尺寸注入**不会**再改写参考图缩放（旧 ImageScale 会被 _wf_inject_size 写成目标尺寸 ⇒ 拉伸变形）")
    check(g["41"]["inputs"]["width"] == 1664 and g["42"]["inputs"]["width"] == 1664,
          "目标画幅仍是 1664（只改参考图缩放，不动出图尺寸）")
    check(c._wf_prune_flux2_refs(g, 3) == 0, "3 张 = 槽位刚好，不动刀")
    check(g["32"]["inputs"]["latent"] == ["20", 0] and g["40"]["inputs"]["conditioning"] == ["32", 0],
          "ReferenceLatent 串链 + 链尾接 BasicGuider 完好")

    g = load("flux2_dev_edit_api.json")
    c._wf_set_image(g, ["r1.png", "r2.png"])
    check(c._wf_prune_flux2_refs(g, 2) == 3, "2 张：摘掉第 3 槽支链的 3 个节点")
    check(all(k not in g for k in ("18", "19", "20")), "支链节点（LoadImage/ImageScale/VAEEncode）已删")
    check("latent" not in g["32"]["inputs"], "第 3 槽 ReferenceLatent 的 latent 入参已摘（optional ⇒ 空过）")
    check(g["32"]["inputs"]["conditioning"] == ["31", 0], "conditioning 串链**没断**（断了就是 400）")
    check(g["30"]["inputs"]["latent"] == ["14", 0] and g["31"]["inputs"]["latent"] == ["17", 0],
          "保留槽仍挂着各自正确的 latent（没有错位）")

    g = load("flux2_dev_edit_api.json")
    c._wf_set_image(g, ["only.png"])
    check(c._wf_prune_flux2_refs(g, 1) == 6, "1 张：摘掉 2 条支链共 6 个节点")
    check(g["30"]["inputs"]["latent"] == ["14", 0], "第 1 槽保留")
    check("latent" not in g["31"]["inputs"] and "latent" not in g["32"]["inputs"], "第 2/3 槽空过")

    # ── 4. img2img ──────────────────────────────────────────────────────────
    print("\n[4] flux2_dev_img2img_api.json")
    g = load("flux2_dev_img2img_api.json")
    check(c._wf_is_flux2(g), "识别为 FLUX.2")
    check(c._wf_latent_is_img2img(g) is True, "识别为真 img2img")
    c._wf_inject_sampler(g, 1, 20, 4.0, 0.65)
    check(abs(g["48"]["inputs"]["denoise"] - 0.65) < 1e-9, "denoise → SplitSigmasDenoise")
    check(g["45"]["inputs"]["sigmas"] == ["48", 1], "sigmas 接 low_sigmas（槽 1）")
    check(g["45"]["inputs"]["latent_image"] == ["14", 0], "采样起点 = VAEEncode 底图")
    c._wf_inject_size(g, 1024, 1024)
    check(g["13"]["inputs"]["width"] == 1024, "底图缩到目标尺寸")

    # ── 5. Qwen 通路不被误判（防回归）────────────────────────────────────────
    print("\n[5] 回归：Qwen 工作流不被误判为 FLUX.2")
    for name in ("qwen_image_txt2img_film_api.json", "qwen_image_edit_api.json",
                 "qwen_image_img2img_api.json"):
        g = load(name)
        check(not c._wf_is_flux2(g), "%s 不误判" % name)
    g = load("qwen_image_edit_api.json")
    c._wf_set_image(g, ["a.png", "b.png"])
    check(c._wf_prune_unused_images(g, 2) == 1, "Qwen 版摘空槽仍走旧逻辑（只摘 LoadImage）")
    pid, nid = c._wf_inject_text(g, "P", "N")
    check(pid == "6" and nid == "7", "Qwen 正/负词定位不受影响")

    print("\n结果：%d 项通过，%d 项失败" % (OK[0], len(FAIL)))
    for m in FAIL:
        print("  - %s" % m)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
