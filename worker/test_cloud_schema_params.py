#!/usr/bin/env python3
"""P12：验证「按模型 schema 填参数」——重点是参考图字段名。

背景（踩过的坑）：flux-2-klein-9b 的参考图字段叫 `images`，早期按模型名猜成 `input_images`，
Replicate 对未知字段是**静默忽略** → 参考图完全没生效（一致性全无），而且不报错、很难发现。

本脚本用假 Replicate 拦下创建预测的请求体，断言：
  1) 参考图发到 schema 指定的字段（images），且不是 input_images；
  2) 数组字段会带全（并遵守 maxItems）；
  3) 模型不支持的字段（negative_prompt / width / height）不会被发；
  4) 用户全局参数（画质：megapixels / output_quality）会合并进去；
  5) 无 schema 时退回兜底（flux-2 → images），仍然修对了。
"""
import json
import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("WEAVEORA_CLOUD_DELAY_MS", "0")
os.environ.setdefault("WEAVEORA_CLOUD_RETRIES", "1")

from http.server import BaseHTTPRequestHandler, HTTPServer  # noqa: E402

import cloud_client  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
PORT = 18891
CAPTURED = []


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _j(self, o):
        b = json.dumps(o).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        if self.path.endswith("/predictions"):
            CAPTURED.append(json.loads(body.decode()))
        self._j({"id": "p1", "status": "processing",
                 "urls": {"get": "http://127.0.0.1:%d/status/p1" % PORT}})

    def do_GET(self):
        if self.path.startswith("/status/"):
            self._j({"id": "p1", "status": "succeeded",
                     "output": ["http://127.0.0.1:%d/file.png" % PORT]})
        elif self.path.startswith("/file.png"):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG)))
            self.end_headers()
            self.wfile.write(PNG)
        else:
            self.send_response(404)
            self.end_headers()


cloud_client.API = "http://127.0.0.1:%d/v1" % PORT
cloud_client.TOKEN = "fake"          # 假服务器不校验，仅为绕过 _headers 的缺 token 检查

# 假上传/假拉图，避免真的走 internal 通道
cloud_client._fetch_asset = lambda key: PNG
cloud_client._upload_file = lambda token, name, data, ctype="image/png": \
    "http://fake/ref/%s" % (name or "x")

# flux-2-klein-9b 的真实 schema（摘自 Replicate openapi_schema，2026-09 拉取）
FLUX2_SCHEMA = {
    "provider": "replicate",
    "model": "black-forest-labs/flux-2-klein-9b",
    "version": "963f7b2c4aa2",
    "mapping": {"m": {
        "refs": "images", "refsIsArray": True, "refsMax": 5,
        "prompt": "prompt", "aspect": "aspect_ratio", "seed": "seed",
    }},
    "schemaParams": [
        {"name": "seed", "type": "integer", "group": "control", "userEditable": False},
        {"name": "images", "type": "array", "group": "refs", "userEditable": False},
        {"name": "prompt", "type": "string", "group": "prompt", "userEditable": False},
        {"name": "go_fast", "type": "boolean", "default": True, "group": "control", "userEditable": True},
        {"name": "megapixels", "type": "enum", "default": "1", "enum": ["0.25", "1", "4"],
         "group": "quality", "userEditable": True},
        {"name": "aspect_ratio", "type": "enum", "default": "1:1",
         "enum": ["1:1", "16:9", "9:16"], "group": "quality", "userEditable": True},
        {"name": "output_format", "type": "enum", "default": "jpg", "enum": ["jpg", "png"],
         "group": "quality", "userEditable": True},
        {"name": "output_quality", "type": "integer", "default": 95, "group": "quality",
         "userEditable": True},
        {"name": "disable_safety_checker", "type": "boolean", "default": False,
         "group": "control", "userEditable": True},
    ],
}

PAYLOAD = {
    "positive_prompt": "关羽骑着赤兔马冲入阵中",
    "negative_prompt": "多手多脚",          # 该模型没有 negative_prompt 字段
    "seed": 424242,
    "aspect_ratio": "9:16",
    "params": {"width": 768, "height": 1344},   # 该模型也没有 width/height
    "referenceKeys": ["ws/p/ref-guanyu.png", "ws/p/ref-lvbu.png"],
    "referenceSubjects": ["关羽", "吕布"],
    "primarySubject": "关羽",
}

