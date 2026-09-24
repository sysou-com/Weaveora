#!/usr/bin/env python3
"""Weaveora comfy worker 引擎（W4：真 ComfyUI txt2img + IP-Adapter 一致性锚定）。

用法见 stub_worker.py：WEAVEORA_WORKER_MODE=comfy 时走本模块；
WEAVEORA_COMFY_URL 指向 ComfyUI（默认 http://127.0.0.1:8188）。
本模块只依赖标准库（urllib），不依赖 comfyui 客户端库。
"""
import base64
import io
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

try:
    import numpy as np          # 段间缝合的时间淡入淡出（_splice）用；缺失时自动退回硬替换
except Exception:
    np = None

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
TOKEN = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")
COMFY = os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8188").rstrip("/")
# IP-Adapter 工作流节点缺失或失败时是否降级 txt2img（默认降级，保证能出图）
FALLBACK = os.environ.get("WEAVEORA_COMFY_FALLBACK_TXT2IMG", "1") == "1"

# ── 文生图（本机 ComfyUI 工作流，Qwen-Image / FLUX）──────────────────────────────
# 为什么用「工作流 JSON」而不是在代码里拼节点：换模型（SDXL → Qwen-Image → FLUX）
# 只要换一个 JSON，不用改 worker、不用重启；参数注入靠 class_type/接线约定（见 generate_via_workflow）。
# 这些值优先由「生成引擎配置 → 服务地址 → 文生图」随任务下发（apply_services），环境变量只作回退。
IMAGE_COMFY = os.environ.get("WEAVEORA_IMAGE_COMFY_URL", "").strip()        # 空 = 用 COMFY
IMAGE_ENGINE = os.environ.get("WEAVEORA_IMAGE_ENGINE", "builtin").strip().lower()
IMAGE_TXT2IMG_WF = os.environ.get("WEAVEORA_IMAGE_WORKFLOW", "").strip()
IMAGE_IMG2IMG_WF = os.environ.get("WEAVEORA_IMAGE_IMG2IMG_WORKFLOW", "").strip()
# ★ 参考图**锚定**档（Qwen-Image-Edit 这类：把参考图编码进 conditioning，而不是“以它为底重画”）。
#   与 img2img 的区别：Edit 用 EmptySD3LatentImage + TextEncodeQwenImageEditPlus(image1/image2) → denoise 保持 1.0。
IMAGE_EDIT_WF = os.environ.get("WEAVEORA_IMAGE_EDIT_WORKFLOW", "").strip()
# ★ P1 防复发（2026-09-18 用户实测事故）：Edit 通路是「参考图锚定」的唯一正确姿势（denoise 必须 1.0）。
#   若 edit 工作流为空，关键帧会**静默降级**成 img2img（槽位少 + denoise 0.65）→ 把定妆照半重绘成
#   「不像的定妆照」（用户报的正是这个）。所以：环境变量为空时回落到本机标准路径。
#   ★ 2026-09-23：默认值随生产切到 FLUX.2（原来是 qwen_image_edit_api.json）。
#     这是个**隐性回退**：DB 的 services.image.editWorkflow 才是运行时真源，这里只是它为空时的兵底。
#     两个值指向不同模型 = 一旦 DB 被清空就会“静默地跑另一个模型”（本项目的经典事故形态）。
if not IMAGE_EDIT_WF:
    _default_edit_wf = os.environ.get("WEAVEORA_IMAGE_EDIT_WORKFLOW",
                                      "/opt/weaveora/workflows/flux2_dev_edit_api.json").strip()
    if _default_edit_wf and os.path.exists(_default_edit_wf):
        IMAGE_EDIT_WF = _default_edit_wf
IMAGE_MODEL = os.environ.get("WEAVEORA_IMAGE_MODEL", "").strip()
IMAGE_STEPS = int(os.environ.get("WEAVEORA_IMAGE_STEPS", "0") or 0)
IMAGE_DENOISE = float(os.environ.get("WEAVEORA_IMAGE_DENOISE", "0.65") or 0.65)
# ★ cfg（true_cfg_scale / CFG）：0 = 不改，用工作流 JSON 里的值。
#   为什么必须有这条（2026-09-16 排查"参数推送"）：引擎配置页一直只有 steps/denoise，
#   没有 cfg —— 想调 cfg 只能去手改工作流 JSON。Qwen-Image-Edit 的官方 Qwen 口径是
#   steps 40 / cfg 4.0（Comfy 模板默认同值），而 cfg 直接决定**提示词遵从度**，
#   是"出图效果不好"时第一个该调的旋钮，必须能从配置页下发。
IMAGE_CFG = float(os.environ.get("WEAVEORA_IMAGE_CFG", "0") or 0)
# ★ 2026-09-19：图片/关键帧的 **LoRA（提速用）**，留空 = 不挂（保持原行为）。
#   背景：2K/40 步一张关键帧 435~476s（7~8 分钟）——用户点名“渲染速度”是主要问题之一。
#   Qwen-Image-Edit-2511 有官方蒸馏 LoRA（lightx2v/Qwen-Image-Edit-2511-Lightning，Apache-2.0，810MB）：
#   官方 ComfyUI 模板把 4step LoRA 当**标配**，并且 **CFG=4.0（不是 1.0）**、LoRA 强度 1.0
#   → 所以负词/风格不会被吹掉，只是把采样步数从 40 降到 4。
#   生效优先级：payload.params.lora > 环境变量 > 引擎配置下发。
IMAGE_LORA = os.environ.get("WEAVEORA_IMAGE_LORA", "").strip()
IMAGE_LORA_STRENGTH = float(os.environ.get("WEAVEORA_IMAGE_LORA_STRENGTH", "1.0") or 1.0)
# 挂 LoRA 时的**默认步数**：蒸馏 LoRA 是按「4 步 + cfg 4.0」训的，拿 40 步去跑它
# 既慢又不是它的工作点 → 有 LoRA 且调用方没显式给 steps 时，Edit 通路默认用这个值。
IMAGE_LORA_STEPS = int(os.environ.get("WEAVEORA_IMAGE_LORA_STEPS", "4") or 4)
# 挂 LoRA 时的**默认 cfg**。
#   ★ 2026-09-19 实测（同 seed / 同参考图，只改 cfg）：
#     cfg 4.0 + 4步LoRA → **坏图**（大片红色色块 + 扫描线状条纹 + 糊脸；亮度 +49%）
#     cfg 2.0 + 4步LoRA → 也不干净
#     cfg 1.5 + 4步LoRA → 干净、写实、细节在（高频占比与 40 步持平）
#     cfg 1.0 + 4步LoRA → 干净，但 **负词完全失效**（cfg=1 无 uncond 分支）
#   所以取 1.5：蒸馏模型的工作点附近，同时保留部分负词权重（cfg-1=0.5）。
#   注意：负词里如果还写着「真人肖像」这类“禁止画真实人脸”的词，在这个 cfg 下依然会把脸推向非写实 → 必须删。
IMAGE_LORA_CFG = float(os.environ.get("WEAVEORA_IMAGE_LORA_CFG", "1.5") or 1.5)
# ★ 2026-09-23：**FLUX.2 专用档位**。理由：`Flux_2-Turbo-LoRA_comfyui` 是按「**8 步 + guidance 4.0**」训的
#   （官方量化档模板里 Switch(Step) 在 20 ↔ 8 之间切，而 FluxGuidance 固定 4）——
#   与 Qwen-Image Lightning 的「4 步 + cfg 1.5」**不是同一套工作点**，混用就是坏图。
IMAGE_LORA_STEPS_FLUX2 = int(os.environ.get("WEAVEORA_IMAGE_LORA_STEPS_FLUX2", "8") or 8)
IMAGE_LORA_CFG_FLUX2 = float(os.environ.get("WEAVEORA_IMAGE_LORA_CFG_FLUX2", "4.0") or 4.0)
# ★ FLUX.2 [dev] 是 **guidance 蒸馏**（官方模板用 BasicGuider 单条件、无 uncond 分支）⇒ 负词架构上不生效
#   **且不报错**。产品 2026-09-23 裁定：把负词**折进正词**。置 0 则直接丢弃（只打 WARN，不静默）。
FLUX2_FOLD_NEGATIVE = (os.environ.get("WEAVEORA_FLUX2_FOLD_NEGATIVE", "1") or "1").strip() == "1"
# ★ 2026-09-21：出图后**自动放大**（SeedVR2 —— ComfyUI 0.34 核心原生节点，无需自定义节点）。
#   动机（实测，同图同 seed）：I2V 只吃 480p（长边 832）→ 首帧会被下采样；**源越锐、降采样后越锐**：
#     1664×928 原生 → 下采样到 832 的高频能量 3.15
#     先 SeedVR2 放 2×（3328×1856）再降采样 → 4.39（**+40%**）；耗时 ~20s、显存 ~5G、
#     内容保真（降回原尺寸平均绝对差 3.4/255）、无黑边、不改构图与身份。
#   对比“原生出 2560×1408”：561s/张 + 43% 概率上下黑边 + 超官方预算 3.4× 的漂移 ⇒ 便宜得多。
#   生效范围：只作用于**出图**（still / portrait）；不碰出片（clip）/对口型/配乐。
#   失败策略：任何异常都**保留原图**并把原因写进资产 notes（绝不因此让出图失败）。
#   开关：WEAVEORA_IMAGE_UPSCALE=seedvr2（留空=关）｜倍数 WEAVEORA_IMAGE_UPSCALE_SCALE（默认 2）
#         长边上限 WEAVEORA_IMAGE_UPSCALE_MAX（默认 4096）｜步数 WEAVEORA_IMAGE_UPSCALE_STEPS（默认 1）
IMAGE_UPSCALE = os.environ.get("WEAVEORA_IMAGE_UPSCALE", "").strip().lower()
IMAGE_UPSCALE_SCALE = float(os.environ.get("WEAVEORA_IMAGE_UPSCALE_SCALE", "2") or 2)
IMAGE_UPSCALE_MAX = int(os.environ.get("WEAVEORA_IMAGE_UPSCALE_MAX", "4096") or 4096)
IMAGE_UPSCALE_STEPS = int(os.environ.get("WEAVEORA_IMAGE_UPSCALE_STEPS", "1") or 1)
IMAGE_UPSCALE_TIMEOUT = int(os.environ.get("WEAVEORA_IMAGE_UPSCALE_TIMEOUT", "600") or 600)
SEEDVR2_MODEL = os.environ.get("WEAVEORA_SEEDVR2_MODEL", "seedvr2_3b_fp8_e4m3fn.safetensors")
SEEDVR2_VAE = os.environ.get("WEAVEORA_SEEDVR2_VAE", "ema_vae_fp16.safetensors")
# ★ 区域条件（位置优先）开关：默认关闭。
#   原因（2026-09-16 实测两轮）：Qwen-Image-Edit 的 conditioning（TextEncodeQwenImageEditPlus 带参考图
#   latent）被 ConditioningSetAreaPercentage 包裹再用 ConditioningCombine 合并后，KSampler 必报
#   `IndexError: tuple index out of range`。故生产默认走「位置写进提示词」（路线A，后端拼句）；
#   代码保留，等路线 B 找到在 2511 上成立的组合方式再置 1 打开。
IMAGE_AREA_COND = os.environ.get("WEAVEORA_IMAGE_AREA_COND", "0") == "1"
# 轮询超时：默认 30 分钟。踩过的坑（2026-09-15）：ComfyUI 重启后的**首张图**要从磁盘冷加载 ~28GB
# （Qwen-Image 20.4G + Qwen2.5-VL 7.9G），而且 fp8 权重在 CPU 上手动 cast 成 bf16 很耗时，
# 实测首张 prompt 花了 12 分 05 秒 —— 旧的 600s 超时会让 worker 先报 timeout，
# 而 ComfyUI 那头其实还在跑、图最终也出来了（白跑一趟）。
IMAGE_TIMEOUT = int(os.environ.get("WEAVEORA_IMAGE_TIMEOUT", "1800") or 1800)


def _image_comfy():
    return (IMAGE_COMFY or COMFY).rstrip("/")


def _looks_zh(text):
    """提示词是否以中文为主（与后端 JobService.isZhText 同口径）。

    用途：worker 会在正/负词前**追加**一段英文指令（Edit 档的“怎么用参考图”、负词的
    “不要白底”等）；用户把提示词改成中文后，这段英文就成了“英文头 + 中文身 + 中文尾”，
    实测会让模型两头听（用户报过中英混杂）。所以追加语必须跟随提示词语言。
    判据：汉字 ≥ 6 且占「汉字+拉丁字母」≥ 20%（“英文里带中文角色名”不会误判）。
    """
    if not text:
        return False
    cjk = 0
    latin = 0
    for ch in text:
        o = ord(ch)
        if 0x4E00 <= o <= 0x9FFF:
            cjk += 1
        elif ('a' <= ch <= 'z') or ('A' <= ch <= 'Z'):
            latin += 1
    return cjk >= 6 and cjk * 100 >= (cjk + latin) * 20


def image_workflow_ready():
    """文生图是否走「本机 ComfyUI 工作流」（engine=comfy + 工作流 JSON 存在）。"""
    return (IMAGE_ENGINE == "comfy" and bool(IMAGE_TXT2IMG_WF) and os.path.exists(IMAGE_TXT2IMG_WF))


def _wf_load(path):
    with open(path, encoding="utf-8") as fh:
        graph = json.load(fh)
    graph.pop("_comment", None)
    return graph


def _wf_of_class(graph, class_type):
    return [(nid, n) for nid, n in graph.items()
            if isinstance(n, dict) and n.get("class_type") == class_type]


# ── FLUX.2（[dev] / klein）通路的识别与专属注入 ──────────────────────────────────
# 为什么必须单独一套：Flux2 的官方结构**没有 KSampler** —— 采样走 `SamplerCustomAdvanced` + `BasicGuider`，
#   步数在 `Flux2Scheduler`、引导在 `FluxGuidance`、种子在 `RandomNoise`，且**没有 uncond 分支**。
#   不识别它 = 现网那套「参数推送」对它完全失效（UI 改了步数/引导却毫无变化）。
_FLUX2_LATENT = "EmptyFlux2LatentImage"


def _wf_is_flux2(graph):
    """工作流是不是 FLUX.2 结构（Flux2Scheduler / EmptyFlux2LatentImage 任一存在即可判定）。"""
    return bool(_wf_of_class(graph, "Flux2Scheduler")) or bool(_wf_of_class(graph, _FLUX2_LATENT))


def _wf_upstream_text_node(graph, start_ref):
    """顺着 conditioning 链往回找第一个文本编码节点 id（Flux2 没有 KSampler 可以定位正/负词）。"""
    seen = set()
    stack = [str(start_ref)] if start_ref is not None else []
    while stack:
        nid = stack.pop()
        if nid in seen or nid not in graph:
            continue
        seen.add(nid)
        n = graph[nid]
        if n.get("class_type") in ("CLIPTextEncode", "CLIPTextEncodeFlux",
                                   "TextEncodeQwenImageEditPlus", "TextEncodeQwenImageEdit"):
            return nid
        for v in (n.get("inputs") or {}).values():
            if isinstance(v, list) and v and str(v[0]) in graph:
                stack.append(str(v[0]))
    return None


# ★ 2026-09-23：FLUX.2 参考槽措辞改写（**功能性**开关，默认开）。
#   为什么必须：生产正词是 Java 侧拼的**中文**，里面写死了 Qwen 的口径
#     「参考图映射（按送入顺序；Picture N 与 imageN 指同一张图）：Picture 1 (image1) = 宝玉…」
#   —— 那是 `TextEncodeQwenImageEditPlus` 自己的序号约定。FLUX.2 的参考图走 `ReferenceLatent`，
#   官方模板的措辞是 "Reference Image N"，**没有 Picture 这个概念**。不改就等于让模型去找一张
#   不存在的 "Picture 2" ⇒ 参考图与角色的映射**可能整个丢掉**（且不会报错）。
#   只改序号写法，不改任何剧情/身份描述；WEAVEORA_FLUX2_SLOT_REWRITE=0 可关。
FLUX2_SLOT_REWRITE = (os.environ.get("WEAVEORA_FLUX2_SLOT_REWRITE", "1") or "1").strip() == "1"
_FLUX2_SLOT_RE_PIC = re.compile(r"Picture\s*(\d+)\s*\(\s*image\s*\1\s*\)", re.IGNORECASE)
# 光杆的 "Picture N"（没带括号的那种）也要管 —— 英文正词里就出现过 "...are Picture 1 and Picture 2"
_FLUX2_SLOT_RE_BARE = re.compile(r"Picture\s*(\d+)", re.IGNORECASE)
_FLUX2_SLOT_RE_IMG = re.compile(r"(?<![A-Za-z])image\s*(\d+)(?![0-9])", re.IGNORECASE)
# 陈旧解释句：“参考图映射（按送入顺序；Picture N 与 imageN 指同一张图）：”
#   —— 那里是**字面 N**（不是数字）⇒ 上面三条数字规则盖不到；
#   槽号改完它还留着就变成自相矛盾的噪声（同句里既说“参考图 N” 又说“Picture N”）。一并清掉。
_FLUX2_SLOT_RE_TAIL = re.compile(
    r"[；;]\s*(?:Picture|image)\s*N\s*(?:与|和|and)\s*(?:Picture|image)\s*N\s*"
    r"(?:指同一张图|refers? to the same image|are the same image)",
    re.IGNORECASE)


# ★ 2026-09-24：**字面 N**（没有数字）的残留。Java 侧那句「不同 imageN 是**不同的人**」是
#   Qwen 口径的遗留（`Picture N`/`imageN` 在那里只是记号，不是「第 N 张图」）——FLUX.2 没有 Picture
#   这个概念，留着就是让模型去找一张不存在的图。句子语境已知，按语境改写成通顺的正向句；
#   其余兜底成「参考图」/「reference image」。
_FLUX2_SLOT_RE_LIT_ZH = re.compile(r"不同\s*(?:Picture|image)\s*N\s*是", re.IGNORECASE)
_FLUX2_SLOT_RE_LIT_EN = re.compile(r"Different\s+(?:Picture|image)\s*N\s+are", re.IGNORECASE)
_FLUX2_SLOT_RE_LIT = re.compile(r"(?:Picture|image)\s*N(?![A-Za-z0-9])", re.IGNORECASE)


def _flux2_slot_rewrite(text):
    r"""把 Qwen 口径的参考槽编号（Picture N (imageN) / imageN）改写成 FLUX.2 的口径。

    中文正词用「参考图 N」，英文正词用「Reference Image N」（官方模板口径）—— 跟提示词语言走，
    避免又搞出「英文头 + 中文身」那种两头听（2026-09-16 用户报过中英混杂）。

    ⚠️ 本函数**只能作用在 Java 侧拼的那份正词上**，绝不能跑在 `_image_edit_prefix` 的产物上 ——
    前缀里的 "Reference Image N" 会被 `image\s*(\d+)` 再吃一遍（2026-09-24 的静默 bug）。
    组装顺序见 `_image_edit_prompt`。
    """
    if not text:
        return text
    zh = _looks_zh(text)
    slot = "参考图 %s" if zh else "Reference Image %s"
    out = _FLUX2_SLOT_RE_TAIL.sub("", text)
    out = _FLUX2_SLOT_RE_PIC.sub(lambda m: slot % m.group(1), out)
    out = _FLUX2_SLOT_RE_BARE.sub(lambda m: slot % m.group(1), out)
    out = _FLUX2_SLOT_RE_IMG.sub(lambda m: slot % m.group(1), out)
    # 字面 N 放最后：上面三条只认数字，认不出它
    if zh:
        out = _FLUX2_SLOT_RE_LIT_ZH.sub("不同的参考图对应", out)
    else:
        out = _FLUX2_SLOT_RE_LIT_EN.sub("Different reference images are", out)
    out = _FLUX2_SLOT_RE_LIT.sub("参考图" if zh else "reference image", out)
    return out


def _flux2_fold_negative(positive, negative, zh):
    """把负词**折进正词**（FLUX.2 专用）。

    为什么必须折：FLUX.2 [dev] 是 guidance 蒸馏，`BasicGuider` 只吃一个 conditioning ⇒ 负词不参与计算、
    而且**不报错**（静默失效）。但不能把负词原样拼进正词 —— 对蒸馏模型「别画 X」这类否定句可能起反作用，
    所以这里做**同义正向改写**（不是照抄）。原负词会打进日志，不静默丢失。
    """
    if not (negative or "").strip():
        return positive
    text = (positive or "").rstrip()
    low = text.lower()
    neg_low = negative.lower()
    # ★ 2026-09-24：负词**分组**折进正词。旧实现只把 negative 打进日志（正文被整条丢掉）⇒
    #   Java 侧给多主体加的「换脸/身份混淆/同一张脸重复/复制脸庞/同一人出现两次」这些在 Qwen 通路
    #   真进过负条件线的身份约束**静默消失**。这里按组识别并改写成**正向**约束句（仍不照抄负词）；
    #   只有正词**还没写**同类约束时才补，避免与 Java 侧「禁止互换面孔…同一张脸」重复（提示词白变长）。
    ident = (any(k.lower() in neg_low for k in _FLUX2_NEG_IDENTITY)
             and not any(k.lower() in low for k in _FLUX2_POS_IDENTITY_COVERED))
    if zh:
        mid = ("画面中每个角色只对应自己那张参考图，不同角色的面容/发型/服饰不得混用，"
               "同一张脸不得在画面里重复出现。" if ident else "")
        tail = ("画面要求：写实摄影质感（真实皮肤与材质）；背景是真实场景而非纯色/白色/影棚背景；"
                "构图是叙事镜头而非证件照式正面像；不要 3D 渲染或 CGI 感；不要插画、卡通或塑料感皮肤。")
        if not text.endswith(("。", "；", "！", "？")):
            text += "。"
    else:
        mid = ("Each character matches only their own reference image: faces, hair and costume are never "
               "swapped or blended between characters, and the same face never appears twice in frame. "
               if ident else "")
        tail = ("Rendering requirements: photorealistic photography with real skin and material detail; "
                "the background is a real scene rather than a plain, white or studio backdrop; "
                "framing is a narrative camera shot rather than an ID-photo frontal pose; "
                "no 3D-render or CGI look; no illustration, cartoon or plastic-skin style.")
        if not text.endswith((".", "!", "?")):
            text += "."
        text += " "
    return text + mid + tail


def _wf_inject_size(graph, width, height):
    """把目标尺寸写进工作流：Empty*LatentImage（txt2img）与 ImageScale/ImageResize（Edit 档：
    官方结构是**把参考图缩放到目标尺寸 → VAEEncode → 当采样起点**）。

    ★ 2026-09-18 修正：以前命中第一个节点就 return —— Edit/img2img 工作流里同时存在多个尺寸节点时，
    只有一部分被改写（img2img 可能一个都没命中）→ 出图尺寸**继承参考图尺寸**
    （用户事故：关键帧变成 2560×1440 的定妆照尺寸）。现在**遍历所有尺寸节点**都写，
    并返回写入个数（0 = 尺寸由底图决定，调用侧需自行缩放底图）。
    """
    n_hit = 0
    for ct in ("EmptySD3LatentImage", "EmptyLatentImage", _FLUX2_LATENT, "ImageScale", "ImageResize"):
        for _nid, n in _wf_of_class(graph, ct):
            ins = n.get("inputs", {})
            if "width" in ins and "height" in ins:
                ins["width"], ins["height"] = int(width), int(height)
                n_hit += 1
    # ★ Flux2：`Flux2Scheduler` 的 w/h 决定 sigma 排程（seq_len = W*H/256），**必须与 latent 尺寸同步写**。
    #   只改 latent 不改 scheduler ⇒ 排程按旧尺寸算 → 出图发黑/退化（与 steps/cfg 那类静默错值同性质）。
    for _nid, n in _wf_of_class(graph, "Flux2Scheduler"):
        ins = n.setdefault("inputs", {})
        if "width" in ins and "height" in ins:
            ins["width"], ins["height"] = int(width), int(height)
            n_hit += 1
    if n_hit == 0:
        for _nid, n in _wf_of_class(graph, "ImageScaleBy"):
            ins = n.get("inputs", {})
            print("[comfy] WARN 工作流只靠 ImageScaleBy(scale_by=%s) 控制尺寸：无法按目标 %dx%d 精确注入"
                  % (ins.get("scale_by"), width, height), flush=True)
            break
    return n_hit


def _wf_inject_text(graph, positive, negative):
    """正/负词：按 KSampler 的接线分辨那两条件节点，再用 _meta.title 兑底。

    兼容两类节点（重要）：普通 `CLIPTextEncode`（入参名为 text）与 Qwen-Image-**Edit** 的
    `TextEncodeQwenImageEditPlus`（入参名为 **prompt**）—— 写错入参名会静默不生效（图照出，但用的还是空提示词）。
    """
    # ★ FLUX.2：没有 KSampler，正词要靠 FluxGuidance.conditioning 往回走；**负词没有位置**
    #   （guidance 蒸馏，单条件）—— 折进正词的动作在 generate_via_workflow 里做，这里只写正词。
    if _wf_is_flux2(graph):
        gf = _wf_of_class(graph, "FluxGuidance")
        ref = (gf[0][1].get("inputs") or {}).get("conditioning") if gf else None
        pos_id = _wf_upstream_text_node(graph, ref[0] if isinstance(ref, list) and ref else None)
        if not pos_id:
            texts = _wf_of_class(graph, "CLIPTextEncode")
            pos_id = texts[0][0] if texts else None
        if pos_id and pos_id in graph:
            ins = graph[pos_id].setdefault("inputs", {})
            ins["text" if "text" in ins else "prompt"] = positive or ""
        return pos_id, None
    ks = _wf_of_class(graph, "KSampler") or _wf_of_class(graph, "KSamplerAdvanced")
    pos_id = neg_id = None
    if ks:
        ins = ks[0][1].get("inputs", {})
        for key, which in (("positive", "pos"), ("negative", "neg")):
            ref = ins.get(key)
            if isinstance(ref, list) and ref and isinstance(ref[0], str):
                if which == "pos":
                    pos_id = ref[0]
                else:
                    neg_id = ref[0]
    texts = (_wf_of_class(graph, "CLIPTextEncode")
             + _wf_of_class(graph, "TextEncodeQwenImageEditPlus")
             + _wf_of_class(graph, "TextEncodeQwenImageEdit"))
    if not pos_id or not neg_id:
        for nid, n in texts:
            title = ((n.get("_meta") or {}).get("title") or "").lower()
            if not pos_id and ("pos" in title or "正" in title):
                pos_id = nid
            elif not neg_id and ("neg" in title or "负" in title):
                neg_id = nid
    if not pos_id and texts:
        pos_id = texts[0][0]
    if not neg_id:
        for nid, _n in texts:
            if nid != pos_id:
                neg_id = nid
                break
    def _set(nid, val):
        if not (nid and nid in graph):
            return
        ins = graph[nid].setdefault("inputs", {})
        if "text" in ins:
            ins["text"] = val or ""
        elif "prompt" in ins:          # Qwen-Image-Edit 系
            ins["prompt"] = val or ""
        else:
            ins["text"] = val or ""
    _set(pos_id, positive)
    _set(neg_id, negative)
    return pos_id, neg_id


def _wf_inject_sampler(graph, seed, steps, cfg, denoise):
    if _wf_is_flux2(graph):
        # Flux2 语义映射：steps→Flux2Scheduler.steps；**cfg→FluxGuidance.guidance**；
        #   seed→RandomNoise.noise_seed；denoise→SplitSigmasDenoise（只有真 img2img 那份工作流有）。
        #   尺寸由 _wf_inject_size 同步写 Flux2Scheduler。
        hit = False
        if steps and int(steps) > 0:
            for _nid, n in _wf_of_class(graph, "Flux2Scheduler"):
                n.setdefault("inputs", {})["steps"] = int(steps)
                hit = True
        if cfg is not None:
            for _nid, n in _wf_of_class(graph, "FluxGuidance"):
                n.setdefault("inputs", {})["guidance"] = float(cfg)
                hit = True
        if seed is not None:
            for _nid, n in _wf_of_class(graph, "RandomNoise"):
                n.setdefault("inputs", {})["noise_seed"] = int(seed)
                hit = True
        if denoise is not None and float(denoise) < 1.0:
            for _nid, n in _wf_of_class(graph, "SplitSigmasDenoise"):
                n.setdefault("inputs", {})["denoise"] = float(denoise)
                hit = True
        return hit
    for ct in ("KSampler", "KSamplerAdvanced"):
        for _nid, n in _wf_of_class(graph, ct):
            ins = n.setdefault("inputs", {})
            if seed is not None:
                ins["seed"] = int(seed)
            if steps and int(steps) > 0:
                ins["steps"] = int(steps)
            if cfg is not None:
                ins["cfg"] = float(cfg)
            if denoise is not None and "denoise" in ins:
                ins["denoise"] = float(denoise)
            return True
    return False


def _wf_inject_model(graph, model):
    """把主模型名换成配置里的（留空 = 不改，工作流写什么就用什么）。"""
    if not model:
        return False
    hit = False
    for ct, key in (("UNETLoader", "unet_name"), ("CheckpointLoaderSimple", "ckpt_name")):
        for _nid, n in _wf_of_class(graph, ct):
            if key in n.get("inputs", {}):
                n["inputs"][key] = model
                hit = True
    return hit


def _wf_inject_lora(graph, name, strength=1.0):
    """给主模型挂 LoRA（提速用）。

    做法：在 UNETLoader 后面插一个 LoraLoaderModelOnly，并把**所有**指向该 loader 的
    `model` 引用改指向 LoRA 节点。
    为什么用 LoraLoaderModelOnly（不是 LoraLoader）：Qwen-Image 的 Lightning LoRA 只训了 **DiT**，
    文本编码器（Qwen2.5-VL）不挂 LoRA；用 LoraLoader 反而会因缺 clip 权重报错。
    """
    if not (name or "").strip():
        return False
    loaders = _wf_of_class(graph, "UNETLoader") or _wf_of_class(graph, "CheckpointLoaderSimple")
    if not loaders:
        return False
    lid, node = loaders[0]
    ct = str(node.get("class_type") or "")
    key = "unet_name" if ct == "UNETLoader" else "ckpt_name"
    if key not in (node.get("inputs") or {}):
        return False
    lora_id = "lora_main"
    graph[lora_id] = {"class_type": "LoraLoaderModelOnly",
                      "inputs": {"model": [lid, 0], "lora_name": name.strip(),
                                 "strength_model": float(strength)}}
    # 改写引用：必须用 list(graph.items()) 快照并跳过 LoRA 自己，
    # 否则会把它自己的 model 引用也改掉 → 自引用死循环。
    for _nid, n in list(graph.items()):
        if _nid == lora_id:
            continue
        for k, v in (n.get("inputs") or {}).items():
            if k == "model" and isinstance(v, list) and len(v) >= 2 and str(v[0]) == str(lid):
                n["inputs"][k] = [lora_id, 0]
    return True


def _wf_prune_unused_images(graph, used):
    """把**多余的第 N 个参考图槽**从图里摘掉（连同对它的引用）。

    为什么不能像 _wf_set_image 那样「槽位比参考图多就复用第一张」：
    TextEncodeQwenImageEditPlus 会把**每个槽**都编码成 reference latent —— 复用等于同一张脸
    被注入两次（多花 token，还可能把某个角色的权重带偏）。

    为什么现在必须会摘（2026-09-16）：Edit 工作流从 2 个槽扩到 **3 个槽** ——
    用户第 4 镜有 3 个主体（宝玉/可卿/警幻），旧工作流只有 2 个 LoadImage，
    第 3 张**不报错但被静默丢掉**，于是「警幻」这一镜完全没有参考图、人物对不上（用户报的一致性）。
    扩槽之后「少于槽位」的镜头变多，所以必须能把空槽摘干净。
    """
    if used <= 0:
        return 0
    nodes = sorted(_wf_of_class(graph, "LoadImage"), key=lambda x: str(x[0]))
    drop = [str(nid) for nid, _n in nodes[used:]]
    if not drop:
        return 0
    for nid in drop:
        graph.pop(nid, None)
    for _nid, n in list(graph.items()):
        ins = n.get("inputs")
        if not isinstance(ins, dict):
            continue
        for k, v in list(ins.items()):
            if isinstance(v, list) and v and str(v[0]) in drop:
                del ins[k]
    return len(drop)


def _wf_prune_flux2_refs(graph, used):
    """FLUX.2 版「摘空槽」（**不能照用 Qwen 版**）。

    为什么不能照用（两条都会 400）：
      ① Flux2 的参考槽是**链**：LoadImage → ImageScale → VAEEncode → ReferenceLatent。
         只摘 LoadImage 会留下"缺输入"的 ImageScale/VAEEncode ⇒ ComfyUI 直接 400
         （2026-09-23 二段坑 1：模板摊平出的节点问题只有真 POST 才暴露）。
      ② 也不能把 ReferenceLatent 整个删掉 —— 它是**串链**（conditioning 逐个往下传），
         删中间一个就断了整条 conditioning 链。
    ⇒ 正确做法：**摘掉该槽 ReferenceLatent 的 `latent` 入参**（该入参是 optional，不传即空过），
      再把从这个 LoadImage 出发的整条支链（ImageScale / VAEEncode）删掉。
    """
    if used <= 0:
        return 0
    # ★ 排序键必须与 _wf_set_image 完全一致（都是 str(id)），否则槽位对齐会错位。
    refs = sorted(_wf_of_class(graph, "ReferenceLatent"), key=lambda x: str(x[0]))
    imgs = sorted(_wf_of_class(graph, "LoadImage"), key=lambda x: str(x[0]))
    if used >= len(imgs) and used >= len(refs):
        return 0
    drop = {str(nid) for nid, _n in imgs[used:]}
    for nid, n in refs[used:]:
        ins = n.get("inputs")
        if isinstance(ins, dict):
            ins.pop("latent", None)   # optional ⇒ 不传 = 空过（ReferenceLatent 只是透传 conditioning）
    ref_ids = {str(nid) for nid, _n in refs}
    # 从被摘掉的 LoadImage **向下游**走（LoadImage→ImageScale→VAEEncode），遇 ReferenceLatent 停。
    frontier = list(drop)
    while frontier:
        nid = frontier.pop()
        for oid, on in list(graph.items()):
            if str(oid) in ref_ids or str(oid) in drop:
                continue
            ins = on.get("inputs") if isinstance(on, dict) else None
            if not isinstance(ins, dict):
                continue
            for _k, v in list(ins.items()):
                if isinstance(v, list) and v and str(v[0]) == nid:
                    drop.add(str(oid))
                    frontier.append(str(oid))
    for nid in drop:
        graph.pop(nid, None)
    for _nid, n in list(graph.items()):
        ins = n.get("inputs") if isinstance(n, dict) else None
        if not isinstance(ins, dict):
            continue
        for k, v in list(ins.items()):
            if isinstance(v, list) and v and str(v[0]) in drop:
                del ins[k]
    return len(drop)


