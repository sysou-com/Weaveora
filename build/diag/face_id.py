import os, sys, glob
import numpy as np
from insightface.app import FaceAnalysis
from PIL import Image

ROOT = "/tmp/diag/fa"
IN = "/opt/weaveora/ComfyUI/input"
OUT = "/opt/weaveora/ComfyUI/output"
REFS = {"宝玉": "08d7fb46-6d17-4e9a-a772-03f4f42a0df1.png",
        "可卿": "18b910c8-1ef3-4916-9db1-258f509bf236.png",
        "警幻": "a1ff5778-101b-47f6-8460-f22f28515605.png"}

app = FaceAnalysis(name="buffalo_l", root=ROOT, providers=["CPUExecutionProvider"])
app.prepare(ctx_id=-1, det_size=(640, 640))


def emb(path):
    import cv2
    img = cv2.imread(path)
    if img is None:
        img = np.array(Image.open(path).convert("RGB"))[:, :, ::-1]
    faces = app.get(img)
    h, w = img.shape[:2]
    out = []
    for f in faces:
        x1, y1, x2, y2 = [int(v) for v in f.bbox]
        out.append({"e": f.normed_embedding, "box": (x1, y1, x2, y2),
                    "cx": (x1 + x2) / 2.0 / w, "cy": (y1 + y2) / 2.0 / h,
                    "px": min(x2 - x1, y2 - y1)})
    return out


refs = {}
for name, fn in REFS.items():
    p = os.path.join(IN, fn)
    if not os.path.exists(p):
        print("REF MISSING", name)
        continue
    e = emb(p)
    if e:
        refs[name] = e[0]["e"]
        print("REF %s: faces=%d box=%s px=%d" % (name, len(e), e[0]["box"], e[0]["px"]))
    else:
        print("REF %s: NO FACE" % name)


def who(vec):
    best, bs = None, -2.0
    for n, v in refs.items():
        s = float(np.dot(vec, v))
        if s > bs:
            best, bs = n, s
    return best, bs


for pat in sys.argv[1:]:
    for p in sorted(glob.glob(pat)):
        fs = emb(p)
        print("--- %s  faces=%d" % (os.path.basename(p), len(fs)))
        for f in sorted(fs, key=lambda x: -x["px"])[:5]:
            n, s = who(f["e"])
            print("    px=%4d 位置(%.2f,%.2f) → 最像 %s  cos=%.3f" % (f["px"], f["cx"], f["cy"], n, s))
