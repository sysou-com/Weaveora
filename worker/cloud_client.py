#!/usr/bin/env python3
"""Weaveora 云引擎适配器（引擎中立；先支持 Replicate，便于无 GPU 的前期测试）。

环境变量：
  WEAVEORA_CLOUD_PROVIDER  replicate（默认）
  WEAVEORA_REPLICATE_TOKEN  Replicate API token
  WEAVEORA_REPLICATE_MODEL  模型 owner/name:version（默认 stability-ai/sdxl 固定版本）
  WEAVEORA_CLOUD_DELAY_MS   相邻云调用间隔（默认 1500ms，避免短时多次调用被限流，
                            复现过镜像短时间多调失败的问题）
  WEAVEORA_CLOUD_RETRIES    创建重试次数（默认 4，429/5xx 指数退避）
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("WEAVEORA_REPLICATE_TOKEN", "")
MODEL = os.environ.get(
    "WEAVEORA_REPLICATE_MODEL",
    "stability-ai/sdxl:39ed52f2a78e934b3ba6e2a89f5b1c712de7dfea535525255b1aa35c5565e08b")
API = "https://api.replicate.com/v1"
DELAY_MS = int(os.environ.get("WEAVEORA_CLOUD_DELAY_MS", "1500"))
RETRIES = int(os.environ.get("WEAVEORA_CLOUD_RETRIES", "4"))

# ---- 云 API 模型默认（生产以用户引擎配置为准；测试模型需显式开启）----
# §11.6 测试口径：仅当 WEAVEORA_REPLICATE_TEST_MODELS=1（或显式 env 指定模型）时生效。
TEST_MODE = os.environ.get("WEAVEORA_REPLICATE_TEST_MODELS", "").lower() in ("1", "true", "yes", "on")
TEST_IMAGE_MODEL = "stability-ai/stable-diffusion:ac732df83cea7fff18b8472768c88ad041fa750ff7682a21affe81863cbe77e4"
TEST_VIDEO_MODEL = "prunaai/p-video"
_env_img = os.environ.get("WEAVEORA_REPLICATE_IMAGE_MODEL")
DEFAULT_IMAGE_MODEL = _env_img if _env_img is not None else (TEST_IMAGE_MODEL if TEST_MODE else "")
_env_vid = os.environ.get("WEAVEORA_REPLICATE_VIDEO_MODEL")
DEFAULT_VIDEO_MODEL = _env_vid if _env_vid is not None else (TEST_VIDEO_MODEL if TEST_MODE else "")
_env_draft = os.environ.get("WEAVEORA_REPLICATE_VIDEO_DRAFT")
VIDEO_DRAFT = (_env_draft.lower() not in ("0", "false", "no")) if _env_draft is not None else TEST_MODE
env_res = os.environ.get("WEAVEORA_REPLICATE_VIDEO_RESOLUTION")
VIDEO_RESOLUTION = env_res if env_res is not None else ("720p" if TEST_MODE else "")
# 尾帧引导参数名（payload.tailKey）：p-video 无末帧参数，留空即忽略；Wan 系可设如 last_frame_image
VIDEO_LAST_FRAME_PARAM = os.environ.get("WEAVEORA_VIDEO_LAST_FRAME_PARAM", "").strip()

TRANSIENT = {429, 500, 502, 503, 504}

# 参考图内联上限（base64 后的字符数）：超了就退回 /v1/files 上传
DATA_URI_MAX_B64 = int(os.environ.get("WEAVEORA_REF_INLINE_MAX_B64", str(6 * 1024 * 1024)))


class CloudError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _headers():
    if not TOKEN:
        raise CloudError("缺少 WEAVEORA_REPLICATE_TOKEN")
    return {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
            "Prefer": "wait"}


def _post(path, payload, timeout=300):
    req = urllib.request.Request(API + path, data=json.dumps(payload).encode(),
                                 headers=_headers(), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = e.read()[:300].decode(errors="replace")
        raise CloudError("replicate %s -> %s %s" % (path, e.code, body), status=e.code)


def _open_retry(req, timeout=120, attempts=4):
    """网络层瞬断重试（Errno 101 Network unreachable / 111 / 104 / 超时）；HTTPError 直接上抛。"""
    last = None
    for i in range(1, attempts + 1):
        try:
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError:
            raise
        except Exception as e:
            last = e
            if i < attempts:
                wait = min(3 * i, 12)
                print("[cloud] network retry %d/%d after %ss: %s" % (i, attempts, wait, e), flush=True)
                time.sleep(wait)
    raise CloudError("replicate 网络不可达（重试 %d 次后仍失败）: %s" % (attempts, last))


def _get(path, timeout=60):
    target = path if path.startswith("http") else API + path
    req = urllib.request.Request(target, headers=_headers())
    try:
        with _open_retry(req, timeout=timeout, attempts=3) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate %s -> %s %s" % (path, e.code, e.read()[:300]),
                         status=e.code)


def _create_with_retry(body):
    """创建预测：429/5xx 指数退避重试（限流友好）。"""
    for attempt in range(1, RETRIES + 1):
        try:
            return _post("/predictions", body)
        except CloudError as e:
            if e.status not in TRANSIENT or attempt == RETRIES:
                raise
            wait = min(5 * (2 ** (attempt - 1)) + 2, 40)
            print("[cloud] create retry %d/%d after %ss (%s)" % (attempt, RETRIES, wait, e.status),
                  flush=True)
            time.sleep(wait)
    raise CloudError("replicate 创建预测失败（重试耗尽）")


def _download(url):
    req = urllib.request.Request(url)
    with _open_retry(req, timeout=180, attempts=3) as r:
        return r.read()


def generate_still(payload, progress_fn=None):
    """云 txt2img；返回 [(bytes, mime, w, h, None)]。调用前按间隔限速。"""
    if DELAY_MS > 0:
        time.sleep(DELAY_MS / 1000.0)
    if progress_fn:
        progress_fn(10, "cloud_submit")
    params = payload.get("params") or {}
    version = MODEL.split(":", 1)[1] if ":" in MODEL else MODEL
    body = {
        "version": version,
        "input": {
            "prompt": payload.get("positive_prompt", ""),
            "negative_prompt": payload.get("negative_prompt", ""),
            "width": int(params.get("width") or 1024),
            "height": int(params.get("height") or 1024),
            "num_outputs": 1,
            "scheduler": "DPMSolverMultistep",
            "num_inference_steps": int(params.get("steps", 30)),
            "guidance_scale": float(params.get("cfg", 7.5)),
            "seed": int(payload.get("seed") or int(time.time() % 10 ** 9)),
        },
    }
    if progress_fn:
        progress_fn(25, "cloud_wait")
    pred = _create_with_retry(body)
    url = pred.get("urls", {}).get("get")
    if not url:
        raise CloudError("replicate 无预测查询 URL")
    deadline = time.time() + 420
    state = None
    while time.time() < deadline:
        st = _get(url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            err = str(st.get("error"))[:300]
            # 任务端瞬时失败（模型超时/排队被拒）也可整体重试一次
            if err and ("timeout" in err.lower() or "rate" in err.lower()) and RETRIES > 1:
                time.sleep(6)
                pred = _create_with_retry(body)
                url = pred.get("urls", {}).get("get")
                deadline = time.time() + 420
                continue
            raise CloudError("replicate 预测失败: %s" % err, status=500)
        time.sleep(4)
    else:
        raise CloudError("replicate 预测超时", status=504)
    outputs = st.get("output") or []
    if not outputs:
        raise CloudError("replicate 无输出", status=500)
    out0 = outputs[0] if isinstance(outputs[0], str) else outputs[0]["url"]
    data = _download(out0)
    w = int(params.get("width") or 1024)
    h = int(params.get("height") or 1024)
    return [(data, _sniff_image_mime(data), w, h, None)]


# ---------- P12：按模型 schema 填参数（修「参考图字段名猜错 → 静默忽略」） ----------


def _mapping(cfg):
    """取出 API 侧归一化好的参数映射（Java 用 Map.of("m", …) 包了一层，两种形状都兼容）。"""
    m = (cfg or {}).get("mapping") or {}
    if isinstance(m, dict) and isinstance(m.get("m"), dict):
        return m["m"]
    return m if isinstance(m, dict) else {}


def _fields(cfg):
    """schema 声明的参数：name -> {type,default,enum,...}（用于只发模型认识的字段）。"""
    out = {}
    raw = (cfg or {}).get("schemaParams")
    if not isinstance(raw, list):
        raw = ((cfg or {}).get("schema") or {}).get("params") or []
    for x in raw or []:
        if isinstance(x, dict) and x.get("name"):
            out[x["name"]] = x
    return out


def _refs_spec(mapping, model, fields=None):
    """参考图字段：优先 schema 映射；无 schema 时退回“按模型名”的保守兜底。

    实测踩坑：flux-2-klein-9b 的参考图字段是 images，早期按 'flux' 猜成 input_images，
    Replicate 对未知字段**静默忽略** → 图根本没传进去（参考图完全不生效）。
    因此：**有 schema 就以 schema 为准，没有参考图字段就宁可不发**（不凭空造字段）。
    返回 (field, is_array, max_items)；field=None 表示该模型不支持参考图。
    """
    field = (mapping.get("refs") or "").strip()
    if field:
        return field, bool(mapping.get("refsIsArray")), int(mapping.get("refsMax") or 0)
    if fields:
        return None, False, 0          # 有 schema 且没有参考图字段：不要猜
    ml = (model or "").lower()
    if "flux-2" in ml or "klein" in ml:
        return "images", True, 5
    if "nano-banana" in ml:
        return "image_input", True, 0
    if "flux" in ml or "kontext" in ml:
        return "input_images", True, 0
    return "image", False, 0


def _put_user_params(inp, mapping, cfg, fields):
    """合并用户全局参数（画质等）：只在 schema 认识该字段时下发，防手改坏调用。"""
    params = (cfg or {}).get("params") or {}
    if not isinstance(params, dict):
        return
    keep = set(fields.keys()) if fields else set()
    for k, v in params.items():
        if keep and k not in keep:
            continue
        if k in (mapping.get("refs"), mapping.get("prompt"), mapping.get("seed")):
            continue          # 系统独占字段不让全局参数覆盖
        inp[k] = v


def _model_has(fields, name):
    """该模型是否有这个参数字段（没 schema 时不限制）。"""
    return bool(name) and (not fields or name in fields)


def _ref_transport(token, storage_key):
    """把参考图变成模型能吃的 URL：**优先 data URI**，其次才走 /v1/files 上传。

    为什么要这样（2026-09 实测）：Replicate 的 `POST /v1/files` 会**持续 500**
    （同一 token 对 /v1/account 返回 200、对 /predictions 正常），而旧代码在上传失败时
    **只打一行警告就继续生成** → 出图完全没有参考图，表现就是「人物形象和参考图完全对不上」。
    data URI 走 predictions 请求体本身，不依赖那个文件接口（实测被 flux-2 正常接受）。
    """
    import base64 as _b64
    blob = _fetch_asset(storage_key)
    mime = _sniff_image_mime(blob)
    b64 = _b64.b64encode(blob).decode()
    if len(b64) <= DATA_URI_MAX_B64:
        return "data:%s;base64,%s" % (mime, b64)
    print("[cloud-image] 参考图 %d bytes（base64 %d）超过内联上限，改走 /v1/files 上传"
          % (len(blob), len(b64)), flush=True)
    return _upload_file(token, storage_key.split("/")[-1] or "ref.png", blob, mime)


def _wh_for(ar, base_w=0, base_h=0):
    """按画幅给出生成尺寸（16 的倍数）。

    **尺寸不能写死**：同一个项目里把模型从「认 aspect_ratio 的」换成「只认 width/height 的」，
    如果这里回一张自己的硬编码表（1280x720 / 720x1280…），同一项目在不同模型间就会得到不同画幅
    （实测：项目是 16:9 = 1280x704，硬编码却给 1280x720）。
    因此：调用方给了尺寸（来自**项目画幅**）就用它，只在没给时才回落。
    """
    if base_w and base_h and int(base_w) > 0 and int(base_h) > 0:
        return _round64(int(base_w)), _round64(int(base_h))
    m = {"16:9": (1280, 720), "9:16": (720, 1280), "1:1": (1024, 1024),
         "3:2": (1152, 768), "2:3": (768, 1152)}
    w, h = m.get(ar, (1024, 1024))
    return _round64(w), _round64(h)


def _fit_enum_side(fields, name, want):
    """若 schema 把尺寸写成了枚举（如 width: ["1024","1280","1920"]），挑最接近的允许值。

    切换模型时这个很关键：新模型的尺寸枚举与上一个模型不同，直接发项目尺寸会被拒。
    """
    p = (fields or {}).get(name) or {}
    vals = []
    for v in (p.get("enum") or []):
        sv = str(v)
        if sv.isdigit():
            vals.append(int(sv))
    if not vals:
        return None
    return min(vals, key=lambda x: abs(x - int(want)))


def _image_size(data):
    """从图片字节里读出真实尺寸（写回资产元数据，避免记录与产物不符）。"""
    try:
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            return (int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big"))
        if data[:2] == b"\xff\xd8":          # JPEG：扫 SOF 段
            i = 2
            n = len(data)
            while i < n - 9:
                if data[i] != 0xFF:
                    i += 1
                    continue
                m = data[i + 1]
                if m in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    h = int.from_bytes(data[i + 5:i + 7], "big")
                    w = int.from_bytes(data[i + 7:i + 9], "big")
                    return (w, h)
                if m in (0xD8, 0xD9) or 0xD0 <= m <= 0xD7:
                    i += 2
                    continue
                i += 2 + int.from_bytes(data[i + 2:i + 4], "big")
            return None
        if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            fmt = data[12:16]
            if fmt == b"VP8X":
                w = int.from_bytes(data[24:27], "little") + 1
                h = int.from_bytes(data[27:30], "little") + 1
                return (w, h)
    except Exception:
        return None
    return None


def _sniff_image_mime(data):
    """按字节头判断真实图片类型。

    模型的 output_format 默认可能是 jpg（flux-2-klein-9b 就是），早期我们把 mime 写死成
    image/png → 资产类型与实际字节不一致（下游预览/视频首帧会踩）。
    """
    if not data or len(data) < 12:
        return "image/png"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:2] == b"\xff\xd8":
        return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:3] == b"GIF":
        return "image/gif"
    return "image/png"


# ---------- 云图片（Replicate 通道：模型如 black-forest-labs/flux-2-pro） ----------

def replicate_image(payload, token, model, progress_fn=None, cfg=None):
    """Replicate 通用 txt2img（model=owner/name 或 owner/name:version）。返回 [(bytes,'image/png',w,h,None)]。

    §11.6：model 为空时用测试固定版本 stability-ai/stable-diffusion:ac732df8…；
    SD 系需 width/height 为 64 倍数，并支持 steps/cfg → num_inference_steps/guidance_scale。"""
    if not token:
        raise CloudError("图片云未配置 API Key（Replicate）")
    model = model or DEFAULT_IMAGE_MODEL
    if not model:
        raise CloudError("图片云未配置模型（Replicate）：请在引擎设置中填写模型；"
                         "调试可设 WEAVEORA_REPLICATE_TEST_MODELS=1 使用 §11.6 测试模型")
    positive = payload.get("positive_prompt", "")
    params = payload.get("params") or {}
    mp = _mapping(cfg)
    fields = _fields(cfg)
    prompt_field = (mp.get("prompt") or "prompt").strip() or "prompt"
    inp = {prompt_field: positive}
    # 参考图：多主体必须带全 + 保留“第 i 张=哪个主体”的映射；单图模型不得拿多图当 img2img（会整图串脸）
    refs = payload.get("referenceKeys") or []
    subjects = payload.get("referenceSubjects") or []
    primary = payload.get("primarySubject") or ""
    if refs:
        refs_field, refs_is_array, refs_max = _refs_spec(mp, model, fields)
        if not refs_field:
            print("[cloud-image] 警告：模型 %s 的 schema 里没有参考图字段，本镜 %d 张参考图无法使用"
                  "（人物/场景一致性会变差，建议换支持参考图的模型，如 FLUX.2 系）"
                  % (model, len(refs)), flush=True)
            refs = []
    if refs:
        picked_idx = list(range(len(refs)))
        if len(refs) > 1 and not refs_is_array:
            # 单图/img2img 系模型：只允许用“该镜主主体”对应的那张，其余不挂（避免两张脸互相带偏）
            chosen = None
            for i, subj in enumerate(subjects):
                if primary and subj == primary:
                    chosen = i
                    break
            if chosen is None:
                chosen = 0
            picked_idx = [chosen]
            print("[cloud-image] 模型 %s 的 %s 只收单图，只用主主体图 idx=%d subject=%s"
                  % (model, refs_field, chosen, (subjects[chosen] if chosen < len(subjects) else "-")), flush=True)
        if refs_max > 0 and len(picked_idx) > refs_max:
            print("[cloud-image] 模型 %s 最多 %d 张参考图，裁到前 %d 张" % (model, refs_max, refs_max), flush=True)
            picked_idx = picked_idx[:refs_max]
        urls = []
        subj_map = []
        failed = []
        for i in picked_idx:
            try:
                u = _ref_transport(token, refs[i])
                urls.append(u)
                subj = subjects[i] if i < len(subjects) else ""
                if subj:
                    subj_map.append("%d) %s" % (len(urls), subj))
            except Exception as e:
                subj = subjects[i] if i < len(subjects) else "?"
                failed.append("%s(%s)" % (subj, str(e)[:80]))
                print("[cloud-image] ref#%d 参考图准备失败: %s" % (i, e), flush=True)
        if not urls:
            # 关键：绝不能静默降级成“无参考图”出图 —— 那会直接毁掉人物一致性，
            #       而用户看不出原因（实测踩过：/v1/files 持续 500 → 出图完全不参考）
            raise CloudError("参考图全部准备失败（%d 张）：%s；本镜已中止，"
                             "请稍后重试（不会用无参考图的结果冒充）" % (len(picked_idx), "; ".join(failed)))
        if failed:
            print("[cloud-image] 警告：%d 张参考图准备失败，本镜只用剩下的 %d 张：%s"
                  % (len(failed), len(urls), "; ".join(failed)), flush=True)
        if urls:
            # 按 schema 给的字段名填（数组字段给全，标量字段给主主体那张）
            inp[refs_field] = urls if refs_is_array else urls[0]
            if len(urls) > 1 and subj_map:
                # 把“图序→主体”写进 prompt，降低串脸
                inp[prompt_field] = "Reference images in order: " + "; ".join(subj_map) + ". " + positive
            _kind = "data-uri" if str(urls[0]).startswith("data:") else "file-url"
            print("[cloud-image] refs=%d field=%s transport=%s subjects=%s model=%s"
                  % (len(urls), refs_field, _kind, subj_map, model), flush=True)
        else:
            print("[cloud-image] 警告：%d 张参考图全部不可用，本镜一致性不能保证"
                  % len(picked_idx), flush=True)
    elif fields and not any(f.get("group") == "refs" for f in fields.values()):
        print("[cloud-image] 警告：模型 %s 没有参考图字段，本镜一致性无法保证" % model, flush=True)
    ar = payload.get("aspect_ratio")
    # 尺寸/画幅/采样：有 schema 就按 schema 填；没有则走老的模型名判断
    if fields or mp:
        aspect_field = (mp.get("aspect") or "").strip()
        w_field, h_field = (mp.get("width") or "").strip(), (mp.get("height") or "").strip()
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            if _model_has(fields, aspect_field):
                inp[aspect_field] = ar
            elif _model_has(fields, w_field) and _model_has(fields, h_field):
                # 尺寸取**项目画幅**（params 里的 w/h），不写死；模型用枚举限尺寸时挑最接近的
                pw, ph = _wh_for(ar, int(params.get("width") or 0), int(params.get("height") or 0))
                inp[w_field] = _fit_enum_side(fields, w_field, pw) or pw
                inp[h_field] = _fit_enum_side(fields, h_field, ph) or ph
        elif _model_has(fields, w_field) and _model_has(fields, h_field):
            pw, ph = _wh_for("", int(params.get("width") or 1024), int(params.get("height") or 1024))
            inp[w_field] = _fit_enum_side(fields, w_field, pw) or pw
            inp[h_field] = _fit_enum_side(fields, h_field, ph) or ph
        neg = payload.get("negative_prompt") or ""
        if neg and _model_has(fields, (mp.get("negative") or "").strip()):
            inp[mp.get("negative")] = neg
        steps_field = (mp.get("steps") or "").strip()
        if _model_has(fields, steps_field):
            inp[steps_field] = max(1, min(100, int(params.get("steps") or 28)))
        cfg_field = (mp.get("cfg") or "").strip()
        if _model_has(fields, cfg_field) and isinstance(params.get("cfg"), (int, float)):
            inp[cfg_field] = float(params["cfg"])
        seed_field = (mp.get("seed") or "").strip()
        if _model_has(fields, seed_field):
            inp[seed_field] = int(payload.get("seed") or int(time.time() % 10 ** 9))
        _put_user_params(inp, mp, cfg, fields)   # 用户全局参数（画质等）
    elif (model or "").lower().startswith("stability-ai/") or "sdxl" in (model or "").lower():
        # SD 系按 width/height 出图（aspect_ratio 不生效；尺寸必须为 64 倍数）
        inp["width"] = _round64(params.get("width") or 1024)
        inp["height"] = _round64(params.get("height") or 1024)
        inp["num_outputs"] = 1
        steps = int(params.get("steps") or 30)
        inp["num_inference_steps"] = max(10, min(100, steps))
        cfgv = params.get("cfg")
        if isinstance(cfgv, (int, float)):
            inp["guidance_scale"] = float(cfgv)
        neg = payload.get("negative_prompt") or ""
        if neg:
            inp["negative_prompt"] = neg
    elif ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
        inp["aspect_ratio"] = ar
    body_in = {"input": inp}
    if ":" in model:
        body_in["version"] = model.split(":", 1)[1]
    if progress_fn:
        progress_fn(20, "cloud_submit")
    if "version" in body_in:
        req = urllib.request.Request(API + "/predictions", data=json.dumps(body_in).encode(),
                                     headers=_auth(token), method="POST")
    else:
        parts = model.split("/")
        owner = parts[0] if parts else ""
        name = parts[1] if len(parts) > 1 else ""
        if not owner or not name:
            raise CloudError("图片云模型名不完整（如 black-forest-labs/flux-2-pro）")
        req = urllib.request.Request(API + "/models/%s/%s/predictions" % (owner, name),
                                     data=json.dumps(body_in).encode(), headers=_auth(token), method="POST")
    try:
        with _open_retry(req, timeout=120) as r:
            pred = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate 图片创建失败: %s %s" % (e.code, e.read()[:300]), status=e.code)
    get_url = pred.get("urls", {}).get("get")
    if not get_url:
        raise CloudError("replicate 图片无查询 URL")
    deadline = time.time() + 420
    state = None
    while time.time() < deadline:
        st = _get(get_url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            raise CloudError("replicate 图片预测失败: %s" % str(st.get("error"))[:300])
        time.sleep(4)
    else:
        raise CloudError("replicate 图片预测超时")
    outputs = st.get("output")
    url = None
    if isinstance(outputs, str):
        url = outputs
    elif isinstance(outputs, list) and outputs:
        o = outputs[0]
        url = o if isinstance(o, str) else (o.get("url") if isinstance(o, dict) else None)
    elif isinstance(outputs, dict):
        url = outputs.get("url") or outputs.get("video") or outputs.get("output")
        if not isinstance(url, str):
            url = None
    if not url or not url.startswith("http"):
        raise CloudError("replicate 图片无有效输出 url（output=%s）" % str(outputs)[:120])
    data = _download(url)
    actual = _image_size(data) or (int(params.get("width") or 1024), int(params.get("height") or 1024))
    return [(data, _sniff_image_mime(data), actual[0], actual[1], None)]


# ---------- 云视频（Replicate 通道，每用户 token/model） ----------

_API_ENV = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
_TOKEN_ENV = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")


def _auth(token):
    if not token:
        raise CloudError("云视频未配置 Replicate Token")
    return {"Authorization": "Bearer " + token, "Content-Type": "application/json"}


def _fetch_asset(storage_key):
    """经 weaveora internal 通道取参考帧原始字节。"""
    import base64 as _b64
    q = urllib.parse.quote(_b64.urlsafe_b64encode(storage_key.encode()).decode(), safe="")
    req = urllib.request.Request("%s/internal/assets?key=%s" % (_API_ENV, q),
                                 headers={"X-Worker-Token": _TOKEN_ENV})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def _upload_file(token, filename, data, ctype="image/png"):
    boundary = "----wvfile" + str(int(time.time() * 1e6))
    head = ("--%s\r\nContent-Disposition: form-data; name=\"content\"; "
            "filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, filename, ctype)).encode()
    body = head + data + ("\r\n--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(API + "/files", data=body,
                                 headers={"Authorization": "Bearer " + token,
                                          "Content-Type": "multipart/form-data; boundary=" + boundary},
                                 method="POST")
    last = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                resp = json.loads(r.read())
            urls = resp.get("urls") or {}
            get_url = urls.get("get")
            if get_url:
                return get_url
            raise CloudError("replicate 文件上传无 get url")
        except CloudError:
            raise
        except Exception as e:
            last = e
            time.sleep(3 * attempt)
    raise CloudError("replicate 文件上传失败: %s" % last)


def _round64(v):
    try:
        n = int(v)
    except (TypeError, ValueError):
        n = 1024
    return max(64, int(round(n / 64.0)) * 64)


def _video_input(model, positive, image_url, mapping=None, fields=None):
    """图生视频输入：参考帧字段以 schema 为准（image / input_image / start_image …）。"""
    mp = mapping or {}
    prompt_field = (mp.get("prompt") or "prompt").strip() or "prompt"
    ml = (model or "").lower()
    inp = {prompt_field: positive}
    if "minimax" in ml and not mp.get("refs"):
        return inp                          # minimax/video-01 文生视频（无参考帧字段）
    ref_field = (mp.get("refs") or "").strip()
    if not ref_field:
        ref_field = "image"                 # 无 schema 时的保守兜底（多数图生视频模型如此）
    if image_url and _model_has(fields, ref_field):
        inp[ref_field] = image_url
    return inp


def generate_motion_via_replicate(payload, token, model, progress_fn=None, cfg=None):
    """云视频：参考帧上传 → 模型预测 → 轮询 → 下载 mp4。返回 [(bytes,'video/mp4',w,h,None)]。

    §11.6 测试口径（仅 WEAVEORA_REPLICATE_TEST_MODELS=1 时生效）：model 为空 → prunaai/p-video，
    强制 draft=ON、resolution ≤720p；payload.tailKey 在配置 WEAVEORA_VIDEO_LAST_FRAME_PARAM 时作为末帧上传。"""
    model = model or DEFAULT_VIDEO_MODEL or "minimax/video-01"
    if progress_fn:
        progress_fn(15, "cloud_upload")
    key = payload.get("keyframeKey")
    img_url = None
    if key:
        try:
            data = _fetch_asset(key)
            img_url = _upload_file(token, key.split("/")[-1] or "frame.png", data)
        except Exception as e:
            if "minimax" not in (model or "").lower():
                raise CloudError("参考帧上传失败: %s" % e)
    positive = payload.get("positive_prompt", "")
    mp = _mapping(cfg)
    fields = _fields(cfg)
    inp = _video_input(model, positive, img_url, mp, fields)
    ml = (model or "").lower()
    if fields:
        # P12：模型 schema 为准 —— 只填它真有的字段（不再按模型名猜）
        ar = payload.get("aspect_ratio")
        aspect_field = (mp.get("aspect") or "").strip()
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3") and _model_has(fields, aspect_field):
            inp[aspect_field] = ar
        neg = payload.get("negative_prompt") or ""
        if neg and _model_has(fields, (mp.get("negative") or "").strip()):
            inp[mp.get("negative")] = neg
        # 时长/帧数：README 式的 duration/frames/fps 只在 schema 里有才发
        dur = payload.get("duration_sec")
        if dur is not None:
            for cand in ("duration", "duration_seconds", "seconds", "length"):
                if _model_has(fields, cand):
                    try:
                        inp[cand] = max(1, min(20, int(round(float(dur)))))
                    except (TypeError, ValueError):
                        pass
                    break
        seed_field = (mp.get("seed") or "").strip()
        if _model_has(fields, seed_field):
            inp[seed_field] = int(payload.get("seed") or int(time.time() % 10 ** 9))
        _put_user_params(inp, mp, cfg, fields)   # 用户全局参数（分辨率/画质等）
    elif "p-video" in ml:
        inp["fps"] = 24
        try:
            dur = int(round(float(payload.get("duration_sec") or 5)))
        except (TypeError, ValueError):
            dur = 5
        inp["duration"] = max(1, min(20, dur))
        # 测试档硬约束 draft ON + ≤720p；生产（TEST_MODE=0）仅在显式 env 指定时注入
        if TEST_MODE:
            inp["draft"] = True
            inp["resolution"] = VIDEO_RESOLUTION or "720p"
            inp["prompt_upsample"] = False
        else:
            if VIDEO_DRAFT:
                inp["draft"] = True
            if VIDEO_RESOLUTION:
                inp["resolution"] = VIDEO_RESOLUTION
        ar = payload.get("aspect_ratio")
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            inp["aspect_ratio"] = ar
    elif "minimax" not in ml and "wan" not in ml:
        # kling 等图生视频：画幅与负面词可选注入（wan 系不支持 aspect_ratio，随参考图比例）
        ar = payload.get("aspect_ratio")
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            inp["aspect_ratio"] = ar
        neg = payload.get("negative_prompt") or ""
        if neg:
            inp["negative_prompt"] = neg
    # P2 尾帧引导（双关键帧）：按 schema/参数名可选注入
    tail = payload.get("tailKey")
    tail_param = (mp.get("lastFrame") or "").strip() or VIDEO_LAST_FRAME_PARAM
    if tail and tail_param and _model_has(fields, tail_param):
        try:
            tdata = _fetch_asset(tail)
            turl = _upload_file(token, tail.split("/")[-1] or "tail.png", tdata)
            inp[tail_param] = turl
            print("[cloud-video] last frame attached param=%s" % tail_param, flush=True)
        except Exception as e:
            print("[cloud-video] tailKey 上传失败，忽略: %s" % e, flush=True)
    elif tail:
        print("[cloud-video] 模型 %s 无末帧参数（tailKey 忽略；如需可设 WEAVEORA_VIDEO_LAST_FRAME_PARAM）"
              % model, flush=True)
    # owner/name 或 owner/name:version
    body_in = {"input": inp}
    if ":" in model:
        ver = model.split(":", 1)[1]
        body_in["version"] = ver
    if progress_fn:
        progress_fn(30, "cloud_submit")
    parts = model.split(":", 1)[0].split("/")
    owner, name = (parts[0], parts[1]) if len(parts) > 1 else ("minimax", "video-01")
    if "version" in body_in:
        req = urllib.request.Request(API + "/predictions", data=json.dumps(body_in).encode(),
                                     headers=_auth(token), method="POST")
    else:
        req = urllib.request.Request(API + "/models/%s/%s/predictions" % (owner, name),
                                     data=json.dumps(body_in).encode(), headers=_auth(token), method="POST")
    try:
        with _open_retry(req, timeout=120) as r:
            pred = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate 视频创建失败: %s %s" % (e.code, e.read()[:300]), status=e.code)
    get_url = pred.get("urls", {}).get("get")
    if not get_url:
        raise CloudError("replicate 无预测查询 URL")
    if progress_fn:
        progress_fn(40, "cloud_wait")
    deadline = time.time() + 1500
    state = None
    while time.time() < deadline:
        st = _get(get_url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            raise CloudError("replicate 视频预测失败: %s" % str(st.get("error"))[:300])
        time.sleep(5)
    else:
        raise CloudError("replicate 视频预测超时")
    outputs = st.get("output")
    print("[cloud-video] output=", repr(outputs)[:200], flush=True)
    first = None
    if isinstance(outputs, str):
        first = outputs
    elif isinstance(outputs, list) and outputs:
        o = outputs[0]
        first = o if isinstance(o, str) else (o.get("url") if isinstance(o, dict) else None)
    elif isinstance(outputs, dict):
        first = outputs.get("url") or outputs.get("video") or outputs.get("output")
    if not first or not str(first).startswith("http"):
        raise CloudError("replicate 视频无有效输出 url（%s）" % str(outputs)[:200])
    print("[cloud-video] download=", str(first)[:100], flush=True)
    data = _download(str(first))
    w = int((payload.get("params") or {}).get("width") or 720)
    h = int((payload.get("params") or {}).get("height") or 1280)
    return [(data, "video/mp4", w, h, None)]
