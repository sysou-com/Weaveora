# Weaveora 补丁（2026-09-15）：用 insightface 替掉 RetinaFace
#
# 原实现：from retinaface import RetinaFace（官方 requirements 里钉了 tensorflow==2.15.0 +
#   retina-face==0.0.17）—— 与 numpy2 / 现代 diffusers-torch 栈冲突，且 TF 体积大。
# 我们只需要「一张图里最大的人脸框」来生成 ip_mask；机器上已经有 insightface + buffalo_l
# （在 ComfyUI-LatentSyncWrapper/checkpoints/auxiliary，LatentSync 人脸服务也在用）。
#
# 返回口径与原实现完全一致：get_mask_coord(image_path) -> (y, y2, x, x2, height, width)
import os
import sys
import numpy as np
from PIL import Image

# 注意：用**私有 aux 目录**只放检测模型 det_10g.onnx。
#   共享的那份 buffalo_l（ComfyUI-LatentSyncWrapper/checkpoints/auxiliary）里
#   `1k3d68.onnx` 与 `w600k_r50.onnx` 都是 **33,554,432 字节（被截断的 32MiB）**，
#   insightface 一旦去加载它们就会 `INVALID_PROTOBUF`；我们的补丁只做“找最大脸框”，
#   用私有目录既快又不受污染。
_LATENTSYNC_NODE = os.environ.get("WEAVEORA_LATENTSYNC_DIR") or \
    "/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper"
_AUX = os.environ.get("WEAVEORA_FACE_AUX") or "/opt/weaveora/models/face_aux"
_APP = None


def _app():
    global _APP
    if _APP is None:
        from insightface.app import FaceAnalysis
        app = FaceAnalysis(allowed_modules=["detection"], root=_AUX,
                           providers=["CPUExecutionProvider"])
        app.prepare(ctx_id=-1, det_size=(640, 640))
        _APP = app
    return _APP


def get_mask_coord(image_path):
    img = Image.open(image_path).convert("RGB")
    arr = np.array(img)[:, :, ::-1]          # RGB -> BGR（insightface 要 BGR）
    if arr is None:
        raise ValueError("Exception while loading %s" % image_path)
    height, width, _ = arr.shape
    faces = _app().get(arr)
    if not faces:
        print("%s has no face detected!" % image_path)
        return None
    f = max(faces, key=lambda x: (x.bbox[2] - x.bbox[0]) * (x.bbox[3] - x.bbox[1]))
    x, y, x2, y2 = [int(v) for v in f.bbox[:4]]
    x = max(0, min(x, width - 1)); x2 = max(1, min(x2, width))
    y = max(0, min(y, height - 1)); y2 = max(1, min(y2, height))
    return y, y2, x, x2, height, width


if __name__ == "__main__":
    print(get_mask_coord(sys.argv[1]))