# 用户/项目没配凭据时，假 token 就够（假服务器不校验）
srv = HTTPServer(("127.0.0.1", PORT), Fake)
threading.Thread(target=srv.serve_forever, daemon=True).start()

fails = []


def check(name, cond, extra=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + ("  " + extra if extra else ""))
    if not cond:
        fails.append(name)


print("[1] 有 schema（flux-2-klein-9b，用户设了画质）")
CAPTURED.clear()
cfg = dict(FLUX2_SCHEMA)
cfg["params"] = {"megapixels": "1", "output_quality": 92, "output_format": "png",
                 "disable_safety_checker": False}
cloud_client.replicate_image(dict(PAYLOAD), "fake-token", "black-forest-labs/flux-2-klein-9b",
                             cfg=cfg)
inp = CAPTURED[-1]["input"]
print("   提交的 input =", json.dumps(inp, ensure_ascii=False)[:260])
_imgs = inp.get("images") or []
check("参考图发到 images（schema 指定）", len(_imgs) == 2)
check("走 data URI（不依赖会 500 的 /v1/files）",
      bool(_imgs) and all(str(u).startswith("data:image/") for u in _imgs))
check("data URI 带对 mime", bool(_imgs) and str(_imgs[0]).startswith("data:image/png;base64,"))
check("没有误发 input_images（旧 bug）", "input_images" not in inp)
check("没有误发 image", "image" not in inp)
check("画幅 aspect_ratio=9:16", inp.get("aspect_ratio") == "9:16")
check("seed 已下发", inp.get("seed") == 424242)
check("模型不支持的 negative_prompt 未下发", "negative_prompt" not in inp)
check("模型不支持的 width/height 未下发", "width" not in inp and "height" not in inp)
check("用户全局参数已合并（output_quality=92）", inp.get("output_quality") == 92)
check("用户全局参数已合并（output_format=png）", inp.get("output_format") == "png")
check("多图时把 图序→主体 写进 prompt", "Reference images in order" in inp.get("prompt", ""))

print("[2] maxItems=5：6 张参考图会被裁到 5 张")
CAPTURED.clear()
many = dict(PAYLOAD)
many["referenceKeys"] = ["ws/p/r%d.png" % i for i in range(6)]
many["referenceSubjects"] = ["A", "B", "C", "D", "E", "F"]
cloud_client.replicate_image(many, "fake-token", "black-forest-labs/flux-2-klein-9b", cfg=cfg)
inp = CAPTURED[-1]["input"]
check("裁到 5 张", len(inp.get("images") or []) == 5, str(len(inp.get("images") or [])))

print("[3] 无 schema 时兜底：flux-2 系仍走 images")
CAPTURED.clear()
cloud_client.replicate_image(dict(PAYLOAD), "fake-token", "black-forest-labs/flux-2-klein-9b")
inp = CAPTURED[-1]["input"]
check("兜底字段 = images", "images" in inp and "input_images" not in inp)

print("[4] 模型没有参考图字段却带了参考图 → 必须报错中止（不能静默出图）")
CAPTURED.clear()
no_ref_cfg = {
    "mapping": {"m": {"prompt": "prompt"}},
    "schemaParams": [{"name": "prompt", "type": "string", "group": "prompt", "userEditable": False}],
}
try:
    cloud_client.replicate_image(dict(PAYLOAD), "fake-token", "some/text2img-only", cfg=no_ref_cfg)
    check("应抛异常", False, "却正常返回（静默降级）")
except Exception as e:
    check("抛出可操作错误", "没有可用的参考图字段" in str(e), str(e)[:70])
    check("没有发预测请求", len(CAPTURED) == 0)

