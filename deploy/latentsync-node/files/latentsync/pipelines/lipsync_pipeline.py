# Adapted from https://github.com/guoyww/AnimateDiff/blob/main/animatediff/pipelines/pipeline_animation.py

import inspect
import math
import os
import shutil
from typing import Callable, List, Optional, Union
import subprocess

import numpy as np
import torch
import torchvision
from torchvision import transforms

from packaging import version

from diffusers.configuration_utils import FrozenDict
from diffusers.models import AutoencoderKL
from diffusers.pipelines import DiffusionPipeline
from diffusers.schedulers import (
    DDIMScheduler,
    DPMSolverMultistepScheduler,
    EulerAncestralDiscreteScheduler,
    EulerDiscreteScheduler,
    LMSDiscreteScheduler,
    PNDMScheduler,
)
from diffusers.utils import deprecate, logging

from einops import rearrange
import cv2

from ..models.unet import UNet3DConditionModel
from ..utils.util import read_video, read_audio, write_video, check_ffmpeg_installed
from ..utils.image_processor import ImageProcessor, load_fixed_mask, DEFAULT_MASK_PATH as _DEFAULT_MASK_PATH
from ..whisper.audio2feature import Audio2Feature
import tqdm
import soundfile as sf

# Weaveora：调试画框开关（WEAVEORA_DEBUG_BOX=1）——把「本帧锁定的脸 + 是否驱动」画进产物，
# 供远端（worker 在 API 服务器、节点在 GPU 服务器）核对到底选了谁。
_DEBUG_BOX = (os.environ.get("WEAVEORA_DEBUG_BOX", "") or "").lower() not in ("", "0", "false", "no")


def _crop_box_to_frame_quad(box, affine_matrix):
    """把「裁剪坐标系里的矩形」用仿射逆变换映射回**原帧坐标系**，返回 4 个角点（Nx2）。

    ★ 2026-09-22 勘误：`ImageProcessor.affine_transform()` 返回的 `box` 恒为
    `[0, 0, 裁剪宽, 裁剪高]`（`AlignRestore.align_warp_face` 把脸对齐到固定模板，
    裁剪图左上角就是 (0,0)），它**与脸在原帧的哪个位置无关**。上段直接拿它当原帧坐标
    画调试框 ⇒ 绿框永远贴在画面左上角（实测 x 0.000–0.505 / y 0.000–0.996，正好等于
    裁剪尺寸）⇒ 任何「框住谁 / 框在不在嘴上」的目视判断都是错的，据此得出的
    「贴回半幅 ⇒ 嘴部乱码」结论**已作废**（真正的贴回是 restore_img 的仿射逆变换 + 羽化掩码）。

    `affine_matrix` 是「原帧 → 裁剪」的 2x3（kornia 约定；`restore_img` 正是用它的逆把脸
    贴回原帧），所以把裁剪矩形四角乘上它的逆矩阵就得到原帧上的四边形 —— 对齐带旋转，
    故返回 4 个点而不是 bbox。纯调试用，任何异常都不许影响业务：失败返回 None。
    """
    try:
        m = affine_matrix
        if hasattr(m, "detach"):
            m = m.detach().to("cpu", torch.float32).numpy()
        m = np.asarray(m, dtype=np.float64).reshape(2, 3)
        inv = np.linalg.inv(np.vstack([m, np.array([0.0, 0.0, 1.0])]))
        x1, y1, x2, y2 = [float(v) for v in box]
        corners = np.array([[x1, y1, 1.0], [x2, y1, 1.0], [x2, y2, 1.0], [x1, y2, 1.0]])
        return (corners @ inv.T)[:, :2]
    except Exception as e:
        print("[weaveora] 调试画框：坐标反算失败（本帧退回旧画法）: %s" % e, flush=True)
        return None

logger = logging.get_logger(__name__)  # pylint: disable=invalid-name


