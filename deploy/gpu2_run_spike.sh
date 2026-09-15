#!/bin/bash
# 第5镜 jaw-lip 实测 v2：/talk_batch（一次加载 2 个 item：第1个=交付产物，第2个=摊薄后的单镜耗时基准）
set -u
ROOT=/opt/weaveora; L="$ROOT/logs"; O="$ROOT/talk_out"; mkdir -p "$L" "$O"
PROMPT="close-up of a terrified young Qing-dynasty man in soaked silk robes, lips pressed tightly shut, jaw clenched, wide panicked eyes, wet hair strands stuck to his forehead, water dripping down his face, dark clawed demon hands at the frame edges, black churning river blurred behind him, ash-grey night, low-key cold light, cinematic, film grain, high detail"
"$ROOT/envs/talk/bin/python" - "$PROMPT" <<'PY'
import base64, json, sys, time, urllib.request
prompt = sys.argv[1]
img = base64.b64encode(open("/opt/weaveora/talk_in/imgs/shot5.jpg", "rb").read()).decode()
aud = base64.b64encode(open("/opt/weaveora/talk_in/audios/shot5.wav", "rb").read()).decode()
item = {"image_b64": img, "audio_b64": aud, "image_suffix": ".jpg", "audio_suffix": ".wav", "prompt": prompt}
body = dict(item, items=[item, dict(item)],
            jaw_gain=1.0, steps=8, guidance=4.0, audio_guidance=2.9, seed=43, fps=25,
            sample_size=[512, 512], partial_video_length=81)
req = urllib.request.Request("http://127.0.0.1:8094/talk_batch", data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
t0 = time.time()
try:
    d = json.loads(urllib.request.urlopen(req, timeout=7200).read())
except Exception as e:
    print("SPIKE_FAIL", type(e).__name__, str(e)[:400], flush=True); raise SystemExit(1)
for i, r in enumerate(d.get("results", [])):
    p = "/opt/weaveora/talk_out/shot5_native%s.mp4" % ("" if i == 0 else "_b")
    vb = base64.b64decode(r["video_b64"]); open(p, "wb").write(vb)
    print("ITEM%d ok bytes=%d -> %s" % (i, len(vb), p), flush=True)
print("SPIKE_OK", json.dumps({"wall_seconds": round(time.time()-t0, 1), "meta": d.get("meta")}, ensure_ascii=False), flush=True)
PY
ls -l "$O" | tail -4