print("[4b] seedream-4：字段 image_input + 数量上限 10")
CAPTURED.clear()
sd_cfg = {
    "mapping": {"m": {"refs": "image_input", "refsIsArray": True, "refsMax": 10,
                      "prompt": "prompt", "aspect": "aspect_ratio", "size": "size"}},
    "schemaParams": [{"name": "prompt"}, {"name": "image_input", "type": "array", "group": "refs"},
                      {"name": "aspect_ratio", "type": "enum", "enum": ["1:1", "16:9", "9:16"]},
                      {"name": "size", "type": "enum", "enum": ["1K", "2K", "4K", "custom"]}],
}
cloud_client.replicate_image(dict(PAYLOAD), "fake-token", "bytedance/seedream-4", cfg=dict(sd_cfg))
inp = CAPTURED[-1]["input"]
check("参考图发到 image_input", len(inp.get("image_input") or []) == 2)
check("不发 images（那是 flux 的字段）", "images" not in inp)
check("画幅 aspect_ratio=9:16", inp.get("aspect_ratio") == "9:16")

print("[5] 视频：参考帧字段同样按 schema（start_image）")
CAPTURED.clear()
vid_cfg = {"mapping": {"m": {"prompt": "prompt", "refs": "start_image", "seed": "seed"}},
           "schemaParams": [{"name": "prompt"}, {"name": "start_image"}, {"name": "seed"},
                            {"name": "resolution", "default": "720p", "group": "quality",
                             "userEditable": True}]}
vid_payload = {"positive_prompt": "镜头推进", "keyframeKey": "ws/p/kf.png", "seed": 7,
               "duration_sec": 5, "aspect_ratio": "9:16"}
CAPTURED.clear()
saved_fetch = cloud_client._fetch_asset
cloud_client._fetch_asset = lambda key: PNG
try:
    cloud_client.generate_motion_via_replicate(vid_payload, "fake-token", "acme/video-model",
                                               cfg=dict(vid_cfg, params={"resolution": "1080p"}))
    inp = CAPTURED[-1]["input"]
    print("   提交的 input =", json.dumps(inp, ensure_ascii=False)[:200])
    check("参考帧 -> start_image", inp.get("start_image") == "http://fake/ref/kf.png")
    check("没有误发 image", "image" not in inp)
    check("视频全局参数 resolution=1080p", inp.get("resolution") == "1080p")
except Exception as e:
    check("视频路径未抛异常", False, str(e))
finally:
    cloud_client._fetch_asset = saved_fetch

print("[6] 只认 width/height 的模型：尺寸取**项目画幅**（16:9 = 1280x704），不能写死成 1280x720")
vwh_cfg = {
    "mapping": {"m": {"prompt": "prompt", "width": "width", "height": "height"}},
    "schemaParams": [{"name": "prompt"}, {"name": "width"}, {"name": "height"}],
}
CAPTURED.clear()
cloud_client.replicate_image(
    {"positive_prompt": "村口老樟树", "aspect_ratio": "16:9",
     "params": {"width": 1280, "height": 704}},          # 来自项目画幅
    "fake-token", "acme/sdxl-like", cfg=dict(vwh_cfg))
inp = CAPTURED[-1]["input"]
print("   提交尺寸 =", inp.get("width"), "x", inp.get("height"))
check("16:9 项目 → 1280x704（不写死成 1280x720）",
      (inp.get("width"), inp.get("height")) == (1280, 704))

CAPTURED.clear()
cloud_client.replicate_image(
    {"positive_prompt": "竖屏街景", "aspect_ratio": "9:16",
     "params": {"width": 704, "height": 1280}},
    "fake-token", "acme/sdxl-like", cfg=dict(vwh_cfg))
inp = CAPTURED[-1]["input"]
print("   提交尺寸 =", inp.get("width"), "x", inp.get("height"))
check("9:16 项目 → 704x1280", (inp.get("width"), inp.get("height")) == (704, 1280))

print("[7] 模型把尺寸写成枚举时，挑最接近的允许值")
enum_cfg = {
    "mapping": {"m": {"prompt": "prompt", "width": "width", "height": "height"}},
    "schemaParams": [
        {"name": "prompt"},
        {"name": "width", "enum": ["1024", "1344"]},
        {"name": "height", "enum": ["768", "1024"]},
    ],
}
CAPTURED.clear()
cloud_client.replicate_image(
    {"positive_prompt": "x", "aspect_ratio": "16:9", "params": {"width": 1280, "height": 704}},
    "fake-token", "acme/enum-size", cfg=dict(enum_cfg))
