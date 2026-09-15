#!/usr/bin/env python3
"""GPU#2 · Phase 1 下完后**自动排队出第一张图**（Qwen-Image T3 主力）

行为（全部写入 /opt/weaveora/logs/first_image.log）：
  1. 等 Phase 1 下载完成（dl_qwen.log 出现 ALL_DONE 且 4 个文件字节正确）；
  2. 等 ComfyUI 空闲：/queue 的 running/pending 都空，且 /system_stats 的 vram_free ≥ 38 GiB
     （Qwen-Image fp8 20G + 文本编码器 9.4G + VAE/LoRA ≈ 31G，必须留足余量；生产任务在跑时绝不插队）；
  3. 提交 deploy/comfy/qwen_image_txt2img_api.json（本地副本 /opt/weaveora/qwen_image_txt2img_api.json），
     出两张对照图：中文提示词（Qwen 的强项） + 项目里第 5 镜的英文正词（可与云端/参考图对比）；
  4. 取回图片存 /opt/weaveora/img_out/，日志打 FIRST_IMAGE_OK / FIRST_IMAGE_FAIL。
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = "/opt/weaveora"
LOG = os.path.join(ROOT, "logs/first_image.log")
WF = os.path.join(ROOT, "qwen_image_txt2img_api.json")
OUT = os.path.join(ROOT, "img_out")
COMFY = "http://127.0.0.1:8001"
CLIENT = "weaveora-firstimg"

PHASE1 = [
    ("models/diffusion_models/qwen_image_fp8_e4m3fn.safetensors", 20430635136),
    ("models/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors", 9384670680),
    ("models/vae/qwen_image_vae.safetensors", 253806246),
    ("models/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors", 1698951104),
]
PROMPT_ZH = ("中国古典小说插画风格，黄昏下的太虚幻境牌坊，青石牌坊上悬着「太虚幻境」匾额，"
             "牌坊后云雾缭绕、仙鹤掠过，前景一位清代服饰的年轻公子回望，电影感光影，高细节")
PROMPT_EN = ("close-up of a terrified young Qing-dynasty man in soaked silk robes, lips pressed tightly shut, "
             "jaw clenched, wide panicked eyes, wet hair stuck to his forehead, dark clawed demon hands at frame edges, "
             "black churning river blurred behind, ash-grey night, low-key cold light, cinematic, film grain, high detail")


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def get(path, timeout=60):
    with urllib.request.urlopen(COMFY + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def post(path, body, timeout=600):
    req = urllib.request.Request(COMFY + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_download(deadline_s=7200):
    t0 = time.time()
    while time.time() - t0 < deadline_s:
        done = os.path.exists(os.path.join(ROOT, "logs/dl_qwen.log")) and \
            "ALL_DONE" in open(os.path.join(ROOT, "logs/dl_qwen.log"), encoding="utf-8", errors="replace").read()
        sizes_ok = all(os.path.exists(os.path.join(ROOT, p)) and os.path.getsize(os.path.join(ROOT, p)) == n
                       for p, n in PHASE1)
        if done and sizes_ok:
            log("Phase1 模型就位（4/4 字节正确）")
            return True
        log("等 Phase1：done=%s sizes_ok=%s" % (done, sizes_ok))
        time.sleep(30)
    log("等 Phase1 超时")
    return False


def wait_idle(need_gb=38.0, deadline_s=21600):
    t0 = time.time()
    while time.time() - t0 < deadline_s:
        try:
            q = get("/queue")
            running = len(q.get("queue_running") or [])
            pending = len(q.get("queue_pending") or [])
            st = get("/system_stats")
            free = st["devices"][0]["vram_free"] / 2 ** 30
        except Exception as e:
            log("查询 ComfyUI 失败：%s" % e)
            time.sleep(30)
            continue
        if running == 0 and pending == 0 and free >= need_gb:
            log("ComfyUI 空闲（running=%d pending=%d vram_free=%.1fG）→ 提交出图" % (running, pending, free))
            return True
        log("等空闲：running=%d pending=%d vram_free=%.1fG（需 ≥%.0fG）" % (running, pending, free, need_gb))
        time.sleep(30)
    log("等空闲超时")
    return False


def run_one(name, prompt, seed, width=1344, height=768, timeout_s=3600):
    graph = json.load(open(WF, encoding="utf-8"))
    graph.pop("_comment", None)
    graph["6"]["inputs"]["text"] = prompt
    graph["7"]["inputs"]["text"] = ""
    graph["8"]["inputs"].update({"width": width, "height": height, "batch_size": 1})
    graph["9"]["inputs"]["seed"] = seed
    graph["11"]["inputs"]["filename_prefix"] = "weaveora_qwen_%s" % name
    t0 = time.time()
    r = post("/prompt", {"prompt": graph, "client_id": CLIENT})
    pid = r.get("prompt_id")
    if not pid:
        log("FIRST_IMAGE_FAIL 提交被拒：%s" % json.dumps(r)[:600])
        return None
    log("已入队 %s prompt_id=%s" % (name, pid))
    while time.time() - t0 < timeout_s:
        try:
            h = get("/history/%s" % pid)
        except Exception:
            h = {}
        if h and pid in h:
            node_out = h[pid].get("outputs") or {}
            for node in node_out.values():
                for im in (node.get("images") or []):
                    q = urllib.parse.urlencode({"filename": im.get("filename", ""),
                                                "subfolder": im.get("subfolder", ""),
                                                "type": im.get("type", "output")})
                    with urllib.request.urlopen(COMFY + "/view?" + q, timeout=300) as resp:
                        blob = resp.read()
                    p = os.path.join(OUT, "%s_%s" % (name, im.get("filename", "out.png")))
                    open(p, "wb").write(blob)
                    log("FIRST_IMAGE_OK %s → %s（%d bytes，用时 %.1fs）" % (name, p, len(blob), time.time() - t0))
                    return p
            log("history 无图片输出：%s" % json.dumps(h[pid])[:400])
            return None
        time.sleep(10)
    log("等出图超时（%s）" % name)
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    log("=== 自动出图任务启动（Phase1 → 等空闲 → Qwen-Image 出图） ===")
    if not os.path.exists(WF):
        log("FIRST_IMAGE_FAIL 缺少工作流文件 %s" % WF)
        return
    if not wait_download():
        log("FIRST_IMAGE_FAIL Phase1 未就位")
        return
    if not wait_idle():
        log("FIRST_IMAGE_FAIL 长时间没有空闲窗口")
        return
    ok = run_one("zh", PROMPT_ZH, 12345)
    if ok:
        run_one("en", PROMPT_EN, 43)     # 与项目第5镜同 seed 便于对照
    log("=== 自动出图任务结束 ===")


if __name__ == "__main__":
    main()