def _wf_set_image(graph, filenames):
    """把（多）参考图写进工作流里的 LoadImage 节点；多个 LoadImage 按节点 id 顺序依次分配。"""
    if isinstance(filenames, str):
        filenames = [filenames]
    nodes = sorted(_wf_of_class(graph, "LoadImage"), key=lambda x: str(x[0]))
    if not nodes:
        return False
    for i, (nid, n) in enumerate(nodes):
        if i < len(filenames) and filenames[i]:
            n.setdefault("inputs", {})["image"] = filenames[i]
        elif filenames:
            n.setdefault("inputs", {})["image"] = filenames[0]   # 节点比参考图多 → 复用第一张
    return True


def _wf_latent_is_img2img(graph):
    """KSampler 的 latent 来自 VAEEncode ⇒ 真 img2img（要设 denoise）；来自 Empty*Latent ⇒ txt2img/Edit（denoise 必须 1.0）。

    踩过的坑：Edit 工作流也有 LoadImage 节点（参考图），但它用的是 EmptySD3LatentImage，
    若一律按“有参考图就设 denoise=0.5”，Edit 会只画一半步数 → 图糊。
    """
    if _wf_is_flux2(graph):
        sc = _wf_of_class(graph, "SamplerCustomAdvanced")
        if not sc:
            return False
        ref = sc[0][1].get("inputs", {}).get("latent_image")
        if not (isinstance(ref, list) and ref and isinstance(ref[0], str)):
            return False
        return graph.get(ref[0], {}).get("class_type") == "VAEEncode"
    ks = _wf_of_class(graph, "KSampler") or _wf_of_class(graph, "KSamplerAdvanced")
    if not ks:
        return False
    ref = ks[0][1].get("inputs", {}).get("latent_image")
    if not (isinstance(ref, list) and ref and isinstance(ref[0], str)):
        return False
    return graph.get(ref[0], {}).get("class_type") == "VAEEncode"


def _wf_apply_areas(graph, mode, pairs, log=print):
    """把「每个主体在画面里的位置」变成**区域条件**（位置优先于整段提示词）。

    pairs: [(ref_index, ref_filename, subject, x, y, w, h)]（归一化 0–1，w/h 表达远近与大小）
    做法：每个主体一个自己的条件节点（Edit 档带上**那个主体自己的定妆照**）→
         ConditioningSetAreaPercentage(conditioning,x,y,w,h) → 与全局正词 ConditioningCombine 合并 → 接给 KSampler.positive。
    为什么不用 ControlNet：位置=“谁在哪、多大”，区域条件最直接且不需要新模型（community 常用做法）。
    """
    ks = _wf_of_class(graph, "KSampler") or _wf_of_class(graph, "KSamplerAdvanced")
    if not ks:
        return False
    ks_id, ks_node = ks[0]
    base_ref = (ks_node.get("inputs") or {}).get("positive")
    if not (isinstance(base_ref, list) and base_ref):
        return False
    base = base_ref[0]
    # 参考图文件名 → LoadImage 节点 id
    by_name = {}
    for nid, n in _wf_of_class(graph, "LoadImage"):
        by_name[n.get("inputs", {}).get("image")] = nid
    prev = base
    n = 0
    for (idx, fname, subj, x, y, w, h) in pairs:
        n += 1
        label = subj or ("subject%d" % (idx + 1))
        txt_id = "area_txt_%d" % n
        if mode == "edit" and base in graph and str(graph[base].get("class_type", "")).startswith("TextEncodeQwenImageEdit"):
            tpl = graph[base]
            inp = dict(tpl.get("inputs") or {})
            # ★ 必须与基础正词节点**结构完全一致**（保留同样的 image1/image2/... 与 vae）：
            #   区域的 conditioning 与它合并时，参考 latent 数量/字段必须对齐，否则 KSampler 会
            #   `tuple index out of range`（2026-09-16 实测：只带 1 张参考图的区域节点合并后必崩）。
            #   区域只负责“文字/身份在哪块生效”，参考图仍旧用同一批。
            inp["prompt"] = ("%s — the character shown in reference image %d; keep exactly this character's face, "
                             "hairstyle, age and costume" % (label, idx + 1))
            graph[txt_id] = {"class_type": tpl.get("class_type"), "inputs": inp}
        else:
            # 非 Edit 档：普通 CLIPTextEncode（沿用正词节点的 clip）
            clip_ref = (graph.get(base, {}).get("inputs") or {}).get("clip")
            if not clip_ref:
                continue
            graph[txt_id] = {"class_type": "CLIPTextEncode",
                             "inputs": {"clip": clip_ref, "text": "%s (the character in this area)" % label}}
        set_id = "area_set_%d" % n
        graph[set_id] = {"class_type": "ConditioningSetAreaPercentage",
                         "inputs": {"conditioning": [txt_id, 0], "width": float(w), "height": float(h),
                                    "x": float(x), "y": float(y), "strength": 1.0}}
        comb_id = "area_comb_%d" % n
        graph[comb_id] = {"class_type": "ConditioningCombine",
                          "inputs": {"conditioning_1": [prev, 0], "conditioning_2": [set_id, 0]}}
        prev = comb_id
        log("[comfy] 区域条件 第%d个：%s x=%.3f y=%.3f w=%.3f h=%.3f" % (n, label, x, y, w, h))
    ks_node.setdefault("inputs", {})["positive"] = [prev, 0]
    return True


# ★ 2026-09-24：负词分组关键词（折进正词用，见 `_flux2_fold_negative`）
_FLUX2_NEG_IDENTITY = ("同一张脸重复", "换脸", "同一人出现两次", "身份混淆", "复制脸庞",
                       "same face", "face swap", "swap face", "identity confusion", "duplicate face")
# 正词里已经写了同类约束的判据（写了就不再补，避免重复）
_FLUX2_POS_IDENTITY_COVERED = ("互换面孔", "同一张脸", "重复出现",
                               "swap face", "same face", "appears twice")


def _image_edit_prefix(positive, negative, ref_names, is_flux2):
    """Edit 档的"怎么用这些参考图"前缀 + 负词补充（**单一真源**，worker 与 diag 共用）。

    为什么抽成函数（2026-09-23）：出图 A/B 诊断脚本也必须用**生产同一套**提示词前缀，否则对照无效
    （Qwen 用 "Picture N" / FLUX.2 用 "Reference Image N"、Flux2 不注负词 —— 两处各写一遍必然漂移）。
    返回 (positive, negative)；不改入参。
    """
    if not ref_names:
        return positive, negative
    # 槽位措辞必须跟模型走：FLUX.2 的参考图机制是 ReferenceLatent（官方模板措辞 Reference Image 1/2/…），
    # Qwen-Image-Edit 是 TextEncodeQwenImageEditPlus（内部拼 Picture 1/2/…）——
    # 拿 Qwen 的措辞去喂 FLUX.2，等于让模型去找一个不存在的 "Picture 2"。
    # 分隔符跟着语言走（中文「、」/ 英文「, 」）：别让模型看到 "Reference Image 1 Reference Image 2"
    # 这种无分隔堆叠 —— 它得先自己切词才能对上「第几张」。
    _sep = "、" if _looks_zh(positive) else ", "
    slot_hint = _sep.join(("Reference Image %d" if is_flux2 else "Picture %d") % (i + 1)
                          for i in range(len(ref_names)))
    if _looks_zh(positive):
        positive = ("参考图按送入顺序对应片中角色（%s）。每个角色的面容、发型、年龄与服装必须严格跟随"
                    "其自己的参考图；把角色放进下面描述的剧情场景里（背景/光线/机位/动作以文字描述为准，"
                    "**不要**保留参考图的纯色/白底写真背景）。场景：" % slot_hint) + (positive or "")
        if not is_flux2:
            negative = ((negative + ", ") if negative else "") + \
                       "白色背景, 纯色背景, 影棚背景, 角色设定图, 证件照, 正面证件照, 3d渲染, cgi"
    else:
        positive = ("The reference image(s) are %s and show this shot's character(s) in that order; keep each "
                    "character's face, hairstyle, age and costume strictly consistent with their own reference, "
                    "and place them into the scene described below (background / lighting / camera framing / "
                    "action follow the description; do NOT keep the plain or white studio backdrop of the "
                    "reference image(s)). Scene: " % slot_hint) + (positive or "")
        if not is_flux2:
            negative = ((negative + ", ") if negative else "") + \
                       "white background, plain backdrop, solid color background, studio portrait, character sheet, " \
                       "front facing ID photo, 3d render, cgi"
    return positive, negative


def _image_edit_prompt(positive, negative, ref_names, is_flux2):
    r"""Edit 档正词的**完整组装**（单一真源：生产 / diag / 测试共用 —— 三处各写一遍必然漂移）。

    顺序是**功能性的**，别调（2026-09-24 那次静默 bug 就是顺序错）：
      ① FLUX.2 槽位措辞改写：Java 侧那份 Qwen 口径 `Picture N (imageN)` → FLUX.2 口径；
      ② 加「怎么用这些参考图」前缀（`_image_edit_prefix`，FLUX.2 写官方的 `Reference Image N`）；
      ③ FLUX.2 折负词进正词（它没有负词通路），并把 negative 清空。
    为什么 ① 必须在 ② **之前**：② 写出来的已经是 FLUX.2 官方口径，① 的规则 `image\s*(\d+)` 会把
    刚写好的 "Reference Image 1" 再改一遍 → 正词变成「（Reference 参考图 1 Reference 参考图 2…）」，
    **官方口径从未进过模型**（2026-09-24 关键帧实测：faceid 身份 cos 0.49/0.54 → 0.27/0.17）。
    """
    if is_flux2 and FLUX2_SLOT_REWRITE and positive:
        _rw = _flux2_slot_rewrite(positive)
        if _rw != positive:
            print("[comfy] FLUX.2：参考槽措辞已改写（Picture N (imageN) → %s）—— 否则映射会丢"
                  % ("参考图 N" if _looks_zh(positive) else "Reference Image N"), flush=True)
        positive = _rw
    if ref_names:
        positive, negative = _image_edit_prefix(positive, negative, ref_names, is_flux2)
    if is_flux2:
        if FLUX2_FOLD_NEGATIVE:
            positive = _flux2_fold_negative(positive, negative, _looks_zh(positive))
        negative = ""
    return positive, negative


def _wf_save_prefix(graph, prefix):
    for _nid, n in _wf_of_class(graph, "SaveImage"):
        n.setdefault("inputs", {})["filename_prefix"] = prefix
    return True


def _wf_watch_nodes(graph):
    """采样/解码节点 id：让 worker 能用 /ws 或 /history 看进度（拿不到也不影响出图）。"""
    ids = []
    for ct in ("KSampler", "KSamplerAdvanced", "SamplerCustomAdvanced", "VAEDecode", "SaveImage"):
        ids += [nid for nid, _n in _wf_of_class(graph, ct)]
    return ids


def generate_via_workflow(client_id, payload, progress_fn=None, on_tick=None):
    """用「本机 ComfyUI 工作流」出图（Qwen-Image / FLUX 等）。

    工作流路径来自「生成引擎配置 → 服务地址 → 文生图」（worker 机器上的绕对路径）。参数注入规则：
      · 正/负词 → KSampler.positive/negative 接的那两个 CLIPTextEncode；
      · seed/steps/cfg/denoise → KSampler；
      · 尺寸 → EmptySD3LatentImage / EmptyLatentImage；
      · 主模型名 → UNETLoader.unet_name（配了 IMAGE_MODEL 才改）；
      · 参考图（本镜关键帧/定妆照）→ 上传后写入 LoadImage.image，并自动改走 img2img 工作流+denoise。

    返回与 generate() 同口径：[{filename, bytes, subfolder}]。
    """
    global COMFY
    params = payload.get("params") or {}
    positive = payload.get("positive_prompt") or ""
    negative = payload.get("negative_prompt") or ""
    seed = payload.get("seed")
    width = params.get("width") if isinstance(params.get("width"), int) else 1344
    height = params.get("height") if isinstance(params.get("height"), int) else 768
    prefix = "weaveora_wf%s" % (("_shot" + str(payload.get("shot_no"))) if payload.get("shot_no") else "")

    ref_names = []
    ref_keys = payload.get("referenceKeys") or []
    # ★ 2026-09-16（用户实测第 4 镜“张冠李戴”的一个真因）：参考图**上传失败不能静默跳过**。
    #   旧写法失败就 continue → 后面的图**前移一格**，而提示词里的 `Picture 2 = 可卿` 不会变，
    #   于是 Picture 2 实际装的是第三个主体的脸 → 两个角色的脸被互换（张冠李戴）。
    #   现在：任一图拿不到就**直接报错**，宁可让任务失败并给出可行动的信息，也不输出一张错脸的图。
    failed = []
    for i, key in enumerate(ref_keys[:3]):
        try:
            data, ctype = fetch_reference_bytes(key)
            nm = _upload_image(data, (key.split("/")[-1] or ("ref_%d.png" % i)), ctype or "image/png")
            if not nm:
                failed.append("#%d(%s): 上传未返回文件名" % (i + 1, key.split("/")[-1][:12]))
            else:
                ref_names.append(nm)
        except Exception as e:
            failed.append("#%d(%s): %s" % (i + 1, key.split("/")[-1][:12], e))
    if failed:
        raise ComfyError(
            "参考图上传失败 %d 张 → 已中止（继续跑会导致角色与参考图错位/张冠李戴）：%s。"
            "请重试；若持续失败，检查 /opt/weaveora 存储与 API 连通性。"
            % (len(failed), "; ".join(failed)))
    if len(ref_names) != len(ref_keys[:3]):
        raise ComfyError("参考图数量不一致（上传 %d / 期望 %d）→ 已中止，避免角色错位"
                         % (len(ref_names), len(ref_keys[:3])))
    if ref_names:
        print("[comfy] 参考图槽位映射：%s"
              % ", ".join("Picture %d = %s" % (i + 1, n) for i, n in enumerate(ref_names)), flush=True)
    # 选工作流：参考图锚定（Edit）> img2img（以参考为底）> 纯文生图
    if ref_names and IMAGE_EDIT_WF and os.path.exists(IMAGE_EDIT_WF):
        path, mode = IMAGE_EDIT_WF, "edit"
    elif ref_names and IMAGE_IMG2IMG_WF and os.path.exists(IMAGE_IMG2IMG_WF):
        path, mode = IMAGE_IMG2IMG_WF, "img2img"
    else:
        path, mode = IMAGE_TXT2IMG_WF, "txt2img"
    graph = _wf_load(path)
    # ★ FLUX.2 通路判定（一次算好）：它没有 KSampler，参数/文字/尺寸/参考图槽的注入路径全部不同。
    _is_flux2 = _wf_is_flux2(graph)
    if progress_fn:
        progress_fn(40, "sampling")
    # ★ Edit 档（Qwen-Image-Edit）：模型本来就是**「prompt + 参考图」同时输入**——
    #   一遍就能两头兼顾（人物按参考图、场景按分镜提示词），不需要“先场景再换脸”的两遍法
    #   （那样耗时翻倍、还可能中途跑偏）。这里只做两件小事：
    #   ① 正词前加一句“怎么用这些参考图”的指令（人不变、场景按下面描述、别把参考图的纯色背景搬过来）；
    #   ② 负词补上“白色背景/证件照/角色设定图”这类词（定妆照就是纯色背景，不补它很容易被沿习）。
    #   ★ 2026-09-16：① 的语言**跟随提示词语言** —— 用户把正词改成中文后，这里再插一句英文
    #     就变成“英文头 + 中文身 + 中文尾”，实测会让模型两头听（用户报过中英混杂）。
    #     槽位名用模型自己的 `Picture N` 口径（TextEncodeQwenImageEditPlus 内部就是这么拼的）。
    # ★ FLUX.2：负词架构上不生效（guidance 蒸馏 / BasicGuider 单条件）→ 产品 2026-09-23 裁定：折进正词。
    #   折完要把 negative 清空（在 _image_edit_prompt 里做），否则会被当成第二条 conditioner 去找位置。
    if _is_flux2 and (negative or "").strip():
        if FLUX2_FOLD_NEGATIVE:
            print("[comfy] FLUX.2：负词在 guidance 蒸馏下不生效 → 已折进正词（原文留档）：%s"
                  % negative.strip().replace("\n", " ")[:240], flush=True)
        else:
            print("[comfy] ⚠️ FLUX.2：负词被丢弃（WEAVEORA_FLUX2_FOLD_NEGATIVE=0，产品已确认无需）：%s"
                  % negative.strip().replace("\n", " ")[:240], flush=True)
    # ★ 正词组装**只走这一个真源**（改写 → 前缀 → 折负词；顺序在 _image_edit_prompt 里锁死）。
    #   2026-09-24 修：旧代码把「加前缀」写在「改写」之前 ⇒ 前缀里刚写好的官方口径 "Reference Image N"
    #   被改写规则又吃了一遍，正词里只剩「Reference 参考图 1 …」残句（官方口径从未进过模型）。
    positive, negative = _image_edit_prompt(positive, negative,
                                            ref_names if mode == "edit" else [], _is_flux2)
    _wf_inject_text(graph, positive, negative)
    _wf_inject_model(graph, IMAGE_MODEL)
    # LoRA（提速）：params.lora 优先（用于同镜同 seed 的 A/B 对照，不必重启 worker）> 环境变量/引擎配置
    _lora = params.get("lora") if isinstance(params.get("lora"), str) else ""
    _lora = (_lora or "").strip() or IMAGE_LORA
    _lora_s = (params.get("lora_strength") if isinstance(params.get("lora_strength"), (int, float))
               else IMAGE_LORA_STRENGTH)
    # ★ 只在 **Edit 通路（Qwen-Image-Edit-2511）** 挂 LoRA：
    #   蒸馏 LoRA 与基座严格绑定，挂到 txt2img 的 Qwen-Image 基座上会权重不匹配（报错或出鬼图）；
    #   而关键帧/定妆照（带参考图的那些）走的正是 edit 通路 —— 也正是 7~8 分钟那一批。
    # ★ 挂在哪一档：Qwen 只给 Edit 通路挂（蒸馏 LoRA 与基座严格绑定，挂到 Qwen-Image txt2img 基座上会不匹配）；
    #   FLUX.2 的 Turbo LoRA 是**在 dev 基座上训的**，t2i 与 edit 通用 ⇒ 两种模式都挂。
    lora_on = bool(_lora) and (mode == "edit" or _is_flux2) and _wf_inject_lora(graph, _lora, _lora_s)
    log_line = ("挂 LoRA %s @%.2f" % (_lora, float(_lora_s))) if lora_on else ""
    _wf_inject_size(graph, width, height)
    steps = IMAGE_STEPS or (params.get("steps") if isinstance(params.get("steps"), (int, float)) else 0)
    # 有 LoRA 且调用方没显式指定步数 → 用 LoRA 的工作点（Qwen 默认 4；FLUX.2 Turbo 默认 8），
    # 否则拿 40/20 步去跑蒸馏模型白浪费时间。
    _lora_steps = IMAGE_LORA_STEPS_FLUX2 if _is_flux2 else IMAGE_LORA_STEPS
    if lora_on and not isinstance(params.get("steps"), (int, float)) and _lora_steps > 0:
        steps = _lora_steps
    # cfg 优先级：引擎配置页（IMAGE_CFG）> payload.params.cfg > 工作流 JSON 自带值
    cfg = IMAGE_CFG if IMAGE_CFG > 0 else (params.get("cfg") if isinstance(params.get("cfg"), (int, float)) else None)
    # 有 LoRA 且调用方没显式指定 cfg → 用 LoRA 的工作点；
    #   拿引擎配置里的 40 步/cfg4.0 去跑 4 步蒸馏模型会直接出坏图（已实测）。
    #   ★ FLUX.2 这里承载的是 **guidance**（FluxGuidance），Turbo 档官方就是 4.0。
    _lora_cfg = IMAGE_LORA_CFG_FLUX2 if _is_flux2 else IMAGE_LORA_CFG
    if lora_on and not isinstance(params.get("cfg"), (int, float)) and _lora_cfg > 0:
        cfg = _lora_cfg
    is_i2i = _wf_latent_is_img2img(graph)
    denoise = 1.0
    if mode == "edit":
        # Edit 档：采样起点**就是**参考图（官方结构）→ denoise 必须 1.0，否则等于把定妆照原图半保留（白底/证件照感）
        denoise = 1.0
    elif ref_names and is_i2i:
        denoise = (params.get("denoise") if isinstance(params.get("denoise"), (int, float))
                   else IMAGE_DENOISE)
    # ★ P1 防复发（2026-09-18 用户实测事故）：多主体参考图却没走 Edit 通路 = **静默降级**，
    #   会把定妆照半重绘成「不像的定妆照」（img2img + denoise 0.65 + 槽位不足丢参考图）。
    _img_notes = []
    # ★ 2026-09-24：API 侧「位置不对称 → 整镜降级为纯文字」的提示（payload.layoutNote）
    #   必须随资产 notes 上报（前端资产卡 ⚠ 可见），不能只躺在后端日志/正词里 —— 用户看不到就等于没说。
    _layout_note = payload.get("layoutNote")
    if isinstance(_layout_note, str) and _layout_note.strip():
        print("[comfy] ⚠️ " + _layout_note.strip(), flush=True)
        _img_notes.append(_layout_note.strip())
    if len(ref_names) >= 2 and mode != "edit":
        _degraded = ("出图降级：参考图 %d 张但没有 Edit 通路（editWorkflow=%s）→ 已降级为 %s；"
                     "denoise 已强制 1.0（否则等于把参考图半重绘），并可能因槽位不足丢参考图。"
                     "请到「生成引擎配置 → GPU 服务器」检查图片工作流（editWorkflow）。"
                     % (len(ref_names), IMAGE_EDIT_WF or "未配置", mode))
        print("[comfy] ⚠️ " + _degraded, flush=True)
        denoise = 1.0
        _img_notes.append(_degraded)
    _wf_inject_sampler(graph, seed, steps, cfg, denoise)
    if ref_names:
        if not _wf_set_image(graph, ref_names):
            print("[comfy] 工作流 %s 无 LoadImage 节点，%d 张参考图未使用" % (os.path.basename(path), len(ref_names)),
                  flush=True)
        else:
            slots = len(_wf_of_class(graph, "LoadImage"))
            if len(ref_names) > slots:
                # ★ 静默丢图是最坏的一种：模型少一张身份锚定，用户只会看到「这镜人脸不对」
                print("[comfy] ⚠️ 参考图 %d 张 > 工作流槽位 %d 个 → 多出的**会被丢掉**（%s）；"
                      "该镜可能丢身份锚定，请给工作流加 LoadImage 槽或用更少主体"
                      % (len(ref_names), slots, ",".join(ref_names[:len(ref_names) - slots])), flush=True)
            pruned = (_wf_prune_flux2_refs(graph, len(ref_names)) if _is_flux2
                      else _wf_prune_unused_images(graph, len(ref_names)))
            if pruned:
                print("[comfy] 参考图 %d 张 < 工作流槽位 %d 个 → 已摘掉多余空槽 %d 个（避免重复注入同一张脸；"
                      "%s）" % (len(ref_names), slots, pruned, "FLUX.2 版：摘 latent 入参 + 整条支链" if _is_flux2
                              else "Qwen 版：摘 LoadImage"), flush=True)
    # ★ 位置优先：方案里每个主体在画面中的位置（x/y/w/h，归一化）→ 区域条件
    regions = payload.get("referenceRegions") or []
    subjects = payload.get("referenceSubjects") or []
    pairs = []
    for i, nm in enumerate(ref_names):
        reg = regions[i] if i < len(regions) else None
        if not isinstance(reg, dict):
            continue
        try:
            x, y = float(reg.get("x")), float(reg.get("y"))
            w, h = float(reg.get("w")), float(reg.get("h"))
        except (TypeError, ValueError):
            continue
        if w <= 0 or h <= 0:
            continue
        pairs.append((i, nm, (subjects[i] if i < len(subjects) else ""), x, y, w, h))
    if pairs:
        if _is_flux2:
            # FLUX.2 走 BasicGuider，同样没有 "area" 读点 ⇒ 区域条件既无效也可能报错。
            print("[comfy] FLUX.2：区域条件（ConditioningSetArea*）不被模型消费（BasicGuider 无 area 读点）"
                  "→ 位置只写进提示词（%d 个主体）" % len(pairs), flush=True)
        elif IMAGE_AREA_COND:
            _wf_apply_areas(graph, mode, pairs)
        else:
            # ★ 2026-09-19 定性（查过 ComfyUI 源码，不再只写“需开启”）：
            #   `ConditioningSetAreaPercentage` 对 Qwen-Image **架构上无效** ——
            #   `comfy/model_base.py` 全文只有 hiDreamO1 一个类读 `area`；Qwen-Image 的文本走
            #   `txt_in` **拼进序列**（joint attention），根本没有“文本该在哪块生效”的 cross-attn 掩码。
            #   所以开 WEAVEORA_IMAGE_AREA_COND=1 不是“启用功能”，而是“触发 KSampler 的
            #   IndexError: tuple index out of range”（2026-09-16 实测两轮）。
            #   位置要真生效只能走**图像**（采样起点/构图底图 + denoise<1），见后续路线 C。
            print("[comfy] 位置只写进提示词（路线A）：%d 个主体。注意：区域条件（ConditioningSetArea*）"
                  "对 Qwen-Image 本就不被模型消费（只有 HiDream 读 area），开启只会让 KSampler 报"
                  "IndexError，所以位置目前只是“文字暗示”：模型不认归一化数字，实测设 x=0.05 落到 x=0.40。"
                  % len(pairs), flush=True)
    _wf_save_prefix(graph, prefix)
    print("[comfy] 工作流出图：%s(%s) size=%dx%d steps=%s cfg=%s denoise=%s refs=%s%s%s"
          % (os.path.basename(path), mode, width, height, steps or "-", cfg if cfg is not None else "-",
             denoise, ",".join(ref_names) or "-",
             ("  " + log_line if log_line else ""),
             ("  ⚠️降级" if _img_notes else "")), flush=True)
    saved, COMFY = COMFY, _image_comfy()
    try:
        pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
        if progress_fn:
            progress_fn(70, "decoding")
        rec = _poll_history(client_id, pid, timeout=IMAGE_TIMEOUT, on_tick=on_tick)
        outs = _download_outputs(rec, prefix)
        if not outs:
            raise ComfyError("工作流出图无输出（%s）" % os.path.basename(path))
        # ★ 2026-09-21 出图后放大（默认关；WEAVEORA_IMAGE_UPSCALE=seedvr2 开启）：只作用于出图
        outs = _maybe_upscale_outputs(outs)
        # 降级提示随资产上报（前端资产卡 ⚠ 可见），不只藏在日志里
        if _img_notes:
            _note_txt = "；".join(_img_notes)
            for _o in outs:
                if isinstance(_o, dict):
                    _o["notes"] = ((_o.get("notes") + "；") if _o.get("notes") else "") + _note_txt
        return outs
    finally:
        COMFY = saved


class ComfyError(Exception):
    pass


class JobCancelled(Exception):
    """任务被用户在界面上取消（API 侧 cancelRequested=true）→ 立刻中断 ComfyUI 并退出。"""


# 由 stub_worker 在每个任务开始时设置：无参可调用对象，返回 True = 该任务已被取消。
# 放在模块级是为了让**所有**提交/轮询路径（still / clip / lipsync / 分段）共用同一个钩子，
# 而不是每个分支各写一遍（2026-09-16：取消原来只改 API 状态，worker 不知情 → 僵尸 prompt 堵队列）。
CANCEL_CHECK = None

# ★ 本任务是否已经做过"提交前清残留 prompt"（2026-09-16 修的 bug）：
#   旧写法每次都清，于是**重试提交时把我们自己刚提交、还在跑的 prompt 当成僵尸中断了**
#   （实测：Edit 档 20 步跑到一半被自己 interrupt，任务永远拿不到图）。
#   现在每个任务只清一次，由 stub_worker 在任务开始时 reset。
_ORPHAN_CLEAR_DONE = {"v": False}


def reset_job_state():
    _ORPHAN_CLEAR_DONE["v"] = False


def _maybe_cancelled():
    if not CANCEL_CHECK:
        return False
    try:
        return bool(CANCEL_CHECK())
    except Exception:
        return False


def _interrupt_comfy(reason=""):
    """中断 ComfyUI 当前执行（取消任务、清理残留 prompt 时用）。失败不致命。"""
    try:
        _comfy("POST", "/interrupt", timeout=30)
        print("[comfy] 已请求中断 ComfyUI 当前执行%s" % (("（%s）" % reason) if reason else ""), flush=True)
    except Exception as e:
        print("[comfy] /interrupt 失败（忽略）：%s" % e, flush=True)


def _clear_orphan_prompts():
    """提交前确保 ComfyUI 队列干净。

    我们**串行**提交，所以提交时队列里还有东西 = 上一个任务的僵尸 prompt（被取消/超时后残留）→
    它会堵在仓库前端，新任务自跑不了。2026-09-16 实测：一张关键帧白等好几分钟就是这个。
    """
    try:
        st, body = _comfy("GET", "/queue")
        q = json.loads(body or b"{}")
        running = len(q.get("queue_running") or [])
        pending = len(q.get("queue_pending") or [])
        if running or pending:
            print("[comfy] 队里还有残留 prompt（running=%d pending=%d）→ 先中断清空" % (running, pending), flush=True)
            _interrupt_comfy("清理残留")
            time.sleep(2.0)
        return running + pending
    except Exception as e:
        print("[comfy] 队列检查失败（忽略）：%s" % e, flush=True)
        return 0


def _api(path, payload=None, timeout=300):
    headers = {"X-Worker-Token": TOKEN}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        raise ComfyError("weaveora api %s -> %s %s" % (path, e.code, e.read()[:300]))