class LipsyncPipeline(DiffusionPipeline):
    _optional_components = []

    def __init__(
        self,
        vae: AutoencoderKL,
        audio_encoder: Audio2Feature,
        unet: UNet3DConditionModel,
        scheduler: Union[
            DDIMScheduler,
            PNDMScheduler,
            LMSDiscreteScheduler,
            EulerDiscreteScheduler,
            EulerAncestralDiscreteScheduler,
            DPMSolverMultistepScheduler,
        ],
    ):
        super().__init__()

        if hasattr(scheduler.config, "steps_offset") and scheduler.config.steps_offset != 1:
            deprecation_message = (
                f"The configuration file of this scheduler: {scheduler} is outdated. `steps_offset`"
                f" should be set to 1 instead of {scheduler.config.steps_offset}. Please make sure "
                "to update the config accordingly as leaving `steps_offset` might led to incorrect results"
                " in future versions. If you have downloaded this checkpoint from the Hugging Face Hub,"
                " it would be very nice if you could open a Pull request for the `scheduler/scheduler_config.json`"
                " file"
            )
            deprecate("steps_offset!=1", "1.0.0", deprecation_message, standard_warn=False)
            new_config = dict(scheduler.config)
            new_config["steps_offset"] = 1
            scheduler._internal_dict = FrozenDict(new_config)

        if hasattr(scheduler.config, "clip_sample") and scheduler.config.clip_sample is True:
            deprecation_message = (
                f"The configuration file of this scheduler: {scheduler} has not set the configuration `clip_sample`."
                " `clip_sample` should be set to False in the configuration file. Please make sure to update the"
                " config accordingly as not setting `clip_sample` in the config might lead to incorrect results in"
                " future versions. If you have downloaded this checkpoint from the Hugging Face Hub, it would be very"
                " nice if you could open a Pull request for the `scheduler/scheduler_config.json` file"
            )
            deprecate("clip_sample not set", "1.0.0", deprecation_message, standard_warn=False)
            new_config = dict(scheduler.config)
            new_config["clip_sample"] = False
            scheduler._internal_dict = FrozenDict(new_config)

        is_unet_version_less_0_9_0 = hasattr(unet.config, "_diffusers_version") and version.parse(
            version.parse(unet.config._diffusers_version).base_version
        ) < version.parse("0.9.0.dev0")
        is_unet_sample_size_less_64 = (
            hasattr(unet.config, "sample_size") and unet.config.sample_size < 64
        )
        if is_unet_version_less_0_9_0 and is_unet_sample_size_less_64:
            deprecation_message = (
                "The configuration file of the unet has set the default `sample_size` to smaller than"
                " 64 which seems highly unlikely. If your checkpoint is a fine-tuned version of any of the"
                " following: \n- CompVis/stable-diffusion-v1-4 \n- CompVis/stable-diffusion-v1-3 \n-"
                " CompVis/stable-diffusion-v1-2 \n- CompVis/stable-diffusion-v1-1 \n- runwayml/stable-diffusion-v1-5"
                " \n- runwayml/stable-diffusion-inpainting \n you should change 'sample_size' to 64 in the"
                " configuration file. Please make sure to update the config accordingly as leaving `sample_size=32`"
                " in the config might lead to incorrect results in future versions. If you have downloaded this"
                " checkpoint from the Hugging Face Hub, it would be very nice if you could open a Pull request for"
                " the `unet/config.json` file"
            )
            deprecate("sample_size<64", "1.0.0", deprecation_message, standard_warn=False)
            new_config = dict(unet.config)
            new_config["sample_size"] = 64
            unet._internal_dict = FrozenDict(new_config)

        self.register_modules(
            vae=vae,
            audio_encoder=audio_encoder,
            unet=unet,
            scheduler=scheduler,
        )

        self.vae_scale_factor = 2 ** (len(self.vae.config.block_out_channels) - 1)

        self.set_progress_bar_config(desc="Steps")

    def enable_vae_slicing(self):
        self.vae.enable_slicing()

    def disable_vae_slicing(self):
        self.vae.disable_slicing()

    @property
    def _execution_device(self):
        if self.device != torch.device("meta") or not hasattr(self.unet, "_hf_hook"):
            return self.device
        for module in self.unet.modules():
            if (
                hasattr(module, "_hf_hook")
                and hasattr(module._hf_hook, "execution_device")
                and module._hf_hook.execution_device is not None
            ):
                return torch.device(module._hf_hook.execution_device)
        return self.device

    def decode_latents(self, latents):
        latents = latents / self.vae.config.scaling_factor + self.vae.config.shift_factor
        latents = rearrange(latents, "b c f h w -> (b f) c h w")
        decoded_latents = self.vae.decode(latents).sample
        return decoded_latents

    def prepare_extra_step_kwargs(self, generator, eta):
        # prepare extra kwargs for the scheduler step, since not all schedulers have the same signature
        # eta (η) is only used with the DDIMScheduler, it will be ignored for other schedulers.
        # eta corresponds to η in DDIM paper: https://arxiv.org/abs/2010.02502
        # and should be between [0, 1]

        accepts_eta = "eta" in set(inspect.signature(self.scheduler.step).parameters.keys())
        extra_step_kwargs = {}
        if accepts_eta:
            extra_step_kwargs["eta"] = eta

        # check if the scheduler accepts generator
        accepts_generator = "generator" in set(inspect.signature(self.scheduler.step).parameters.keys())
        if accepts_generator:
            extra_step_kwargs["generator"] = generator
        return extra_step_kwargs

    def check_inputs(self, height, width, callback_steps):
        assert height == width, "Height and width must be equal"

        if height % 8 != 0 or width % 8 != 0:
            raise ValueError(f"`height` and `width` have to be divisible by 8 but are {height} and {width}.")

        if (callback_steps is None) or (
            callback_steps is not None and (not isinstance(callback_steps, int) or callback_steps <= 0)
        ):
            raise ValueError(
                f"`callback_steps` has to be a positive integer but is {callback_steps} of type"
                f" {type(callback_steps)}."
            )

    def prepare_latents(self, batch_size, num_frames, num_channels_latents, height, width, dtype, device, generator):
        shape = (
            batch_size,
            num_channels_latents,
            1,
            height // self.vae_scale_factor,
            width // self.vae_scale_factor,
        )
        rand_device = "cpu" if device.type == "mps" else device
        latents = torch.randn(shape, generator=generator, device=rand_device, dtype=dtype).to(device)
        latents = latents.repeat(1, 1, num_frames, 1, 1)

        # scale the initial noise by the standard deviation required by the scheduler
        latents = latents * self.scheduler.init_noise_sigma
        return latents

    def prepare_mask_latents(
        self, mask, masked_image, height, width, dtype, device, generator, do_classifier_free_guidance
    ):
        # resize the mask to latents shape as we concatenate the mask to the latents
        # we do that before converting to dtype to avoid breaking in case we're using cpu_offload
        # and half precision
        mask = torch.nn.functional.interpolate(
            mask, size=(height // self.vae_scale_factor, width // self.vae_scale_factor)
        )
        masked_image = masked_image.to(device=device, dtype=dtype)

        # encode the mask image into latents space so we can concatenate it to the latents
        masked_image_latents = self.vae.encode(masked_image).latent_dist.sample(generator=generator)
        masked_image_latents = (masked_image_latents - self.vae.config.shift_factor) * self.vae.config.scaling_factor

        # aligning device to prevent device errors when concating it with the latent model input
        masked_image_latents = masked_image_latents.to(device=device, dtype=dtype)
        mask = mask.to(device=device, dtype=dtype)

        # assume batch size = 1
        mask = rearrange(mask, "f c h w -> 1 c f h w")
        masked_image_latents = rearrange(masked_image_latents, "f c h w -> 1 c f h w")

        mask = torch.cat([mask] * 2) if do_classifier_free_guidance else mask
        masked_image_latents = (
            torch.cat([masked_image_latents] * 2) if do_classifier_free_guidance else masked_image_latents
        )
        return mask, masked_image_latents

    def prepare_image_latents(self, images, device, dtype, generator, do_classifier_free_guidance):
        images = images.to(device=device, dtype=dtype)
        image_latents = self.vae.encode(images).latent_dist.sample(generator=generator)
        image_latents = (image_latents - self.vae.config.shift_factor) * self.vae.config.scaling_factor
        image_latents = rearrange(image_latents, "f c h w -> 1 c f h w")
        image_latents = torch.cat([image_latents] * 2) if do_classifier_free_guidance else image_latents

        return image_latents

    def set_progress_bar_config(self, **kwargs):
        if not hasattr(self, "_progress_bar_config"):
            self._progress_bar_config = {}
        self._progress_bar_config.update(kwargs)

    @staticmethod
    def paste_surrounding_pixels_back(decoded_latents, pixel_values, masks, device, weight_dtype):
        # Paste the surrounding pixels back, because we only want to change the mouth region
        pixel_values = pixel_values.to(device=device, dtype=weight_dtype)
        masks = masks.to(device=device, dtype=weight_dtype)
        combined_pixel_values = decoded_latents * masks + pixel_values * (1 - masks)
        return combined_pixel_values

    @staticmethod
    def _debug_mark(frame, box, driven, reason="", enabled=None, affine_matrix=None):
        """Weaveora 调试画框（WEAVEORA_DEBUG_BOX=1 时生效）。

        为什么要它：worker 跑在 API 服务器、节点跑在 GPU 服务器，我们看不到节点日志，
        “这一帧到底选了谁/有没有驱动”无法从远端确认。画上框+左上角色块后，产物自己就带答案：
        左上角色块 绿=驱动该帧、红=不驱动（保留原帧）；框=**原帧坐标系里**实际锁定/回贴的那张脸。

        ★ 2026-09-22 勘误（上段「贴回半幅」结论据此作废）：`box` 是**裁剪坐标系**的
        `[0,0,裁剪宽,裁剪高]`（锚在裁剪图左上角，只编码裁剪尺寸），拿它当原帧坐标画框
        = 绿框恒从画面左上角起画。要看脸到底在哪，必须用 `affine_matrix`（原帧→裁剪）的
        逆矩阵把裁剪矩形映射回原帧（见 `_crop_box_to_frame_quad`）。
        """
        if enabled is None:
            enabled = _DEBUG_BOX
        if not enabled:
            return frame
        f = np.ascontiguousarray(frame.copy())
        color = (0, 200, 0) if driven else (0, 0, 255)  # 绿=驱动，红=不驱动
        if box is not None:
            quad = _crop_box_to_frame_quad(box, affine_matrix) if affine_matrix is not None else None
            if quad is not None:
                cv2.polylines(f, [np.round(quad).astype(np.int32)], True, color, 2)
            else:
                # 无仿射矩阵（整段没检到脸 → box 是整帧）时退回旧画法，不影响判断
                x1, y1, x2, y2 = [int(v) for v in box]
                cv2.rectangle(f, (max(0, x1), max(0, y1)), (max(1, x2), max(1, y2)), color, 2)
        cv2.rectangle(f, (0, 0), (24, 24), color, -1)
        if not driven and reason:
            cv2.putText(f, reason[:12].encode("ascii", "ignore").decode(), (28, 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)
        return f

    @staticmethod
    def pixel_values_to_images(pixel_values: torch.Tensor):
        pixel_values = rearrange(pixel_values, "f c h w -> f h w c")
        pixel_values = (pixel_values / 2 + 0.5).clamp(0, 1)
        images = (pixel_values * 255).to(torch.uint8)
        images = images.cpu().numpy()
        return images

    def affine_transform_video(self, video_frames: np.ndarray):
        faces = []
        boxes = []
        affine_matrices = []
        driven = []  # Weaveora：逐帧「要不要驱动」——跟丢/脸太小/侧脸 的帧不驱动（保留原帧）
        print(f"Affine transforming {len(video_frames)} faces...")
        for frame in tqdm.tqdm(video_frames):
            face, box, affine_matrix = self.image_processor.affine_transform(frame)
            faces.append(face)
            boxes.append(box)
            affine_matrices.append(affine_matrix)
            driven.append(bool(self.image_processor.last_driven))

        faces = torch.stack(faces)
        n_drive = sum(1 for d in driven if d)
        print(f"Weaveora 轨迹锁人：{n_drive}/{len(driven)} 帧驱动"
              f"（不驱动的帧保留原画面），选脸统计：{self.image_processor.face_detector.stats_line()}")
        return faces, boxes, affine_matrices, driven

    def restore_video(self, faces: torch.Tensor, video_frames: np.ndarray, boxes: list, affine_matrices: list,
                      driven: list = None):
        video_frames = video_frames[: len(faces)]
        out_frames = []
        print(f"Restoring {len(faces)} faces...")
        for index, face in enumerate(tqdm.tqdm(faces)):
            is_driven = True if driven is None else (index < len(driven) and bool(driven[index]))
            if not is_driven:
                # Weaveora：不驱动 → 输出原帧（绝不用别人的脸顶替）
                out_frames.append(self._debug_mark(video_frames[index], None, False, "skip", self._debug_box))
                continue
            x1, y1, x2, y2 = boxes[index]
            height = int(y2 - y1)
            width = int(x2 - x1)
            face = torchvision.transforms.functional.resize(
                face, size=(height, width), interpolation=transforms.InterpolationMode.BICUBIC, antialias=True
            )
            out_frame = self.image_processor.restorer.restore_img(video_frames[index], face, affine_matrices[index])
            out_frames.append(self._debug_mark(out_frame, boxes[index], True, "", self._debug_box,
                                               affine_matrix=affine_matrices[index]))
        return np.stack(out_frames, axis=0)

    def loop_video(self, whisper_chunks: list, video_frames: np.ndarray):
        # If the audio is longer than the video, we need to loop the video
        if len(whisper_chunks) > len(video_frames):
            faces, boxes, affine_matrices, driven = self.affine_transform_video(video_frames)
            num_loops = math.ceil(len(whisper_chunks) / len(video_frames))
            loop_video_frames = []
            loop_faces = []
            loop_boxes = []
            loop_affine_matrices = []
            loop_driven = []
            for i in range(num_loops):
                if i % 2 == 0:
                    loop_video_frames.append(video_frames)
                    loop_faces.append(faces)
                    loop_boxes += boxes
                    loop_affine_matrices += affine_matrices
                    loop_driven += driven
                else:
                    loop_video_frames.append(video_frames[::-1])
                    loop_faces.append(faces.flip(0))
                    loop_boxes += boxes[::-1]
                    loop_affine_matrices += affine_matrices[::-1]
                    loop_driven += driven[::-1]

            video_frames = np.concatenate(loop_video_frames, axis=0)[: len(whisper_chunks)]
            faces = torch.cat(loop_faces, dim=0)[: len(whisper_chunks)]
            boxes = loop_boxes[: len(whisper_chunks)]
            affine_matrices = loop_affine_matrices[: len(whisper_chunks)]
            driven = loop_driven[: len(whisper_chunks)]
        else:
            video_frames = video_frames[: len(whisper_chunks)]
            faces, boxes, affine_matrices, driven = self.affine_transform_video(video_frames)

        return video_frames, faces, boxes, affine_matrices, driven

    @torch.no_grad()
    def __call__(
        self,
        video_path: str,
        audio_path: str,
        video_out_path: str,
        video_mask_path: str = None,
        num_frames: int = 16,
        video_fps: int = 25,
        audio_sample_rate: int = 16000,
        height: Optional[int] = None,
        width: Optional[int] = None,
        num_inference_steps: int = 20,
        guidance_scale: float = 1.5,
        weight_dtype: Optional[torch.dtype] = torch.float16,
        eta: float = 0.0,
        mask_image_path: str = _DEFAULT_MASK_PATH,
        # Weaveora：说话人定妆照的人脸特征（512 维）—— 多人同框时靠它「锁人」，
        # 否则管线逐帧取“面积最大的脸”，多张脸大小中途互换时会突然换人、画面坏掉。
        target_embedding=None,
        # Weaveora：用户**点选**的人脸位置（归一化 0–1）——比人脸识别可靠（风格化素材上
        # 识别区分度会崩）；两者同时给时点选优先。
        target_point=None,
        # Weaveora：本次请求是否把「选中的脸 + 是否驱动」画框进产物（供远端核对选了谁）
        debug_box: bool = False,
        generator: Optional[Union[torch.Generator, List[torch.Generator]]] = None,
        callback: Optional[Callable[[int, int, torch.FloatTensor], None]] = None,
        callback_steps: Optional[int] = 1,
        **kwargs,
    ):
        is_train = self.unet.training
        self.unet.eval()
        # Weaveora：调试画框开关（按请求传入，环境变量 WEAVEORA_DEBUG_BOX=1 作全局默认）
        self._debug_box = bool(debug_box) or _DEBUG_BOX

        check_ffmpeg_installed()

        # 0. Define call parameters
        batch_size = 1
        device = self._execution_device
        mask_image = load_fixed_mask(height, mask_image_path)
        self.image_processor = ImageProcessor(height, device="cuda", mask_image=mask_image,
                                              target_embedding=target_embedding,
                                              target_point=target_point)
        self.set_progress_bar_config(desc=f"Sample frames: {num_frames}")

        # 1. Default height and width to unet
        height = height or self.unet.config.sample_size * self.vae_scale_factor
        width = width or self.unet.config.sample_size * self.vae_scale_factor

        # 2. Check inputs
        self.check_inputs(height, width, callback_steps)

        # here `guidance_scale` is defined analog to the guidance weight `w` of equation (2)
        # of the Imagen paper: https://arxiv.org/pdf/2205.11487.pdf . `guidance_scale = 1`
        # corresponds to doing no classifier free guidance.
        do_classifier_free_guidance = guidance_scale > 1.0

        # 3. set timesteps
        self.scheduler.set_timesteps(num_inference_steps, device=device)
        timesteps = self.scheduler.timesteps

        # 4. Prepare extra step kwargs.
        extra_step_kwargs = self.prepare_extra_step_kwargs(generator, eta)

        whisper_feature = self.audio_encoder.audio2feat(audio_path)
        whisper_chunks = self.audio_encoder.feature2chunks(feature_array=whisper_feature, fps=video_fps)

        audio_samples = read_audio(audio_path)
        # ★★ Weaveora 2026-09-22（关键修复）：**不许再重采样帧率**。
        #   util.read_video() 的 change_fps 默认 True，里面是 `ffmpeg -i in -r 25` —— 硬编码 25fps！
        #   而本机上游（节点/worker）已按**源片 fps**（本片 32）写好临时视频、并按同一 fps 做
        #   whisper 分块与写回（feature2chunks(fps=video_fps) / write_video(fps=video_fps)）。
        #   于是「解码 25fps + 生成 32fps」两套时基打架，实测后果（第1镜 V80 资产，逐帧对齐量出）：
        #     · 节点只拿到 39/49 帧（= 计划帧数 × 25/32）⇒ 段内画面被拉慢 1.28×、嘴比声音慢；
        #     · 段内内容相对时间轴渐进漂移（输出帧 k 对应源帧 k+4…+9）；
        #     · 段首/段尾硬跳（实测全局帧差 9.71 / 11.90，是同一帧处源片自身运动的 8.1× / 11.6×）
        #       ⇒ 用户看到的「嘴部乱码 + 画面卡顿」。
        #   这里读原样帧率（change_fps=False）；临时视频是节点按 video_fps 写的，两者天然一致。
        video_frames = read_video(video_path, change_fps=False, use_decord=False)
        # 打一行「到底吃进去多少帧 / 按什么 fps 分块」——这两个值不一致就是上面那类时基 bug：
        _fr = len(video_frames)
        print("[weaveora] 帧率口径：解码 %d 帧（change_fps=False，视频原始 fps）｜"
              "whisper 分块 fps=%.2f → %d 块（差 %+d 帧）"
              % (_fr, float(video_fps), len(whisper_chunks), len(whisper_chunks) - _fr), flush=True)

        video_frames, faces, boxes, affine_matrices, driven = self.loop_video(whisper_chunks, video_frames)

        synced_video_frames = []

        num_channels_latents = self.vae.config.latent_channels

        # Prepare latent variables
        all_latents = self.prepare_latents(
            batch_size,
            len(whisper_chunks),
            num_channels_latents,
            height,
            width,
            weight_dtype,
            device,
            generator,
        )

        num_inferences = math.ceil(len(whisper_chunks) / num_frames)
        for i in tqdm.tqdm(range(num_inferences), desc="Doing inference..."):
            if self.unet.add_audio_layer:
                audio_embeds = torch.stack(whisper_chunks[i * num_frames : (i + 1) * num_frames])
                audio_embeds = audio_embeds.to(device, dtype=weight_dtype)
                if do_classifier_free_guidance:
                    null_audio_embeds = torch.zeros_like(audio_embeds)
                    audio_embeds = torch.cat([null_audio_embeds, audio_embeds])
            else:
                audio_embeds = None
            inference_faces = faces[i * num_frames : (i + 1) * num_frames]
            latents = all_latents[:, :, i * num_frames : (i + 1) * num_frames]
            ref_pixel_values, masked_pixel_values, masks = self.image_processor.prepare_masks_and_masked_images(
                inference_faces, affine_transform=False,
                # Weaveora：这一批里哪些帧要驱动（跟丢/太小/侧脸的帧用全 1 遮罩 = 保留原帧）
                driven=driven[i * num_frames: (i + 1) * num_frames],
            )

            # 7. Prepare mask latent variables
            mask_latents, masked_image_latents = self.prepare_mask_latents(
                masks,
                masked_pixel_values,
                height,
                width,
                weight_dtype,
                device,
                generator,
                do_classifier_free_guidance,
            )

            # 8. Prepare image latents
            ref_latents = self.prepare_image_latents(
                ref_pixel_values,
                device,
                weight_dtype,
                generator,
                do_classifier_free_guidance,
            )

            # 9. Denoising loop
            num_warmup_steps = len(timesteps) - num_inference_steps * self.scheduler.order
            with self.progress_bar(total=num_inference_steps) as progress_bar:
                for j, t in enumerate(timesteps):
                    # expand the latents if we are doing classifier free guidance
                    unet_input = torch.cat([latents] * 2) if do_classifier_free_guidance else latents

                    unet_input = self.scheduler.scale_model_input(unet_input, t)

                    # concat latents, mask, masked_image_latents in the channel dimension
                    unet_input = torch.cat(
                        [unet_input, mask_latents, masked_image_latents, ref_latents], dim=1
                    )

                    # predict the noise residual
                    noise_pred = self.unet(
                        unet_input, t, encoder_hidden_states=audio_embeds
                    ).sample

                    # perform guidance
                    if do_classifier_free_guidance:
                        noise_pred_uncond, noise_pred_audio = noise_pred.chunk(2)
                        noise_pred = noise_pred_uncond + guidance_scale * (noise_pred_audio - noise_pred_uncond)

                    # compute the previous noisy sample x_t -> x_t-1
                    latents = self.scheduler.step(noise_pred, t, latents, **extra_step_kwargs).prev_sample

                    # call the callback, if provided
                    if j == len(timesteps) - 1 or ((j + 1) > num_warmup_steps and (j + 1) % self.scheduler.order == 0):
                        progress_bar.update()
                        if callback is not None and j % callback_steps == 0:
                            callback(j, t, latents)

            # Recover the pixel values
            decoded_latents = self.decode_latents(latents)
            # Weaveora：贴回用「收紧+羽化」的遮罩（只改嘴+下巴，保护脸颊与邻脸）
            paste_masks = self.image_processor.last_paste_masks
            decoded_latents = self.paste_surrounding_pixels_back(
                decoded_latents, ref_pixel_values,
                1 - (masks if paste_masks is None else paste_masks),
                device, weight_dtype
            )
            synced_video_frames.append(decoded_latents)

        synced_video_frames = self.restore_video(torch.cat(synced_video_frames), video_frames, boxes,
                                                 affine_matrices, driven)

        audio_samples_remain_length = int(synced_video_frames.shape[0] / video_fps * audio_sample_rate)
        audio_samples = audio_samples[:audio_samples_remain_length].cpu().numpy()

        if is_train:
            self.unet.train()

        temp_dir = "temp"
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir)
        os.makedirs(temp_dir, exist_ok=True)

        # Weaveora：写中间片时用「源片帧率」，与口型驱动帧数一致（播放帧率=生成帧率=源片 fps）
        write_video(os.path.join(temp_dir, "video.mp4"), synced_video_frames, fps=int(video_fps))

        sf.write(os.path.join(temp_dir, "audio.wav"), audio_samples, audio_sample_rate)

        command = f"ffmpeg -y -loglevel error -nostdin -i {os.path.join(temp_dir, 'video.mp4')} -i {os.path.join(temp_dir, 'audio.wav')} -c:v libx264 -crf 18 -c:a aac -q:v 0 -q:a 0 {video_out_path}"
        subprocess.run(command, shell=True)
