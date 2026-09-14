# Weaveora 2026-09-13: torch must be imported (and its lib dir registered as a DLL
# directory) BEFORE onnxruntime creates the CUDA EP session, otherwise ORT cannot find
# cublasLt64_12.dll / cudnn64_9.dll that ship inside torch\lib and it silently falls
# back to CPUExecutionProvider (very slow).
import os
import torch

_TORCH_LIB = os.path.join(os.path.dirname(os.path.abspath(torch.__file__)), "lib")
if os.path.isdir(_TORCH_LIB):
    try:
        os.add_dll_directory(_TORCH_LIB)
    except (AttributeError, OSError):
        pass

from insightface.app import FaceAnalysis  # noqa: E402  (must come after the DLL dir setup)
import numpy as np  # noqa: E402

INSIGHTFACE_DETECT_SIZE = 512

# Weaveora 2026-09-13: the upstream root was CWD-relative, but ComfyUI runs with
# CWD=D:\ComfyUI, so "checkpoints/auxiliary" resolved to D:\ComfyUI\checkpoints\auxiliary
# (missing) and insightface then tried to download buffalo_l from GitHub. Resolve it
# relative to this file instead. See Weaveora.md section 0.2.
_NODE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSIGHTFACE_AUX_ROOT = os.path.join(_NODE_ROOT, "checkpoints", "auxiliary")

# ── Weaveora：轨迹锁人（2026-09-14）────────────────────────────────────────────
# 为什么要有这组参数（用户原话）：「一个视频里面画面是不停变的，选定人脸的帧可能对，
# 动作幅度大的应该就不对了」——静态点选只在**那一帧**成立，人物走位/转身/出画再入画
# 之后必然对不上，一旦对不上就会把嘴贴到隔壁那张脸上（画面被毁）。
# 所以：点选/定妆照只用来**播种**，之后靠「上一帧框 + 轨迹自累积人脸特征」逐帧跟住。
TARGET_MIN_SIM = 0.28        # 定妆照识别的最低相似度（只在首帧播种/兜底时用）
REID_MIN_SIM = 0.30          # 与「轨迹自累积特征」的最低相似度（自监督，比定妆照可靠）
REID_MIN_SIM_REACQ = 0.38    # 丢失后**重新捕获**要更严（避免认错人）
IOU_STRONG = 0.45            # 与上一帧框重叠到这个程度 → 直接认作同一人（不再看识别）
CENTER_MAX = 0.85            # 中心位移上限（× 脸框对角线，容忍快速移动）
MAX_LOST_RESET = 90          # 连续丢失多少帧后重置轨迹（≈3 秒 @30fps）
# 质量闸门：不达标的帧**不驱动**（保留原帧）。宁可这帧嘴不动，也不要把画面搞坏。
MIN_FACE_W = 64              # 源片里脸宽下限（太小 → 对齐不可靠，贴回去必然发虚）
MAX_YAW_PROXY = 0.55         # 侧脸代理上限（鼻子相对两眼中线的水平偏移 / 眼距）
# 实测（2026-09-14，本片素材）：正脸 0.004–0.14；正常 3/4 侧脸 0.32–0.39；
# 转身背离 0.56–0.72。所以上限取 0.55 —— 只挡"快背过去了"的帧，
# 不误杀 3/4 侧脸（那个角度 LatentSync 靠 3 点对齐照样能驱动）。
MIN_DET_SCORE = 0.60         # 检测置信度下限

# 点选播种时允许的最大距离（归一化到画面对角线）：点太远就当没点到（例如点到背景）
POINT_MAX_DIST = 0.35


def _emb_of(face):
    """取归一化后的 512 维人脸特征；没有就返回 None。"""
    emb = getattr(face, "normed_embedding", None)
    if emb is None:
        return None
    v = np.asarray(emb, dtype=np.float32).reshape(-1)
    n = float(np.linalg.norm(v)) or 1.0
    return v / n


def _cos(a, b):
    if a is None or b is None:
        return None
    return float(np.dot(a, b))


def _iou(a, b):
    ax1, ay1, ax2, ay2 = [float(v) for v in a]
    bx1, by1, bx2, by2 = [float(v) for v in b]
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    ua = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1) + max(0.0, bx2 - bx1) * max(0.0, by2 - by1) - inter
    return float(inter / ua) if ua > 0 else 0.0