def _comfy(method, path, payload=None, files=None, timeout=120):
    headers = {}
    data = None
    if files:
        boundary = "----wf" + str(int(time.time() * 1e6))
        parts = []
        for field, (fname, content, ctype) in files.items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                          "Content-Type: %s\r\n\r\n" % (boundary, field, fname, ctype)).encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode())
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(COMFY + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, (r.read() or b"")
    except urllib.error.HTTPError as e:
        raise ComfyError("comfy %s %s -> %s %s" % (method, path, e.code, e.read()[:300]))


# ── 节点补丁版本/能力校验（2026-09-14）────────────────────────────────────
# 为什么：worker 跑在 API 服务器、LatentSync 节点跑在 GPU 服务器，节点侧只能手工同步。
# 曾实测：GPU 机上是旧副本（旧 inference.py 不认「内联 JSON 规格」）→ 退回「取最大脸」→
# 两段台词都驱动同一张脸、画面被毁，而 worker 这边完全看不出异常，只表现为「效果不对」。
# 所以这里先问节点要版本，缺能力就**直接失败**并给出修复指引，不再静默降级。
REQUIRED_NODE_FEATURES = ("point_lock", "inline_spec", "track_lock", "quality_gate", "paste_mask", "fps_pin")
NODE_PATCH_HOWTO = (
    "修复：在 GPU 服务器上执行\n"
    "  curl -fsSL https://sysou.com/weaveora-node/latentsync-node-patch.tar.gz -o /tmp/p.tar.gz"
    " && tar xzf /tmp/p.tar.gz -C /tmp && bash /tmp/latentsync-node/apply.sh\n"
    "然后**重启 ComfyUI**，再用 bash /tmp/latentsync-node/verify.sh 自检。"
    "（应急跳过：worker 环境变量 WEAVEORA_SKIP_NODE_CHECK=1）"
)
_NODE_VERSION_CACHE = {"at": 0.0, "base": None, "payload": None}


def _node_version(base, timeout=15, ttl=300):
    """取节点补丁版本（带缓存，避免每个任务都问一次）。拿不到返回 None。"""
    import time as _t
    now = _t.time()
    c = _NODE_VERSION_CACHE
    if c["payload"] is not None and c["base"] == base and (now - float(c["at"])) < ttl:
        return c["payload"]
    try:
        with urllib.request.urlopen(base + "/weaveora/version", timeout=timeout) as r:
            payload = json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        payload = None
    c.update({"at": now, "base": base, "payload": payload})
    return payload


def _require_node_features():
    """跑对口型前强校验 GPU 机上节点补丁的版本与能力。"""
    if os.environ.get("WEAVEORA_SKIP_NODE_CHECK", "") == "1":
        print("[comfy] 已跳过节点版本校验（WEAVEORA_SKIP_NODE_CHECK=1）", flush=True)
        return
    info = _node_version(COMFY)
    if info is None:
        raise ComfyError(
            "拿不到 GPU 服务器上 LatentSync 节点的版本接口（%s/weaveora/version）。\n"
            "这说明那台机器的节点还是**旧副本**（没打 Weaveora 补丁）—— 旧副本不认「点选人脸/内联规格」，"
            "会退回「取最大脸」，导致嘴型贴到别人脸上。\n%s" % (COMFY, NODE_PATCH_HOWTO))
    feats = info.get("features") or []
    missing = [f for f in REQUIRED_NODE_FEATURES if f not in feats]
    if missing:
        raise ComfyError(
            "GPU 服务器上 LatentSync 节点补丁**版本过旧**（版本 %s），缺少能力：%s。\n%s"
            % (info.get("version") or "未知", "、".join(missing), NODE_PATCH_HOWTO))
    print("[comfy] 节点补丁版本 %s，能力齐全 ✅" % info.get("version"), flush=True)


def _free_comfy_models(wait_gb=None, timeout=90):
    """让 ComfyUI 卸掉自己缓存的模型，把显存让给下一个「重量级且换模型」的任务（motion / lipsync）。

    ★ 2026-09-15 两个坑（都踩过）：
      ① 去掉 `--disable-smart-memory` 后模型会**常驻显存**（同 kind 连跑很快，这是我们要的），
         但切到另一种能力时（still 的 Qwen-Image ~26G → clip 的 A14B 双专家 ~44.5G）就容易 OOM；
      ② `/free` 的卸载是**异步**的：发完立刻量显存往往还没掉（实测 19.9G → 19.5G），
         旧代码量一次就往下走 → motion 直接在 KSamplerAdvanced 上 `torch.OutOfMemoryError`。
    所以：先 POST /free，再**轮询等到显存真的回来**（wait_gb 为目标，默认只等 1 次要求不苛刻）。
    """
    try:
        _comfy("POST", "/free", payload={"unload_models": True, "free_memory": True}, timeout=180)
        print("[comfy] 已请求卸载 ComfyUI 缓存模型（为下个重量级任务腾显存）", flush=True)
    except Exception as e:
        print("[comfy] /free 失败（忽略）: %s" % e, flush=True)
        return None
    if wait_gb is None:
        return None
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(3)
        try:
            free, _total = vram_stats()
        except Exception:
            free = None
        if free is not None and free >= wait_gb:
            print("[comfy] 卸载完成：可用显存 %.1f GiB（目标 %.1f）" % (free, wait_gb), flush=True)
            return free
    try:
        free, _total = vram_stats()
    except Exception:
        free = None
    print("[comfy] 等待卸载超时，当前可用 %.1f GiB（目标 %.1f）"
          % (free if free is not None else -1, wait_gb), flush=True)
    return free


def fetch_reference_bytes(storage_key):
    """经 weaveora 内部通道取参考图/参考音原始字节（token 鉴权）。

    注意：storage_key 必须是**存储 key**（形如 ws/project/ref/uuid.png），
    不是资产 UUID —— 传 UUID 服务端会 404（readAssetByKey 按 key 查）。
    """
    q = urllib.parse.quote(base64.urlsafe_b64encode(storage_key.encode()).decode(), safe="")
    req = urllib.request.Request("%s/internal/assets?key=%s" % (API, q),
                                 headers={"X-Worker-Token": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read(), (r.headers.get("Content-Type") or "image/png")
    except urllib.error.HTTPError as e:
        raise ComfyError("fetch ref asset %s -> %s（key 须为存储 key，不能传资产 UUID）"
                         % (storage_key, e.code))



_KS_INFO = None
_NODE_INFO = None


def _node_info(class_type):
    """缓存式节点探测：返回 object_info 字典或 None（节点不存在）。"""
    global _NODE_INFO
    if _NODE_INFO is None:
        _NODE_INFO = {}
    if class_type in _NODE_INFO:
        return _NODE_INFO[class_type]
    try:
        import urllib.request as _ur
        with _ur.urlopen(COMFY + "/object_info/" + urllib.parse.quote(class_type), timeout=15) as r:
            _NODE_INFO[class_type] = json.loads(r.read())
    except Exception:
        _NODE_INFO[class_type] = None
    return _NODE_INFO[class_type]


def _has_input(info, name):
    """节点是否有某输入（required/optional 都算）。"""
    if not info:
        return False
    node = next(iter(info.values())) if isinstance(info, dict) else None
    if not node:
        return False
    ipt = node.get("input") or {}
    for sec in ("required", "optional"):
        if name in (ipt.get(sec) or {}):
            return True
    return False


def _node_input_options(class_type, name):
    """从 object_info 取某输入的允许值列表（无则返回 []）。"""
    info = _node_info(class_type)
    if not info:
        return []
    node = next(iter(info.values())) if isinstance(info, dict) else None
    if not node:
        return []
    ipt = node.get("input") or {}
    for sec in ("required", "optional"):
        v = (ipt.get(sec) or {}).get(name)
        if isinstance(v, list) and v and isinstance(v[0], list):
            return v[0]
    return []


def _pick_option(class_type, name, requested, fallback):
    """在允许值里选：requested → fallback → 第一个；无约束则用 requested。"""
    opts = _node_input_options(class_type, name)
    if not opts:
        return requested if requested else fallback
    if requested in opts:
        return requested
    if fallback in opts:
        return fallback
    return opts[0]


def _rect_mask_png(width, height, region):
    """按归一化区域 {x,y,w,h} 生成灰度 PNG 遮罩（白=区域，黑=其余），纯标准库。"""
    w, h = int(width), int(height)
    x = int(round(float(region.get("x", 0)) * w))
    y = int(round(float(region.get("y", 0)) * h))
    rw = max(1, int(round(float(region.get("w", 0)) * w)))
    rh = max(1, int(round(float(region.get("h", 0)) * h)))
    rows = []
    white = b"\xff" * rw
    black_l = b"\x00" * max(0, x)
    black_r = b"\x00" * max(0, w - x - rw)
    empty_row = b"\x00" + b"\x00" * w
    for j in range(h):
        if y <= j < y + rh:
            rows.append(b"\x00" + black_l + white + black_r)
        else:
            rows.append(empty_row)
    raw = b"".join(rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def _upload_image(data, filename, ctype="image/png"):
    _, body = _comfy("POST", "/upload/image", files={"image": (filename, data, ctype)})
    return json.loads(body.decode()).get("name")


def _png_dims(data):
    """只读 PNG 头的宽高（不依赖 PIL）。非 PNG / 太短 → None。"""
    try:
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
            return None
        w, h = struct.unpack(">II", data[16:24])
        return int(w), int(h) if w > 0 else None
    except Exception:
        return None


def _seedvr2_graph(src_name, tw, th, steps):
    """SeedVR2 放大图（节点签名实测自 ComfyUI 0.34 的 /object_info）。"""
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": SEEDVR2_MODEL, "weight_dtype": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": SEEDVR2_VAE}},
        "12": {"class_type": "LoadImage", "inputs": {"image": src_name}},
        "13": {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos",
                                                    "width": int(tw), "height": int(th), "crop": "disabled"}},
        "14": {"class_type": "SeedVR2Preprocess", "inputs": {"resized_images": ["13", 0]}},
        "15": {"class_type": "VAEEncode", "inputs": {"pixels": ["14", 0], "vae": ["3", 0]}},
        "16": {"class_type": "SeedVR2Conditioning",
               "inputs": {"model": ["1", 0], "vae_conditioning": ["15", 0]}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["16", 0],
                                                   "negative": ["16", 1], "latent_image": ["15", 0],
                                                   "seed": 1234, "steps": int(steps), "cfg": 1.0,
                                                   "sampler_name": "euler", "scheduler": "simple",
                                                   "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "17": {"class_type": "SeedVR2PostProcessing",
               "inputs": {"images": ["10", 0], "original_resized_images": ["14", 0],
                          "color_correction_method": "lab"}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["17", 0], "filename_prefix": "weaveora_up"}},
    }


def _seedvr2_upscale(data):
    """把出图字节交给 SeedVR2 放大；成功返回 (新字节, 说明)。失败抛异常，由调用方兜底保原图。"""
    dims = _png_dims(data)
    if not dims:
        raise RuntimeError("不是可识别的 PNG，跳过放大")
    w, h = dims
    k = IMAGE_UPSCALE_SCALE if IMAGE_UPSCALE_SCALE > 1 else 2.0
    tw, th = int(round(w * k)), int(round(h * k))
    if max(tw, th) > IMAGE_UPSCALE_MAX:      # 只降不升：超上限时按上限等比缩
        f = IMAGE_UPSCALE_MAX / float(max(tw, th))
        tw, th = int(round(tw * f)), int(round(th * f))
    src = _upload_image(data, "wv_up_%d.png" % int(time.time() * 1000))
    if not src:
        raise RuntimeError("上传到 ComfyUI 失败")
    nc = "weaveora-upscale"
    pid = _post_prompt({"prompt": _seedvr2_graph(src, tw, th, IMAGE_UPSCALE_STEPS), "client_id": nc}, nc)
    rec = _poll_history(nc, pid, timeout=IMAGE_UPSCALE_TIMEOUT)
    outs = _download_outputs(rec, "weaveora_up")
    if not outs:
        raise RuntimeError("SeedVR2 放大无输出")
    return outs[0]["bytes"], "已 SeedVR2 放大 %d×%d → %d×%d" % (w, h, tw, th)


def _annotate_dims(outs):
    """★ 2026-09-21（§5-3）：把**实际字节**的宽高写回条目（`o["width"]`/`o["height"]`）。

    为什么：stub_worker 以前只用 payload 的 params 尺寸上报 complete —— 放大成功后 DB 里
    仍是放大前的 1664×928（磁盘文件其实 3328×1856），资产卡显示的数字与成片不符。
    `_download_outputs` 返回的条目本来只有 filename/bytes/subfolder，没有尺寸，所以这里补。
    读不出尺寸的（mp4/webp，非 PNG）保持原样不动。
    """
    for o in outs or []:
        d = _png_dims(o.get("bytes") or b"")
        if d:
            o["width"], o["height"] = d
    return outs


def _maybe_upscale_outputs(outs):
    """出图后的统一放大入口（开关关闭/失败 → 原样返回，绝不影响出图成功）。"""
    if not outs:
        return outs
    _annotate_dims(outs)          # ★ §5-3：先按真实字节记尺寸（关掉放大时也要记）
    if IMAGE_UPSCALE in ("", "0", "off", "none", "false"):
        return outs
    for o in outs:
        try:
            t0 = time.time()
            nb, note = _seedvr2_upscale(o["bytes"])
            if nb and len(nb) > len(o["bytes"]):
                d0, d1 = _png_dims(o["bytes"]), _png_dims(nb)
                o["bytes"] = nb
                if d1:
                    o["width"], o["height"] = d1    # ★ 放大后的尺寸才是落库/展示尺寸
                o["notes"] = ((o.get("notes") + "；") if o.get("notes") else "") + note
                print("[comfy] 出图放大：%s → %s  %.0fs（%s）"
                      % (d0, d1, time.time() - t0, IMAGE_UPSCALE), flush=True)
        except Exception as e:
            print("[comfy] WARN 出图放大失败（保留原图）：%s" % str(e)[:200], flush=True)
    return outs


def _sampler_options(kind, fallback):
    """从 Comfy object_info 读 KSampler 允许列表并缓存；返回 (list, chosen)。"""
    global _KS_INFO
    try:
        if _KS_INFO is None:
            import urllib.request as _ur
            _KS_INFO = json.loads(_ur.urlopen(COMFY + "/object_info/KSampler", timeout=15).read())
        opts = _KS_INFO["KSampler"]["input"]["required"][kind][0]
        if isinstance(opts, list) and opts:
            return opts, (fallback if fallback in opts else opts[0])
    except Exception:
        pass
    return [fallback], fallback


def _prompt(client_id, positive, negative, params, seed, width=None, height=None,
            reference_image_name=None, references=None, prefix="weaveora"):
    """构造 ComfyUI prompt（txt2img；带参考图则加 IP-Adapter 分支）。

    P5：多参考图 + 归一化区域遮罩（分区 IP-Adapter，按角色解耦）：
      references = [{"name": ..., "subject": "唐僧", "region": {x,y,w,h}|None}, ...]
    策略（节点探测）：IPAdapterAdvanced(attn_mask) > IPAdapterMS(mask) > 单图回退（只用主主体）。
    """
    cfg = float(params.get("cfg", 5.5))
    steps = int(params.get("steps", 30))
    requested = params.get("sampler", "dpmpp_2m")
    _, sampler = _sampler_options("sampler_name", requested)
    _, scheduler = _sampler_options("scheduler", params.get("scheduler", "normal"))
    width = int(width or params.get("width") or 1024)
    height = int(height or params.get("height") or 1024)

    nodes = {
        "ckpt": {"class_type": "CheckpointLoaderSimple",
                 "inputs": {"ckpt_name": params.get("model", "sd_xl_base_1.0.safetensors")}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"text": positive, "clip": ["ckpt", 1]}},
        "neg": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["ckpt", 1]}},
        "empty": {"class_type": "EmptyLatentImage",
                  "inputs": {"width": width, "height": height, "batch_size": 1}},
        "ksampler": {"class_type": "KSampler",
                     "inputs": {"model": ["ckpt", 0], "positive": ["pos", 0], "negative": ["neg", 0],
                                "latent_image": ["empty", 0],
                                "seed": int(seed or 1), "steps": steps, "cfg": cfg,
                                "sampler_name": sampler, "scheduler": scheduler,
                                "denoise": 1.0}},
        "vae": {"class_type": "VAEDecode", "inputs": {"samples": ["ksampler", 0], "vae": ["ckpt", 2]}},
        "save": {"class_type": "SaveImage",
                 "inputs": {"images": ["vae", 0], "filename_prefix": prefix}},
    }

    refs = list(references or [])
    if not refs and reference_image_name:
        refs = [{"name": reference_image_name, "subject": "", "region": None}]
    if refs:
        # IP-Adapter（参考图 → 主体一致性；P5 多主体按区域遮罩解耦）
        nodes["ip_unified"] = {"class_type": "IPAdapterUnifiedLoader",
                               "inputs": {"preset": params.get("ipadapter_preset",
                                                                   "STANDARD (medium strength)"),
                                          "model": ["ckpt", 0]}}
        weight = float(params.get("ipadapter_weight", 0.85))
        req_wtype = params.get("ipadapter_weight_type")
        adv = _node_info("IPAdapterAdvanced")
        adv_masked = _has_input(adv, "attn_mask")
        ms = _node_info("IPAdapterMS")
        ms_masked = (not adv_masked) and _has_input(ms, "mask")
        regional_ok = bool(params.get("ipadapter_regional", True)) and (adv_masked or ms_masked)
        has_region = any((r.get("region") or {}) for r in refs)
        if len(refs) > 1 and not (regional_ok and has_region):
            # 无遮罩能力/未标注区域：多图不能全挂（会串脸）→ 只用首张（generate 已把主主体排首位）
            print("[comfy] 多参考图但无分区能力/未标注区域，仅用首张（%s）"
                  % (refs[0].get("subject") or "-"), flush=True)
            refs = [refs[0]]
        prev_model = ["ip_unified", 0]
        for i, r in enumerate(refs):
            nodes["load_ref_%d" % i] = {"class_type": "LoadImage", "inputs": {"image": r["name"]}}
            inp = {"model": prev_model, "ipadapter": ["ip_unified", 1], "image": ["load_ref_%d" % i, 0],
                   "weight": weight, "start_at": 0.0, "end_at": 1.0,
                   "weight_type": _pick_option("IPAdapter", "weight_type", req_wtype, "standard")}
            region = r.get("region") or None
            cls = "IPAdapter"
            if regional_ok and region:
                try:
                    mask_name = _upload_image(
                        _rect_mask_png(width, height, region),
                        "wvmask_%d_%d.png" % (int(seed or 0), i))
                    nodes["mask_%d" % i] = {"class_type": "LoadImage", "inputs": {"image": mask_name}}
                    mask_out = ["mask_%d" % i, 1]  # LoadImage 的 MASK 输出
                    blur = _node_info("MaskBlur")
                    if blur is not None:
                        nodes["mask_blur_%d" % i] = {
                            "class_type": "MaskBlur",
                            "inputs": {"mask": mask_out,
                                       "blur_radius": int(params.get("ipadapter_mask_blur", 12)),
                                       "sigma": float(params.get("ipadapter_mask_sigma", 8.0))}}
                        mask_out = ["mask_blur_%d" % i, 0]
                    if adv_masked:
                        cls = "IPAdapterAdvanced"
                        inp["weight_type"] = _pick_option("IPAdapterAdvanced", "weight_type", req_wtype, "linear")
                        inp["embeds_scaling"] = _pick_option("IPAdapterAdvanced", "embeds_scaling",
                                                             params.get("ipadapter_embeds_scaling"), "V only")
                        inp["attn_mask"] = mask_out
                    else:
                        cls = "IPAdapterMS"
                        inp["weight_type"] = _pick_option("IPAdapterMS", "weight_type", req_wtype, "linear")
                        inp["embeds_scaling"] = _pick_option("IPAdapterMS", "embeds_scaling",
                                                             params.get("ipadapter_embeds_scaling"), "V only")
                        inp["mask"] = mask_out
                except Exception as e:
                    print("[comfy] 区域遮罩构建失败，退化为全局 IP-Adapter: %s" % e, flush=True)
                    cls = "IPAdapter"
            nodes["ip_%d" % i] = {"class_type": cls, "inputs": inp}
            prev_model = ["ip_%d" % i, 0]
        nodes["ksampler"]["inputs"]["model"] = prev_model

    # ComfyUI 需要节点 id 为字符串键 + client_id
    return {"prompt": nodes, "client_id": client_id}


def _poll_history(client_id, prompt_id, poll=2.0, timeout=600, on_tick=None):
    """轮询 /history 直到成功/失败/超时。

    on_tick(elapsed_sec) 每轮回调一次（用于上报“已运行 N 分钟”，
    否则对口型 25–30 分钟期间 UI 会一直停在 40%，看着像卡死）。

    ★ 每轮还检查一次「任务是否已被取消」（CANCEL_CHECK）：是则 **/interrupt 中断 ComfyUI** 并抛 JobCancelled，
    不再白等到 timeout（那是僵尸 prompt 堵队列的根源）。
    """
    deadline = time.time() + timeout
    t0 = time.time()
    while time.time() < deadline:
        if _maybe_cancelled():
            _interrupt_comfy("任务已取消")
            raise JobCancelled("任务已被取消（已中断 ComfyUI）")
        st, body = _comfy("GET", "/history/" + prompt_id)
        if st == 200:
            data = json.loads(body or b"{}")
            rec = data.get(prompt_id)
            if rec:
                status = (rec.get("status") or {})
                if status.get("status_str") == "success":
                    return rec
                if status.get("status_str") == "error":
                    msgs = status.get("messages", [])
                    raise ComfyError("comfy error: " + str(msgs[-1] if msgs else status)[:500])
        if on_tick:
            try:
                on_tick(time.time() - t0)
            except Exception:
                pass
        time.sleep(poll)
    raise ComfyError("comfy prompt %s timeout" % prompt_id)


def _download_outputs(rec, prefix="weaveora"):
    outs = []
    outputs = rec.get("outputs") or {}
    for node in outputs.values():
        # ★ 2026-09-23：SaveVideo / SaveWEBM 之类把产物放在 videos/gifs 键下（LTX-2.5 走 SaveVideo 出 mp4）。
        for vid in (node.get("videos") or []) + (node.get("gifs") or []):
            fname = vid.get("filename", "")
            if not fname.startswith(prefix):
                continue
            sub = vid.get("subfolder") or ""
            typ = vid.get("type") or "output"
            q = urllib.parse.urlencode({"filename": fname, "subfolder": sub, "type": typ})
            _, body = _comfy("GET", "/view?" + q)
            outs.append({"filename": fname, "bytes": body, "subfolder": sub})
        for img in (node.get("images") or []):
            fname = img.get("filename", "")
            if not fname.startswith(prefix):
                continue
            sub = img.get("subfolder") or ""
            typ = img.get("type") or "output"
            q = urllib.parse.urlencode({"filename": fname, "subfolder": sub, "type": typ})
            _, body = _comfy("GET", "/view?" + q)
            outs.append({"filename": fname, "bytes": body, "subfolder": sub})
    return outs


def generate(client_id, payload, progress_fn=None):
    """执行一个 job payload；返回输出图片字节列表（png）。"""
    if progress_fn:
        progress_fn(30, "sampling")

    positive = payload.get("positive_prompt", "")
    negative = payload.get("negative_prompt", "")
    params = payload.get("params") or {}
    seed = payload.get("seed")
    width = (params.get("width") if isinstance(params.get("width"), int) else None)
    height = (params.get("height") if isinstance(params.get("height"), int) else None)
    prefix = "weaveora" + (("_" + str(payload.get("shot_no") or "")) if payload.get("kind") == "video" else "")

    refs = []
    ref_keys = payload.get("referenceKeys") or []
    ref_subjects = payload.get("referenceSubjects") or []
    ref_regions = payload.get("referenceRegions") or []
    primary = payload.get("primarySubject") or ""
    for i, key in enumerate(ref_keys[:4]):
        try:
            data, ctype = fetch_reference_bytes(key)
            name = _upload_image(data, key.split("/")[-1] or ("ref_%d.png" % i), ctype or "image/png")
            region = ref_regions[i] if i < len(ref_regions) else None
            if not isinstance(region, dict):
                region = None
            subject = ref_subjects[i] if i < len(ref_subjects) else ""
            if name:
                refs.append({"name": name, "subject": subject, "region": region})
        except Exception as e:
            print("[comfy] ref#%d 上传失败，跳过: %s" % (i, e), flush=True)
    # 主主体排首位（单图回退与权重聚焦都按首位）
    if primary:
        refs.sort(key=lambda r: 0 if r.get("subject") == primary else 1)
    n_regions = sum(1 for r in refs if r.get("region"))
    if refs:
        print("[comfy] refs=%d regions=%d primary=%s" % (len(refs), n_regions, primary or "-"), flush=True)

    last_err = None
    try:
        prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                         references=refs, prefix=prefix)
        pid = _post_prompt(prompt, client_id)
        rec = _poll_history(client_id, pid)
        return _download_outputs(rec, prefix)
    except ComfyError as e:
        last_err = e
        # IP-Adapter 图未用上或节点缺失 → 降级纯 txt2img
        if refs and FALLBACK:
            prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                             references=None, prefix=prefix)
            pid = _post_prompt(prompt, client_id)
            rec = _poll_history(client_id, pid)
            return _download_outputs(rec, prefix)
        raise


# ★ 2026-09-24：换模型前先让 ComfyUI **卸掉上一套**（防“换模型 OOM”）。
#   事故（真实任务，非压测）：2026-09-24 00:00 第一镜跑完关键帧（FLUX.2 主模型 33 GB + 文字编码器 16.8 GB）
#   → 紧接着跑 motion（LTX 20 GB + gemma 14 GB）⇒ 47 GiB 内存的盒上 anon-rss 顶到 **47.8 GB**，
#   OOM killer 杀掉 ComfyUI（**整栈连同网关一起死**），而 worker 收到的只是
#   `COMFY_ERROR: <urlopen error [Errno 111] Connection refused>` —— 现象与根因隔着两层，极易误判。
#   为什么 `--cache-ram` 调参治不了：被赖着不走的是“上一个正在用的模型”（active pin），
#   `model_management.ensure_pin_budget()` 只在 `evict_active=True` 时才动它，而**加载路径不传**这个参数。
#   解法：ComfyUI 自带 `POST /free {"unload_models": true}`
#   （`server.py:1192` 设 flag → `main.py:424 unload_all_models()`），
#   而主循环**空闲时也会读 flag** ⇒ 提交前调一下，就能保证新模型加载时旧的已经卸完。
# ★ 跨模型家族切换时必须重启 ComfyUI（本机装不下两套大模型）—— 开关，默认开。
COMFY_RELOAD_ON_SWITCH = (os.environ.get("WEAVEORA_COMFY_RELOAD_ON_SWITCH", "1") or "1").strip() == "1"
# 与盒上 edge_proxy.py 的 `WEAVEORA_EDGE_ADMIN_TOKEN` 必须一致；未配置则跳过重启（只告警）
EDGE_ADMIN_TOKEN = os.environ.get("WEAVEORA_EDGE_ADMIN_TOKEN", "").strip()
_MODEL_KEY_LAST = {"v": ""}
# 小件（3 GB 的放大模型等）：不值得为它触发一次重启（否则“出图→放大→再出图”会把 33 GB 反复重载）
_MODEL_KEY_SKIP = ("seedvr2",)


def _graph_model_key(prompt):
    """从待提交的 prompt 图里抽出“这活要加载哪些大模型”的指纹（用来判是否换模型）。

    只看三类加载器（UNETLoader / CheckpointLoaderSimple / CLIPLoader）—— 它们才是吃内存的大头；
    VAE 不纳入（几十 MB，纳入了会因为 t2i/edit 用不同 VAE 而误触发卸载）。
    自研节点（LatentSync/talk 等）的图里没有这些类 → key 为空 → 直接跳过（它们走别的服务）。
    """
    graph = (prompt or {}).get("prompt")
    if not isinstance(graph, dict):
        return ""
    parts = []
    for ct, key in (("UNETLoader", "unet_name"), ("CheckpointLoaderSimple", "ckpt_name"),
                    ("CLIPLoader", "clip_name")):
        for _nid, n in _wf_of_class(graph, ct):
            v = (n.get("inputs") or {}).get(key)
            if isinstance(v, str) and v and not any(s in v for s in _MODEL_KEY_SKIP):
                parts.append("%s:%s" % (ct, v))
    return "|".join(sorted(parts))


def _comfy_vram_used_gb():
    """问 ComfyUI 要“当前已占用多少显存”（拿不到返回 None）。

    用途：worker 进程刚重启时，进程内没有“上次用了哪套模型”的记忆，
    但盒上可能还驻留着上一套（例：刚跑完出片，LTX 34 GB 还在显存里）。
    此时直接提交出图任务就是叠上去 → OOM。所以要看这个数做判断。
    """
    try:
        with urllib.request.urlopen(_image_comfy() + "/system_stats", timeout=8) as r:
            d = json.loads(r.read())
        dev = (d.get("devices") or [{}])[0]
        total = dev.get("vram_total") or 0
        free = dev.get("vram_free") or 0
        return (total - free) / (1024 ** 3) if total else None
    except Exception:
        return None


def _edge_reload_comfy(reason):
    """请盒上网关重启 ComfyUI 并等到就绪（返回是否确认就绪）。"""
    if not (COMFY_RELOAD_ON_SWITCH and EDGE_ADMIN_TOKEN):
        print("[comfy] ⚠️ 未启用/未配置重启通道（WEAVEORA_COMFY_RELOAD_ON_SWITCH=%s token=%s）"
              "—— %s，本次很可能 OOM，请人工看内存"
              % (COMFY_RELOAD_ON_SWITCH, "有" if EDGE_ADMIN_TOKEN else "无", reason), flush=True)
        return False
    try:
        req = urllib.request.Request(
            _image_comfy() + "/__edge/reload_comfy", data=b"{}", method="POST",
            headers={"Content-Type": "application/json", "X-WV-Token": EDGE_ADMIN_TOKEN})
        with urllib.request.urlopen(req, timeout=30) as r:
            r.read()
        t0 = time.time()
        while time.time() - t0 < 180:          # 冷启通常 10~40s
            time.sleep(3)
            if _comfy_vram_used_gb() is not None:
                print("[comfy]    ComfyUI 重启完成（%.0fs）—— 本次将付一次冷加载" % (time.time() - t0),
                      flush=True)
                return True
        print("[comfy] ⚠️ ComfyUI 重启后 180s 仍未就绪（继续提交，可能失败）", flush=True)
        return False
    except Exception as e:  # noqa: BLE001
        print("[comfy] ⚠️ 重启请求失败（本次可能 OOM/Connection refused）：%s" % e, flush=True)
        return False


def _unload_before_model_switch(prompt):
    """决定本次提交是否需要**先重启 ComfyUI**，需要就重启并等就绪（返回是否重启了）。

    ★ 2026-09-24 实测结论（三层，全是真事故/真实测，不是推测）：
      1) 不变重启 = OOM：盒是 48 GB 内存 / 48 GB 显存，**图像家族 49.8 GB**（FLUX.2 主模型 33 + 编码器 16.8）
         与**视频家族 34 GB**（LTX 20 + gemma 14）装不下；上一套还钉在内存里就装新一套 ⇒
         `anon-rss 47.8 GB` 被 OOM killer 杀，整栈连网关一起死，worker 只看到 `Connection refused`
         （真实任务两次：关键帧→motion 失败；worker 重启后首次出图失败）。
      2) `POST /free {"unload_models": true}` **不能用**：实测它把 33 GB 权重从显存**卸到内存**
         （anon 13.5 → 45.3 GB，整机 available 只剩 179 MB）= 往 OOM 枪口上撞。
         根因：`free_memory()` 对 `sys.getrefcount(model) > 1`（被执行缓存引用中）的模型只 offload 不释放。
      3) 唯一可靠的手段 = **重启 ComfyUI**（~10–18 秒回来、其它服务不受影响）。
         而 VPS worker **没有**盒上 SSH 权限（实测 `Permission denied`）⇒ 走我们自己网关的
         `POST /__edge/reload_comfy`（`edge_proxy.py`，带 `X-WV-Token`）。

    两类触发条件：
      a) **跨模型家族**（指纹变化）—— 正常路径；
      b) **本进程首次提交但盒上已有大模型驻留**（进程重启/部署后没记忆）—— 实战踩过：
         我的 diag 把 LTX 留在显存 → worker 重启后直接出图 → 叠上去 OOM。
         另：**ComfyUI 压根连不上**时也走重启（顺带自愈，而不是交个 502 出去）。

    代价：重启后本次任务付一次冷加载（~1–2 分钟）—— 比 OOM 杀进程便宜得多。
    """
    key = _graph_model_key(prompt)
    last = _MODEL_KEY_LAST["v"]
    if not key:
        return False
    used = _comfy_vram_used_gb()
    need, why = False, ""
    if used is None:
        need, why = True, "ComfyUI 不可达（可能是上次 OOM/重启没起来）→ 先重启自愈"
    elif last and last != key:
        need, why = True, ("跨模型家族切换（装不下两套）\n        旧：%s\n        新：%s" % (last, key))
    elif (not last) and used > 8:
        need, why = True, ("本进程首次提交，但盒上已驻留 %.1f GB 显存（无法确认是哪套模型）" % used)
    if need:
        print("[comfy] ⚠️ 需重启 ComfyUI 释放上一套：%s" % why, flush=True)
        _edge_reload_comfy(why)
    _MODEL_KEY_LAST["v"] = key
    return True


def _reload_before_familyless_task(reason):
    """给**没有 UNETLoader/CLIPLoader 指纹**的通路（lipsync/talk 等自研节点）用的重启守卫。

    为什么需要：`_unload_before_model_switch` 靠图里的加载器指纹判家族，而 lipsync/talk 的图里
    只有自研节点 ⇒ `key` 为空 ⇒ 直接 return False（不做任何释放）。于是它只能靠 `/free`——
    而 `/free` 恰恰是把上一套（FLUX.2 33 GB）**从显存搬进内存**、不是释放。
    实测同一机制已造成一次真事故（2026-09-24 10:54 motion：/free 后 13 秒 OOM，anon-rss 47.8 GB，
    整栈连网关一起死、worker 只看到 Connection refused）。

    策略：只要上一套是“大模型家族”（`_MODEL_KEY_LAST` 非空，即刚跑过出图/出片），就先重启 ComfyUI
    把这套卸干净；连续跑同类任务时 last 已置空 ⇒ 不会再重启。
    """
    if not _MODEL_KEY_LAST["v"]:
        return False
    print("[comfy] ⚠️ 需重启 ComfyUI 释放上一套：%s（上一套模型 = %s）"
          % (reason, _MODEL_KEY_LAST["v"]), flush=True)
    _edge_reload_comfy(reason)
    _MODEL_KEY_LAST["v"] = ""
    return True


def _post_prompt(prompt, client_id):
    """POST /prompt；对偶发的 prompt 校验失败重试 1 次（同图重投，服务端状态问题）。"""
    _unload_before_model_switch(prompt)
    for attempt in (1, 2):
        try:
            # 串行提交前提下，队里有东西就是上一个任务的僵尸 prompt → 清掉（否则本任务白等）。
            # ★ 但每个任务**只清一次**：清了之后就认为队列里的东西是我们自己的（重试提交时绝不能自中断）。
            if not _ORPHAN_CLEAR_DONE["v"]:
                _clear_orphan_prompts()
                _ORPHAN_CLEAR_DONE["v"] = True
            st, body = _comfy("POST", "/prompt", payload=prompt)
            pid = json.loads(body.decode()).get("prompt_id")
            if not pid:
                raise ComfyError("comfy /prompt 无 prompt_id")
            return pid
        except ComfyError as e:
            if attempt == 1 and "failed_validation" in str(e):
                sys.stderr.write("[comfy] prompt validation 偶发失败，重试: %s\n" % str(e)[:600])
                time.sleep(1.5)
                continue
            raise



# ---------------------------------------------------------------------------
# 图生视频（motion）模型档案：Wan2.2 I2V-A14B **双专家 MoE**（ComfyUI 原生节点）
#
#   为什么不是单专家 5B、也不是「4 步蒸馏一路到底」：
#     Wan2.2 A14B 的**高噪声专家**负责整体布局与**大幅运动**，低噪声专家负责细节收尾。
#     把 4-step 蒸馏 LoRA 同样压在高噪声专家上 → 运动幅度被压扁（现象：慢动作 / 动态丢失）。
#     ⇒ 修法：高噪声专家**少蒸馏或完全不蒸馏**（强度 0 = 该专家不加 LoRA），低噪声专家可足量蒸馏。
#     实测口径（GPU#2 48G / 2026-09-14）：给 fp8 高噪声专家挂 LoRA，ComfyUI 要额外
#     dequantize 一份权重（13.3GiB fp8 → ~28GiB fp16）→ **48G 卡也 OOM**；且动态会被压扁。
#     ⇒ 默认 high=0.0（只给低噪声专家蒸馏）、low=1.0（换 LoRA 家族时强度约定会变，见下）。
#
#   档位（payload["params"]["preset"]；每一项都能用显式键覆盖）：
#     draft    4 步  switch 2   最快，动态最弱（只看构图）——官方加速档骨架
#     balanced 6 步  switch 3   默认生产档
#     motion   8 步  switch 4   动态优先
#     hero    20 步  switch 10  ★官方质量档（ComfyUI 官方模板口径）：不蒸馏 + cfg 3.5
#     full    40 步  switch 36  ★官方原厂档（Wan 仓库 wan_i2v_A14B.py）：不蒸馏 + cfg 3.5
#   切分口径：高/低噪声按总步数折半（4→2、6→3、8→4、20→10），与官方/社区一致；
#   full 例外：官方 `boundary=0.900` 按**时间步**切专家 → 步序近似 0.9×40 = 36。
#
#   显式覆盖键：steps / switch_step / cfg_high / cfg_low / cfg / lora_high / lora_low /
#               lora_high_name / lora_low_name / shift / sampler_name / scheduler /
#               model_high / model_low / model（单专家旧路径）/ vae / text_encoder / weight_dtype
#   环境默认档：WEAVEORA_MOTION_PRESET（缺省 balanced）
#   注：旧 engine settings 里的 params.steps / params.cfg 仍会被采纳（不静默替换用户设置），
#       但「蒸馏 LoRA + steps>12」是异常组合，会打 WARN。
# ---------------------------------------------------------------------------
MOTION_MODEL_HIGH = "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors"
MOTION_MODEL_LOW = "wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors"
MOTION_LORA_HIGH = "Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
MOTION_LORA_LOW = "Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
MOTION_VAE = "wan_2.1_vae.safetensors"          # I2V-A14B 用 2.1 VAE；wan2.2_vae 是 TI2V-5B 的
MOTION_TEXT_ENCODER = "umt5_xxl_fp8_e4m3fn_scaled.safetensors"

