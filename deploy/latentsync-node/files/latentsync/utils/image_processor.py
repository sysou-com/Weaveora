# Copyright (c) 2024 Bytedance Ltd. and/or its affiliates
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from latentsync.utils.util import read_video, write_video
from torchvision import transforms
import os
import cv2
from einops import rearrange
import torch
import numpy as np
from typing import Union
from .affine_transform import AlignRestore
from .face_detector import FaceDetector

# Weaveora 2026-09-13: ComfyUI runs with CWD=D:\ComfyUI, so this CWD-relative default
# resolved to a non-existent path. Anchor it to the repo instead (Weaveora.md section 0.2).
_NODE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_MASK_PATH = os.path.join(_NODE_ROOT, "latentsync", "utils", "mask.png")


def load_fixed_mask(resolution: int, mask_image_path=DEFAULT_MASK_PATH) -> torch.Tensor:
    mask_image = cv2.imread(mask_image_path)
    mask_image = cv2.cvtColor(mask_image, cv2.COLOR_BGR2RGB)
    mask_image = cv2.resize(mask_image, (resolution, resolution), interpolation=cv2.INTER_LANCZOS4) / 255.0
    mask_image = rearrange(torch.from_numpy(mask_image), "h w c -> c h w")
    return mask_image


# ── Weaveora：贴回遮罩（2026-09-14）──────────────────────────────────────────
# 原版 mask.png 的「重绘区」是整个下半脸 + 两侧脸颊（U 形，占宽约 80%、高约 63%）。
# 那是训练时的形状，模型**看到**它没问题；但**贴回**时同样把两侧脸颊一起覆盖掉，就等于
# 「只要对齐稍偏一点，半张脸甚至隔壁那张脸都会被重写」——这正是“画面被毁”的直接原因
# （两张脸只隔 ~180px 时必然互相污染）。
# 所以：**喂给模型的遮罩保持原样**（不造成训练/推理分布偏移），只把**贴回**的区域收紧到
# 嘴+下巴（脸已按 3 点对齐归一化到 512，嘴必在中央竖带内），并羽化边界。
# 三档参数可用环境变量覆盖，便于实拍微调（改完重启 ComfyUI 即可）：
#   WEAVEORA_PASTE_BAND="0.22,0.78,0.40,1.00"  WEAVEORA_PASTE_GROW=10  WEAVEORA_PASTE_BLUR=9

def _env_band(name, default):
    raw = os.environ.get(name, "")
    if not raw:
        return default
    try:
        vals = [float(x) for x in raw.replace(" ", "").split(",")]
        return tuple(vals) if len(vals) == 4 else default
    except Exception:
        return default


def _env_int(name, default):
    try:
        return int(os.environ.get(name, "") or default)
    except Exception:
        return default


def build_paste_mask(mask_image: torch.Tensor) -> torch.Tensor:
    """把训练用的 mask.png 收紧成「只改嘴+下巴」的贴回遮罩（1=保留原图，0=采用模型结果）。

    ① 限制在中央竖带内 → 两侧脸颊交还原图（保护邻脸）
    ② 「保留区」向外扩张 PASTE_GROW 像素 → 重绘区再缩一圈
    ③ 高斯羽化 → 边界过渡自然，不出现硬边
    """
    band_x = (0.22, 0.78)
    band_y = (0.40, 1.00)
    b = _env_band("WEAVEORA_PASTE_BAND", None)
    if b is not None:
        band_x, band_y = (b[0], b[1]), (b[2], b[3])
    grow = _env_int("WEAVEORA_PASTE_GROW", 10)
    blur = _env_int("WEAVEORA_PASTE_BLUR", 9)

    m = mask_image[0:1].float()  # (1,H,W)，1=保留原图
    h, w = int(m.shape[-2]), int(m.shape[-1])
    band = torch.zeros_like(m)
    x1, x2 = max(0, int(band_x[0] * w)), min(w, int(band_x[1] * w))
    y1, y2 = max(0, int(band_y[0] * h)), min(h, int(band_y[1] * h))
    band[..., y1:y2, x1:x2] = 1.0
    m = m * band + (1.0 - band)  # 带外强制 1（保留原图）
    if grow > 0:
        k = 2 * grow + 1
        m = torch.nn.functional.max_pool2d(m.unsqueeze(0), kernel_size=k, stride=1, padding=grow).squeeze(0)
    if blur > 0:
        k = 2 * int(np.ceil(3.0 * blur)) + 1
        m = transforms.functional.gaussian_blur(m, kernel_size=[k, k], sigma=[float(blur), float(blur)])
    return m.clamp(0.0, 1.0)