inp = CAPTURED[-1]["input"]
print("   提交尺寸 =", inp.get("width"), "x", inp.get("height"))
check("枚举内取值", (inp.get("width"), inp.get("height")) == (1344, 768))

print("[7b] 没有 mapping 时，从 schema 自行认出 input_files / input_file（ComfyUI 命名）")
for fld, is_arr in (("input_files", True), ("input_file", False)):
    CAPTURED.clear()
    c = {"mapping": {}, "schemaParams": [
        {"name": "prompt"},
        {"name": fld, "type": "array" if is_arr else "string"},
        {"name": "num_images", "type": "integer", "default": 1},
    ]}
    cloud_client.replicate_image({"positive_prompt": "x", "referenceKeys": ["ws/p/a.png", "ws/p/b.png"],
                                  "referenceSubjects": ["A", "B"], "params": {}},
                                 "fake-token", "acme/comfy-style", cfg=dict(c))
    inp = CAPTURED[-1]["input"]
    got = inp.get(fld)
    print("   %s ->" % fld, (len(got) if isinstance(got, list) else got) if got else None)
    if is_arr:
        check("发到 %s（数组，2 张）" % fld, isinstance(got, list) and len(got) == 2)
    else:
        check("发到 %s（标量，只取主主体那张）" % fld, isinstance(got, str) and got.startswith("data:"))

print("[7c] 模型没有 negative_prompt → 负向词并入正向提示词（不静默丢）")
CAPTURED.clear()
no_neg_cfg = {"mapping": {"m": {"refs": "images", "refsIsArray": True, "prompt": "prompt"}},
              "schemaParams": [{"name": "prompt"}, {"name": "images", "type": "array"}]}
p2 = dict(PAYLOAD)
p2["negative_prompt"] = "多手多脚, 脸崩"
cloud_client.replicate_image(p2, "fake-token", "black-forest-labs/flux-2-klein-9b", cfg=no_neg_cfg)
inp = CAPTURED[-1]["input"]
check("负向词并入 prompt", "Avoid the following" in inp.get("prompt", "") and "多手多脚" in inp.get("prompt", ""))
check("没有 negative_prompt 字段", "negative_prompt" not in inp)

print("[7d] 模型有 negative_prompt → 原样单独下发，不并入")
CAPTURED.clear()
has_neg_cfg = {"mapping": {"m": {"prompt": "prompt", "negative": "negative_prompt"}},
               "schemaParams": [{"name": "prompt"}, {"name": "negative_prompt"}]}
p3 = {k: v for k, v in p2.items() if k not in ("referenceKeys", "referenceSubjects", "primarySubject")}
cloud_client.replicate_image(p3, "fake-token", "acme/sdxl-like", cfg=has_neg_cfg)
inp = CAPTURED[-1]["input"]
check("单独下发 negative_prompt", inp.get("negative_prompt") == "多手多脚, 脸崩")
check("prompt 未被污染", "Avoid the following" not in inp.get("prompt", ""))

print("[8] 参考图全部准备失败 → 必须报错中止（不能静默出无参考图的图）")
import base64 as _b64
_ok_fetch = cloud_client._fetch_asset
_ok_sniff = cloud_client._sniff_image_mime

def _boom(key):
    raise RuntimeError("asset fetch 500")

cloud_client._fetch_asset = _boom
CAPTURED.clear()
try:
    cloud_client.replicate_image(dict(PAYLOAD), "fake-token", "black-forest-labs/flux-2-klein-9b", cfg=dict(cfg))
    check("应抛异常", False, "却正常返回了（静默降级）")
except Exception as e:
    check("抛出明确错误", "参考图全部准备失败" in str(e), str(e)[:90])
    check("没有发出任何预测请求", len(CAPTURED) == 0)
finally:
    cloud_client._fetch_asset = _ok_fetch

srv.shutdown()
print()
print("RESULT", "PASS" if not fails else ("FAIL: " + ", ".join(fails)))
sys.exit(1 if fails else 0)