def _center_dist(a, b):
    """中心距离 / 脸框对角线（无单位，0 = 完全重合）。"""
    acx, acy = (a[0] + a[2]) / 2.0, (a[1] + a[3]) / 2.0
    bcx, bcy = (b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0
    diag = max(1.0, float(np.hypot(a[2] - a[0], a[3] - a[1])))
    return float(np.hypot(acx - bcx, acy - bcy) / diag)


def _yaw_proxy(face):
    """侧脸程度代理：鼻子（106 点里的 74/77/83/86 均值）相对两眼中线的水平偏移 / 眼距。

    实测拿不到 face.pose（该属性为 None，需要额外的 pose 模型）。106 点关键点够用：
    正脸 ≈ 0.02，明显侧脸会 > 0.3。返回 None 表示算不出来（这时不拦）。
    """
    lm = getattr(face, "landmark_2d_106", None)
    if lm is None:
        return None
    try:
        eye_l = np.mean(lm[[43, 48, 49, 51, 50]], axis=0)
        eye_r = np.mean(lm[101:106], axis=0)
        nose = np.mean(lm[[74, 77, 83, 86]], axis=0)
    except Exception:
        return None
    w = float(np.hypot(*(eye_r - eye_l)))
    if w <= 1e-3:
        return None
    return float(abs(nose[0] - (eye_l[0] + eye_r[0]) / 2.0) / w)


def _pick_by_target(cands, target, min_sim=TARGET_MIN_SIM):
    """在多张候选脸里选与目标特征最像的那张（余弦相似度）。

    给 target（归一化后的 512 维）→ 返回最像的 face，或 None（都不像）。
    为什么用识别而不是位置：同一人物在镜头里会移动/转身，位置式启发式会跟丢，
    而身份特征跨帧稳定（同一张脸的嵌入向量变化很小）。
    """
    best, best_sim = None, -1.0
    for f in cands:
        v = _emb_of(f)
        if v is None:
            continue
        sim = float(np.dot(v, target))
        if sim > best_sim:
            best_sim, best = sim, f
    if best is None or best_sim < min_sim:
        return None
    return best


def _pick_by_point(cands, point, f_w, f_h):
    """选「脸中心最靠近指定点」的那张（point 为归一化 0–1，来自用户点选）。

    为什么需要这条路径（2026-09-13 实测）：在 480p/AI 古风这类风格化素材上，
    人脸识别（ArcFace）区分度崩了 —— 同一个人的相似度只有 0.2 上下，
    且宝玉/警幻/袭人 互相混淆，任何阈值都把噪声当信号。
    用户点一下“谁是警幻”是最可靠的信号，且完全确定、不受画风影响。

    2026-09-14：它现在只用于**播种**（首帧 / 轨迹重置后），不再逐帧按点找脸
    —— 逐帧按点找脸在走位、交错、出画时会突然跳到别人身上。
    """
    if not cands:
        return None
    px, py = float(point[0]) * f_w, float(point[1]) * f_h
    best, best_d = None, 1e9
    for f in cands:
        x1, y1, x2, y2 = [float(v) for v in f.bbox]
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        d = float(np.hypot(cx - px, cy - py)) / max(1.0, float(np.hypot(f_w, f_h)))
        if d < best_d:
            best_d, best = d, f
    # 点太远就不认（例如用户点到了背景），避免随手贴到别人脸上
    if best is None or best_d > POINT_MAX_DIST:
        return None
    return best


class FaceDetector:
    """逐帧选「该驱动的那张脸」，并用**轨迹**把它跟住（Weaveora 2026-09-14）。

    契约：`__call__(frame)` 返回 `(bbox, lmk)`（可能为上一帧的值，便于调用方裁剪），
    并设置 `self.last_driven`：False 表示**这一帧不要驱动**（调用方必须保留原帧）。
    这样即使是「人不在画面里 / 太小 / 侧脸」也不会抛错、更不会改用别人的脸。
    """

    # 兼容旧引用（旧代码用它做「沿用上限」，现已由轨迹+质量闸门取代）
    CARRY_MAX_FRAMES = MAX_LOST_RESET

    def __init__(self, device="cuda", target_embedding=None, target_point=None):
        # target_embedding: np.ndarray(512,) —— 说话人定妆照的人脸特征（**仅用于播种**）
        # target_point: (x, y) 归一化 0–1 —— 用户点选的人脸位置（**仅用于播种**，优先）
        self.target = None
        self.point = None
        self._box = None       # 轨迹：上一帧的框 (x1,y1,x2,y2)
        self._lmk = None       # 轨迹：上一帧的关键点
        self._emb = None       # 轨迹自累积特征（EMA）—— 同一视频里长出来的，比定妆照可靠
        self._lost = 0         # 连续丢失帧数
        self.last_driven = False
        self.last_reason = ""
        self.stats = {}
        if target_point is not None:
            try:
                px, py = float(target_point[0]), float(target_point[1])
                if 0.0 <= px <= 1.0 and 0.0 <= py <= 1.0:
                    self.point = (px, py)
            except (TypeError, ValueError, IndexError):
                self.point = None
        if target_embedding is not None:
            t = np.asarray(target_embedding, dtype=np.float32).reshape(-1)
            n = float(np.linalg.norm(t)) or 1.0
            self.target = t / n
        self.app = FaceAnalysis(
            # Weaveora：需要「锁人」时多加载 recognition（w600k_r50）算嵌入向量；
            # 不需要时不加载（省显存）。轨迹跟踪也用同一套特征（自累积），所以锁人时都要。
            allowed_modules=(["detection", "landmark_2d_106", "recognition"]
                             if (target_embedding is not None or target_point is not None)
                             else ["detection", "landmark_2d_106"]),
            root=INSIGHTFACE_AUX_ROOT,
            providers=["CUDAExecutionProvider"],
        )
        self.app.prepare(ctx_id=cuda_to_int(device), det_size=(INSIGHTFACE_DETECT_SIZE, INSIGHTFACE_DETECT_SIZE))

    # ── 统计（给日志用）────────────────────────────────────────────────────
    def _bump(self, key):
        self.stats[key] = self.stats.get(key, 0) + 1

    def stats_line(self):
        """一行摘要，便于在 ComfyUI 日志里核对「跟住了没有」。"""
        if not self.stats:
            return "无"
        items = sorted(self.stats.items(), key=lambda kv: -kv[1])
        return "，".join("%s×%d" % (k, v) for k, v in items[:6])

    # ── 主流程 ─────────────────────────────────────────────────────────────
    def __call__(self, frame, threshold=0.5):
        f_h, f_w, _ = frame.shape
        cands = self._candidates(frame)
        if not cands:
            self._bump("无人脸")
            self._miss()
            return self._result()
        if self._box is None:
            # ① 播种：用户点选 > 轨迹自累积特征（重新捕获） > 定妆照 > 最大脸
            seed = self._seed(cands, f_w, f_h)
            if seed is None:
                self._bump("播种失败")
                self._miss()
                return self._result()
            return self._follow(seed, f_w, f_h, why="播种")
        # ② 关联：找「还是那个人」的候选脸
        best, best_score, best_why = None, None, ""
        for f in cands:
            iou = _iou(self._box, f.bbox)
            dist = _center_dist(self._box, f.bbox)
            sim = _cos(_emb_of(f), self._emb)
            score, why = None, ""
            if iou >= IOU_STRONG:
                score, why = 2.0 + iou, "重叠%.2f" % iou
            elif dist <= CENTER_MAX and (sim is None or sim >= REID_MIN_SIM):
                score, why = 1.0 + iou - dist * 0.2, "近邻%.2f" % dist
            elif sim is not None and sim >= (REID_MIN_SIM_REACQ if self._lost >= 1 else REID_MIN_SIM):
                score, why = 0.5 + iou, "特征%.2f" % sim
            if score is not None and (best_score is None or score > best_score):
                best, best_score, best_why = f, score, why
        if best is None:
            self._bump("跟丢")
            self._miss()
            return self._result()
        return self._follow(best, f_w, f_h, why=best_why)

    # ── 内部 ───────────────────────────────────────────────────────────────
    def _candidates(self, frame):
        """上游的候选过滤（尺寸/长宽比/置信度）——保持与原版一致。"""
        cands = []
        for face in self.app.get(frame):
            bbox = face.bbox.astype(np.int_).tolist()
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            if w < 50 or h < 80:
                continue
            if w / h > 1.5 or w / h < 0.2:
                continue
            if face.det_score < max(0.5, MIN_DET_SCORE):
                continue
            cands.append(face)
        return cands

    def _seed(self, cands, f_w, f_h):
        if self.point is not None:
            p = _pick_by_point(cands, self.point, f_w, f_h)
            if p is not None:
                self._bump("点选播种")
                return p
        if self._emb is not None:
            p = _pick_by_target(cands, self._emb, min_sim=REID_MIN_SIM_REACQ)
            if p is not None:
                self._bump("轨迹特征重捕获")
                return p
        if self.target is not None:
            p = _pick_by_target(cands, self.target, min_sim=TARGET_MIN_SIM)
            if p is not None:
                self._bump("定妆照播种")
                return p
            return None
        # 没有任何锁人信息（纯自动）：取最大脸
        self._bump("最大脸播种")
        return max(cands, key=lambda f: float((f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1])))

    def _follow(self, face, f_w, f_h, why):
        bbox, lmk = self._to_box(face, f_w, f_h)
        self._box, self._lmk = bbox, lmk
        v = _emb_of(face)
        if v is not None:
            # EMA（新帧权重 0.35）：既跟得上（慢慢换姿态/光照），又不会被单帧噪声带跑
            self._emb = v if self._emb is None else (0.65 * self._emb + 0.35 * v)
            self._emb = self._emb / (float(np.linalg.norm(self._emb)) or 1.0)
        self._lost = 0
        # 质量闸门：不达标 → 这一帧不驱动（保留原帧），但轨迹照常更新（转头回来能接上）
        w = float(face.bbox[2] - face.bbox[0])
        yaw = _yaw_proxy(face)
        reason = ""
        if w < MIN_FACE_W:
            reason = "脸太小"
        elif yaw is not None and yaw > MAX_YAW_PROXY:
            reason = "侧脸"
        self.last_driven = not reason
        self.last_reason = reason or why
        self._bump("驱动" if not reason else ("不驱动:" + reason))
        return bbox, lmk

    def _miss(self):
        """这一帧没跟住：不驱动。丢太久就重置轨迹（下次可用点选/特征重新捕获）。"""
        self._lost += 1
        if self._lost > MAX_LOST_RESET:
            self._box, self._lmk, self._emb = None, None, None
        self.last_driven = False
        self.last_reason = "跟丢%d帧" % self._lost

    def _result(self):
        """返回「上一帧的框」便于调用方裁剪；是否驱动看 last_driven。"""
        return self._box, self._lmk

    def _to_box(self, face, f_w, f_h):
        """把选中的 face 换算成 (bbox, landmarks)（与上游原逻辑一致）。"""
        if f_w is None:
            return face[0], face[1]
        lmk = np.round(face.landmark_2d_106).astype(np.int_)

        halk_face_coord = np.mean([lmk[74], lmk[73]], axis=0)  # lmk[73]

        sub_lmk = lmk[LMK_ADAPT_ORIGIN_ORDER]
        halk_face_dist = np.max(sub_lmk[:, 1]) - halk_face_coord[1]
        upper_bond = halk_face_coord[1] - halk_face_dist  # *0.94

        x1, y1, x2, y2 = (np.min(sub_lmk[:, 0]), int(upper_bond), np.max(sub_lmk[:, 0]), np.max(sub_lmk[:, 1]))

        if y2 - y1 <= 0 or x2 - x1 <= 0 or x1 < 0:
            x1, y1, x2, y2 = face.bbox.astype(np.int_).tolist()

        y2 += int((x2 - x1) * 0.1)
        x1 -= int((x2 - x1) * 0.05)
        x2 += int((x2 - x1) * 0.05)

        x1 = max(0, x1)
        y1 = max(0, y1)
        x2 = min(f_w, x2)
        y2 = min(f_h, y2)

        return (x1, y1, x2, y2), lmk


def cuda_to_int(cuda_str: str) -> int:
    """
    Convert the string with format "cuda:X" to integer X.
    """
    if cuda_str == "cuda":
        return 0
    device = torch.device(cuda_str)
    if device.type != "cuda":
        raise ValueError(f"Device type must be 'cuda', got: {device.type}")
    return device.index


LMK_ADAPT_ORIGIN_ORDER = [
    1,
    10,
    12,
    14,
    16,
    3,
    5,
    7,
    0,
    23,
    21,
    19,
    32,
    30,
    28,
    26,
    17,
    43,
    48,
    49,
    51,
    50,
    102,
    103,
    104,
    105,
    101,
    73,
    74,
    86,
]