class ImageProcessor:
    def __init__(self, resolution: int = 512, device: str = "cpu", mask_image=None,
                 target_embedding=None, target_point=None):
        self.resolution = resolution
        self.resize = transforms.Resize(
            (resolution, resolution), interpolation=transforms.InterpolationMode.BICUBIC, antialias=True
        )
        self.normalize = transforms.Normalize([0.5], [0.5], inplace=True)

        self.restorer = AlignRestore(resolution=resolution, device=device)

        if mask_image is None:
            self.mask_image = load_fixed_mask(resolution)
        else:
            self.mask_image = mask_image

        # Weaveora：贴回用的「收紧+羽化」遮罩（模型输入仍用原始 mask_image，见 build_paste_mask）
        self.paste_mask = build_paste_mask(self.mask_image) if self.mask_image is not None else None
        self.last_paste_masks = None   # 最近一次 prepare_* 的贴回遮罩（管线贴回时取用）
        self.last_driven = True        # 最近一帧是否要驱动（False = 保留原帧）
        self.last_driven_list = None   # 整段视频的逐帧「是否驱动」

        if device == "cpu":
            self.face_detector = None
        else:
            # Weaveora：target_embedding = 说话人定妆照的人脸特征；target_point = 用户点选的人脸位置
            # （两者都给时以点选优先，见 face_detector）
            self.face_detector = FaceDetector(device=device, target_embedding=target_embedding,
                                              target_point=target_point)

    def affine_transform(self, image: torch.Tensor) -> np.ndarray:
        if self.face_detector is None:
            raise NotImplementedError("Using the CPU for face detection is not supported")
        bbox, landmark_2d_106 = self.face_detector(image)
        # Weaveora：轨迹跟丢/质量不达标时**不驱动**（保留原帧），而不是抛错整个任务失败。
        self.last_driven = bool(self.face_detector.last_driven)
        if bbox is None or landmark_2d_106 is None:
            # 整段都没见过目标脸：给一个合法的整帧裁剪，保证批处理张量不崩（输出帧会保留原样）
            img_t = rearrange(torch.from_numpy(np.ascontiguousarray(image)), "h w c -> c h w")
            face = self.resize(img_t)
            return face, [0, 0, int(image.shape[1]), int(image.shape[0])], None

        pt_left_eye = np.mean(landmark_2d_106[[43, 48, 49, 51, 50]], axis=0)  # left eyebrow center
        pt_right_eye = np.mean(landmark_2d_106[101:106], axis=0)  # right eyebrow center
        pt_nose = np.mean(landmark_2d_106[[74, 77, 83, 86]], axis=0)  # nose center

        landmarks3 = np.round([pt_left_eye, pt_right_eye, pt_nose])

        face, affine_matrix = self.restorer.align_warp_face(image.copy(), landmarks3=landmarks3, smooth=True)
        box = [0, 0, face.shape[1], face.shape[0]]  # x1, y1, x2, y2
        face = cv2.resize(face, (self.resolution, self.resolution), interpolation=cv2.INTER_LANCZOS4)
        face = rearrange(torch.from_numpy(face), "h w c -> c h w")
        return face, box, affine_matrix

    def preprocess_fixed_mask_image(self, image: torch.Tensor, affine_transform=False, driven=True):
        if affine_transform:
            image, _, _ = self.affine_transform(image)
        else:
            image = self.resize(image)
        pixel_values = self.normalize(image / 255.0)
        # 模型输入（条件）永远用训练时的 mask_image，避免分布偏移
        masked_pixel_values = pixel_values * self.mask_image
        # 贴回遮罩：不驱动（跟丢/太小/侧脸）→ 全 1（整帧保留原图）；否则用收紧+羽化的遮罩
        if driven and self.paste_mask is not None:
            paste = self.paste_mask
        else:
            paste = torch.ones_like(self.mask_image[0:1])
        return pixel_values, masked_pixel_values, self.mask_image[0:1], paste

    def prepare_masks_and_masked_images(self, images: Union[torch.Tensor, np.ndarray], affine_transform=False,
                                        driven=None):
        """driven: 逐帧「是否驱动」布尔列表（None = 全部驱动）。

        返回值与上游一致（3 个），贴回遮罩另存 `self.last_paste_masks` —— 这样训练侧
        （unet_dataset）的调用点不用改。
        """
        if isinstance(images, np.ndarray):
            images = torch.from_numpy(images)
        if images.shape[3] == 3:
            images = rearrange(images, "f h w c -> f c h w")

        results = [
            self.preprocess_fixed_mask_image(
                image, affine_transform=affine_transform,
                driven=True if driven is None else bool(driven[i]),
            )
            for i, image in enumerate(images)
        ]

        pixel_values_list, masked_pixel_values_list, masks_list, paste_list = list(zip(*results))
        self.last_paste_masks = torch.stack(paste_list)
        return torch.stack(pixel_values_list), torch.stack(masked_pixel_values_list), torch.stack(masks_list)

    def process_images(self, images: Union[torch.Tensor, np.ndarray]):
        if isinstance(images, np.ndarray):
            images = torch.from_numpy(images)
        if images.shape[3] == 3:
            images = rearrange(images, "f h w c -> f c h w")
        images = self.resize(images)
        pixel_values = self.normalize(images / 255.0)
        return pixel_values


class VideoProcessor:
    def __init__(self, resolution: int = 512, device: str = "cpu"):
        self.image_processor = ImageProcessor(resolution, device)

    def affine_transform_video(self, video_path):
        video_frames = read_video(video_path, change_fps=False)
        results = []
        driven = []
        for frame in video_frames:
            frame, _, _ = self.image_processor.affine_transform(frame)
            results.append(frame)
            driven.append(bool(self.image_processor.last_driven))
        self.last_driven_list = driven
        results = torch.stack(results)

        results = rearrange(results, "f c h w -> f h w c").numpy()
        return results


if __name__ == "__main__":
    video_processor = VideoProcessor(256, "cuda")
    video_frames = video_processor.affine_transform_video("assets/demo2_video.mp4")
    write_video("output.mp4", video_frames, fps=25)