MOTION_PRESETS = {
    # ★ 2026-09-14 实测修正（GPU#2 48G）：**高噪声专家一律不蒸馏（lora_high=0）**。
    #   原因：给 fp8 的高噪声专家挂 LoRA 时，ComfyUI 需额外 dequantize 一份权重
    #   （13.3 GiB fp8 → 28 GiB fp16）× 两专家 → 在 **48GB 卡上也会 OOM**（实测 draft/balanced
    #   报 "Allocation on device ... out of memory"）；而 lora_high=0 的 hero 档实测通过：
    #   832×480 / 33 帧 / 6 步，**显存峰值 41.5 GiB、54 秒**出片。
    #   这正合本方案的初衷：高噪声专家负责大幅运动，越蒸馏动态越扁 —— 所以只给**低噪声**专家
    #   足量蒸馏，高噪声靠步数/cfg 调。需要旧行为时显式传 params.lora_high。
    "draft":    {"steps": 4,  "switch": 2,  "cfg_high": 1.0, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "balanced": {"steps": 6,  "switch": 3,  "cfg_high": 1.0, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "motion":   {"steps": 8,  "switch": 4,  "cfg_high": 1.5, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    # ★ 2026-09-20 对齐官方（两个官方工作点，均**不蒸馏**；要蒸馏档用 draft/balanced/motion）：
    #   hero = ComfyUI 官方模板 `video_wan2_2_14B_i2v.json` 的**质量档**：steps 20 / switch 10 / cfg 3.5
    #          （该模板 MarkdownNote 同时给出官方耗时：4090D 24G @640×640，无 LoRA ≈536s / 挂 4 步 LoRA ≈97s）
    #   full = Wan 官方仓库 `wan/configs/wan_i2v_A14B.py` 的**原厂推理默认**：
    #          sample_steps 40 / sample_shift 5.0 / sample_guide_scale (3.5, 3.5) / boundary 0.900 → switch 36
    #   旧值（hero 6 步挂低噪蒸馏 / full 24 步）已弃用：hero 那套实测「冲」，见 docs/notes/出图管线-经验与坑.md。
    # ★ 2026-09-22 用户裁定：**两档 non-distilled 档的 shift 由 5.0 改为 8.0**。
    #   依据（社区/模板一致口径，非官方文档）：`ModelSamplingSD3` 在**非蒸馏标准档 = 8.0**，
    #   只有「4 步蒸馏档」才用 5.0；我们跑的是 20/40 步非蒸馏，却一直用着蒸馏档的 5.0 → 口径错配。
    #   注：Wan 仓库 gui.py 对小尺寸（832×480 等）反而把 sample_shift 降到 3.0，两种口径不冲突但不同源；
    #   本次按 ComfyUI 模板（我们实际运行的运行时）对齐。回退：改这一行 + 清 DB video_params.shift。
    "hero":     {"steps": 20, "switch": 10, "cfg_high": 3.5, "cfg_low": 3.5, "lora_high": 0.0, "lora_low": 0.0, "shift": 8.0},
    "full":     {"steps": 40, "switch": 36, "cfg_high": 3.5, "cfg_low": 3.5, "lora_high": 0.0, "lora_low": 0.0, "shift": 8.0},
}
# 给高噪声专家挂 LoRA 的显存风险阈值：超过它就在日志里点名提醒（48G 实测 0.6 也 OOM）
MOTION_LORA_HIGH_WARN = 0.0
MOTION_PRESET_ENV = os.environ.get("WEAVEORA_MOTION_PRESET", "balanced").strip().lower()

# ★ 引擎配置下发的 motion 档位（随任务下发，见 api/…/EngineSettingsService.motionServices）。
#   为什么需要：clip 的 payload.params 只带 {width,height}，否则「UI 改档位没反应」。
#   生效优先级：payload.params（每镜显式） > 这里的下发值 > MOTION_PRESETS 档位默认。
MOTION_OVERRIDES = {}
MOTION_SERVICE_KEYS = ("preset", "steps", "switch", "switch_step", "cfg", "cfg_high", "cfg_low",
                       "lora_high", "lora_low", "lora_high_name", "lora_low_name", "shift",
                       "sampler_name", "scheduler", "model_high", "model_low", "mode", "dual",
                       "width", "height", "frames", "fps",
                       # ★ 2026-09-23：出片引擎选择（wan22 = 现役 Wan2.2 I2V；ltx25 = LTX-2.5 生产档）。
                       #   必须放进白名单，否则引擎配置页下发到 services.motion.engine 会被静默丢弃（四段坑 3）。
                       "engine",
                       # ★ 2026-09-22（用户报「视频分辨率 720p 不可用」）——`resolution` 之前**不在名单里**：
                       #   引擎配置页把「GPU 服务器最大支持分辨率」写进 services.motion.resolution，
                       #   到这里被 continue 掉 → _motion_resolution 只读得到 params.resolution
                       #   （clip 的 payload.params 里没这个键）⇒ **永远回落 480p 桶**，而且不报错、日志无异常。
                       #   教训：新增配置项必须同时改 API 与 worker 的接收白名单（见四段交接 §5-3）。
                       "resolution",
                       # ★ 2026-09-23（P5）：LTX-2.5 **时间轴 ×2**（24→48fps，走 *_48fps 工作流）。
                       #   与 API 的 `MOTION_KEYS` + 前端 `MOTION_KEYS` **三处必须成对** ——
                       #   少一处就是「UI 上配了 48fps，实际还是 24fps」的静默失效。
                       "fps_x2")

# 显存口径（A14B 双专家实测，GPU#2 48G，832×480）：
#   · 33 帧 → 峰值 41.8 GiB；121 帧 → 峰值 47.3 GiB（线性拟合）
#     ⇒ 峰值 ≈ BASE + PER_FRAME×帧数，BASE≈39.7 GiB、每帧≈0.0625 GiB（832×480）
#   · 同时实测：**64/84 帧反而 OOM、而 121 帧能过** ⇒ OOM 不是帧数导致的，
#     是**共存争显存**（CosyVoice 常驻 ~7GiB + ComfyUI 缓存双专家 26.6GiB）。
#     所以阈值用「运行时可用显存 vs 预估需求」而非常量帧上限；不够先让 TTS 让位，再不够就明确报错。
MOTION_VRAM_BASE_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_BASE_GB", "39.7") or 39.7)
MOTION_VRAM_PER_FRAME_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_PER_FRAME_GB", "0.0625") or 0.0625)
MOTION_VRAM_AREA_REF = 832.0 * 480.0   # 标定分辨率（换成其它分辨率按面积等比缩放）
# 卡片预留（GiB）：预估值与总显存留这么多余地；只用来挡「根本放不下」的组合。
# 注意：**不能拿 /system_stats 的 vram_free 当判据** —— ComfyUI 自己缓存的双专家（26.6GiB）
# 是可以回收的，实测就吃过这个坑：1280x704/48 帧被误拦（当时 free 只有 20GiB）。
MOTION_VRAM_SAFETY_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_SAFETY_GB", "4") or 4)
# 自动降分辨率的**下限**（长边），避免“为了塞下无限降”：512 长边在 16:9 下约 512x288
MOTION_MIN_SIDE = int(os.environ.get("WEAVEORA_MOTION_MIN_SIDE", "512") or 512)
# ★ 2026-09-15 从 0 改为 4：旧值 0 = 没有余量，于是 80 帧 @832×464（预估 44.5 GiB / 总 47.4 GiB）
# “体检通过”但实际在 KSamplerAdvanced 上 `torch.OutOfMemoryError: Allocation on device`
# （预估没有算显存碎片、CUDA 上下文、VAE 解码瞬时峰值）。

# 总显存低于这个值就别浪费 GPU 时间了（A14B 硬门槛：24G 卡无论如何跑不了）
MOTION_MIN_FREE_GB = float(os.environ.get("WEAVEORA_MOTION_MIN_FREE_GB", "30") or 30)
# motion 出片分辨率：A14B 的甜点是 **480p 级**（官方模板就是 640×640；实测 832×480 比 1280×704
# 快 5~10 倍，而且不会把 48G 卡跑到 47.4/47.4 GiB 反复换入换出 —— 实测 720p/48 帧要 >600s）。
# 默认把长边压到 ≤ MOTION_DEFAULT_LONG_SIDE；params.resolution=720p/原始 可放开。
MOTION_DEFAULT_LONG_SIDE = int(os.environ.get("WEAVEORA_MOTION_LONG_SIDE", "832") or 832)

# ★ Wan2.2 I2V-A14B 的**原生节奏 = 16fps**（ComfyUI 官方模板 CreateVideo fps=16）。
#   踩过的坑（2026-09-15 线上）：按项目 fps(30) 生成同样帧数 → 运动被 1.875× 加速
#   （用户反馈「像开了倍速」），而且时长变成 帧数/30（5s 的镜头只出 4.13s）。
#   正确做法：**按 16fps 决定帧数**（= 时长 × 16），先按 16fps 出片（速度/时长都对）；
#   再按【整数倍 → RIFE / 非整数倍 → 不插帧并提示】处理帧率（见下方 2026-09-18 口径）。
MOTION_NATIVE_FPS = float(os.environ.get("WEAVEORA_MOTION_NATIVE_FPS", "16") or 16)

# ★ 插帧口径（用户 2026-09-18 定）：
#   · 目标 fps 是原生 16fps 的**整数倍**（32/48…）→ 用 ComfyUI 自带的 RIFE 节点插帧
#     （FrameInterpolationModelLoader + FrameInterpolate，multiplier 为整数 2–16）
#   · **非整数倍**（如 24 = 1.5×）→ **不插帧**，直接按原生 fps 出片，并在资产 notes 里提示「未插帧」
#   · ffmpeg minterpolate(mci) **全面禁用**：分数倍下要交替合成半帧 → 重影/几何扭曲/节奏不均，
#     用户实测的「多余/奇怪动作」来源之一。
MOTION_INTERP_MODEL = os.environ.get("WEAVEORA_MOTION_INTERP_MODEL", "rife_v4.26.safetensors") or ""
_INTERP_MODEL_CACHE = {"at": 0.0, "opts": None}


def _interp_model_available(timeout=15, ttl=300):
    """RIFE 权重是否就位（查 ComfyUI 的 FrameInterpolationModelLoader options，带 5 分钟缓存）。"""
    import time as _t
    now = _t.time()
    cached = _INTERP_MODEL_CACHE.get("opts")
    if cached is not None and now - float(_INTERP_MODEL_CACHE.get("at") or 0) < ttl:
        return MOTION_INTERP_MODEL in cached
    try:
        _, body = _comfy("GET", "/object_info/FrameInterpolationModelLoader", timeout=timeout)
        node = (json.loads(body.decode()) or {}).get("FrameInterpolationModelLoader") or {}
        req = ((node.get("input") or {}).get("required") or {}).get("model_name")
        opts = ((req[1] or {}).get("options") if isinstance(req, list) and len(req) > 1 else None) or []
        _INTERP_MODEL_CACHE.update({"at": now, "opts": opts})
        return MOTION_INTERP_MODEL in opts
    except Exception as e:
        print("[comfy] WARN 查询插帧模型失败（本次按不可用处理）：%s" % e, flush=True)
        return False


def _motion_interp_plan(out_fps, native_fps=None):
    """插帧决策 → (multiplier, note)。

    multiplier>=2 → RIFE 插帧倍数（整数倍的帧率）；1 → 原样输出；0 → 不插帧（note 为给用户看的提示）。
    """
    native = float(native_fps or MOTION_NATIVE_FPS)
    try:
        out = float(out_fps or 0)
    except (TypeError, ValueError):
        out = 0.0
    if out <= 0 or native <= 0 or abs(out - native) < 0.01:
        return 1, None
    ratio = out / native
    n = int(round(ratio))
    if 2 <= n <= 16 and abs(ratio - n) < 0.01:
        return n, None
    return 0, ("未插帧：目标 %gfps 不是原生 %gfps 的整数倍（%.2f×），已按原生 %gfps 输出"
               "（时长与速度不变、无插值伪影；要插帧请把成片帧率设为 %d 或 %d）"
               % (out, native, ratio, native, int(native * 2), int(native * 3)))


def _motion_resolution(params, width, height):
    """motion 实际出片尺寸：按「分辨率上限」压到桶内（保持画幅，宽高 /16 对齐）。

    params.resolution / services.motion.resolution 语义 = **上限**（来自引擎配置里的
    「GPU 服务器最大支持分辨率」）：
      480p → 长边 ≤ 832 ｜ 720p → ≤ 1280 ｜ 1080p → ≤ 1920
      auto / full / original / source → 不限制
      空/未识别 → 用环境默认上限（WEAVEORA_MOTION_LONG_SIDE，缺省 832，即 480p 级）
    实测依据：48G 卡 1280×704/48 帧要 >600s（跑满显存反复换入换出），832×480 只要 27~50s；
    A14B 的甜点本来就是 480p 级（ComfyUI 官方模板 = 640×640）。
    """
    res = str((params or {}).get("resolution") or "").strip().lower()
    w, h = int(width), int(height)
    if res in ("full", "original", "source", "auto", "none"):
        return w, h
    caps = {"480p": 832, "480": 832, "720p": 1280, "720": 1280, "1080p": 1920, "1080": 1920}
    long_side = caps.get(res, MOTION_DEFAULT_LONG_SIDE)
    if long_side <= 0 or max(w, h) <= long_side:
        return w, h
    scale = float(long_side) / float(max(w, h))
    nw = max(16, int(round(w * scale / 16.0)) * 16)
    nh = max(16, int(round(h * scale / 16.0)) * 16)
    return nw, nh

MOTION_MIN_TOTAL_GB = float(os.environ.get("WEAVEORA_MOTION_MIN_TOTAL_GB", "44") or 44)
# 出片轮询上限（秒）。旧值 600s 是 TI2V-5B 时代的；A14B 双专家在 720p 下光采样就要 10~30 分钟
# （线上实测：1280x704/48 帧跑到 604s 被 timeout 打断）。默认 2700s（45 分钟），可用 env 调。
MOTION_TIMEOUT = float(os.environ.get("WEAVEORA_MOTION_TIMEOUT", "2700") or 2700)


def _motion_vram_need_gb(frames, width, height):
    """预估一次 A14B 双专家出片的峰值显存（GiB）。

    按实测标定（832×480：33 帧 41.8 / 121 帧 47.3）+ 分辨率面积等比；
    只用于**跑前体检与明确报错**，不追求精确：真正上限看运行时可用显存。
    """
    area = max(1.0, float(width) * float(height))
    scale = area / MOTION_VRAM_AREA_REF
    return MOTION_VRAM_BASE_GB + MOTION_VRAM_PER_FRAME_GB * float(frames) * scale


def _motion_fit_resolution(mw, mh, frames, budget_gb):
    """给定帧数与可用显存预算，算一个**能放下的最大分辨率**（保画幅、/16 对齐）。

    为什么要有这个（2026-09-16 夜用户裁定）：显存不够时**首选降分辨率，而不是降帧** ——
    降帧会静默牺牲**时长与速度**（动作被压进更短时长 → 看起来快进，尾部还要补帧静止），
    而降分辨率只牺牲一点清晰度，时长与速度完全不变。

    从 `_motion_vram_need_gb()` 反解面积：need = BASE + PER_FRAME × frames × area/REF
      ⇒ area_need = (budget - BASE) × REF / (PER_FRAME × frames)
    返回 (w, h)；连最低档都放不下（budget ≤ BASE 或算出的尺寸小于下限）返回 None。
    """
    try:
        budget_gb = float(budget_gb)
    except (TypeError, ValueError):
        return None
    room = budget_gb - MOTION_VRAM_BASE_GB
    if room <= 0 or frames <= 0:
        return None
    area_need = room * MOTION_VRAM_AREA_REF / max(1e-6, MOTION_VRAM_PER_FRAME_GB * float(frames))
    cur = float(mw) * float(mh)
    if cur <= 0 or area_need >= cur:
        return (int(mw), int(mh))
    import math
    k = math.sqrt(area_need / cur) * 0.97            # 留 3% 余量，避免刚卡在阀值上
    w = max(MOTION_MIN_SIDE, int(mw * k) // 16 * 16)
    h = max(MOTION_MIN_SIDE, int(mh * k) // 16 * 16)
    if w * h >= cur:                                  # 舍入后没变小 → 强制降一档
        w = max(MOTION_MIN_SIDE, (int(mw) - 16) // 16 * 16)
        h = max(MOTION_MIN_SIDE, (int(mh) - 16) // 16 * 16)
    if w < 16 or h < 16 or w * h >= cur:
        return None
    return (int(w), int(h))


def _snake_key(k):
    """camelCase → snake_case（switchStep → switch_step）；已是 snake_case 的原样返回。"""
    out = []
    for ch in str(k or ""):
        if ch.isupper():
            out.append("_")
            out.append(ch.lower())
        else:
            out.append(ch)
    return "".join(out)


def _motion_params(payload):
    """本镜 motion 参数：引擎配置下发的档位 ← payload.params 覆盖（每镜显式值优先）。"""
    p = dict(MOTION_OVERRIDES)
    raw = (payload or {}).get("params")
    if isinstance(raw, dict):
        p.update(raw)
    return p


def _motion_plan(params):
    """preset 打底 + 显式键覆盖 → 采样计划（纯函数，便于扫描实验）。"""
    params = params or {}
    name = str(params.get("preset") or MOTION_PRESET_ENV or "balanced").strip().lower()
    if name not in MOTION_PRESETS:
        print("[comfy] 未知 motion preset=%s，回退 balanced" % name, flush=True)
        name = "balanced"
    plan = dict(MOTION_PRESETS[name])
    plan["preset"] = name

    plan["steps"] = max(1, int(params.get("steps") or plan["steps"]))
    sw = params.get("switch_step")
    if sw is None:
        sw = params.get("switch")
    if sw is not None:
        plan["switch"] = max(1, min(plan["steps"] - 1, int(sw))) if plan["steps"] > 1 else 1
    else:
        plan["switch"] = max(1, plan["steps"] // 2)   # 4→2 / 6→3 / 8→4 / 24→12

    plan["cfg_high"] = float(params.get("cfg_high", params.get("cfg", plan["cfg_high"])))
    plan["cfg_low"] = float(params.get("cfg_low", params.get("cfg", plan["cfg_low"])))
    plan["lora_high"] = float(params.get("lora_high", params.get("lora_strength_high", plan["lora_high"])))
    plan["lora_low"] = float(params.get("lora_low", params.get("lora_strength_low", plan["lora_low"])))
    plan["shift"] = float(params.get("shift", plan["shift"]))
    plan["sampler"] = str(params.get("sampler_name") or "euler")
    plan["scheduler"] = str(params.get("scheduler") or "simple")
    if plan["steps"] > 12 and (plan["lora_high"] or plan["lora_low"]):
        print("[comfy] WARN steps=%d 仍在用蒸馏 LoRA（>12 步时蒸馏意义不大；"
              "若要高步数请用 preset=full 并把 lora_* 归零）" % plan["steps"], flush=True)
    return plan


def _motion_assets(params, dual):
    """解析素材名并对 object_info 做存在性校验（错误信息直接点出缺哪个文件）。"""
    p = params or {}

    def _need(class_type, key, wanted, label):
        opts = _node_input_options(class_type, key)
        if opts and wanted not in opts:
            raise ComfyError("%s 不存在：%s（ComfyUI %s 可用：%s）"
                             % (label, wanted, class_type, "、".join(opts[:6]) or "无"))
        return wanted

    a = {"dual": bool(dual)}
    a["clip"] = _need("CLIPLoader", "clip_name", p.get("text_encoder") or MOTION_TEXT_ENCODER, "文本编码器")
    a["vae"] = _need("VAELoader", "vae_name", p.get("vae") or MOTION_VAE, "VAE")
    if dual:
        a["high"] = _need("UNETLoader", "unet_name", p.get("model_high") or MOTION_MODEL_HIGH, "高噪声专家")
        a["low"] = _need("UNETLoader", "unet_name", p.get("model_low") or MOTION_MODEL_LOW, "低噪声专家")
    else:
        a["single"] = _need("UNETLoader", "unet_name", p.get("model") or MOTION_MODEL_HIGH, "扩散模型")
    a["lora_high_name"] = p.get("lora_high_name") or MOTION_LORA_HIGH
    a["lora_low_name"] = p.get("lora_low_name") or MOTION_LORA_LOW
    # fp8_scaled 权重自带 scale：weight_dtype 必须 default（旧 settings 里的
    # quantization=fp8_e4m3fn 是给 fp16 的 5B 文件用的，套到 fp8_scaled 上会掉画质）
    wd = p.get("weight_dtype")
    if not wd:
        names = [a.get("high"), a.get("low"), a.get("single")]
        if any(n and n.endswith("_fp8_scaled.safetensors") for n in names):
            wd = "default"
            if p.get("quantization"):
                print("[comfy] 忽略 quantization=%s（fp8_scaled 权重用 default）" % p.get("quantization"), flush=True)
        else:
            wd = p.get("quantization") or "default"
    a["weight_dtype"] = wd
    return a


def _flf_node_inputs():
    """探测「首尾帧→视频」节点的可用入参名；返回 (class_type, {start:name, end:name}) 或 None。

    为什么要探测而不是写死：Wan 的首尾帧节点在不同 ComfyUI 版本里叫法/入参不同
    （`WanFirstLastFrameToVideo` / `Wan22FirstLastFrameToVideo`，入参 start_image/end_image
    或 first_frame/last_frame）。**拿不到就回退到只吃首帧**，并在日志里说清楚，不静默。
    """
    for ct in ("WanFirstLastFrameToVideo", "Wan22FirstLastFrameToVideo"):
        info = _node_info(ct)
        if not info:
            continue
        req = (info.get("input") or {}).get("required") or {}
        opt = (info.get("input") or {}).get("optional") or {}
        names = set(req) | set(opt)
        start = next((n for n in ("start_image", "first_frame", "start_frame") if n in names), None)
        end = next((n for n in ("end_image", "last_frame", "end_frame") if n in names), None)
        if start and end:
            return ct, {"start": start, "end": end}
    return None


def _motion_base_nodes(positive, negative, first_frame_name, a, width, height, length,
                       last_frame_name=None):
    nodes = {
        "clip": {"class_type": "CLIPLoader",
                 "inputs": {"clip_name": a["clip"], "type": "wan"}},
        "vae": {"class_type": "VAELoader",
                "inputs": {"vae_name": a["vae"]}},
        "pos": {"class_type": "CLIPTextEncode",
                "inputs": {"text": positive, "clip": ["clip", 0]}},
        "neg": {"class_type": "CLIPTextEncode",
                "inputs": {"text": negative, "clip": ["clip", 0]}},
        "img": {"class_type": "LoadImage", "inputs": {"image": first_frame_name}},
    }
    flf = _flf_node_inputs() if last_frame_name else None
    if flf:
        ct, keys = flf
        nodes["img_last"] = {"class_type": "LoadImage", "inputs": {"image": last_frame_name}}
        nodes["latent"] = {"class_type": ct,
                           "inputs": {"vae": ["vae", 0],
                                      keys["start"]: ["img", 0],
                                      keys["end"]: ["img_last", 0],
                                      "positive": ["pos", 0], "negative": ["neg", 0],
                                      "width": width, "height": height, "length": length,
                                      "batch_size": 1}}
        print("[comfy] motion 使用首尾帧引导：%s（%s / %s）" % (ct, keys["start"], keys["end"]), flush=True)
    else:
        if last_frame_name:
            print("[comfy] WARN 未找到首尾帧节点（WanFirstLastFrameToVideo），本次只用首帧；"
                  "尾帧引导未生效（该镜的起始/结束帧衔接依赖后续镜头首帧一致）", flush=True)
        # ★ 必须用**原生** WanImageToVideo（2026-09-14 修）：它按 Wan2.2 14B 的
        #   patch_size=2 口径造潜变量，并顺便把正/负条件一起吐出来（输出 0/1/2）。
        #   之前这里用的是 Wan22ImageToVideoLatent（旧的自定义路径，按 patch=1 造 60×104）
        #   → A14B 模型内部按 2×2 patchify 得 30×52，两者对不上，
        #   报 “The expanded size of the tensor (52) must match the existing size (104)”。
        #   对照：ComfyUI 自带官方模板 video_wan2_2_14B_i2v.json 用的就是 WanImageToVideo。
        nodes["latent"] = {"class_type": "WanImageToVideo",
                           "inputs": {"vae": ["vae", 0], "start_image": ["img", 0],
                                      "positive": ["pos", 0], "negative": ["neg", 0],
                                      "width": width, "height": height, "length": length,
                                      "batch_size": 1}}
    return nodes


def _motion_expert(nodes, tag, unet_name, weight_dtype, lora_name, lora_strength, shift):
    """一条专家支路：UNETLoader →（可选 LoRA）→ ModelSamplingSD3；返回 model 来源。"""
    nodes["unet_" + tag] = {"class_type": "UNETLoader",
                            "inputs": {"unet_name": unet_name, "weight_dtype": weight_dtype}}
    src = ["unet_" + tag, 0]
    if lora_name and lora_strength:
        nodes["lora_" + tag] = {"class_type": "LoraLoaderModelOnly",
                               "inputs": {"model": src, "lora_name": lora_name,
                                          "strength_model": lora_strength}}
        src = ["lora_" + tag, 0]
    nodes["shift_" + tag] = {"class_type": "ModelSamplingSD3",
                             "inputs": {"model": src, "shift": shift}}
    return ["shift_" + tag, 0]


def _motion_graph_frames(payload, params):
    """帧数/分辨率口径：frames=4n、latent length=frames+1。

    ★ 帧数按 **原生 16fps × 镜头时长** 取（16fps 是 A14B 的原生节奏），
      payload.frames（UI 选的帧数）只当**上限**：min(时长×16, 上限)。
      为什么不再直接用 payload.frames（2026-09-15 线上事故）：
        UI 选 121 帧时按 30fps 出片 → 运动快 1.875×（像开了倍速），且 5s 镜头只剩 4.13s。
    """
    duration = float(payload.get("duration_sec") or 2.0)
    native = MOTION_NATIVE_FPS
    want = int(round(duration * native))              # 时长 × 原生 fps
    cap = payload.get("frames")
    if isinstance(cap, int) and 32 <= cap <= 128:
        want = min(want, int(cap))                    # UI 只当上限
    raw = max(32, min(121, want))                     # A14B 原生上限 121（官方模板）
    frames = int((raw + 3) / 4) * 4                   # 4n 对齐（latent = frames+1）
    width = int(params.get("width", 768))
    height = int(params.get("height", 768))
    return frames, width, height, frames + 1


def _motion_graph(client_id, payload, positive, negative, first_frame_name, prefix,
                  last_frame_name=None, interp_mult=0):
    """构造图生视频 prompt。

    双专家（默认，Wan2.2 I2V-A14B 高/低噪声）或单专家（params.model 显式给了单文件时走旧路径）。
    输出帧由 generate_motion 用 ffmpeg 合成 mp4（不依赖 VHS）。
    """
    params = _motion_params(payload)
    plan = _motion_plan(params)
    # P1：自托管路径**不能**因为 params.model 里有云模型名就退化成单专家
    #   （旧写法 `dual = not params.get("model")`：云模型名如 minimax/video-01 会让 worker
    #   拿它去 UNETLoader 找文件 → 报「扩散模型不存在」）。
    #   只认：显式 mode=single / dual=false / model 形如本地文件（*.safetensors）。
    _m = str(params.get("model") or "")
    _want_single = (str(params.get("mode") or "").strip().lower() == "single"
                    or params.get("dual") is False
                    or _m.endswith(".safetensors"))
    if _m and not _want_single:
        print("[comfy] 忽略 params.model=%r（云模型名不影响自托管双专家路由）" % _m, flush=True)
    if not _want_single:
        params.pop("model", None)
    dual = not _want_single
    a = _motion_assets(params, dual)
    seed = int(payload.get("seed") or 1)
    frames, width, height, length = _motion_graph_frames(payload, params)
    sampler = _pick_option("KSamplerAdvanced", "sampler_name", plan["sampler"], "euler")
    scheduler = _pick_option("KSamplerAdvanced", "scheduler", plan["scheduler"], "simple")

    nodes = _motion_base_nodes(positive, negative, first_frame_name, a, width, height, length,
                               last_frame_name=last_frame_name)

    if dual:
        hi = _motion_expert(nodes, "hi", a["high"], a["weight_dtype"],
                            a["lora_high_name"], plan["lora_high"], plan["shift"])
        lo = _motion_expert(nodes, "lo", a["low"], a["weight_dtype"],
                            a["lora_low_name"], plan["lora_low"], plan["shift"])
        nodes["sampler_hi"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": hi, "add_noise": "enable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_high"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["latent", 2], "start_at_step": 0,
            "end_at_step": plan["switch"], "return_with_leftover_noise": "enable"}}
        nodes["sampler_lo"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": lo, "add_noise": "disable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_low"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["sampler_hi", 0], "start_at_step": plan["switch"],
            "end_at_step": 10000, "return_with_leftover_noise": "disable"}}
        tail = ["sampler_lo", 0]
        shape = "dual 高/低噪声"
    else:
        one = _motion_expert(nodes, "hi", a["single"], a["weight_dtype"],
                             a["lora_high_name"], plan["lora_high"], plan["shift"])
        nodes["sampler"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": one, "add_noise": "enable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_high"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["latent", 2], "start_at_step": 0,
            "end_at_step": 10000, "return_with_leftover_noise": "disable"}}
        tail = ["sampler", 0]
        shape = "single 单专家"

    nodes["dec"] = {"class_type": "VAEDecode",
                    "inputs": {"samples": tail, "vae": ["vae", 0]}}
    # ★ RIFE 插帧（只做**整数倍**）：ComfyUI 0.34 自带 FrameInterpolationModelLoader +
    #   FrameInterpolate（multiplier 为整数 2–16，表达不了 1.5× 这种分数倍）。
    #   非整数倍由 generate_motion 直接按原生 fps 出片并在 notes 里提示「未插帧」。
    if int(interp_mult or 0) >= 2:
        nodes["interp_model"] = {"class_type": "FrameInterpolationModelLoader",
                                 "inputs": {"model_name": MOTION_INTERP_MODEL}}
        nodes["interp"] = {"class_type": "FrameInterpolate",
                           "inputs": {"interp_model": ["interp_model", 0],
                                      "images": ["dec", 0],
                                      "multiplier": int(interp_mult)}}
        nodes["save"] = {"class_type": "SaveImage",
                         "inputs": {"images": ["interp", 0], "filename_prefix": prefix}}
    else:
        nodes["save"] = {"class_type": "SaveImage",
                         "inputs": {"images": ["dec", 0], "filename_prefix": prefix}}

    lora_txt = "hi=%s(%s) lo=%s(%s)" % (plan["lora_high"],
                                        "—" if not plan["lora_high"] else a["lora_high_name"][:28],
                                        plan["lora_low"],
                                        "—" if not plan["lora_low"] else a["lora_low_name"][:28])
    if float(plan.get("lora_high") or 0) > 0:
        print("[comfy] WARN lora_high=%.2f：给高噪声 fp8 专家挂 LoRA 需额外 dequantize 一份权重"
              "（13.3GiB fp8 → ~28GiB fp16），实测 48G 卡也会 OOM；动态也会被压扁。"
              "除非显式要求，建议保持 0（只给低噪声专家蒸馏）。" % float(plan["lora_high"]), flush=True)
    print("[comfy] motion %s preset=%s steps=%d(switch %d) cfg=%s/%s shift=%s "
          "%dx%d %d帧 lora %s sampler=%s/%s weight=%s"
          % (shape, plan["preset"], plan["steps"], plan["switch"], plan["cfg_high"],
             plan["cfg_low"], plan["shift"], width, height, frames, lora_txt,
             sampler, scheduler, a["weight_dtype"]), flush=True)
    if int(interp_mult or 0) >= 2:
        print("[comfy] motion 插帧：RIFE ×%d（%s）" % (int(interp_mult), MOTION_INTERP_MODEL), flush=True)
    return {"prompt": nodes, "client_id": client_id}


def vram_stats():
    """ComfyUI 视角的显存 (free_gb, total_gb)；取不到返回 (None, None)。

    给 stub_worker 做「出视频前让 TTS 让出显存」的判据用（见 worker/stub_worker.py）。
    """
    try:
        _, body = _comfy("GET", "/system_stats", timeout=20)
        dev = (json.loads(body.decode()).get("devices") or [{}])[0]
        return (float(dev.get("vram_free") or 0) / 1073741824.0,
                float(dev.get("vram_total") or 0) / 1073741824.0)
    except Exception:
        return (None, None)


def _vram_note(tag):
    """打一行 ComfyUI 视角的显存体检；余量 < MOTION_MIN_FREE_GB 时提示。

    A14B 双专家实测峰值 41.8~42.4 GiB（832×480/33 帧），所以阈值不是旧 24G 卡的 15GiB。
    """
    free, total = vram_stats()
    if free is None:
        print("[comfy] %s 取显存失败（忽略）" % tag, flush=True)
        return
    print("[comfy] %s 显存 free=%.1f/%.1f GiB" % (tag, free, total), flush=True)
    if free < MOTION_MIN_FREE_GB:
        print("[comfy] WARN 显存余量 %.1f GiB < %.1f GiB：14B 双专家会频繁换入换出，"
              "请确认 TTS 未常驻（WEAVEORA_TTS_PRELOAD=0）或已让出显存"
              % (free, MOTION_MIN_FREE_GB), flush=True)


def _retime_to_fps(mp4, src_fps, dst_fps, target_frames=None):
    """把 mp4 重定时到 dst_fps（**保持时长与速度**）。

    为什么：A14B 按原生 16fps 生成（速度正确），而项目成片可能是 24/30fps。
    ★ 2026-09-18 用户口径：**全面禁用 ffmpeg minterpolate**（分数倍 mci 会出重影/几何扭曲/节奏不均，
    是用户实测「多余/奇怪动作」的来源之一）。插帧改由 **RIFE（整数倍）** 在 ComfyUI 图内完成；
    非整数倍则直接按原生 fps 出片并给用户提示。所以本函数现在只负责：
      · src == dst：只补/截帧做到帧精确（正常路径）
      · src ≠ dst（历史调用路径）：**告警并按原生 fps 原样输出**，不插值

    target_frames：目标总帧数（= round(镜头时长 × dst_fps)）。给了就把它**做成帧精确**：
      短了用 tpad 克隆尾帧补齐、长了截断。
      为什么（2026-09-15 线上）：81 帧@16fps 插值到 30fps 得到 149 帧 = 4.97s，
      播放器按整秒显示就是「4 秒」——用户会以为时长又不对。
    src == dst（且无需补帧）时原样返回。
    """
    try:
        src_fps = float(src_fps or 0)
        dst_fps = float(dst_fps or 0)
    except (TypeError, ValueError):
        return mp4
    need_retime = dst_fps > 0 and abs(dst_fps - src_fps) >= 0.01
    # ★ 2026-09-18：禁用 minterpolate。被要求变帧率时一律按原生输出（只补/截帧），并向日志明说。
    if need_retime:
        print("[comfy] WARN 已禁用 minterpolate：请求 %g→%gfps，按原生 %gfps 输出（未插帧）"
              % (src_fps, dst_fps, src_fps), flush=True)
        dst_fps = src_fps
        need_retime = False
    try:
        tf = int(target_frames) if target_frames else 0
    except (TypeError, ValueError):
        tf = 0
    if not need_retime and tf <= 0:
        return mp4
    import subprocess as _sp
    import tempfile as _tf
    ff = _ffmpeg_exe()
    d = _tf.mkdtemp(prefix="wv_retime_")
    try:
        src = os.path.join(d, "in.mp4")
        dst = os.path.join(d, "out.mp4")
        with open(src, "wb") as fh:
            fh.write(mp4)
        # 插值已全面禁用（2026-09-18）→ 只做补/截帧，绝不用 minterpolate
        base = "null"
        attempts = []
        if need_retime:
            attempts.append((base, "运动补偿插值"))
            attempts.append((None, "重复帧退化"))
        else:
            attempts.append((base, "仅补/截帧"))
        for vf, label in attempts:
            cmd = [ff, "-y", "-loglevel", "error", "-i", src]
            if vf == "null":
                pass
            elif vf:
                cmd += ["-vf", vf]
            else:
                cmd += ["-r", "%g" % dst_fps]
            if tf > 0:
                # 先克隆尾帧保证够长，再按目标帧数截断 → 帧精确
                if vf and vf != "null":
                    cmd += ["-vf", vf + ",tpad=stop_mode=clone:stop_duration=2"]
                else:
                    cmd += ["-vf", "tpad=stop_mode=clone:stop_duration=2"]
                cmd += ["-frames:v", str(tf)]
            cmd += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", dst]
            r = _sp.run(cmd, stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True)
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 0:
                with open(dst, "rb") as fh:
                    out = fh.read()
                nf = "?"
                try:
                    _w, _h, _du, _n = _probe_video_meta_ex(out)
                    nf = str(_n or "?")
                except Exception:
                    pass
                print("[comfy] 帧率补齐 %g → %gfps（%s）%s"
                      % (src_fps, dst_fps, label,
                         "，帧数 → %s（目标 %d）" % (nf, tf) if tf > 0 else ""), flush=True)
                return out
        print("[comfy] 帧率补齐失败（保持 %gfps 原样输出）" % src_fps, flush=True)
        return mp4
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _probe_video_meta_ex(mp4_bytes):
    """更详细的探测：返回 (w, h, duration_ms, nb_frames)。ffprobe 不可用时退到 ffmpeg -i 解析。"""
    import subprocess as _sp
    import tempfile as _tf
    import re as _re
    d = _tf.mkdtemp(prefix="wv_probe2_")
    try:
        p = os.path.join(d, "o.mp4")
        with open(p, "wb") as fh:
            fh.write(mp4_bytes)
        ff = _ffmpeg_exe()
        probe = os.path.join(os.path.dirname(ff), "ffprobe")
        if os.path.exists(probe):
            r = _sp.run([probe, "-v", "error", "-select_streams", "v:0",
                         "-show_entries", "stream=width,height,nb_frames",
                         "-show_entries", "format=duration", "-of", "default=nw=1", p],
                        stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True, timeout=120)
            out = r.stdout or ""
            w = _re.search(r"^width=(\d+)", out, _re.M)
            h = _re.search(r"^height=(\d+)", out, _re.M)
            n = _re.search(r"^nb_frames=(\d+)", out, _re.M)
            du = _re.search(r"^duration=([\d.]+)", out, _re.M)
            return (int(w.group(1)) if w else None, int(h.group(1)) if h else None,
                    int(round(float(du.group(1)) * 1000)) if du else None,
                    int(n.group(1)) if n else None)
        w, h, du = _probe_video_meta(mp4_bytes)
        return w, h, du, None
    except Exception:
        return None, None, None, None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _encode_frames_mp4(frames_bytes, fps, out_dir):
    import subprocess as _sp
    import tempfile
    # 统一走 _ffmpeg_exe()（支持 WEAVEORA_FFMPEG 覆盖 + PATH 回退）。
    # 旧写法直接 import imageio_ffmpeg 并用其自带二进制：老版本（4.2.2）不支持
    # `-fps_mode`（需 ffmpeg >= 4.3），会导致对口型拼接报 "Unrecognized option 'fps_mode'"。
    ff = _ffmpeg_exe()
    if not frames_bytes:
        raise ComfyError("motion 无输出帧")
    for i, b in enumerate(frames_bytes):
        with open(os.path.join(out_dir, "f_%04d.png" % i), "wb") as fh:
            fh.write(b)
    out = os.path.join(out_dir, "motion.mp4")
    r = _sp.run([ff, "-y", "-framerate", str(fps), "-i",
                 os.path.join(out_dir, "f_%04d.png"),
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", out],
                stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True)  # py3.6 兼容（capture_output/text 需 3.7+）
    if r.returncode != 0 or not os.path.exists(out):
        raise ComfyError("ffmpeg 合成失败: %s" % (r.stderr or "")[-300:])
    with open(out, "rb") as fh:
        mp4 = fh.read()
    return mp4


def _motion_tick(progress_fn):
    """motion 采样期间的进度回调：每分钟报一次已耗时（720p 双专家可能跑很久）。

    为什么需要：旧的 `_poll_history` 默认 600s 超时且不报进度 —— 用户看到「50%」卡住十分钟
    然后 timeout，既不知道在跑、也不知道慢在哪。
    """
    state = {"last": -1}

    def _tick(elapsed):
        m = int(elapsed // 60)
        if m > 0 and m != state["last"]:
            state["last"] = m
            if progress_fn:
                progress_fn(60, "sampling 已 %d 分钟" % m)

    return _tick


# ===========================================================================
# ★ 2026-09-23：**LTX-2.5 出片引擎**（Lightricks；官方「生产质量档」= distilled 两段式）
#   为什么要：Wan2.2 I2V 是 2025-07 的模型，已一年；LTX-2.5（2026-09）原生 **1280×704 / 24fps /
#   一次过出片**（自带音轨、不需要 RIFE 插帧、不需要 LatentSync 就已经有口型能力）。
#   盒上实测（2026-09-23，官方样张 1280×704 / 5s）：**Prompt executed in 145.65s**，
#   峰值显存 **44,662 MiB / 46,068 MiB**（97%，跑前必须先 /free 卸掉 ComfyUI 常驻缓存）。
#   切引擎：环境变量 `WEAVEORA_MOTION_ENGINE=ltx25`（缺省 wan22，生产行为不变）；
#   也可按镜覆盖：引擎配置页下发 `services.motion.engine=ltx25` → payload.params.engine。
#   工作流文件：/opt/weaveora/workflows/ltx25_i2v_api.json（由官方模板摊平，见 deploy/flatten_comfy_template.py）
# ===========================================================================
MOTION_ENGINE = (os.environ.get("WEAVEORA_MOTION_ENGINE", "wan22") or "wan22").strip().lower()
LTX25_WORKFLOW = os.environ.get("WEAVEORA_LTX25_WORKFLOW", "/opt/weaveora/workflows/ltx25_i2v_api.json")
LTX25_KEEP_AUDIO = (os.environ.get("WEAVEORA_LTX25_KEEP_AUDIO", "0") or "0").strip() == "1"
LTX25_NATIVE_FPS = float(os.environ.get("WEAVEORA_LTX25_NATIVE_FPS", "24") or 24)   # 工作流 PrimitiveInt(361)
LTX25_MAX_SIDE = int(os.environ.get("WEAVEORA_LTX25_MAX_SIDE", "1280") or 1280)
LTX25_FREE_GB = float(os.environ.get("WEAVEORA_LTX25_FREE_GB", "40") or 40)
LTX25_TIMEOUT = float(os.environ.get("WEAVEORA_LTX25_TIMEOUT", "1800") or 1800)
# ★ 2026-09-23：LTX-2.5 的**时间轴 ×2**（24fps → 48fps）开关。
#   原理：官方 `ltx-2.5-latent-temporal-upscaler-x2-bf16` 在 latent 域把时间轴放大 2×
#   （时长不变）→ 解码后帧数×2、音画仍同步。比 RIFE 插帧更原生（无插帧伪影）。
#   用法：把下面这行设 1（并在引擎页把帧数按 24fps 口径填）→ 走 *_48fps 工作流。
#   注意：解码阶段显存/耗时会上涨，且单卡只能串行跑（实测 5s/121 帧已占 44.7/46.1 GiB）。
LTX25_FPS_X2 = (os.environ.get("WEAVEORA_LTX25_FPS_X2", "0") or "0").strip() == "1"
LTX25_WORKFLOW_X2 = os.environ.get("WEAVEORA_LTX25_WORKFLOW_X2",
                                   "/opt/weaveora/workflows/ltx25_i2v_48fps_api.json")


def _ltx25_x2_on(params):
    """时间轴 ×2（24→48fps）是否开：**引擎配置页下发优先**（`services.motion.fps_x2`），
    未配才看环境变量 `WEAVEORA_LTX25_FPS_X2`。

    与「出片引擎」同一套优先级（UI > env）——不要只看 env，否则 UI 上改了开关没反应。
    """
    v = (params or {}).get("fps_x2")
    if v is None:
        return LTX25_FPS_X2
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in ("1", "true", "yes", "on")


def _strip_audio(mp4):
    """LTX 出的 mp4 自带音轨；交付链（对口型/导出）用的是 TTS 音轨 → 默认剥掉，避免串轨。
    要保留（做「环境声/氛围镜」）就设 WEAVEORA_LTX25_KEEP_AUDIO=1。"""
    if LTX25_KEEP_AUDIO:
        return mp4
    import tempfile, subprocess as _sp, shutil as _sh
    d = tempfile.mkdtemp(prefix="wv_ltx25_")
    try:
        src = os.path.join(d, "in.mp4"); dst = os.path.join(d, "out.mp4")
        with open(src, "wb") as fh:
            fh.write(mp4)
        r = _sp.run([_ffmpeg_exe(), "-y", "-v", "error", "-i", src, "-c:v", "copy", "-an", dst],
                    stdout=_sp.PIPE, stderr=_sp.PIPE)   # py3.6 兼容（capture_output 需 3.7+）
        if r.returncode != 0 or not os.path.exists(dst):
            print("[comfy] ltx25 剥音轨失败，保留原音轨：%s" % (r.stderr or b"")[-200:], flush=True)
            return mp4
        with open(dst, "rb") as fh:
            return fh.read()
    finally:
        _sh.rmtree(d, ignore_errors=True)


def _motion_ltx25(client_id, payload, progress_fn=None):
    """LTX-2.5 出片：关键帧 + 运动正词 → 短视频（返回契约与 generate_motion 完全一致）。"""
    import tempfile, uuid as _uuid
    if progress_fn:
        progress_fn(25, "loading_model")
    key = payload.get("keyframeKey")
    if not key:
        raise ComfyError("clip 任务缺少 keyframeKey（先出关键帧）")
    data, ctype = fetch_reference_bytes(key)
    _p = _motion_params(payload)
    positive = payload.get("positive_prompt", "") or ""
    negative = payload.get("negative_prompt", "") or ""
    out_fps = float(payload.get("fps") or MOTION_NATIVE_FPS)
    # ---- 时长：优先 payload.duration_sec，否则用「请求帧数 ÷ 交付帧率」（与 Wan 通路同一口径）
    try:
        dur = float(payload.get("duration_sec") or 0)
    except (TypeError, ValueError):
        dur = 0.0
    frames_req = 0
    for _src in (_p.get("frames"), payload.get("frames")):
        try:
            frames_req = int(_src or 0)
        except (TypeError, ValueError):
            frames_req = 0
        if frames_req:
            break
    if dur <= 0:
        dur = (frames_req / out_fps) if (frames_req and out_fps > 0) else 5.0
    dur = max(1.0, min(20.0, dur))
    # ---- 分辨率：保持请求的画幅（方形/未给 → 16:9），长边压到 LTX25_MAX_SIDE，且 32 的倍数
    try:
        rw = int(_p.get("width") or 0); rh = int(_p.get("height") or 0)
    except (TypeError, ValueError):
        rw = rh = 0
    if not rw or not rh or rw == rh:
        rw, rh = 1280, 704
    long_side = max(rw, rh)
    if long_side > LTX25_MAX_SIDE:
        k = LTX25_MAX_SIDE / float(long_side)
        rw, rh = int(rw * k), int(rh * k)
    w = max(256, rw // 32 * 32)
    h = max(256, rh // 32 * 32)
    seed = int(payload.get("seed") or 1)
    prefix = "weaveora_ltx25_" + _uuid.uuid4().hex[:6]
    # ★ 时间轴 ×2（24→48fps）开关：走 *_48fps 工作流（生成仍 24fps，解码前把 latent 时间轴放大 2×）
    #   优先级：引擎配置页 services.motion.fps_x2 > env WEAVEORA_LTX25_FPS_X2（P5）
    _x2 = _ltx25_x2_on(_p)
    wf = LTX25_WORKFLOW_X2 if _x2 else LTX25_WORKFLOW
    _deliver_fps = int(LTX25_NATIVE_FPS * 2) if _x2 else int(LTX25_NATIVE_FPS)
    print("[comfy] ltx25 出片：%dx%d 时长 %.2fs（生成 %gfps → %d 帧；交付 %dfps%s）seed=%d workflow=%s"
          % (w, h, dur, LTX25_NATIVE_FPS, int(round(dur * LTX25_NATIVE_FPS)) + 1,
             _deliver_fps, "，时间轴 ×2" if _x2 else "", seed, wf),
          flush=True)
    # ⛔ 2026-09-24 修（「motion 全失败」的真因）：这里原有一句 `_free_comfy_models(wait_gb=LTX25_FREE_GB)`，
    #   已**删除**。实测链路（真事，非推测）：10:52:56 发 /free → 10:54:35 回「卸载完成：可用显存 44.3 GiB
    #   （目标 44.0）」看上去很好，其实它是把上一套（FLUX.2 主模型 33 GB + 编码器）**从显存搬到内存**、
    #   而不是释放（`free_memory()` 对还被引用着的模型只 offload）⇒ 13 秒后 OOM killer 杀 ComfyUI
    #   （`anon-rss 47.8 GB`）⇒ 整个 stack cgroup 一起死（ComfyUI/TTS/face/talk/网关），worker 只看到
    #   `Connection refused`；而 unit 当时没有 Restart 策略 ⇒ 盒子一直死着 = 「motion 全失败」。
    #   真正能释放大模型的手段只有一个：**重启 ComfyUI** —— 它由 `_post_prompt → _unload_before_model_switch`
    #   里的**跨家族守卫**在提交前完成（本任务的图含 UNETLoader/CLIPLoader ⇒ 指纹与出图家族不同 ⇒ 必重启）。
    #   所以这里只需把工作流读进来，不再需要任何 /free 前置动作。**别再把 /free 加回来。**
    try:
        with open(wf, encoding="utf-8") as fh:
            g = json.load(fh)
    except Exception as e:
        raise ComfyError("LTX-2.5 工作流不可读（%s）：%s" % (wf, e))
    # ---- 注入：图 / 正负词 / 种子 / 时长 / 分辨率 / 输出前缀 / 首帧图上传
    name = _upload_image(data, key.split("/")[-1] or "keyframe.png", ctype or "image/png")
    g["395"]["inputs"]["image"] = name
    g["376"]["inputs"]["value"] = positive
    if negative:
        g["373"]["inputs"]["text"] = negative
    g["339"]["inputs"]["noise_seed"] = seed
    g["338"]["inputs"]["noise_seed"] = (seed + 1) % (2 ** 63 - 1)
    g["362"]["inputs"]["value"] = int(round(dur))          # Duration(秒)：length = D*24+1
    g["361"]["inputs"]["value"] = int(LTX25_NATIVE_FPS)    # Frame Rate
    g["372"]["inputs"]["value"] = w                         # Width（覆盖 ResolutionSelector 的接线）
    g["360"]["inputs"]["value"] = h                         # Height
    g["75"]["inputs"]["filename_prefix"] = prefix
    pid = _post_prompt({"prompt": g, "client_id": client_id}, client_id)
    rec = _poll_history(client_id, pid, poll=2.0, timeout=LTX25_TIMEOUT,
                        on_tick=_motion_tick(progress_fn))
    outs = _download_outputs(rec, prefix=prefix)
    if not outs:
        raise ComfyError("LTX-2.5 无输出（prefix=%s）" % prefix)
    mp4 = _strip_audio(outs[0]["bytes"])
    pw, ph, pdur = _probe_video_meta(mp4)
    try:
        fr = _face_probe(mp4)
    except Exception:
        fr = None
    face = None if fr is None else (fr[0] == fr[1] and fr[1] > 0)
    face_n = None if fr is None else "%d/%d" % (fr[0], fr[1])
    notes = ("LTX-2.5 生产档（distilled 两段式；生成 %gfps%s；音轨%s）"
             % (LTX25_NATIVE_FPS, "→ 时间轴×2 交付 %dfps" % _deliver_fps if _x2 else "",
                "已保留" if LTX25_KEEP_AUDIO else "已剥离（交付用 TTS 音轨）"))
    print("[comfy] ltx25 出片完成：%sx%s %s 帧 %.2fs" % (pw, ph,
          int(round(float(pdur or 0) / 1000.0 * LTX25_NATIVE_FPS)), float(pdur or 0) / 1000.0), flush=True)
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": mp4, "mime": "video/mp4", "width": int(pw or w), "height": int(ph or h),
             "duration_ms": pdur, "face_detected": face, "face_frames": face_n, "notes": notes}]


def generate_motion(client_id, payload, progress_fn=None):
    """Wan2.2 i2v motion（关键帧→短视频 mp4）。返回 [{bytes,mime,width,height}]。"""
    import tempfile, uuid as _uuid
    # ★ 2026-09-23：引擎分派。取值用 _motion_params()（= 引擎配置页下发的 services.motion ← payload.params 覆盖），
    #   不要只看 payload.params —— 平台上「出片引擎」走的是 services.motion.engine → apply_services() → MOTION_OVERRIDES。
    #   优先级：platform/payload 显式值 > 环境变量 WEAVEORA_MOTION_ENGINE > wan22。
    _eng = str(_motion_params(payload).get("engine") or MOTION_ENGINE or "wan22").strip().lower()
    if _eng in ("ltx25", "ltx-2.5", "ltx2.5", "ltx"):
        print("[comfy] motion 引擎 = LTX-2.5（%s）" % LTX25_WORKFLOW, flush=True)
        return _motion_ltx25(client_id, payload, progress_fn)
    if progress_fn:
        progress_fn(25, "loading_model")
    key = payload.get("keyframeKey")
    if not key:
        raise ComfyError("clip 任务缺少 keyframeKey（先出关键帧）")
    data, ctype = fetch_reference_bytes(key)
    positive = payload.get("positive_prompt", "")
    negative = payload.get("negative_prompt", "")
    # ★ 帧率分两个：
    #   out_fps = 项目导出帧率（payload.fps，如 30）→ 出片后由 _retime_to_fps() 补帧到它
    #   fps     = 生成/编码帧率 = 模型原生节奏（A14B = 16fps）→ 保证**速度与时长正确**
    #   踩过的坑（2026-09-15）：以前直接用 payload.fps 生成+编码 → 运动快 1.875×（“像开了倍速”），
    #   且 5s 镜头只剩 4.13s。
    out_fps = int(payload.get("fps") or 16)
    fps = MOTION_NATIVE_FPS
    # ★ 插帧决策（用户 2026-09-18 口径）：整数倍 → RIFE 插帧；非整数倍 → **不插帧** + 提示
    interp_mult, interp_note = _motion_interp_plan(out_fps, fps)
    if interp_mult >= 2 and not _interp_model_available():
        interp_mult, interp_note = 0, (
            "未插帧：RIFE 权重不可用（%s 不在 ComfyUI 的 frame_interpolation 目录），已按原生 %gfps 输出"
            % (MOTION_INTERP_MODEL, fps))
    if interp_note:
        print("[comfy] motion %s" % interp_note, flush=True)
    _mp = _motion_params(payload)
    # motion 固定 768×768（Comfy 原生 Wan2.2 方形档位；8GB fp8），关键帧缩放后上传保证一致
    mw = int(_mp.get("width", 768))
    mh = int(_mp.get("height", 768))
    # 分辨率口径：默认压到 480p 桶（A14B 甜点 + 48G 卡不换入换出），显式 720p 才放开
    _w0, _h0 = mw, mh
    # ★ 2026-09-22：记下「引擎配置请求的分辨率」，后面显存不够降分辨率时要能说清「你要求的 720p 没达成」。
    _res_req = str(_mp.get("resolution") or "").strip().lower()
    mw, mh = _motion_resolution(_mp, mw, mh)
    # ★ 无论改没改都打一行（否则 720p 生效时日志里什么都没有 → 无法验收；见四段交接「排障只看日志三行」）
    print("[comfy] motion 分辨率 %dx%d → %dx%d（请求 resolution=%s%s）"
          % (_w0, _h0, mw, mh, _res_req or "未设",
             "" if _res_req else " → 默认 480p 桶；要原分辨率请在「生成引擎配置 → GPU 服务器 → 视频分辨率」设 720p"),
          flush=True)
    # 因显存做的取舍会汇成 notes 随资产上报（不只藏在日志里）
    _notes = []
    # ★ 2026-09-22 问题 5：本镜需要对口型时，**图内不插帧** —— 把插帧让给对口型之后的步骤（默认关，见上方开关）。
    #   为什么要成对地改：只把图内插帧去掉、而不在对口型后补回来 → 交付帧率降一半、
    #   导出阶段会用 ffmpeg `fps=` **复制帧**拉齐 → 反而把顿挫做回来。所以两者必须同一个开关控。
    if LIPSYNC_INTERP_AFTER and interp_mult >= 2 and bool(payload.get("lipSync")):
        _notes.append("对口型镜：本段按原生 %gfps 生成，插帧（×%d → %dfps）放到对口型之后做"
                      % (fps, int(interp_mult), int(out_fps)))
        print("[comfy] motion 插帧延后：本镜需要对口型 → 先出原生 %gfps，对口型后再 RIFE ×%d 到 %dfps"
              % (fps, int(interp_mult), int(out_fps)), flush=True)
        interp_mult, interp_note = 1, None
    _res_shrunk = False
    _want_frames = None
    # P2：A14B 双专家实测峰值 ~42 GiB → 总显存不够就**快速失败并点名换机**
    # （旧行为：硬跑 → OOM，报一堆看不懂的错；24G 卡无论如何跑不了 A14B）
    if str(_mp.get("mode") or "").strip().lower() != "single":
        _free, _total = vram_stats()
        _frames = _motion_graph_frames(payload, _mp)[0]
        _want_frames = _frames
        # ★ 必须用**压后**尺寸（mw/mh）估算：线上踩过 —— 已把 1280×704 压成 832×464，
        #   体检却仍按 1280×704 算 → 误报「放不下」（124 帧报 57.2 GiB，实际只需 ~47 GiB）
        _need = _motion_vram_need_gb(_frames, mw, mh)
        if _total is not None and _total < MOTION_MIN_TOTAL_GB:
            raise ComfyError(
                "本机 ComfyUI 总显存 %.1f GiB < A14B 双专家所需 %.0f GiB（实测峰值 ~42–47 GiB）："
                "Wan2.2 I2V-A14B 在这台机器上跑不了。\n"
                "  修法一（推荐）：把 clip 任务路由到 48G 的 GPU#2（生成引擎配置 → GPU 服务器地址）\n"
                "  修法二：显式降级单专家（params.mode=single + params.model=本地 5B 权重名）"
                % (_total, MOTION_MIN_TOTAL_GB))
        if _free is not None and _free < _need:
            # ★ 2026-09-24 修：这里原来是 `_free_comfy_models(wait_gb=_need)`，**已删除** ——
            #   `/free` 会把上一套模型从显存搬进内存（不是释放）⇒ 往 OOM 枪口上撞
            #   （实测：LTX 出片前 /free 把 FLUX.2 的 33 GB 搬到 RAM，13 秒后 OOM 杀掉整栈）。
            #   跨家族释放由 `_post_prompt → _unload_before_model_switch` 的重启守卫负责；
            #   这里只重读一次显存并如实告知（放不下就交给 ComfyUI 自己换入换出）。
            try:
                _free, _total2 = vram_stats()
                _total = _total2 or _total
                print("[comfy] 可用显存 %.1f GiB（未做 /free 卸载；跨家族释放交给重启守卫）" % _free, flush=True)
            except Exception as _e:
                print("[comfy] 重读显存失败（忽略）: %s" % _e, flush=True)
        # 判据：**只在真放不下时才降帧**。
        #   free < need 但仍能放下 → 给 WARN 继续跑，交给 ComfyUI 自己换入换出
        #   （旧写法拿 free 硬拦，会把本来能跑的任务误杀 —— 实测 121 帧 47.3GiB 是能跑的）。
        #
        # ★ 2026-09-16 夜线上事故（用户报「motion 前几秒快进、3s 后停住、最后又补齐」）：
        #   旧写法把 `_need` 对比 `_total - SAFETY`（47.4-4 = **43.4**），而紧接着的日志自己就写着
        #   「可用 **46.1**/47.4 GiB」—— 判据用的是“总显存减预留”，不是**刚卸载后实测的可用**，
        #   于是 44.5GiB 的 80 帧任务被误判成放不下 → 降帧到 56 帧：
        #     56 帧 @16fps = 3.56s（整段动作被压进这 3.56s，看起来快进 1.4x），
        #     再由 _retime_to_fps 按“帧精确” **克隆尾帧补到 150 帧** → 尾部约 1.4s 静止。
        #   现在：能拿到实测可用显存就以它为准（卸载后测得的最准）；
        #   只有 `_free` 不可用时才回退到 `_total - SAFETY` 这个保守值。
        _fit_budget = _free if _free is not None else (_total - MOTION_VRAM_SAFETY_GB if _total else None)
        # ★ 2026-09-16 夜（用户裁定）：显存不够时**首选降分辨率保时长/速度**，降帧是最后手段。
        _res_shrunk = False
        if _fit_budget is not None and _need > _fit_budget:
            _shrink = _motion_fit_resolution(mw, mh, _frames, _fit_budget)
            if _shrink and _shrink != (mw, mh):
                _w1, _h1 = _shrink
                print("[comfy] 显存不够 → **先降分辨率保时长**：%dx%d → %dx%d（帧数 %d 不变 = 时长 %.2fs与速度不变；"
                      "只牺牲一点清晰度）｜预估需 %.1f GiB ≤ 可用 %.1f GiB"
                      % (mw, mh, _w1, _h1, _frames, _frames / max(1.0, MOTION_NATIVE_FPS), _need, _fit_budget),
                      flush=True)
                _res_shrunk = True
                mw, mh = _w1, _h1
                _need = _motion_vram_need_gb(_frames, mw, mh)
        if _fit_budget is not None and _need > _fit_budget:
            _area_scale = (float(mw) * float(mh)) / MOTION_VRAM_AREA_REF
            _max_frames = max(32, int((_fit_budget - MOTION_VRAM_BASE_GB)
                                      / max(1e-6, MOTION_VRAM_PER_FRAME_GB * _area_scale)))
            _max_px = int(((_fit_budget - MOTION_VRAM_BASE_GB) /
                           max(1e-6, MOTION_VRAM_PER_FRAME_GB * float(_frames))) * MOTION_VRAM_AREA_REF)
            if _max_frames < 40:
                raise ComfyError(
                    "这次 motion 放不下：%dx%d / %d 帧 预估需 %.1f GiB，本机总显存 %.1f GiB。\n"
                    "  ① 本分辨率下最多约 %d 帧；或总像素降到约 %d\n"
                    "  ② 降分辨率（如 832x480）往往比降帧数划算\n"
                    "  ③ 跨镜并发时确认没有其它任务同时占卡（A14B 会独占）"
                    % (mw, mh, _frames, _need, _total, _max_frames, _max_px))
            # ★ 万不得已才降帧（时长/速度会被牺牲），所以日志要把后果和替代方案说清楚。
            _new_frames = max(32, (_max_frames - 4) // 4 * 4)   # 4n 对齐（latent = frames+1）且再留余量
            print("[comfy] 显存不够 → 自动降帧：%d → %d 帧（预估需 %.1f GiB > 可用 %.1f GiB，总 %.1f GiB）。"
                  "⚠️ 降帧会把整段动作压进更短的时长（看起来快进）且尾部静止（补帧补齐）；"
                  "要保时长与速度，优先降分辨率（resolution=480p/更低）或把镜头拆短，别靠降帧"
                  % (_frames, _new_frames, _need, _fit_budget, _total or 0), flush=True)
            payload = dict(payload)
            payload["frames"] = _new_frames
            _mp["frames"] = _new_frames
            _frames = _new_frames
        # 把“因显存做的取舍”汇成一句 notes（前端在资产卡上看得见，不只藏在日志里）
        if _res_shrunk:
            _note = "显存不够 → 分辨率自动降到 %dx%d（时长与速度不变，只略糊）" % (mw, mh)
            if _res_req:
                _note += "；注意：引擎配置要求 resolution=%s **未达成**（本机显存只够这个尺寸）——建议降帧数或拆短镜头" % _res_req
            _notes.append(_note)
        if _want_frames is not None and _frames != _want_frames:
            _notes.append("显存仍不够 → 帧数 %d→%d（实际约 %.2fs，动作会被压缩且尾部静止补齐；建议拆短镜头）"
                          % (_want_frames, _frames, _frames / max(1.0, MOTION_NATIVE_FPS)))
        _notes = "；".join(_notes)
        if _free is not None and _free < _need:
            print("[comfy] WARN 当前可用 %.1f GiB < 预估 %.1f GiB：ComfyUI 会自行换入换出，"
                  "若 OOM 请降分辨率或帧数" % (_free, _need), flush=True)
        elif _free is not None:
            print("[comfy] motion 显存体检：预估需 %.1f GiB，可用 %.1f/%.1f GiB"
                  % (_need, _free, _total or 0), flush=True)
    try:
        from PIL import Image as _PIL
        _im = _PIL.open(io.BytesIO(data)).convert("RGB").resize((mw, mh), _PIL.LANCZOS)
        _buf = io.BytesIO()
        _im.save(_buf, format="PNG")
        data = _buf.getvalue()
    except Exception:
        pass
    params2 = dict(payload.get("params") or {})
    params2["width"] = mw
    params2["height"] = mh
    payload = dict(payload)
    payload["params"] = params2
    prefix = "weaveora_mot_" + _uuid.uuid4().hex[:6]
    if progress_fn:
        progress_fn(35, "sampling")
    _, body = _comfy("POST", "/upload/image",
                     files={"image": (key.split("/")[-1], data, ctype)})
    name = json.loads(body.decode()).get("name")
    # ★ 尾帧（多关键帧镜头）：payload.tailKey 是后端选的**末帧**（如第3镜的「结束帧」）。
    #   有它就做首尾帧引导（FLF2V），让这一镜从起始帧演到结束帧 → 前后镜能接上。
    tail_name = None
    tail_key = (payload.get("tailKey") or "").strip()
    if tail_key:
        try:
            tdata, tctype = fetch_reference_bytes(tail_key)
            tail_name = _upload_image(tdata, (tail_key.split("/")[-1] or "tail.png"),
                                      tctype or "image/png")
            print("[comfy] 尾帧已上传（%s），将做首尾帧引导" % tail_name, flush=True)
        except Exception as e:
            print("[comfy] WARN 尾帧上传失败，本次只用首帧：%s" % e, flush=True)
            tail_name = None
    _vram_note("motion 前")
    prompt = _motion_graph(client_id, payload, positive, negative, name, prefix,
                           last_frame_name=tail_name, interp_mult=interp_mult)
    st, resp = _comfy("POST", "/prompt", payload=prompt)
    pid = json.loads(resp.decode()).get("prompt_id")
    if not pid:
        raise ComfyError("comfy /prompt 无 prompt_id")
    if progress_fn:
        progress_fn(50, "sampling")
    rec = _poll_history(client_id, pid, timeout=MOTION_TIMEOUT, on_tick=_motion_tick(progress_fn))
    # 收集输出帧
    frames = []
    outputs = rec.get("outputs") or {}
    for node in outputs.values():
        if not isinstance(node, dict):
            continue
        for it in node.get("images") or []:
            fname = it.get("filename", "")
            if not fname.startswith(prefix):
                continue
            q = urllib.parse.urlencode({"filename": fname,
                                        "subfolder": it.get("subfolder", ""),
                                        "type": it.get("type", "output")})
            _, fb = _comfy("GET", "/view?" + q)
            frames.append(fb)
    if not frames:
        raise ComfyError("motion 无输出帧（prefix=%s）" % prefix)
    # 帧的时间基：
    #   · RIFE 插帧后帧数已×N → 直接按**目标 fps** 封装（不再二次重定时、更不插值）
    #   · 未插帧（非整数倍 / RIFE 不可用）→ 按**原生 fps** 封装（时长与速度不变）
    _enc_fps = float(out_fps) if interp_mult >= 2 else float(fps)
    out_dir = tempfile.mkdtemp(prefix="wv_mot_")
    try:
        mp4 = _encode_frames_mp4(frames, _enc_fps, out_dir)
    finally:
        import shutil as _sh
        _sh.rmtree(out_dir, ignore_errors=True)
    # 只做**帧精确**（不足补 / 超出截）：src == dst 传入，绝不插值
    # （否则 81 帧@16 → 4.97s，播放器按整秒显示成「4 秒」）
    _target = 0
    try:
        _target = int(round(float(payload.get("duration_sec") or 0) * _enc_fps))
    except (TypeError, ValueError):
        _target = 0
    mp4 = _retime_to_fps(mp4, _enc_fps, _enc_fps, target_frames=_target)
    _how = ("（RIFE 插帧 ×%d）" % interp_mult) if interp_mult >= 2 else (
        ("（未插帧：原生 %gfps）" % fps) if interp_note else "")
    print("[comfy] motion 出片：%d 帧 @%gfps ≈ %.2fs → 输出 %gfps%s（目标 %d 帧 = %.2fs）"
          % (len(frames), _enc_fps, len(frames) / max(1.0, _enc_fps), _enc_fps, _how,
             _target, _target / max(1.0, _enc_fps)), flush=True)
    # 上报**真实**产出规格：原先直接把 payload 里「请求的」width/height 当结果上报，
    # 于是资产库里记的是 1280×704，而实际文件是 Wan 真正出图桶（本例 832×464）——
    # 2026-09-13 排查时让人误以为「对口型把分辨率改小了」。
    pw, ph, pdur = _probe_video_meta(mp4)
    rw = (payload.get("params") or {}).get("width", 768)
    rh = (payload.get("params") or {}).get("height", 768)
    w, h = pw or rw, ph or rh
    if (pw and int(pw) != int(rw)) or (ph and int(ph) != int(rh)):
        print("[comfy] motion 实际尺寸 %sx%s 与请求 %sx%s 不一致（已按实际上报）"
              % (w, h, rw, rh), flush=True)
    # P13：顺手做一次人脸检测并随资产上报 —— 对口型要求**每一帧**都能检出人脸，
    # 选镜弹窗靠这个把「无人脸 / 部分帧无人脸」的镜提前标出来（否则要等跑到一半才失败）。
    fr = _face_probe(mp4)
    face = None if fr is None else (fr[0] == fr[1] and fr[1] > 0)
    frames = None if fr is None else "%d/%d" % (fr[0], fr[1])
    if fr is not None:
        print("[comfy] motion 人脸检测：%s 帧可检出（%s）"
              % (frames, "全部帧有人脸" if face else ("无人脸" if fr[0] == 0 else "部分帧无人脸")),
              flush=True)
    # 插帧口径（RIFE / 未插帧）与显存取舍一起随资产上报：前端资产卡上能看见（⚠ 提示）
    _nlist = list(_notes) if isinstance(_notes, (list, tuple)) else ([_notes] if _notes else [])
    if interp_note:
        _nlist.append(interp_note)
    _notes = "；".join([x for x in _nlist if x])
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": mp4, "mime": "video/mp4", "width": int(w), "height": int(h),
             "duration_ms": pdur, "face_detected": face, "face_frames": frames, "notes": _notes}]


# ---- P7 配乐：ACE-Step 1.5（ComfyUI 原生节点，非 wrapper） ----
# 模型：all-in-one checkpoint（含 unet + qwen3 文本编码器 + vae），CheckpointLoaderSimple 直接加载。
# 生成配方对齐 ComfyUI 官方蓝图 blueprints/Text to Audio (ACE-Step 1.5).json：
#   ModelSamplingAuraFlow(shift=3) + KSampler(euler/simple, steps=8, cfg=1, denoise=1)
MUSIC_CKPT = os.environ.get("WEAVEORA_MUSIC_CKPT_NAME", "ace_step_1.5_turbo_aio.safetensors")
MUSIC_SAVE_NODE = os.environ.get("WEAVEORA_MUSIC_SAVE_NODE", "SaveAudioMP3")
MUSIC_QUALITY = os.environ.get("WEAVEORA_MUSIC_QUALITY", "320k")
MUSIC_STEPS = int(os.environ.get("WEAVEORA_MUSIC_STEPS", "8"))
MUSIC_CFG = float(os.environ.get("WEAVEORA_MUSIC_CFG", "1.0"))
MUSIC_SHIFT = float(os.environ.get("WEAVEORA_MUSIC_SHIFT", "3.0"))
MUSIC_SCHEDULER = os.environ.get("WEAVEORA_MUSIC_SCHEDULER", "simple")
MUSIC_SAMPLER = os.environ.get("WEAVEORA_MUSIC_SAMPLER", "euler")
MUSIC_TAGS_CFG = float(os.environ.get("WEAVEORA_MUSIC_TAGS_CFG", "2.0"))
MUSIC_TEMPERATURE = float(os.environ.get("WEAVEORA_MUSIC_TEMPERATURE", "0.85"))
MUSIC_BPM = int(os.environ.get("WEAVEORA_MUSIC_BPM", "120"))
MUSIC_KEYSCALE = os.environ.get("WEAVEORA_MUSIC_KEYSCALE", "E minor")
MUSIC_LANGUAGE = os.environ.get("WEAVEORA_MUSIC_LANGUAGE", "en")
MUSIC_TIMESIG = os.environ.get("WEAVEORA_MUSIC_TIMESIG", "4")
MUSIC_AUDIO_CODES = os.environ.get("WEAVEORA_MUSIC_AUDIO_CODES", "1") == "1"
# 配乐单次生成上限（秒）：8GB 卡上 120s 潜在序列很长，必要时可下调
MUSIC_MAX_SEC = float(os.environ.get("WEAVEORA_MUSIC_MAX_SEC", "180"))
MUSIC_TIMEOUT = int(os.environ.get("WEAVEORA_MUSIC_TIMEOUT", "2400"))

_AUDIO_MIME = {"mp3": "audio/mpeg", "flac": "audio/flac", "opus": "audio/ogg",
               "wav": "audio/wav", "m4a": "audio/mp4", "ogg": "audio/ogg"}


def _download_audio(rec, prefix):
    """收集音频产物（SaveAudio* 落到 outputs[node].audio）。"""
    outs = []
    for node in (rec.get("outputs") or {}).values():
        if not isinstance(node, dict):
            continue
        for it in list(node.get("audio") or []) + list(node.get("images") or []):
            fname = it.get("filename", "")
            if not fname.startswith(prefix):
                continue
            q = urllib.parse.urlencode({"filename": fname,
                                        "subfolder": it.get("subfolder", ""),
                                        "type": it.get("type", "output")})
            _, body = _comfy("GET", "/view?" + q)
            outs.append({"filename": fname, "bytes": body})
    return outs


def _music_graph(client_id, payload, prefix):
    """ACE-Step 1.5 文生配乐图（ComfyUI 原生节点 + all-in-one checkpoint）。"""
    tags = (payload.get("prompt") or "").strip() or \
        "cinematic instrumental score, emotional, no vocals"
    lyrics = (payload.get("lyrics") or "").strip()
    try:
        duration = float(payload.get("duration_sec") or 30)
    except (TypeError, ValueError):
        duration = 30.0
    duration = max(5.0, min(MUSIC_MAX_SEC, duration))
    seed = int(payload.get("seed") or 0)
    save = {"audio": ["7", 0], "filename_prefix": prefix}
    if MUSIC_SAVE_NODE == "SaveAudioMP3":
        save["quality"] = MUSIC_QUALITY
    elif MUSIC_SAVE_NODE == "SaveAudioAdvanced":
        save["format"] = {"format": "mp3", "quality": MUSIC_QUALITY}
    nodes = {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": MUSIC_CKPT}},
        "2": {"class_type": "ModelSamplingAuraFlow",
              "inputs": {"model": ["1", 0], "shift": MUSIC_SHIFT}},
        "3": {"class_type": "TextEncodeAceStepAudio1.5", "inputs": {
            "clip": ["1", 1], "tags": tags, "lyrics": lyrics, "seed": seed,
            "bpm": MUSIC_BPM, "duration": duration, "timesignature": MUSIC_TIMESIG,
            "language": MUSIC_LANGUAGE, "keyscale": MUSIC_KEYSCALE,
            "generate_audio_codes": MUSIC_AUDIO_CODES,
            "cfg_scale": MUSIC_TAGS_CFG, "temperature": MUSIC_TEMPERATURE,
            "top_p": 0.9, "top_k": 0, "min_p": 0.0}},
        "4": {"class_type": "ConditioningZeroOut",
              "inputs": {"conditioning": ["3", 0]}},
        "5": {"class_type": "EmptyAceStep1.5LatentAudio",
              "inputs": {"seconds": duration, "batch_size": 1}},
        "6": {"class_type": "KSampler", "inputs": {
            "model": ["2", 0], "positive": ["3", 0], "negative": ["4", 0],
            "latent_image": ["5", 0], "seed": seed, "steps": MUSIC_STEPS,
            "cfg": MUSIC_CFG, "sampler_name": MUSIC_SAMPLER,
            "scheduler": MUSIC_SCHEDULER, "denoise": 1.0}},
        "7": {"class_type": "VAEDecodeAudio",
              "inputs": {"samples": ["6", 0], "vae": ["1", 2]}},
        "8": {"class_type": MUSIC_SAVE_NODE, "inputs": save},
    }
    # ComfyUI 需要节点 id 为字符串键 + client_id
    return {"prompt": nodes, "client_id": client_id}


def generate_music(client_id, payload, progress_fn=None):
    """ACE-Step 1.5 配乐（bgm 任务）。返回 [{bytes, mime, duration_ms}]。"""
    import uuid as _uuid
    if progress_fn:
        progress_fn(20, "loading_model")
    duration = float(payload.get("duration_sec") or 30)
    duration = max(5.0, min(MUSIC_MAX_SEC, duration))
    prefix = "weaveora_bgm_" + _uuid.uuid4().hex[:6]
    prompt = _music_graph(client_id, payload, prefix)
    pid = _post_prompt(prompt, client_id)
    if progress_fn:
        progress_fn(35, "sampling")
    rec = _poll_history(client_id, pid, timeout=MUSIC_TIMEOUT)
    outs = _download_audio(rec, prefix)
    if not outs:
        raise ComfyError("bgm 无输出音频（prefix=%s）" % prefix)
    if progress_fn:
        progress_fn(100, "done")
    out = []
    for o in outs:
        ext = o["filename"].rsplit(".", 1)[-1].lower() if "." in o["filename"] else ""
        out.append({"bytes": o["bytes"], "mime": _AUDIO_MIME.get(ext, "audio/mpeg"),
                    "duration_ms": int(duration * 1000)})
    return out


if __name__ == "__main__":
    import sys
    print("comfy engine url=%s api=%s" % (COMFY, API))
    print("music ckpt=%s node=%s steps=%d cfg=%s shift=%s"
          % (MUSIC_CKPT, MUSIC_SAVE_NODE, MUSIC_STEPS, MUSIC_CFG, MUSIC_SHIFT))


# --------------------------------------------------------------------------- #
# P13 对口型（lipsync）：音频驱动嘴型
#
# 图生视频模型（Wan i2v 等）**没有音频通道**，出来的画面不可能对口型；
# 口型必须靠「音频驱动」的后处理。这里走 **workflow 驱动**：
#   把 ComfyUI 里已装好的口型工作流（LatentSync / MuseTalk / Wav2Lip 任一）
#   导出成 API 格式 JSON，用环境变量 WEAVEORA_LIPSYNC_WORKFLOW 指过来，
#   本函数负责：上传 视频 + 音频 → 按**节点标题**注入输入 → 排队 → 取回 mp4。
#
# 安装步骤见 docs/lipsync-setup.md。未配置时给出明确报错（不静默出无声/无口型结果）。
# --------------------------------------------------------------------------- #
LIPSYNC_WORKFLOW = os.environ.get("WEAVEORA_LIPSYNC_WORKFLOW", "").strip()
LIPSYNC_TIMEOUT = float(os.environ.get("WEAVEORA_LIPSYNC_TIMEOUT", "1800"))
# 工作流里承载「视频/音频路径」的节点标题（用户按自己导出的工作流改这两个值即可）
LIPSYNC_VIDEO_TITLE = os.environ.get("WEAVEORA_LIPSYNC_VIDEO_TITLE", "video").strip()
LIPSYNC_AUDIO_TITLE = os.environ.get("WEAVEORA_LIPSYNC_AUDIO_TITLE", "audio").strip()
# 注入到该节点的哪个 **输入键**：不同加载节点键名不同（原生 LoadVideo = file，VHS_LoadVideo = video）。
# 不设则回退到旧行为（视频键 video / 音频键 audio）。
LIPSYNC_VIDEO_INPUT = os.environ.get("WEAVEORA_LIPSYNC_VIDEO_INPUT", "").strip()
LIPSYNC_AUDIO_INPUT = os.environ.get("WEAVEORA_LIPSYNC_AUDIO_INPUT", "").strip()
# LatentSync 的原生输出帧率（configs/unet/stage2_512.yaml: video_fps: 25）。
# 组装成片时必须用这个值，不能用源片 fps —— 否则时长会按 25/源fps 缩短。
LIPSYNC_FPS = int(os.environ.get("WEAVEORA_LIPSYNC_FPS", "0") or 0)
# ★ 2026-09-22 问题 5（管线顺序）：对口型**必须吃未插帧的原生帧**，插帧放到对口型之后。
#   为什么：RIFE 合成的中间帧在嘴部这类高频区是模糊/拖影的，再喂给 LatentSync → 嘴区叠加成「乱码」。
#   正确顺序：运动（原生 16fps）→ 对口型 → RIFE 插到交付帧率。
#   默认 **0=关**（保持现状：图内插帧→对口型），因为这一层要改两条通路，
#   要先在真实镜头上验证过再开；开=1 时由 `_interp_video_on_box()` 在口型产物上补做插帧。
LIPSYNC_INTERP_AFTER = (os.environ.get("WEAVEORA_LIPSYNC_INTERP_AFTER", "0") or "0").strip() == "1"
# 段间缝合的淡入淡出帧数（0=关）。2026-09-22 用户实测「最后会啪的一下」；逐帧量化 =
# 段尾（帧108）全局帧差 5.0×（同帧源片自身仅 1.02）—— 即段结束时从驱动画面硬切回原帧。
LIPSYNC_SEAM_BLEND = int(float(os.environ.get("WEAVEORA_LIPSYNC_SEAM_BLEND", "4") or 4))
# A 方案：对口型**之前**把底片放大 N 倍（0/1=关）。2026-09-22 用户实测「嘴部都是马赛克」⇒
# 真因是 motion 出片只有 480p（脸 55–84px）而 LatentSync 已是最高 512 配置 ⇒ 给模型更大的脸。
LIPSYNC_PRE_UPSCALE = int(float(os.environ.get("WEAVEORA_LIPSYNC_PRE_UPSCALE", "0") or 0))
LIPSYNC_PRE_UPSCALE_MODEL = os.environ.get("WEAVEORA_LIPSYNC_PRE_UPSCALE_MODEL",
                                          "realesr-general-x4v3.pth")
# 放大方式：esrgan（AI 超分，会"抹平"AI 视频的颗粒 → 实测嘴部变糊斑）｜lanczos（传统重采样，保留锐利边缘）
LIPSYNC_UPSCALE_MODE = os.environ.get("WEAVEORA_LIPSYNC_UPSCALE_MODE", "esrgan").strip().lower()
# ★ 变体 2（2026-09-22 晚）：对口型**之后**再统一放大（0/1=关）。
#   为什么要变：先放大再对口型时整帧是 AI 锐化纹理、只有「嘴部贴回区」是模型软输出
#   ⇒ 用户反馈“嘴部马赛克完全遮挡了嘴”（贴回区与周围质感不一致，像一块糊斑）。
#   放在后面：整帧走同一套纹理，嘴部不再特殊。代价：嘴部细节仍受 480p 底片限制。
LIPSYNC_POST_UPSCALE = int(float(os.environ.get("WEAVEORA_LIPSYNC_POST_UPSCALE", "0") or 0))
LIPSYNC_NODE_CLASS = os.environ.get("WEAVEORA_LIPSYNC_NODE_CLASS", "LatentSyncNode").strip()
# 人脸服务地址（生成引擎配置 → 服务地址 → 人脸）。空 = 用本机 insightface 子进程（原行为）。
# 填了就走远端 HTTP（见 deploy/face/face_server.py），便于把脸算力集中到新 GPU 机器。
FACE_URL = os.environ.get("WEAVEORA_FACE_URL", "").rstrip("/")

# --------------------------------------------------------------------------- #
# 对口型「底片体检」（B）：底片（静帧/片段）本身就不适合做口型时，**宁可拒绝也不出坏画面**。
#
# 背景（2026-09-14 《那宝玉恍恍惚惚》实测，用户反馈「配口型时画面被破坏」）：
#   第 5 镜 action「…抓住宝玉将他拖下溪去，**宝玉失声惊叫**」、正词里写着
#   `his mouth open in a **terrified scream**` —— 底片（motion 片段）里嘴本来就大张，
#   LatentSync 要先把嘴「合上」再按音频重开 → 嘴部掩码区大幅形变 = 画面被破坏。
#
# ★ 阈值是按「真实素材」量的，不是拍的（同项目实测 mouth_open）：
#     正常/平静脸：0.44~0.66（第1镜 0.53、第4镜 0.66、定妆照 0.44、新闭嘴近景 0.41）
#     略开（可接受）：0.59~0.78（第6镜：宝玉喊叫但镜头是「近景+被安抚」，实际没大张）
#     真·大张嘴：1.23（第5镜 静帧 1.232 / 片段 1.238 —— 就是「画面被破坏」那一镜）
#   所以 WARN=0.90 / MAX=1.05：平静脸绝不误伤，只拦真正张口喊叫的底片。
#   别再照「0.3/0.5」这类拍的阈值 —— 那会把所有正常脸全判成「嘴大张」（实测过）。
# 两道硬门禁 + 两道软提醒：
#   ① 嘴张太大（张开度 ≥ LIPSYNC_MOUTH_MAX）→ 拒绝，提示换「嘴部自然的静帧」当底片；
#   ② 脸极小（宽 < LIPSYNC_FACE_MIN_PX）→ 拒绝（跑了也没意义：脸都糊了）；
#   ③ 嘴偏大（≥ WARN）/ 脸偏小（< LIPSYNC_FACE_WARN_PX，但 ≥ MIN_PX）→ 只提醒，不拦；
# 指标来自人脸服务 / 本机 insightface（106 点）；**拿不到指标就跳过**（绝不误拦）。
# 逃生门：payload.lipsyncForce=true 或 env WEAVEORA_LIPSYNC_FORCE=1（用户明确要硬跑）。
# --------------------------------------------------------------------------- #
LIPSYNC_FACE_MIN_RATIO = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_MIN_RATIO", "0.015"))
# 人脸像素宽度（比「占画面占比」稳：同机位 2560x1440 静帧的占比比 1280x720 片段小 4 倍）。
# 注意两个阈值的分工：远小于 MIN_PX = 硬拒（脸都糊了）；MIN_PX~WARN_PX 之间 = 只提醒
# （实测第 5 镜 1280x704 宽景的脸宽 95.7px —— 这种合法宽景不应该被“脸小”一刀切拦掉）。
LIPSYNC_FACE_MIN_PX = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_MIN_PX", "64"))
LIPSYNC_FACE_WARN_PX = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_WARN_PX", "96"))
# 嘴张开度软提醒阈值（实测平静脸 0.44~0.66，真·大张 1.23 —— 见上面的量化表）
LIPSYNC_MOUTH_WARN = float(os.environ.get("WEAVEORA_LIPSYNC_MOUTH_WARN", "0.90"))
LIPSYNC_MOUTH_MAX = float(os.environ.get("WEAVEORA_LIPSYNC_MOUTH_MAX", "1.05"))
LIPSYNC_FORCE = os.environ.get("WEAVEORA_LIPSYNC_FORCE", "").strip().lower() in ("1", "true", "yes", "on")
# 极端表情（惊恐/喊叫）镜头的嘴部驱动强度：工作流写死 1.5 → 高危镜降到**节点允许的下限 1.0**
# （LatentSyncNode 的 lips_expression = min 1.0 / max 3.0 / default 1.5；实测给它 0.8 会被
#   ComfyUI 直接判 prompt_outputs_failed_validation → 任务秒失败。所以这里默认 1.0，并且
#   注入前一律按节点 schema 夹一次区间，env 写错也不会再把任务打挂）
LIPSYNC_EXPRESSION_RISK = float(os.environ.get("WEAVEORA_LIPSYNC_EXPRESSION_RISK", "1.0"))


# 文件名类输入的候选键（按优先级）：不同加载节点名字不一样
_VIDEO_FILE_KEYS = ("file", "video", "video_file", "path", "filename", "image", "url")
_AUDIO_FILE_KEYS = ("audio", "audio_file", "file", "path", "filename", "url")


def _derive_input_key(info, want_video):
    """从节点 schema（object_info）推出「文件名输入」的键名。

    原生 LoadVideo 用 `file`、VHS_LoadVideo 用 `video`、LoadAudio 用 `audio` ——
    不同节点不一致，写错键名会被 ComfyUI 忽略（并静默使用工作流里写死的旧文件名）。
    """
    for k in (_VIDEO_FILE_KEYS if want_video else _AUDIO_FILE_KEYS):
        if _has_input(info, k):
            return k
    return None


def _upload_any(data, filename, ctype, sub="input"):
    """上传任意文件到 ComfyUI 输入目录（视频/音频），返回服务器端文件名。"""
    _, body = _comfy("POST", "/upload/image", files={"image": (filename, data, ctype)},
                     timeout=600)
    try:
        return json.loads(body.decode()).get("name")
    except Exception:
        return None


def _set_node_input(graph, title, value, input_key=None, want_video=True):
    """按节点 title 注入输入；返回命中数。

    input_key 显式指定要写哪个输入键；不传则**从节点 schema 推**（原生 LoadVideo=file、
    VHS_LoadVideo=video、LoadAudio=audio），最后才回退到按 title 猜。

    为什么要 schema 推导 + 清掉非法键（2026-09-13 线上事故）：只靠 title 猜时，
    给 LoadVideo 写了它不认识的 `video` 键，而真正的 `file` 键保留了工作流 JSON 里
    写死的旧文件名 —— ComfyUI 直接加载了那个旧文件，于是拿我调试用的静帧片出了片，
    但外观上「任务成功、资产也有」，极难发现。
    """
    hit = 0
    explicit = (input_key or "").strip()
    candidates = _VIDEO_FILE_KEYS if want_video else _AUDIO_FILE_KEYS
    for _nid, node in (graph or {}).items():
        if not isinstance(node, dict):
            continue
        meta = node.get("_meta") or {}
        if (meta.get("title") or "").strip().lower() != (title or "").lower():
            continue
        info = _node_info(node.get("class_type")) if node.get("class_type") else None
        # 显式指定的键如果该节点并不声明（env 写错键名），**退回 schema 推导**，
        # 而不是写到节点不认识的键上（那正是把真文件丢掉、旧文件被静默使用的成因）
        key = explicit if (explicit and (not info or _has_input(info, explicit))) else None
        key = key or _derive_input_key(info, want_video) or explicit \
            or ("video" if want_video else "audio")
        inputs = node.setdefault("inputs", {})
        # 同一节点上其它「候选文件名键」：节点不认识的直接删掉，认识的清空
        # （宁可让 ComfyUI 报「文件名为空」，也不要静默加载旧文件）
        for k in candidates:
            if k == key or k not in inputs or not isinstance(inputs[k], str):
                continue
            if _has_input(info, k):
                inputs[k] = ""
            else:
                del inputs[k]
        inputs[key] = value
        print("[comfy] 注入 %s -> %s.%s = %s" % (title, node.get("class_type"), key, value),
              flush=True)
        hit += 1
    return hit


def _ensure_output_fps(graph, fps):
    """把「图片序列→视频」那一步的 fps 钉成 LatentSync 的原生帧率（默认 25）。

    为什么要钉（2026-09-13 线上事故）：LatentSync 按 config 的 `video_fps: 25` 生成帧，
    帧数 ≈ 配音时长 × 25；若组装时用**源片 fps**（本项目 motion 是 30），播放会快 25/30，
    成片时长缩短 17% → **末尾对白被截掉**（实例：4.92s 配音 → 4.17s 成片）。
    注意这些帧是「定数」的，改播放速度不能补回内容，只能改回 25。
    """
    fixed = []
    for node in (graph or {}).values():
        if not isinstance(node, dict):
            continue
        inputs = node.get("inputs") or {}
        if "fps" not in inputs:
            continue
        # 无论原值是指向源片的链接还是字面量，一律钉成固定值
        inputs["fps"] = int(fps)
        fixed.append(node.get("class_type"))
    if fixed:
        print("[comfy] 对口型输出 fps 已钉为 %d（节点：%s）" % (int(fps), ",".join(str(x) for x in fixed)),
              flush=True)
    return fixed


def _apply_fps_policy(graph):
    """帧率策略：默认「**生成帧率 = 播放帧率 = 源片帧率**」。

    为什么（2026-09-13 事故）：LatentSync 的 `video_fps` 决定「按配音时长生成多少帧」
    （frames ≈ 配音秒数 × fps）并同步截取音频长度；播放时**必须**用同一个 fps，
    否则时长会按比例变化（曾把组装端接到源片 30fps 而生成端停在默认 25
    → 成片短 17%、末尾对白被截；反过来也会把影片拉长）。

    两种模式：
      · `WEAVEORA_LIPSYNC_FPS` > 0：全部 fps 输入钉成该值（质量兜底开关：
        模型原生训练帧率是 25，若某台机器上非 25 的效果不满意，设 25 回退）；
      · 未设/0（默认，auto）：把除生成节点外所有 fps 输入**对齐到生成节点的 fps**
        （同一个链接/字面量）——工作流把两者都接到源片 fps，就得到源片帧率的成片。
    生成节点自己缺 fps 输入时（旧工作流）回退到 25（= 节点内部默认值）。
    返回 (mode, [被改的节点类名]) 供日志展示。
    """
    nodes = [n for n in (graph or {}).values() if isinstance(n, dict)]
    gen = next((n for n in nodes if n.get("class_type") == LIPSYNC_NODE_CLASS), None)
    gen_fps = ((gen or {}).get("inputs") or {}).get("fps")
    forced = int(LIPSYNC_FPS) if int(LIPSYNC_FPS or 0) > 0 else None
    if forced is None and gen_fps is None:
        forced = 25   # 旧工作流没有 fps 输入 → 跟节点内部默认保持一致
    touched = []
    for n in nodes:
        inputs = n.get("inputs") or {}
        if "fps" not in inputs:
            continue
        if forced is not None:
            inputs["fps"] = forced
        elif n.get("class_type") == LIPSYNC_NODE_CLASS:
            continue                      # 生成节点保留工作流里的来源（源片 fps）
        else:
            inputs["fps"] = gen_fps       # 对齐生成端（同一链接 → 运行时同值）
        touched.append(n.get("class_type"))
    return ("forced=%d" % forced) if forced is not None else "auto=source-fps", touched


# 人脸预检（子进程，**CPU 检测器**）：抽 6 帧看有没有脸。
#
# 为什么必须用 CPU 版：这个检查要在 ComfyUI 刚跑完/还没跑的时候执行，而 ComfyUI 会把模型
# 缓存在显存里（实测残留 ~5GB/8GB）；再起一个 CUDA 上下文（torch + ORT）很容易把 8GiB 卡打爆。
# 6 帧检测在 CPU 上只多花几秒，但完全不吃显存。
_FACE_CHECK = r'''
import os, sys, json, cv2, numpy as np
# 注：这里的常量**不能**写裸名 LATENTSYNC_DIR —— 本脚本是交给 `python -c` 的子进程，
# 父进程的模块变量在子进程里并不存在（写裸名会 NameError → 子进程崩 → 父进程只看到
# “预检无结果，不拦” → 门禁静默失效）。所以只能读 env + 默认值。
node = (os.environ.get("WEAVEORA_LATENTSYNC_DIR") or r"D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper")
sys.path.insert(0, node)
AUX = os.path.join(node, "checkpoints", "auxiliary")
tgt_path = sys.argv[2] if len(sys.argv) > 2 else ""
try:
    from insightface.app import FaceAnalysis
    # landmark_2d_106：底片体检用（脸太小 / 嘴张太大）。没有这个模型时 insightface 会报错，
    # 外层 except 会打印 SKIP → 调用方跳过门禁（而不是误拦）。
    mods = ["detection", "landmark_2d_106"] + (["recognition"] if tgt_path else [])
    app = FaceAnalysis(allowed_modules=mods, root=AUX, providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(512, 512))
except Exception as e:
    print("SKIP:init:" + str(e)[:160]); sys.exit(0)

def mouth_ratio(face):
    """嘴部张开度 ≈ (下唇均y − 上唇均y) / 嘴宽；判不出返 None。

    与 deploy/face/face_server.py 里的 _mouth_open_ratio 同构（子进程无法 import 主进程函数）。
    2d106 点序号可用 WEAVEORA_LIPSYNC_MOUTH_IDX 覆盖；过不了合理性校验就跳过。
    """
    try:
        lm = getattr(face, "landmark_2d_106", None)
        if lm is None:
            return None
        pts = np.asarray(lm, dtype=np.float32)
        if pts.ndim != 2 or pts.shape[0] < 100:
            return None
        spec = (os.environ.get("WEAVEORA_LIPSYNC_MOUTH_IDX") or "52-71").strip()
        sep = "-" if "-" in spec else ":"
        try:
            a, b = [int(x) for x in spec.split(sep, 1)]
        except Exception:
            a, b = 87, 105
        if not (0 <= a < b <= 106):
            a, b = 87, 105
        mouth = pts[a:b + 1]
        bx0, by0, bx1, by1 = [float(v) for v in face.bbox[:4]]
        fw, fh = bx1 - bx0, by1 - by0
        if fw <= 0 or fh <= 0 or mouth.shape[0] < 6:
            return None
        mx, my = mouth[:, 0], mouth[:, 1]
        mw = float(mx.max() - mx.min())
        if mw <= 0 or not (0.15 <= mw / fw <= 0.75):
            return None
        if abs(float(mx.mean()) - (bx0 + fw / 2.0)) > 0.35 * fw:
            return None
        if float(my.mean()) < by0 + 0.5 * fh:
            return None
        # 张开度 = 嘴部**中央区域**的上下唇最大间距 / 嘴宽。
        # 不用「按中位数分组再取均值」：那会把极值平均掉（实测真值 0.5 只测出 0.237 → 漏判）。
        cx = float(mx.mean())
        cen = my[np.abs(mx - cx) <= 0.25 * mw]
        if cen.size < 4:
            return None
        return float(cen.max() - cen.min()) / mw
    except Exception:
        return None

def face_area_ratio(face, frame):
    try:
        bx0, by0, bx1, by1 = [float(v) for v in face.bbox[:4]]
        h, w = frame.shape[0], frame.shape[1]
        if w <= 0 or h <= 0:
            return None
        return max(0.0, (bx1 - bx0) * (by1 - by0)) / float(w * h)
    except Exception:
        return None

tgt = None
if tgt_path:
    try:
        tgt = np.asarray(json.load(open(tgt_path, encoding="utf-8")), dtype=np.float32).reshape(-1)
        tgt = tgt / (float(np.linalg.norm(tgt)) or 1.0)
    except Exception:
        tgt = None
cap = cv2.VideoCapture(sys.argv[1])
if not cap.isOpened():
    print("SKIP:decode"); sys.exit(0)
n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
idx = sorted({int(i * (n - 1) / 5) for i in range(6)}) if n > 1 else [0]
hits = total = 0
best = -1.0
face_ratio = None
face_px = None
mouth_open = None
for i in idx:
    cap.set(cv2.CAP_PROP_POS_FRAMES, i)
    ok, fr = cap.read()
    if not ok:
        continue
    total += 1
    try:
        faces = app.get(fr)
    except Exception:
        faces = []
    if len(faces) > 0:
        hits += 1
    # 「底片体检」指标：取所有帧/所有脸的**最坏**值（最坏情况才是要拦的那个）
    for f in faces:
        r = face_area_ratio(f, fr)
        if r is not None:
            face_ratio = r if face_ratio is None else max(face_ratio, r)
        try:
            wpx = float(f.bbox[2]) - float(f.bbox[0])
            face_px = wpx if face_px is None else max(face_px, wpx)
        except Exception:
            pass
        mo = mouth_ratio(f)
        if mo is not None:
            mouth_open = mo if mouth_open is None else max(mouth_open, mo)
    if tgt is not None:
        for f in faces:
            emb = getattr(f, "normed_embedding", None)
            if emb is None:
                continue
            v = np.asarray(emb, dtype=np.float32)
            v = v / (float(np.linalg.norm(v)) or 1.0)
            s = float(np.dot(v, tgt))
            if s > best:
                best = s
cap.release()
print("RESULT:%d/%d/%s/%s/%s/%s" % (
    hits, total, ("%.4f" % best) if tgt is not None else "",
    ("%.5f" % face_ratio) if face_ratio is not None else "",
    ("%.4f" % mouth_open) if mouth_open is not None else "",
    ("%.1f" % face_px) if face_px is not None else ""))
'''


NO_FACE_MSG = ("该镜检测不到人脸（可能是远景/背影/空镜）——对口型只对正脸/侧脸可见的镜头有意义；"
               "请换一镜，或在导演里把该镜改成对话近景后重生成画面")
# 锁人时「目标人脸」的最低余弦相似度（低于它就不认，宁可不贴也不要贴错人）
TARGET_MIN_SIM = 0.28

# 预检失败原因（给调用方拼错误文案；单线程内单次使用，无需加锁）
_face_reason = {"msg": NO_FACE_MSG, "warn": ""}

# LatentSync 节点目录（本机人脸检测子进程用；可被「服务地址 → 人脸 → latentsyncDir」覆盖）
LATENTSYNC_DIR = os.environ.get("WEAVEORA_LATENTSYNC_DIR", "").strip()


def _face_probe(video_bytes, target_emb=None):
    """抽 6 帧跑人脸检测。

    返回 (hits, total, best_target_sim, extra)：
      hits/total = 检出人脸的帧数；给了 target_emb 时额外给出「最像目标的相似度」。
      extra = {"face_ratio": 最大脸框面积/画面面积, "mouth_open": 嘴部张开度}，
              取不到则为 None（调用方必须跳过门禁，而不是当成 0）。
    无法判定时返回 None（不拦不传）。
    """
    import subprocess
    import sys as _sys
    import tempfile
    if not video_bytes:
        return None
    # 配了「人脸服务」就走远端（新 GPU 机器集中算脸）；失败自动回退本机
    if FACE_URL:
        try:
            body = {"media_b64": base64.b64encode(video_bytes).decode("ascii"), "suffix": ".mp4"}
            if target_emb:
                body["target_embedding"] = [float(x) for x in target_emb]
            _st, resp = _post_json(FACE_URL + "/face/probe", body, timeout=900)
            hits = int(resp.get("hits") or 0)
            total = int(resp.get("total") or 0)
            best = resp.get("best")
            extra = {
                "face_ratio": (float(resp["face_ratio"]) if resp.get("face_ratio") is not None else None),
                "mouth_open": (float(resp["mouth_open"]) if resp.get("mouth_open") is not None else None),
                "face_px": (float(resp["face_px"]) if resp.get("face_px") is not None else None),
            }
            return hits, total, (float(best) if best is not None else None), extra
        except Exception as e:
            print("[comfy] 远端人脸服务不可用，回退本机：%s" % e, flush=True)
    d = tempfile.mkdtemp(prefix="weaveora_facechk_")
    fp = os.path.join(d, "in.mp4")
    tgt = os.path.join(d, "target.json")
    try:
        with open(fp, "wb") as fh:
            fh.write(video_bytes)
        if target_emb:
            with open(tgt, "w", encoding="utf-8") as fh:
                json.dump(list(target_emb), fh)
        r = subprocess.run([_sys.executable, "-c", _FACE_CHECK, fp, tgt if target_emb else ""],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=900)
        out = (r.stdout or "") + (r.stderr or "")
        for line in out.splitlines():
            if line.startswith("SKIP:"):
                print("[comfy] 人脸预检跳过（%s）" % line[5:], flush=True)
                return None
            if line.startswith("RESULT:"):
                parts = line[7:].split("/")
                hits, total = int(parts[0]), int(parts[1])
                # 无目标时脚本会输出 RESULT:h/t/（末段为空）——直接 float("") 会抛异常，
                # 导致“预检异常→不拦”，静默失去这道拦截
                best = float(parts[2]) if len(parts) > 2 and parts[2].strip() else None
                fr = float(parts[3]) if len(parts) > 3 and parts[3].strip() else None
                mo = float(parts[4]) if len(parts) > 4 and parts[4].strip() else None
                px = float(parts[5]) if len(parts) > 5 and parts[5].strip() else None
                return hits, total, best, {"face_ratio": fr, "mouth_open": mo, "face_px": px}
        print("[comfy] 人脸预检无结果，不拦：%s" % out[:120], flush=True)
        return None
    except Exception as e:
        print("[comfy] 人脸预检异常，不拦：%s" % e, flush=True)
        return None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _face_precheck(video_bytes, where="lipsync", target_emb=None, speaker="", force=False):
    """对口型前置门禁。三道：① 画面里有人脸；② （给了目标特征时）**说话人的脸真在画面里**；
    ③ 「底片体检」：脸是否太小 / 嘴是否张得太大（B）。

    为什么要第二道（2026-09-13 实例）：第 6 镜说话人是「袭人」，但她与「警幻」的定妆照
    几乎同一张脸（相似度 0.71）——锁定她时最高相似度只有 0.18，低于阈值 0.28。
    这时不能硬跑（会去驱动最大脸=另一个人，画面坏掉），也不能报一句误导的
    "Face not detected"，而应该**说清楚原因并让用户去修定妆照**。

    为什么要第三道（2026-09-14 实测，用户反馈「画面被破坏」）：第 5 镜 action
    「…抓住宝玉将他拖下溪去，宝玉失声惊叫」、正词 `his mouth open in a terrified scream`，
    底片（motion 片段）里嘴本来就大张，LatentSync 要先把嘴合上再按音频重开 →
    嘴部掩码区大幅形变。实测 mouth_open：第5镜 1.232/1.238（真·大张），
    而平静脸 0.44~0.66、第6镜 0.59/0.78 —— 阈值就按这两档分的（见常量区量化表）。
    底片本身不合格时应**提前拒绝并告诉他怎么换**，而不是烧十几分钟 GPU 出一段坏画面。

    逃生门：force=True（payload.lipsyncForce）或 env WEAVEORA_LIPSYNC_FORCE=1。
    """
    _face_reason["warn"] = ""
    r = _face_probe(video_bytes, target_emb)
    if r is None:
        return True
    hits, total, best = r[0], r[1], r[2]
    extra = r[3] if len(r) > 3 and isinstance(r[3], dict) else {}
    print("[comfy] 人脸预检 %s：%d/%d 帧可检出%s"
          % (where, hits, total, "，最像说话人的相似度=%.2f" % best if best is not None else ""),
          flush=True)
    if total <= 0:
        return True
    if hits == 0:
        _face_reason["msg"] = NO_FACE_MSG
        return False
    if hits < total:
        _face_reason["msg"] = (
            "该镜有 %d/%d 帧检测不到人脸——对口型（LatentSync）要求**每一帧**都能检出人脸，"
            "跑下去会在中途报 Face not detected；请换镜，或先把该镜画面重新生成"
            % (total - hits, total))
        return False
    if target_emb is not None and best is not None and best < TARGET_MIN_SIM:
        _face_reason["msg"] = (
            "画面里找不到说话人「%s」的人脸（最高的相似度仅 %.2f，阈值 %.2f）。"
            "常见原因：① 说话人不在画面里（画外音）；② 该角色的定妆照与画面形象差异过大；"
            "③ 两个角色的定妆照过于相似（实测「袭人」与「警幻」定妆照相似度 0.71，同一张脸），"
            "此时按脸认人本身就不可能。请先核对「%s」的定妆照（导入图）选对了没有。"
            % (speaker or "?", best, TARGET_MIN_SIM, speaker or "?"))
        return False
    return _base_health(extra, where, force, speaker)


def _base_health(extra, where, force=False, speaker=""):
    """底片体检（B）：嘴大张 / 脸极小 → 拒绝；嘴偏大 / 脸偏小 → 只提醒。拿不到指标就放行。

    为什么嘴优先于脸：① 嘴大张是「画面被破坏」的直接原因（第 5 镜实测）；② 合法宽景
    （脸宽 ~96px）不应该被“脸小”一刀切拦掉，坏画面才是真损失。
    拒绝时把**所有命中的原因**一起说出来，别让用户改完一条又撞下一条。
    """
    if not isinstance(extra, dict):
        return True
    fr = extra.get("face_ratio")
    px = extra.get("face_px")
    mo = extra.get("mouth_open")
    print("[comfy] 底片体检 %s：脸占比=%s，脸宽=%s，嘴张开度=%s%s"
          % (where, ("%.3f%%" % (fr * 100)) if fr is not None else "n/a",
             ("%.0fpx" % px) if px is not None else "n/a",
             ("%.3f" % mo) if mo is not None else "n/a",
             "（已开启强制，只记录不拦）" if force else ""), flush=True)
    if force:
        return True
    # 「脸太小」优先用像素宽（不受抽帧分辨率影响）；没有 px 才退到占画面比
    tiny = (px is not None and px < LIPSYNC_FACE_MIN_PX) \
        or (px is None and fr is not None and fr < LIPSYNC_FACE_MIN_RATIO)
    small = px is not None and LIPSYNC_FACE_MIN_PX <= px < LIPSYNC_FACE_WARN_PX
    mouth_bad = mo is not None and mo >= LIPSYNC_MOUTH_MAX
    mouth_warn = mo is not None and LIPSYNC_MOUTH_WARN <= mo < LIPSYNC_MOUTH_MAX
    if mouth_bad or tiny:
        parts = []
        if mouth_bad:
            parts.append(
                "**嘴部大张**（张开度 %.2f，阈值 %.2f）%s：LatentSync 要把大张的嘴先「合上」再按配音"
                "重开，嘴部区域会大幅形变 —— 实测这就是「画面被破坏」的主因"
                % (mo, LIPSYNC_MOUTH_MAX, "（说话人：%s）" % speaker if speaker else ""))
        if tiny:
            parts.append(
                "**人脸太小**（%s）：对口型对远景/小人物没有可见效果"
                % (("脸宽仅 %.0fpx，阈值 %.0fpx" % (px, LIPSYNC_FACE_MIN_PX)) if px is not None
                   else ("最大脸只占画面 %.2f%%，阈值 %.2f%%" % (fr * 100, LIPSYNC_FACE_MIN_RATIO * 100))))
        _face_reason["msg"] = (
            "该镜的**底片**不适合跑对口型：\n- %s\n\n请任选其一：\n"
            "① 换成该镜「嘴部自然（闭合/微张）」的**静帧关键帧**当底片（选镜弹窗 → 底片＝静帧）；\n"
            "② 如该镜本就是喊叫/惊恐，建议改成旁白/画外音或侧脸（导演层「分镜规避」）；\n"
            "③ 确实要试，打开「强制」后重跑（不保证画面完好）。"
            % "\n- ".join(parts))
        return False
    warns = []
    if mouth_warn:
        warns.append("底片嘴部偏大（%.2f）——对口型后嘴部可能变形" % mo)
    if small:
        warns.append("底片人脸偏小（脸宽 %.0fpx，建议 ≥ %.0fpx）——口型会偏糊" % (px, LIPSYNC_FACE_WARN_PX))
    if warns:
        _face_reason["warn"] = "；".join(warns) + "（选镜弹窗 → 底片＝静帧，或挑近景镜）"
    return True


# 预检失败原因（给调用方拼错误文案；线程内单次使用，无需加锁）



# 参考图人脸特征提取（子进程，**CPU**）：用于「锁人」——多人同框时只驱动说话人那张脸。
# 用 CPU 同样是为了不占显存（ComfyUI 还缓存着模型）。
_EMBED = r'''
import os, sys, json, cv2, numpy as np
# 注：不能写裸名 LATENTSYNC_DIR（子进程里不存在 → NameError → 静默回退最大脸）；只读 env + 默认值。
node = (os.environ.get("WEAVEORA_LATENTSYNC_DIR") or r"D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper")
sys.path.insert(0, node)
AUX = os.path.join(node, "checkpoints", "auxiliary")
try:
    from insightface.app import FaceAnalysis
    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=AUX,
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(512, 512))
except Exception as e:
    print("SKIP:init:" + str(e)[:160]); sys.exit(0)
img = cv2.imdecode(np.fromfile(sys.argv[1], dtype=np.uint8), cv2.IMREAD_COLOR)
if img is None:
    print("SKIP:decode"); sys.exit(0)
faces = app.get(img)
if not faces:
    print("SKIP:noface"); sys.exit(0)
f = max(faces, key=lambda x: (x.bbox[2]-x.bbox[0])*(x.bbox[3]-x.bbox[1]))
print("EMB:" + json.dumps([float(v) for v in f.normed_embedding]))
'''


def _embed_reference(img_bytes):
    """取参考图（定妆照）里最大脸的 512 维特征；拿不到返回 None（调用方退回旧行为）。"""
    import subprocess
    import sys as _sys
    import tempfile
    if not img_bytes:
        return None
    if FACE_URL:
        try:
            _st, resp = _post_json(FACE_URL + "/face/embed",
                                   {"image_b64": base64.b64encode(img_bytes).decode("ascii"),
                                    "suffix": ".png"}, timeout=900)
            emb = resp.get("embedding")
            return [float(x) for x in emb] if emb else None
        except Exception as e:
            print("[comfy] 远端人脸服务不可用，回退本机：%s" % e, flush=True)
    d = tempfile.mkdtemp(prefix="weaveora_emb_")
    fp = os.path.join(d, "ref.png")
    try:
        with open(fp, "wb") as fh:
            fh.write(img_bytes)
        r = subprocess.run([_sys.executable, "-c", _EMBED, fp],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=900)
        out = (r.stdout or "") + (r.stderr or "")
        for line in out.splitlines():
            if line.startswith("SKIP:"):
                print("[comfy] 参考图人脸特征提取跳过（%s）" % line[5:], flush=True)
                return None
            if line.startswith("EMB:"):
                import json as _json
                return _json.loads(line[4:])
        print("[comfy] 参考图人脸特征无结果：%s" % out[:120], flush=True)
        return None
    except Exception as e:
        print("[comfy] 参考图人脸特征异常：%s" % e, flush=True)
        return None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _set_node_scalar(graph, node_class, key, value):
    """按 class_type 给节点写一个标量输入（返回命中数）。用于注入人脸特征文件路径等。"""
    hit = 0
    for node in (graph or {}).values():
        if isinstance(node, dict) and node.get("class_type") == node_class:
            node.setdefault("inputs", {})[key] = value
            hit += 1
    return hit


def _ffmpeg_exe():
    """本机 ffmpeg 路径。

    优先级：WEAVEORA_FFMPEG 环境变量 > imageio_ffmpeg 自带的 > PATH 里的 ffmpeg。
    加环境变量覆盖是因为 imageio-ffmpeg 在 Python 3.6 上只能装到 0.4.9，其自带
    ffmpeg 仅 4.2.2，不支持 `-fps_mode`（需 >= 4.3）。
    """
    p = os.environ.get("WEAVEORA_FFMPEG", "").strip()
    if p and os.path.exists(p):
        return p
    try:
        import imageio_ffmpeg as _iif
        return _iif.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def _run_ff(args, timeout=900):
    import subprocess
    r = subprocess.run([_ffmpeg_exe(), "-y", "-loglevel", "error"] + args,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=timeout)
    if r.returncode != 0:
        raise ComfyError("ffmpeg 失败: %s" % (r.stderr or "")[-300:])
    return r


def _cut_frames(video_bytes, a, b, tmp, tag):
    """按**帧号**切 [a, b)（帧精确，用于按段驱动）。

    为什么按帧号而不是按秒：分段驱动要把每段的结果拼回原位，时间戳一旦有偏差
    就会出现音视频错位。`select=between(n,a,b-1)` + `setpts=N/FRAME_RATE/TB` 让
    切出来的片从第 0 帧重新计时、且帧数与原视频一致。
    """
    ip = os.path.join(tmp, "%s_in.mp4" % tag)
    op = os.path.join(tmp, "%s_out.mp4" % tag)
    with open(ip, "wb") as fh:
        fh.write(video_bytes)
    _run_ff(["-i", ip, "-vf",
             "select='between(n\\,%d\\,%d)',setpts=N/FRAME_RATE/TB" % (a, b - 1),
             "-an", "-fps_mode", "passthrough", "-c:v", "libx264", "-pix_fmt", "yuv420p",
             "-crf", "18", op])
    with open(op, "rb") as fh:
        return fh.read()


def _mux_audio(video_bytes, audio_bytes, tmp):
    """给拼好的画面重新接上**完整配音**（音轨不动，保证与原来同一时间轴）。"""
    vp = os.path.join(tmp, "mux_v.mp4")
    ap = os.path.join(tmp, "mux_a.wav")
    op = os.path.join(tmp, "mux_out.mp4")
    with open(vp, "wb") as fh:
        fh.write(video_bytes)
    with open(ap, "wb") as fh:
        fh.write(audio_bytes)
    _run_ff(["-i", vp, "-i", ap, "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
             "-map", "0:v:0", "-map", "1:a:0", op])
    with open(op, "rb") as fh:
        return fh.read()


def _frame_count(video_bytes, tmp, tag="fc"):
    """数一段视频的帧数（用 -count_frames 太重，改用 python 侧 cv2）。"""
    import tempfile as _tf
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    n = int(cap.get(_cv.CAP_PROP_FRAME_COUNT)) if cap.isOpened() else 0
    cap.release()
    return n


def _decode_frames(video_bytes, tmp, tag="dec"):
    """把一段视频解码成帧列表（BGR numpy）。"""
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    frames = []
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        frames.append(fr)
    cap.release()
    return frames


def _encode_frames(frames, fps, tmp, tag="enc"):
    """帧列表 → mp4（复用 _encode_frames_mp4：写 PNG 序列再交 ffmpeg）。"""
    import cv2 as _cv
    pngs = []
    for fr in frames:
        ok, buf = _cv.imencode(".png", fr)
        if ok:
            pngs.append(buf.tobytes())
    if not pngs:
        raise ComfyError("编码失败：没有帧")
    out_dir = os.path.join(tmp, "enc_%s" % tag)
    os.makedirs(out_dir, exist_ok=True)
    return _encode_frames_mp4(pngs, max(1, int(fps)), out_dir)


def _source_fps(video_bytes, tmp, tag="fps"):
    """源片帧率（分段驱动要靠它把秒换算成帧号）。"""
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    fps = cap.get(_cv.CAP_PROP_FPS) if cap.isOpened() else 0
    cap.release()
    return float(fps or 25.0) or 25.0


def _lipsync_seg_windows(durs, fps, total, margin=4):
    """把「每段配音时长」切成帧窗口：返回 `[(a, b, own_b)]`（★ 2026-09-23 / P8）。

    · `own_b` = 该段的**归属窗口**终点 —— 与下一段**首尾相接、绝不重叠**（不再二次贴回）；
    · `b`     = 喂给 LatentSync 的**推理窗口**终点 = 归属 + `margin` 帧 —— 保证「视频帧 ≥ 音频需求」，
                否则节点会走 `loop_video`（正放+倒放凑帧）把时间轴弄乱；`margin` 那几帧只用于推理。
    · 起点按**帧数累加**（不每次四舍五入时间）→ 顺带消掉 ±1 帧的累计漂移。

    旧写法 `a = round(t_cum*fps)` / `b = a + round(dur*fps) + 4` 会让上一段的尾巴（含 4 帧余量）
    与下一段的头**重叠**（实测 「0-50帧」/「46-108帧」），同几帧被两段各推理一次再淡入覆盖。
    """
    out = []
    a = 0
    for d in durs:
        try:
            dd = float(d)
        except (TypeError, ValueError):
            dd = 0.0
        if total <= 0:
            out.append((0, 0, 0))
            continue
        a = max(0, min(a, total - 1))
        own_len = max(1, int(round(max(0.0, dd) * fps)))   # 该段归属的帧数（≈ 音频需求）
        own_b = min(a + own_len, total)
        b = min(a + own_len + max(0, int(margin)), total)
        out.append((a, b, own_b))
        a = own_b
    return out


def _splice(source_bytes, seg_results, fps, tmp):
    """把各段的处理结果**按原时间轴替换回原帧序列**。

    为什么不用「切段→拼接」：LatentSync 是按**音频长度**出帧的，段内产出的帧数
    与切出来的帧数可能差一两帧；一旦用拼接，后面的每一段都会整体前/后移，音画就溧了。
    替换回原位则**总帧数与帧位置完全不变**，音轨也就能原封不动接上。

    ★ 2026-09-22：段头/段尾加 **时间淡入淡出**（原来是 `frames[a:a+n] = pf[:n]` 硬替换）。
    用户实测反馈「最后会啪的一下」；逐帧量化：段尾（帧108）全局帧差 = 同帧源片自身运动的 **5.0×**
    （段首那记 8.1× 已被帧率修复消掉，剩下的就是段结束时从「驱动画面」硬切回「原帧」）。
    只影响接缝处的几帧（面部区域以外本来就是同一张原帧），不动段内内容。
    """
    frames = _decode_frames(source_bytes, tmp, "src")
    if not frames:
        raise ComfyError("源片解码失败（0 帧）")
    total_blended = 0
    for a, b, pf in seg_results:
        if not pf:
            continue
        if a >= len(frames):
            continue
        # 只把**实际产出**的帧放回 [a, a+len(pf))，其余保持原帧：
        # 不外推、不重复末帧 —— 因为 LatentSync 的产出帧数与切出来的帧数本来就可能不等
        # （产出长度由**音频**决定，见 loop_video），重复外推会把后面的画面“冻结”几帧。
        n = min(len(pf), max(0, b - a), len(frames) - a)
        if n <= 0:
            continue
        k = max(0, min(LIPSYNC_SEAM_BLEND, n // 3))   # 段太短时自动减小，头尾不重叠
        for i in range(n):
            dst = a + i
            if k <= 0:
                frames[dst] = pf[i]
                continue
            w = 1.0
            if i < k:
                w = (i + 1) / (k + 1)
            if i >= n - k:
                w = min(w, (n - i) / (k + 1))
            if w >= 1.0:
                frames[dst] = pf[i]
                continue
            if np is None:
                frames[dst] = pf[i]
                continue
            try:
                frames[dst] = ((pf[i].astype(np.float32) * w)
                               + (frames[dst].astype(np.float32) * (1.0 - w))).astype(np.uint8)
                total_blended += 1
            except Exception:
                frames[dst] = pf[i]      # 尺寸不一致等异常：宁可硬替换也不报错
    if LIPSYNC_SEAM_BLEND > 0:
        print("[comfy] 段间缝合：头/尾各至多 %d 帧做时间淡入淡出，共混合 %d 帧（消除段尾“啪”一下）"
              % (LIPSYNC_SEAM_BLEND, total_blended), flush=True)
    elif seg_results:
        print("[comfy] 段间缝合：硬替换（WEAVEORA_LIPSYNC_SEAM_BLEND=0，段边界可能有跳变）", flush=True)
    return _encode_frames(frames, fps, tmp, "spliced")


def _post_json(url, obj, timeout=900):
    """POST JSON → (status, dict)。"""
    import urllib.request as _ur
    req = _ur.Request(url, data=json.dumps(obj).encode("utf-8"),
                      headers={"Content-Type": "application/json"})
    with _ur.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def apply_services(svc):
    """按任务下发（claim 响应）的「服务地址」覆盖本进程运行期配置。

    为什么这样做：这些地址原先只能靠 worker 机器的环境变量决定，换 GPU 服务器就得改脚本、
    重启 worker。现在用户可在「生成引擎配置 → 服务地址」里随时改，随任务下发即刻生效。
    空值/未配置一律**不覆盖**（保持环境变量默认，向后兼容）。
    """
    global COMFY, LIPSYNC_WORKFLOW, LIPSYNC_TIMEOUT, LIPSYNC_FPS, FACE_URL, LATENTSYNC_DIR, MOTION_OVERRIDES
    if not isinstance(svc, dict):
        return
    def g(*path):
        cur = svc
        for k in path:
            if not isinstance(cur, dict):
                return None
            cur = cur.get(k)
        return cur
    # 引擎配置下发的 motion 档位（preset/steps/lora_*/cfg_* …）：收白名单键，switch 归一成 switch_step
    motion = g("motion")
    if isinstance(motion, dict):
        clean = {}
        for k, v in motion.items():
            if v is None:
                continue
            k2 = _snake_key(k)          # camelCase 兼容（前端两种写法都可能出现）
            if k2 not in MOTION_SERVICE_KEYS:
                continue
            clean["switch_step" if k2 == "switch" else k2] = v
        if clean:
            MOTION_OVERRIDES = clean
            print("[comfy] motion 档位（引擎配置下发）：%s" % clean, flush=True)
    comfy = g("lipsync", "comfyUrl")
    if isinstance(comfy, str) and comfy.strip():
        COMFY = comfy.strip().rstrip("/")
    wf = g("lipsync", "workflow")
    if isinstance(wf, str) and wf.strip():
        LIPSYNC_WORKFLOW = wf.strip()
    t = g("lipsync", "timeout")
    if isinstance(t, (int, float)) and t > 0:
        LIPSYNC_TIMEOUT = float(t)
    f = g("lipsync", "fps")
    if isinstance(f, (int, float)):
        LIPSYNC_FPS = int(f)
    face = g("face", "url")
    if isinstance(face, str) and face.strip():
        FACE_URL = face.strip().rstrip("/")
    # ── 文生图（本机 ComfyUI 工作流，Qwen-Image / FLUX）────────────────────────────
    # 为什么用「工作流 JSON」而不是代码里拼节点：换模型（SDXL → Qwen-Image → FLUX）
    # 只要换一个 JSON，不用改 worker、不用重启；参数注入靠 class_type/标题约定（见 generate_via_workflow）。
    img = g("image")
    if isinstance(img, dict):
        global IMAGE_ENGINE, IMAGE_COMFY, IMAGE_TXT2IMG_WF, IMAGE_IMG2IMG_WF, IMAGE_EDIT_WF, IMAGE_MODEL, IMAGE_STEPS, IMAGE_DENOISE, IMAGE_CFG, IMAGE_LORA, IMAGE_LORA_STRENGTH, IMAGE_LORA_CFG, IMAGE_LORA_STEPS_FLUX2, IMAGE_LORA_CFG_FLUX2
        eng = img.get("engine")
        if isinstance(eng, str) and eng.strip():
            IMAGE_ENGINE = eng.strip().lower()
        cu = img.get("comfyUrl")
        if isinstance(cu, str) and cu.strip():
            IMAGE_COMFY = cu.strip().rstrip("/")
        w = img.get("workflow")
        if isinstance(w, str) and w.strip():
            IMAGE_TXT2IMG_WF = w.strip()
        w2 = img.get("img2imgWorkflow") or img.get("img2img_workflow")
        if isinstance(w2, str) and w2.strip():
            IMAGE_IMG2IMG_WF = w2.strip()
        w3 = img.get("editWorkflow") or img.get("edit_workflow")
        if isinstance(w3, str) and w3.strip():
            IMAGE_EDIT_WF = w3.strip()
        m = img.get("model")
        if isinstance(m, str) and m.strip():
            IMAGE_MODEL = m.strip()
        st = img.get("steps")
        if isinstance(st, (int, float)) and st > 0:
            IMAGE_STEPS = int(st)
        dn = img.get("denoise")
        if isinstance(dn, (int, float)) and 0 < float(dn) <= 1:
            IMAGE_DENOISE = float(dn)
        cg = img.get("cfg")
        if isinstance(cg, (int, float)) and float(cg) > 0:
            IMAGE_CFG = float(cg)
        lo = img.get("lora")
        if isinstance(lo, str) and lo.strip():
            IMAGE_LORA = lo.strip()
        ls = img.get("loraStrength") or img.get("lora_strength")
        if isinstance(ls, (int, float)) and float(ls) > 0:
            IMAGE_LORA_STRENGTH = float(ls)
        lc = img.get("loraCfg") or img.get("lora_cfg")
        if isinstance(lc, (int, float)) and float(lc) > 0:
            IMAGE_LORA_CFG = float(lc)
        # ★ FLUX.2 专用档（留空 = 用 env 默认 8 步 / guidance 4.0）；只在引擎页显式配了才覆盖。
        ls2 = img.get("loraStepsFlux2") or img.get("lora_steps_flux2")
        if isinstance(ls2, (int, float)) and float(ls2) > 0:
            IMAGE_LORA_STEPS_FLUX2 = int(ls2)
        lc2 = img.get("loraCfgFlux2") or img.get("lora_cfg_flux2")
        if isinstance(lc2, (int, float)) and float(lc2) > 0:
            IMAGE_LORA_CFG_FLUX2 = float(lc2)
        print("[comfy] 文生图配置（引擎配置下发）：engine=%s workflow=%s img2img=%s edit=%s model=%s steps=%s cfg=%s denoise=%s lora=%s@%s"
              % (IMAGE_ENGINE, IMAGE_TXT2IMG_WF or "-", IMAGE_IMG2IMG_WF or "-", IMAGE_EDIT_WF or "-",
                 IMAGE_MODEL or "-", IMAGE_STEPS or "-", IMAGE_CFG or "-", IMAGE_DENOISE,
                 IMAGE_LORA or "-", IMAGE_LORA_STRENGTH))
    node = g("face", "latentsyncDir")
    if isinstance(node, str) and node.strip():
        LATENTSYNC_DIR = node.strip()
    print("[comfy] 服务地址：comfy=%s lipsync_wf=%s timeout=%s fps=%s face=%s"
          % (COMFY, LIPSYNC_WORKFLOW or "(env 默认)", LIPSYNC_TIMEOUT, LIPSYNC_FPS,
             FACE_URL or "(本机)"), flush=True)


def _clamp_node_scalar(class_type, key, value):
    """按节点 schema 把标量夹进 min/max（越界会被 ComfyUI 直接 400）。

    为什么必须夹：实测 LatentSyncNode 的 `lips_expression` 是 min 1.0 / max 3.0，
    而「降低嘴部形变」的自然想法是给 0.8 → ComfyUI 报
    `prompt_outputs_failed_validation: Value 0.8 smaller than min of 1.0`，整个对口型任务秒失败。
    拿不到 schema 时原样返回（不因为探测失败而拦任务）。
    """
    lo = hi = None
    try:
        info = _node_info(class_type) or {}
        node = next(iter(info.values()), None) or {}
        spec = ((node.get("input") or {}).get("required") or {}).get(key)
        if isinstance(spec, list) and len(spec) > 1 and isinstance(spec[1], dict):
            lo, hi = spec[1].get("min"), spec[1].get("max")
    except Exception:
        lo = hi = None
    try:
        v = value
        if lo is not None and float(v) < float(lo):
            v = float(lo)
        if hi is not None and float(v) > float(hi):
            v = float(hi)
        return v, lo, hi
    except Exception:
        return value, lo, hi


def _run_lipsync_graph(client_id, vbytes, abytes, emb_path, on_tick=None, lips_expression=None):
    """跑一次对口型工作流，返回产物 mp4 bytes（整镜 / 单段共用）。

    lips_expression：嘴部驱动强度（工作流默认 1.5）。极端表情（惊恐/喊叫）的底片降到 0.8，
    减少「先合上再重开」造成的嘴部形变；None = 不改工作流原值。
    """
    import uuid as _uuid
    if not LIPSYNC_WORKFLOW or not os.path.exists(LIPSYNC_WORKFLOW):
        raise ComfyError("对口型工作流不存在：检查 WEAVEORA_LIPSYNC_WORKFLOW=%s" % LIPSYNC_WORKFLOW)
    _tok = _uuid.uuid4().hex[:8]
    vname = _upload_any(vbytes, "weaveora_lipsync_%s_in.mp4" % _tok, "video/mp4")
    if not vname:
        raise ComfyError("上传画面失败")
    aname = _upload_any(abytes, "weaveora_lipsync_%s_voice.wav" % _tok, "audio/wav")
    if not aname:
        raise ComfyError("上传配音失败")
    with open(LIPSYNC_WORKFLOW, "r", encoding="utf-8") as fh:
        graph = json.load(fh)
    vh = _set_node_input(graph, LIPSYNC_VIDEO_TITLE, vname, LIPSYNC_VIDEO_INPUT)
    ah = _set_node_input(graph, LIPSYNC_AUDIO_TITLE, aname, LIPSYNC_AUDIO_INPUT, want_video=False)
    if not vh or not ah:
        raise ComfyError(
            "工作流里没找到标题为「%s」/「%s」的节点：请在 ComfyUI 里把承载视频/音频的节点标题改成这两个值"
            % (LIPSYNC_VIDEO_TITLE, LIPSYNC_AUDIO_TITLE))
    # 自检：确认注入后的图上确实指向本次上传的文件（防止再出现「写错键→静默用旧文件」）
    dirty = []
    for node in graph.values():
        if not isinstance(node, dict):
            continue
        for k, v in (node.get("inputs") or {}).items():
            if isinstance(v, str) and v and v.endswith((".mp4", ".wav", ".png", ".jpg")) \
                    and v not in (vname, aname):
                dirty.append("%s.%s=%s" % (node.get("class_type"), k, v))
    if dirty:
        raise ComfyError("对口型工作流里还有指向其它文件的输入（拒绝跑，避免拿错素材）：%s" % ", ".join(dirty))
    prefix = "weaveora_lipsync_" + _uuid.uuid4().hex[:6]
    for node in graph.values():
        if isinstance(node, dict) and "filename_prefix" in (node.get("inputs") or {}):
            node["inputs"]["filename_prefix"] = prefix
    if emb_path:
        _n = _set_node_scalar(graph, LIPSYNC_NODE_CLASS, "target_embedding_path", emb_path)
        print("[comfy] 已注入 target_embedding_path（命中 %d 个 %s）" % (_n, LIPSYNC_NODE_CLASS), flush=True)
    if lips_expression is not None:
        _val, _lo, _hi = _clamp_node_scalar(LIPSYNC_NODE_CLASS, "lips_expression", float(lips_expression))
        _n = _set_node_scalar(graph, LIPSYNC_NODE_CLASS, "lips_expression", _val)
        print("[comfy] 嘴部驱动强度 lips_expression=%.2f（请求 %.2f；节点允许 %s~%s；命中 %d 个 %s）"
              % (_val, float(lips_expression), _lo, _hi, _n, LIPSYNC_NODE_CLASS), flush=True)
    _mode, _nodes = _apply_fps_policy(graph)
    print("[comfy] 对口型 fps 策略：%s（节点：%s）" % (_mode, ",".join(str(x) for x in _nodes)), flush=True)
    # 裸节点图 → /prompt 要的是 {"prompt": 图, "client_id": ...}
    pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
    try:
        rec = _poll_history(client_id, pid, timeout=LIPSYNC_TIMEOUT, on_tick=on_tick)
    except ComfyError as e:
        try:
            _comfy("POST", "/interrupt", payload={}, timeout=30)
        except Exception:
            pass
        if "Face not detected" in str(e):
            raise ComfyError(NO_FACE_MSG)
        raise ComfyError("对口型推理失败：%s" % e)
    outs = _download_outputs(rec, prefix)
    if not outs:
        outs = _download_outputs(rec, "weaveora")
    if not outs:
        raise ComfyError("对口型无输出视频（prefix=%s）" % prefix)
    return outs[0]["bytes"]


def _probe_video_meta(mp4_bytes):
    """用 ffmpeg -i 读 mp4 的宽高与时长（返回 (w, h, duration_ms)）。

    为什么要做：LatentSync 节点只返回帧，不返回元数据；不回填的话资产库里这条
    对口型产物没有时长/分辨率（卡片显示不完整、导出时也可能被当成未知时长）。
    """
    import re
    import subprocess
    import tempfile
    if not mp4_bytes:
        return None, None, None
    p = os.path.join(tempfile.mkdtemp(prefix="weaveora_probe_"), "o.mp4")
    with open(p, "wb") as fh:
        fh.write(mp4_bytes)
    try:
        r = subprocess.run([_ffmpeg_exe(), "-i", p],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=180)
        err = r.stderr or ""
    except Exception:
        return None, None, None
    w = h = dur_ms = None
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", err)
    if m:
        dur_ms = int((int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))) * 1000)
    m2 = re.search(r"Video:.*?,\s*(\d{2,5})x(\d{2,5})", err)
    if m2:
        w, h = int(m2.group(1)), int(m2.group(2))
    return w, h, dur_ms


def _interp_video_on_box(client_id, vbytes, mult, target_fps):
    """对口型**之后**做 RIFE 插帧（问题 5：把插帧从运动阶段挑到最后一步）。

    为什么不能在 ffmpeg 里做：RIFE 是神经网络插帧，只有 GPU 机的 ComfyUI 节点能跑；
    ffmpeg 的 minterpolate 已在 2026-09-18 被全面禁用（分数倍混合帧 → 重影/几何扭曲）。

    图：LoadVideo → GetVideoComponents →（images/audio 分开）→ FrameInterpolate ×N
        → CreateVideo(fps=交付帧率, audio=原音轨) → SaveVideo
    ★ 必须把原音轨接回 CreateVideo，否则口型产物会丢配音。
    """
    import uuid as _uuid
    vname = _upload_any(vbytes, "weaveora_interp_%s_in.mp4" % _uuid.uuid4().hex[:8], "video/mp4")
    if not vname:
        raise ComfyError("对口型后插帧：上传视频失败")
    prefix = "weaveora_interp_" + _uuid.uuid4().hex[:6]
    graph = {
        "1": {"class_type": "LoadVideo", "inputs": {"file": vname}},
        "2": {"class_type": "GetVideoComponents", "inputs": {"video": ["1", 0]}},
        "3": {"class_type": "FrameInterpolationModelLoader", "inputs": {"model_name": MOTION_INTERP_MODEL}},
        "4": {"class_type": "FrameInterpolate",
              "inputs": {"interp_model": ["3", 0], "images": ["2", 0], "multiplier": int(mult)}},
        "5": {"class_type": "CreateVideo",
              "inputs": {"images": ["4", 0], "fps": float(target_fps), "audio": ["2", 1]}},
        "6": {"class_type": "SaveVideo",
              "inputs": {"video": ["5", 0], "filename_prefix": prefix, "format": "auto"}},
    }
    pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
    rec = _poll_history(client_id, pid, timeout=LIPSYNC_TIMEOUT)
    outs = _download_outputs(rec, prefix) or _download_outputs(rec, "weaveora")
    if not outs:
        raise ComfyError("对口型后插帧：无输出视频（prefix=%s）" % prefix)
    return outs[0]["bytes"]


def _resample_video(vbytes, scale, tag="weaveora_rs"):
    """纯 ffmpeg 重采样放大/缩小（lanczos），保留音轨；不占显存、秒级完成。

    为什么需要这个分支：AI 超分（Real-ESRGAN）会把 AI 视频自带的颗粒"抹平" ⇒ 实测嘴部变糊斑
    （见 docs/lipsync-setup.md §9.5）；传统 lanczos 重采样保留锐利边缘，给 LatentSync 的是一张
    "更大但更硬"的脸。两种方式都保留原音轨与帧率。
    """
    import subprocess, tempfile, os as _os
    d = tempfile.mkdtemp(prefix="wv_rs_")
    try:
        fin = _os.path.join(d, "in.mp4")
        fout = _os.path.join(d, "out.mp4")
        with open(fin, "wb") as fh:
            fh.write(vbytes)
        meta = _probe_video_meta(vbytes)
        w, h = int(meta[0] or 0), int(meta[1] or 0)
        if w <= 0 or h <= 0:
            raise ComfyError("重采样放大：拿不到源分辨率")
        tw, th = int(w * scale) // 2 * 2, int(h * scale) // 2 * 2
        cmd = ["ffmpeg", "-v", "error", "-y", "-i", fin,
               "-vf", "scale=%d:%d:flags=lanczos" % (tw, th),
               "-c:v", "libx264", "-crf", "16", "-preset", "veryfast", "-pix_fmt", "yuv420p",
               "-c:a", "copy", fout]
        subprocess.run(cmd, check=True, timeout=900)
        with open(fout, "rb") as fh:
            return fh.read()
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _upscale_video_on_box(client_id, vbytes, scale):
    """对口型**之前**把底片放大 `scale` 倍（A 方案：给 LatentSync 更大的脸）。

    为什么需要（2026-09-22 用户实测「嘴部都是马赛克」）：motion 阶段出片是 **480p**
    （832×464，`video_params.resolution=480p`），本镜说话人只有 **55–84px** 的脸；
    而 LatentSync 已经是官方最高的 **512×512** 配置（把脸对齐放大到 420×560）⇒ 嘴部几乎没有
    真实像素支撑，只能糊出一块。放大 2× 后脸 → 110–168px，模型输入细节翻倍；
    副产品：交付分辨率对齐定妆照（1664×928）。

    图：LoadVideo → GetVideoComponents → UpscaleModelLoader → ImageUpscaleWithModel
        → ImageScaleBy(1/scale) → CreateVideo(fps=源片, audio=原音轨) → SaveVideo
    ★ GetVideoComponents 的输出序号是 **(images, audio, fps)** ⇒ 音频是 **1**，不是 2！
      （原来插帧那条传的 ["2",2] 把 FLOAT 接到 AUDIO，会被 ComfyUI 判 prompt_outputs_failed_validation）
    ★ 权重：`realesr-general-x4v3.pth`（官方 Real-ESRGAN 发布物，BSD-3，4.9MB，x4 后缩回一半）。
    实测（4090/47G，160 帧 832×464）：**72 秒**，无 OOM。
    """
    if LIPSYNC_UPSCALE_MODE == "lanczos":
        return _resample_video(vbytes, scale, "weaveora_preup")
    import uuid as _uuid
    vname = _upload_any(vbytes, "weaveora_preup_%s_in.mp4" % _uuid.uuid4().hex[:8], "video/mp4")
    if not vname:
        raise ComfyError("对口型前放大：上传视频失败")
    prefix = "weaveora_preup_" + _uuid.uuid4().hex[:6]
    keep = 1.0 / float(scale)
    graph = {
        "1": {"class_type": "LoadVideo", "inputs": {"file": vname}},
        "2": {"class_type": "GetVideoComponents", "inputs": {"video": ["1", 0]}},
        "3": {"class_type": "UpscaleModelLoader", "inputs": {"model_name": LIPSYNC_PRE_UPSCALE_MODEL}},
        "4": {"class_type": "ImageUpscaleWithModel",
              "inputs": {"upscale_model": ["3", 0], "image": ["2", 0]}},
        "5": {"class_type": "ImageScaleBy",
              "inputs": {"image": ["4", 0], "upscale_method": "lanczos", "scale_by": keep}},
        "6": {"class_type": "CreateVideo",
              "inputs": {"images": ["5", 0], "fps": ["2", 2], "audio": ["2", 1]}},
        "7": {"class_type": "SaveVideo",
              "inputs": {"video": ["6", 0], "filename_prefix": prefix, "format": "auto"}},
    }
    pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
    rec = _poll_history(client_id, pid, timeout=LIPSYNC_TIMEOUT)
    outs = _download_outputs(rec, prefix) or _download_outputs(rec, "weaveora")
    if not outs:
        raise ComfyError("对口型前放大：无输出视频（prefix=%s）" % prefix)
    return outs[0]["bytes"]


def generate_lipsync(client_id, payload, progress_fn=None):
    """对口型（lipsync 任务）：画面 + 配音 → 嘴型对齐的视频。返回 [{bytes, mime}]。

    两种模式：
      · **单人镜**：整镜跑一次，并把脸锁在该说话人上（防止逐帧取最大脸中途换人）；
      · **多人镜（按段驱动）**：按方案的台词时间窗分段，每段只驱动**该段说话人**的脸，
        再把处理后的帧按原时间轴拼回去 —— LatentSync 每帧只能驱动一张脸，不分段就必然配错人。
    """
    import uuid as _uuid
    import tempfile
    if not LIPSYNC_WORKFLOW or not os.path.exists(LIPSYNC_WORKFLOW):
        raise ComfyError("未配置对口型工作流：见 docs/lipsync-setup.md（WEAVEORA_LIPSYNC_WORKFLOW）")
    # 先确认 GPU 机上的节点补丁版本/能力（缺就直接失败，不静默降级成「最大脸」）
    _require_node_features()
    if os.environ.get("WEAVEORA_LIPSYNC_DEBUG_BOX", "") == "1":
        print("[comfy] 已开启对口型调试画框（产物上会标出锁定的脸与是否驱动）", flush=True)
    vkey = (payload.get("videoKey") or "").strip()
    vkeys = [k for k in (payload.get("voiceKeys") or []) if k]
    if not vkey or not vkeys:
        raise ComfyError("对口型缺少输入：videoKey/voiceKeys")

    if progress_fn:
        progress_fn(15, "upload")
    # fetch_reference_bytes 返回 (bytes, content_type)
    vdata, vctype = fetch_reference_bytes(vkey)
    adata = _concat_voice(vkeys)          # 完整配音（最终音轨用它，音画同一时间轴）
    still_mode = (bool(payload.get("videoIsStill")) or bool(payload.get("isStill"))
                  or str(vctype or "").startswith("image"))
    # ★ 「片段首帧」底片（2026-09-15）：底片传的是**片段**，但只用它的第一帧当静帧——
    #   既跟随该镜 motion 画面的画面/风格/人物（不再用新生成的图当底片），又只有一张静止干净的嘴。
    if payload.get("useFirstFrame") and not str(vctype or "").startswith("image"):
        # 先试「挑最干净的一帧」（第5镜这种整段大张嘴的镜必需）；拿不到指标再回退首帧
        png = _best_frame_png(vdata) if payload.get("useBestFrame") else _first_frame_png(vdata)
        if png:
            vdata = png
            still_mode = True
            print("[comfy] 底片＝片段取帧：已从片子里取出 1 帧当静帧（%d bytes）" % len(png), flush=True)
        else:
            print("[comfy] WARN 片段取帧失败，改用整段片段当底片", flush=True)
    if still_mode:
        vdata = _still_to_video(vdata, adata, payload.get("duration_sec"))
        print("[comfy] lipsync 底片为静帧 → 已转成与配音等长的 mp4（等比缩放，不做 pad/裁切）", flush=True)
    elif LIPSYNC_PRE_UPSCALE >= 2:
        # ★ A 方案（2026-09-22）：motion 出片是 480p ⇒ 说话人脸只有 55–84px，LatentSync 贴回去就是马赛克。
        #   先把底片放大再对口型（静帧底片本来就是 1664×928，不需要）。失败不影响主流程（退回原片）。
        try:
            import time as _t
            _t0 = _t.time()
            _before = len(vdata)
            vdata = _upscale_video_on_box(client_id, vdata, LIPSYNC_PRE_UPSCALE)
            print("[comfy] 对口型前放大 ×%d：%.2f MB → %.2f MB，耗时 %.0fs（底片分辨率提高后脸像素翻倍）"
                  % (LIPSYNC_PRE_UPSCALE, _before / 1048576.0, len(vdata) / 1048576.0, _t.time() - _t0), flush=True)
        except Exception as _e:
            print("[comfy] WARN 对口型前放大失败，改用原底片：%s" % _e, flush=True)

    speakers = payload.get("speakers") if isinstance(payload.get("speakers"), dict) else {}
    segs = payload.get("segments") if isinstance(payload.get("segments"), list) else []
    speaker_count = int(payload.get("speakerCount") or len(speakers) or 0)
    shot_no = payload.get("shot_no") or "?"

    # 每个说话人的人脸特征（定妆照）—— 锁人用；拿不到就退回「最大脸」
    embs = {}
    for name, pkey in speakers.items():
        try:
            pb, _ = fetch_reference_bytes(pkey)
            e = _embed_reference(pb)
            if e:
                embs[name] = e
                print("[comfy] 已提取说话人「%s」的人脸特征（%d 维）" % (name, len(e)), flush=True)
        except Exception as e:
            print("[comfy] 说话人「%s」特征提取失败：%s" % (name, e), flush=True)

    face_hints = payload.get("faceHints") if isinstance(payload.get("faceHints"), dict) else {}

    def _spec_path_of(name):
        """锁定规格文件：**点选位置优先**，其次定妆照人脸特征（见 face_detector 两种模式）。

        点选（faceHints）不受画风影响、完全确定；识别式在 480p/AI 古风这类素材上
        区分度会崩（实测同一人只有 0.2 上下且互相混淆），所以有坐标就优先用坐标。
        """
        spec = {}
        h = face_hints.get(name)
        if isinstance(h, dict):
            try:
                spec["point"] = [float(h["x"]), float(h["y"])]
            except (KeyError, TypeError, ValueError):
                spec.pop("point", None)
        if embs.get(name):
            spec["embedding"] = embs[name]
        if not spec:
            return ""
        # 调试画框：worker 设 WEAVEORA_LIPSYNC_DEBUG_BOX=1 时，产物上会标出「锁定的哪张脸 + 这一帧有没有驱动」
        # （cross-machine 排查用：worker 在 API 机、节点在 GPU 机，看不到节点日志）
        if os.environ.get("WEAVEORA_LIPSYNC_DEBUG_BOX", "") == "1":
            spec["debugBox"] = True
        try:
            # ★ 返回**内联 JSON**（不是文件路径）：worker 与 ComfyUI 可能不在同一台机
            # （worker 在 API 服务器、ComfyUI 在 GPU 服务器），文件路径在节点侧根本不存在。
            # 节点/inference.py 已支持「以 { 开头 = 直接当 JSON 解析」。
            print("[comfy] 说话人「%s」锁定规格：point=%s embedding=%s"
                  % (name, spec.get("point") and [round(v, 3) for v in spec["point"]],
                     bool(spec.get("embedding"))), flush=True)
            return json.dumps(spec, separators=(",", ":"))
        except Exception as ex:
            print("[comfy] 锁定规格序列化失败（退回最大脸）: %s" % ex, flush=True)
            return ""

    # 先预检：画面里有人脸；且（有特征时）说话人的脸真在画面里；以及「底片体检」（B）
    # ★ 但**有「点选人脸」提示时不做识别式预检**：识别在风格化/低分辨率素材上不可信
    #   （实测「袭人」定妆照与「警幻」相似度 0.71＝同一张脸，画面里最高相似度仅 0.21
    #   < 阈值 0.28 → 会把本来能跑的任务直接判失败）。用户点的位置本身就是权威信号，
    #   点选没点到脸的情况交给管线的「就近选脸 + 沿用上一帧」兜底。
    # 注：B 的底片体检（脸太小/嘴大张）**不看这个**——它用的是全部脸的最坏值，与锁谁无关。
    _first = next(iter(embs)) if len(embs) == 1 else ""
    _emb_pre = None if (_first and face_hints.get(_first)) else embs.get(_first)
    _force = bool(payload.get("lipsyncForce")) or LIPSYNC_FORCE
    if not _face_precheck(vdata, where="第%s镜" % shot_no,
                          target_emb=_emb_pre, speaker=_first, force=_force):
        raise ComfyError(_face_reason["msg"])
    _warn = _face_reason.get("warn") or ""
    if _warn:
        print("[comfy] 第%s镜底片体检提醒：%s" % (shot_no, _warn), flush=True)
        if progress_fn:
            progress_fn(18, _warn)
    # C：方案侧标了「极端表情（惊恐/喊叫）」的镜头 → 降低嘴部驱动强度（1.5 → 0.8）
    _lips_expr = None
    if bool(payload.get("expressionRisk")):
        _lips_expr = LIPSYNC_EXPRESSION_RISK
        print("[comfy] 第%s镜标记为极端表情（张口/喊叫）→ lips_expression=%.2f"
              % (shot_no, _lips_expr), flush=True)

    # ★ 2026-09-24：这里原是 `_free_comfy_models()`（POST /free 卸 ComfyUI 缓存）—— 已换掉，原因：
    #   lipsync 的图里没有 UNETLoader/CLIPLoader ⇒ 跨家族守卫认不出它 ⇒ 以前只能靠 /free，
    #   而 /free 是把上一套（刚出的图 = FLUX.2 33 GB）**从显存搬进内存**、不是释放；
    #   实测同一机制在 motion 上 13 秒后就 OOM（anon-rss 47.8 GB）杀掉整栈。
    #   现在改成：上一套若是大模型家族，就先重启 ComfyUI（真正释放），连续 lip 任务不重复重启。
    _reload_before_familyless_task("lipsync 需要整套显存，且与上一套（出图/出片）家族不同")
    _last_min = [-1]

    def _tick(elapsed):
        m = int(elapsed // 60)
        if m == _last_min[0]:
            return
        _last_min[0] = m
        if progress_fn and m > 0:
            progress_fn(40, "lipsync 已运行 %d 分钟" % m)

    def _wav_bytes_seconds(b):
        """一段音频的秒数（优先 wave；拿不到返回 None）。"""
        try:
            import wave as _w
            import io as _io2
            with _w.open(_io2.BytesIO(b), "rb") as w:
                return w.getnframes() / float(w.getframerate() or 1)
        except Exception:
            return None

    tmp = tempfile.mkdtemp(prefix="weaveora_splice_")
    try:
        if speaker_count <= 1 or not segs:
            # ---------- 单人镜：整镜一次 ----------
            _who = _first or (next(iter(speakers)) if speakers else "")
            ep = _spec_path_of(_who)
            print("[comfy] 第%s镜单人模式：说话人=%s，整镜一次" % (shot_no, _who or "?"), flush=True)
            # 预警：LatentSync 的产出帧数由音频决定，音频比画面长时它会「正放+倒放」循环视频凑帧
            # （loop_video）→ 时间轴会异常。本机无法凭空补画面，只能提醒上游保证画面 ≥ 配音。
            _vms = (_probe_video_meta(vdata)[2] or 0) / 1000.0
            _asec = _wav_bytes_seconds(adata) or 0
            if _asec > _vms + 0.35:
                print("[comfy] ⚠ 第%s镜配音 %.2fs 明显长于画面 %.2fs —— LatentSync 会循环凑帧，"
                      "可能出现时间轴异常；建议把该镜画面时长做到 ≥ 配音" % (shot_no, _asec, _vms), flush=True)
            if progress_fn:
                progress_fn(40, "lipsync")
            out = _run_lipsync_graph(client_id, vdata, adata, ep, on_tick=_tick, lips_expression=_lips_expr)
        else:
            # ---------- 多人镜：按段驱动 ----------
            fps = _source_fps(vdata, tmp)
            total = _frame_count(vdata, tmp, "cnt")
            print("[comfy] 第%s镜多人模式：%d 人说话 / %d 段，源片 %d 帧 @%.2ffps"
                  % (shot_no, speaker_count, len(segs), total, fps), flush=True)
            results = []
            # ★ 各段的帧范围按**配音的真实时间轴**平铺，而不是方案里的 at_sec/end_sec：
            #   平台混音时是把各段配音**首尾相接**拼成一条音轨的（行间没有空隙），
            #   而方案的 at_sec/end_sec 只是大致窗口（实测 第1镜 宝玉窗 0–3.2s、
            #   实际配音 3.3s → 按窗口切 96 帧、音频要 99 帧 → 触发 LatentSync 的
            #   loop_video「正放+倒放」凑帧 → 时间轴错乱）。
            #   所以用「累加配音时长」定位每段的起点，并给 +4 帧余量保证「视频帧 ≥ 音频需求」。
            #
            # ★ 2026-09-23（P8）**收掉段间 4 帧重叠**：
            #   旧写法 `a = round(t_cum*fps)`、`b = a + round(dur*fps) + 4` ⇒ 上一段的尾巴
            #   （含那 4 帧余量）与下一段的头**重叠**：同几帧被两段各推理一次，写回时后
            #   一段再淡入盖在前一段上（实测日志「0-50帧」/「46-108帧」⇒ 46–50 重叠）。
            #   新写法把两件事**分开**：
            #     · 归属窗口 own_b —— 段与段**首尾相接、不重叠**；起点按帧数累加（`a += own_len`）
            #       而不是每次四舍五入时间 → 顺带消掉 ±1 帧的累计漂移；
            #     · 推理窗口 b —— 仍在归属窗口上多切 4 帧（`own_len + 4`）喂给 LatentSync，
            #       保证「视频帧 ≥ 音频需求」（否则会触发节点的 loop_video 正放+倒放凑帧）。
            #   写回（`_splice`）只写 [a, own_b)，那 4 帧余量只用于推理、**不再写回** ⇒ 无二次贴回。
            plan = []
            t_cum = 0.0
            _durs = []          # 每段配音时长（先收齐再统一切窗口，便于单测 _lipsync_seg_windows）
            _rows = []
            for i, s in enumerate(segs):
                who = (s.get("subject") or "").strip()
                vk = (s.get("voiceKey") or "").strip()
                seg_audio = None
                if vk:
                    try:
                        seg_audio, _ = fetch_reference_bytes(vk)
                    except Exception as e:
                        print("[comfy] 第%s段配音获取失败，退回整轨: %s" % (i + 1, e), flush=True)
                dur = _wav_bytes_seconds(seg_audio) if seg_audio else None
                if dur is None:
                    # 拿不到音频时长（极少数）：退回方案窗口
                    seg_audio = seg_audio or adata
                    try:
                        dur = max(0.1, (float(s.get("endMs", 0)) - float(s.get("startMs", 0))) / 1000.0)
                    except (TypeError, ValueError):
                        dur = 0.1
                _rows.append((i, s, who, seg_audio, dur))
                _durs.append(dur)
                t_cum += dur
            for (i, s, who, seg_audio, dur), (a, b, own_b) in zip(_rows, _lipsync_seg_windows(_durs, fps, total)):
                plan.append((i, s, who, seg_audio, a, b, own_b, dur))
            if plan:
                print("[comfy] 第%s镜各段（按配音时间轴）：%s"
                      % (shot_no, " / ".join("%s 归属%d-%d帧(%.2fs) 推理%d-%d帧"
                                        % (q[2] or "?", q[4], q[6], q[7], q[4], q[5])
                                        for q in plan)),
                      flush=True)
                _ov = [(plan[j][6], plan[j + 1][4]) for j in range(len(plan) - 1)
                       if plan[j + 1][4] < min(plan[j][5], plan[j][6])]
                print("[comfy] 段间重叠：%s（归属窗口首尾相接；+4 帧只用于推理、不写回）"
                      % ("无" if not _ov else "仍存在 %s" % _ov), flush=True)
            for (i, s, who, seg_audio, a, b, own_b, dur) in plan:
                if b <= a:
                    continue
                seg_video = _cut_frames(vdata, a, b, tmp, "s%d" % i)
                ep = _spec_path_of(who)
                # 该段先确认「这位说话人的脸真在这一段里」—— 比跑到一半失败便宜得多
                # （多人镜里很常见，比如某段是画外音、或某角色只在这一段背对着镜头）
                # 注：走了点选（faceHints）就不做识别式预检 —— 用户点的位置本身就是权威信号，
                # 而识别在风格化素材上不可信；那种情况交给管线的“就近选脸 + 沿用上一帧”。
                if embs.get(who) and not face_hints.get(who):
                    _pr = _face_probe(seg_video, embs[who])
                    if _pr and _pr[1] > 0 and (_pr[0] == 0 or _pr[2] is None or _pr[2] < TARGET_MIN_SIM):
                        raise ComfyError(
                            "第%d段（%s %.1f–%.1fs）里找不到该说话人的脸（检出 %d/%d 帧，"
                            "最像的相似度 %.2f，阈值 %.2f）。常见原因：这段是画外音；或「%s」的"
                            "定妆照与画面差异过大、与其它角色过于相似。\n"
                            "（多人镜按段驱动：每段只驱动该段说话人的脸，找不到就无法对口型）"
                            % (i + 1, who or "?", a / fps, b / fps, _pr[0], _pr[1], (_pr[2] or 0),
                               TARGET_MIN_SIM, who or "?"))
                if progress_fn:
                    progress_fn(40, "lipsync 第%d/%d段（%s）" % (i + 1, len(segs), who or "?"))
                seg_mp4 = _run_lipsync_graph(client_id, seg_video, seg_audio, ep, on_tick=_tick,
                                             lips_expression=_lips_expr)
                pf = _decode_frames(seg_mp4, tmp, "seg%d" % i)
                # ★ P8：写回的区间是**归属窗口** [a, own_b)，不是推理窗口 [a, b)
                #   —— 那 4 帧余量只用于「让 LatentSync 有足够帧驱动音频」，不再二次贴回。
                results.append((a, own_b, pf))
                cursor = own_b
                print("[comfy] 第%s段完成：%s 帧 %d-%d（归属 %d 帧；推理窗口 %d-%d 共 %d 帧，产出 %d 帧）"
                      % (i + 1, who or "?", a, own_b, own_b - a, a, b, b - a, len(pf)),
                      flush=True)
            if not results:
                raise ComfyError("按段驱动失败：没有可处理的有效段（检查台词的 at_sec/end_sec）")
            if progress_fn:
                progress_fn(75, "lipsync 拼接画面")
            merged = _splice(vdata, results, fps, tmp)
            out = _mux_audio(merged, adata, tmp)
            print("[comfy] 第%s镜按段驱动完成：%d 段已按原时间轴拼回并接回完整音轨" % (shot_no, len(results)),
                  flush=True)
    finally:
        # 锁定规格已内联进工作流（不再有临时文件），只需清临时目录
        import shutil as _sh
        _sh.rmtree(tmp, ignore_errors=True)

    if progress_fn:
        progress_fn(100, "done")
    # ★ 2026-09-22 问题 5：口型产物上补做 RIFE 插帧（只在开关打开时）。
    #   并把三个帧率写进日志与资产 notes —— 排查「口型乱码/时长不对」时这三个值必须是显式的：
    #   源片 fps（对口型吃进去的）/ 生成节点 fps（LatentSyncNode 实际用的）/ 交付 fps（最终成片）。
    #   注意：多人分支在上面已经把 tmp 目录清了 → 这里用**新建的**临时目录探针（不能复用 tmp）
    _src_fps = 0.0
    try:
        _src_fps = float(_source_fps(out, tempfile.mkdtemp(prefix="wv_lipfps_"), "lip_out") or 0.0)
    except Exception:
        _src_fps = 0.0
    _want_fps = int(payload.get("fps") or 0) or int(_src_fps or 0)
    _mult = 0
    if LIPSYNC_INTERP_AFTER and _src_fps > 0 and _want_fps > _src_fps:
        try:
            _mult, _n2 = _motion_interp_plan(_want_fps, _src_fps)
        except Exception:
            _mult = 0
        if _mult >= 2:
            try:
                _b4 = len(out)
                out = _interp_video_on_box(client_id, out, _mult, _want_fps)
                print("[comfy] 对口型后插帧：RIFE ×%d → %dfps（%d→%d bytes）"
                      % (_mult, _want_fps, _b4, len(out)), flush=True)
            except Exception as e:
                print("[comfy] WARN 对口型后插帧失败，按 %.2ffps 交付：%s" % (_src_fps, e), flush=True)
                _mult = 0
    _fps_note = "帧率：源片=%.2f｜LatentSync=%s｜交付=%s" % (
        _src_fps or 0,
        (_src_fps if LIPSYNC_FPS <= 0 else float(LIPSYNC_FPS)) or 0,
        ("%.2f（RIFE ×%d）" % (_want_fps, _mult)) if _mult >= 2 else ("%.2f（未插帧）" % (_src_fps or 0)))
    print("[comfy] 对口型 %s" % _fps_note, flush=True)
    # ★ 变体 2（2026-09-22 晚）：对口型后**统一放大** —— 整帧同一套纹理，
    #   避免「只有嘴部贴回区是软输出、周围是 AI 锐化」⇒ 用户看到的“马赛克盖住嘴”。
    if LIPSYNC_POST_UPSCALE >= 2 and not still_mode:
        try:
            import time as _t
            _t0 = _t.time()
            _b4 = len(out)
            out = _upscale_video_on_box(client_id, out, LIPSYNC_POST_UPSCALE)
            print("[comfy] 对口型后统一放大 ×%d：%.2f MB → %.2f MB，耗时 %.0fs（整帧同一纹理）"
                  % (LIPSYNC_POST_UPSCALE, _b4 / 1048576.0, len(out) / 1048576.0, _t.time() - _t0), flush=True)
        except Exception as _e:
            print("[comfy] WARN 对口型后放大失败，按原分辨率交付：%s" % _e, flush=True)
    w, h, dur = _probe_video_meta(out)
    return [{"bytes": out, "mime": "video/mp4", "width": w, "height": h, "duration_ms": dur,
             "notes": _fps_note}]

def _concat_voice(voice_keys):
    """把多段配音按顺序拼成一个 wav（用本机 ffmpeg；失败时退回第一段）。"""
    import subprocess, tempfile
    blobs = []
    for k in voice_keys:
        try:
            # fetch_reference_bytes 返回 (bytes, ctype)
            b, _ct = fetch_reference_bytes(k)
            blobs.append(b)
        except Exception:
            continue
    if not blobs:
        raise ComfyError("配音素材读取失败")
    if len(blobs) == 1:
        return blobs[0]
    tmp = tempfile.mkdtemp(prefix="weaveora_voice_")
    paths = []
    for i, b in enumerate(blobs):
        fp = os.path.join(tmp, "v%d.bin" % i)
        with open(fp, "wb") as fh:
            fh.write(b)
        paths.append(fp)
    out = os.path.join(tmp, "out.wav")
    cmd = [_ffmpeg_exe(), "-y"]
    for fp in paths:
        cmd += ["-i", fp]
    cmd += ["-filter_complex", "".join("[%d:a]" % i for i in range(len(paths)))
            + "concat=n=%d:v=0:a=1[out]" % len(paths), "-map", "[out]", "-ar", "16000", out]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
        with open(out, "rb") as fh:
            return fh.read()
    except Exception:
        return blobs[0]


def _ffmpeg_exe():
    """本机 ffmpeg 路径。

    优先级：WEAVEORA_FFMPEG 环境变量 > imageio_ffmpeg 自带的 > PATH 里的 ffmpeg。
    加环境变量覆盖是因为 imageio-ffmpeg 在 Python 3.6 上只能装到 0.4.9，其自带
    ffmpeg 仅 4.2.2，不支持 `-fps_mode`（需 >= 4.3）。
    """
    p = os.environ.get("WEAVEORA_FFMPEG", "").strip()
    if p and os.path.exists(p):
        return p
    try:
        import imageio_ffmpeg as _iif
        return _iif.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def _wav_seconds(path):
    try:
        import wave
        with wave.open(path, "rb") as w:
            return w.getnframes() / float(w.getframerate() or 1)
    except Exception:
        return None


def _sample_frames_png(video_bytes, max_long=1280, max_frames=12, fps=2.0):
    """从视频里均匀抽帧，返回 [PNG bytes]（失败返回 []）。"""
    import shutil as _shutil
    import subprocess as _sp
    import tempfile
    ff = os.environ.get("WEAVEORA_FFMPEG", "ffmpeg")
    if not (_shutil.which(ff) or os.path.exists(ff)):
        return []
    d = tempfile.mkdtemp(prefix="wf_frames_")
    try:
        vin = os.path.join(d, "in.bin")
        with open(vin, "wb") as fh:
            fh.write(video_bytes)
        pat = os.path.join(d, "f_%03d.png")
        _sp.run([ff, "-v", "error", "-y", "-i", vin, "-vf",
                 "fps=%.2f,scale='min(%d,iw)':-2" % (float(fps), int(max_long)),
                 "-frames:v", str(int(max_frames)), pat],
                check=True, timeout=180, stdout=_sp.DEVNULL, stderr=_sp.DEVNULL)
        return [open(os.path.join(d, f), "rb").read()
                for f in sorted(os.listdir(d)) if f.startswith("f_") and f.endswith(".png")]
    except Exception as e:
        print("[comfy] 抽帧失败：%s" % e, flush=True)
        return []
    finally:
        _shutil.rmtree(d, ignore_errors=True)


def _best_frame_png(video_bytes, max_long=1280):
    """从片段里挑「最适合当对口型底片」的一帧：**脸够大 + 嘴张开度最小**。

    为什么需要（2026-09-15 用户反馈）：用户要求对口型要参考该镜的 motion 画面，
    但片段往往整段都在张嘴（第5镜实测 mouth_open 1.23–1.25）—— 拿首帧当底片会被 B 保护拒（阀 1.05）；
    用整段当底片又会“先把嘴合上再重开”把嘴部区改坏。
    均匀抽 N 帧（默认 2fps、最多 12 帧）逐帧量 mouth_open / face_px，挑最干净的一帧 →
    画面/风格/人物依旧来自片段，但底片只有一张静止且嘴型干净的癱。
    取不到指标时返回 None（由调用方回退到首帧）。
    """
    frames = _sample_frames_png(video_bytes, max_long=max_long)
    if not frames:
        return None
    best = None      # (score, png)  score 越小越好
    stats = []
    for i, png in enumerate(frames):
        mo = px = None
        try:
            body = {"media_b64": base64.b64encode(png).decode("ascii"), "suffix": ".png"}
            _st, resp = _post_json(FACE_URL + "/face/probe", body, timeout=120)
            if resp.get("mouth_open") is not None:
                mo = float(resp["mouth_open"])
            if resp.get("face_px") is not None:
                px = float(resp["face_px"])
            elif resp.get("hits") in (0, "0"):
                mo = None      # 没人脸
        except Exception as e:
            print("[comfy] 逐帧人脸探测失败（第%d帧）：%s" % (i, e), flush=True)
            return None
        stats.append((i, mo, px))
        if mo is None:
            continue
        # 打分：脸太小直接排除（优先）；其余取 mouth_open 最小
        if px is not None and px < FACE_MIN_PX:
            continue
        score = mo
        if best is None or score < best[0]:
            best = (score, png, i)
    if best is None:
        # 没有同时满足“有脸+脸够大”的帧 → 退一步：取 mouth_open 最小的帧（哪怕脸小）
        cand = [(mo, png, i) for (i, mo, px), png in zip(stats, frames) if mo is not None]
        if cand:
            cand.sort(key=lambda x: x[0])
            best = cand[0]
    if best is None:
        print("[comfy] 片段里量不到人脸/嘴型，回退首帧", flush=True)
        return None
    print("[comfy] 片段抽帧 %d 张，选中第 %d 帧当底片（mouth_open=%.3f，阈值 %.2f）"
          % (len(frames), best[2], best[0], MOUTH_MAX), flush=True)
    return best[1]


def _first_frame_png(video_bytes, max_long=1280):
    """从视频字节里抽第 1 帧，返回 PNG 字节（失败返回 None）。

    为什么需要：用户要求对口型要**参考该镜 motion 的画面**（而不是拿新生成的关键帧当底片），
    但直接用整段片段当底片又会碰上“片段里嘴一直在动/大张 → 嘴部区大幅形变”的老问题。
    取首帧能两头兼顾：画面/风格/人物来自片段，嘴部状态却只有一种。
    人 2026-09-15。
    """
    import shutil as _shutil
    import subprocess as _sp
    import tempfile
    ff = os.environ.get("WEAVEORA_FFMPEG", "ffmpeg")
    if not (_shutil.which(ff) or os.path.exists(ff)):
        return None
    d = tempfile.mkdtemp(prefix="wf_first_frame_")
    vin = os.path.join(d, "in.bin")
    vout = os.path.join(d, "f1.png")
    try:
        with open(vin, "wb") as fh:
            fh.write(video_bytes)
        cmd = [ff, "-v", "error", "-y", "-i", vin, "-frames:v", "1",
               "-vf", "scale='min(%d,iw)':-2" % int(max_long), vout]
        _sp.run(cmd, check=True, timeout=120, stdout=_sp.DEVNULL, stderr=_sp.DEVNULL)
        with open(vout, "rb") as fh:
            return fh.read()
    except Exception as e:
        print("[comfy] 首帧抽取失败：%s" % e, flush=True)
        return None
    finally:
        try:
            _shutil.rmtree(d, ignore_errors=True)
        except Exception:
            pass


def _still_to_video(img_bytes, audio_bytes, duration_sec=None, fps=25, max_long=1280):
    """关键帧静帧 + 配音 → mp4（口型工作流需要视频轨）。时长以实际配音为准。

    为什么必须做：LatentSync 工作流是 LoadVideo → GetVideoComponents，
    直接把 png 当 mp4 传进去 LoadVideo 会解码失败。

    为什么**不再 pad 成 512x512**（2026-09-14 实测）：原来是 scale=512:512 + pad=512:512，
    于是 2560x1440 的 16:9 静帧被压成**带黑边的方片**，LatentSync 产物也就成了 512x512 方视频
    —— 成片画幅被破坏，而且平白把脸缩小（黑边还占了一半像素）。现在只做**等比缩放**：
    长边 ≤ max_long（默认 1280，与实测能跑通的片段底片 1280x720 同一量级），不 pad 不裁。
    可用 WEAVEORA_LIPSYNC_STILL_MAX 覆盖。
    """
    import subprocess, tempfile
    if not img_bytes:
        raise ComfyError("静帧画面为空，无法生成对口型输入视频")
    try:
        max_long = int(os.environ.get("WEAVEORA_LIPSYNC_STILL_MAX") or max_long)
    except Exception:
        pass
    d = tempfile.mkdtemp(prefix="weaveora_still_")
    ip = os.path.join(d, "in.png")
    ap = os.path.join(d, "a.wav")
    op = os.path.join(d, "out.mp4")
    with open(ip, "wb") as fh:
        fh.write(img_bytes)
    with open(ap, "wb") as fh:
        fh.write(audio_bytes or b"")
    dur = _wav_seconds(ap) or float(duration_sec or 0) or 3.0
    vf = ("scale='if(gt(iw,ih),min(%d,iw),-2)':'if(gt(iw,ih),-2,min(%d,ih))'"
          % (max_long, max_long))
    r = subprocess.run(
        [_ffmpeg_exe(), "-y", "-loglevel", "error", "-loop", "1", "-i", ip, "-t", "%.3f" % dur,
         "-r", str(fps), "-vf", vf, "-pix_fmt", "yuv420p", "-c:v", "libx264", op],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=300)
    if r.returncode != 0 or not os.path.exists(op):
        raise ComfyError("静帧转视频失败: %s" % (r.stderr or "")[-300:])
    _w, _h, _dur = _probe_video_meta(open(op, "rb").read())
    print("[comfy] 静帧 → 视频：%sx%s（长边封顶 %d，等比不 pad）/ %.2fs"
          % (_w, _h, max_long, dur), flush=True)
    with open(op, "rb") as fh:
        return fh.read()
