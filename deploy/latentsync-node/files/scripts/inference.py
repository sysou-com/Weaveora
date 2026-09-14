# At the top of inference.py, make sure you have these imports:
import argparse
import os
import numpy as np
from omegaconf import OmegaConf
import torch
from diffusers import AutoencoderKL, DDIMScheduler
from latentsync.models.unet import UNet3DConditionModel
from latentsync.pipelines.lipsync_pipeline import LipsyncPipeline
from accelerate.utils import set_seed
from latentsync.whisper.audio2feature import Audio2Feature
from DeepCache import DeepCacheSDHelper


def main(config, args):
    if not os.path.exists(args.video_path):
        raise RuntimeError(f"Video path '{args.video_path}' not found")
    if not os.path.exists(args.audio_path):
        raise RuntimeError(f"Audio path '{args.audio_path}' not found")

    # Check if the GPU supports float16
    is_fp16_supported = torch.cuda.is_available() and torch.cuda.get_device_capability()[0] > 7
    dtype = torch.float16 if is_fp16_supported else torch.float32

    print(f"Input video path: {args.video_path}")
    print(f"Input audio path: {args.audio_path}")
    print(f"Loaded checkpoint path: {args.inference_ckpt_path}")

    # FIXED: Create DDIMScheduler directly (NO HUGGINGFACE)
    scheduler = DDIMScheduler(
        beta_end=0.012,
        beta_schedule="scaled_linear",
        beta_start=0.00085,
        clip_sample=False,
        num_train_timesteps=1000,
        prediction_type="epsilon",
        set_alpha_to_one=False,
        steps_offset=1
    )
    print("[ok] Created DDIMScheduler directly (fully offline)")

    if config.model.cross_attention_dim == 768:
        whisper_model_name = "small"
    elif config.model.cross_attention_dim == 384:
        whisper_model_name = "tiny"
    else:
        raise NotImplementedError("cross_attention_dim must be 768 or 384")

    audio_encoder = Audio2Feature(
        model_path=whisper_model_name,
        device="cuda",
        num_frames=config.data.num_frames,
        audio_feat_length=config.data.audio_feat_length,
    )

    # FIXED: Load VAE locally with proper path resolution
    # Get the base directory (where the extension is located)
    if hasattr(args, 'extension_dir'):
        base_dir = args.extension_dir
    else:
        # Fallback: try to determine from script location
        script_dir = os.path.dirname(os.path.abspath(__file__))
        base_dir = os.path.dirname(script_dir)  # Go up one level from scripts/ to extension root
    
    # Try multiple VAE locations in order of preference
    vae_locations = [
        # New vae folder structure
        os.path.join(base_dir, "checkpoints", "vae", "sd-vae-ft-mse.safetensors"),
        os.path.join(base_dir, "checkpoints", "vae"),  # Directory with config.json
        # Original locations
        os.path.join(base_dir, "checkpoints", "sd-vae-ft-mse.safetensors"),
        os.path.join(base_dir, "checkpoints", "sd-vae-ft-mse"),
    ]
    
    vae = None
    for vae_path in vae_locations:
        if os.path.exists(vae_path):
            try:
                if vae_path.endswith('.safetensors'):
                    print(f"Attempting to load VAE from safetensors file: {vae_path}")
                    vae = AutoencoderKL.from_single_file(vae_path, torch_dtype=dtype)
                elif os.path.isdir(vae_path):
                    print(f"Attempting to load VAE from directory: {vae_path}")
                    vae = AutoencoderKL.from_pretrained(vae_path, torch_dtype=dtype, local_files_only=True)
                
                if vae is not None:
                    print(f"[ok] Successfully loaded VAE from: {vae_path}")
                    break
            except Exception as e:
                print(f"Failed to load VAE from {vae_path}: {str(e)}")
                vae = None  # Reset vae to None if loading failed
                continue
    
    if vae is None:
        print("Local VAE not found in any location, creating VAE with standard configuration")
        print(f"Searched locations: {vae_locations}")
        # Create VAE with standard SD configuration if local model doesn't exist
        vae = AutoencoderKL(
            in_channels=3,
            out_channels=3,
            down_block_types=["DownEncoderBlock2D", "DownEncoderBlock2D", "DownEncoderBlock2D", "DownEncoderBlock2D"],
            up_block_types=["UpDecoderBlock2D", "UpDecoderBlock2D", "UpDecoderBlock2D", "UpDecoderBlock2D"],
            block_out_channels=[128, 256, 512, 512],
            layers_per_block=2,
            act_fn="silu",
            latent_channels=4,
            norm_num_groups=32,
            sample_size=512,
        ).to(dtype=dtype)
        print("[warn] Using default VAE configuration - consider downloading VAE model locally for better results")

    # Set VAE configuration
    vae.config.scaling_factor = 0.18215
    vae.config.shift_factor = 0

    # Rest of the function continues as before...
    unet, _ = UNet3DConditionModel.from_pretrained(
        OmegaConf.to_container(config.model),
        args.inference_ckpt_path,
        device="cpu",
    )

    unet = unet.to(dtype=dtype)

    pipeline = LipsyncPipeline(
        vae=vae,
        audio_encoder=audio_encoder,
        unet=unet,
        scheduler=scheduler,
    ).to("cuda")

    # use DeepCache
    helper = DeepCacheSDHelper(pipe=pipeline)
    helper.set_params(cache_interval=3, cache_branch_id=0)
    helper.enable()

    # Weaveora 2026-09-13: slice VAE encode/decode to cut peak VRAM on 8 GiB cards
    # (upstream never enabled it; without it 512x512x16f OOMs on a 3070 Ti).
    try:
        pipeline.enable_vae_slicing()
        print("[ok] VAE slicing enabled")
    except Exception as _e:
        print(f"[warn] enable_vae_slicing failed: {_e}")

    if args.seed != -1:
        set_seed(args.seed)
    else:
        torch.seed()

    print(f"Initial seed: {torch.initial_seed()}")

    target_embedding = None
    target_point = None
    target_debug_box = False
    _tep = getattr(args, "target_embedding_path", "") or ""
    if _tep:
        try:
            import json as _json
            # Weaveora：支持**内联 JSON**（worker 与 ComfyUI 不在同一台机时，文件路径在节点侧不存在）。
            #   字符串以 { 开头 = 直接是规格 JSON；否则按文件路径读（向后兼容）。
            _stripped = _tep.strip()
            if _stripped.startswith("{"):
                _spec = _json.loads(_stripped)
            else:
                with open(_tep, "r", encoding="utf-8") as _fh:
                    _spec = _json.load(_fh)
            # 兼容两种格式：
            #   [512 个 float]                     = 纯人脸特征（早期）
            #   {"embedding": [...], "point": [x,y]} = 锁定规格（点选优先）
            if isinstance(_spec, dict):
                if _spec.get("embedding"):
                    target_embedding = np.asarray(_spec["embedding"], dtype=np.float32).reshape(-1)
                if _spec.get("point"):
                    target_point = (float(_spec["point"][0]), float(_spec["point"][1]))
                # Weaveora：按请求开调试画框（产物上直接能看出「选了谁 / 有没有驱动」）
                target_debug_box = bool(_spec.get("debugBox"))
            elif _spec:
                target_embedding = np.asarray(_spec, dtype=np.float32).reshape(-1)
            print("[weaveora] 锁定规格：point=%s embedding=%s"
                  % (target_point, (target_embedding.size if target_embedding is not None else None)))
        except Exception as _e:
            print("[weaveora] 锁定规格加载失败，退回「最大脸」策略: %s" % _e)
            target_embedding = None
            target_point = None

    pipeline(
        video_path=args.video_path,
        audio_path=args.audio_path,
        video_out_path=args.video_out_path,
        video_mask_path=args.video_out_path.replace(".mp4", "_mask.mp4"),
        num_frames=config.data.num_frames,
        num_inference_steps=args.inference_steps,
        guidance_scale=args.guidance_scale,
        weight_dtype=dtype,
        width=config.data.resolution,
        height=config.data.resolution,
        mask_image_path=config.data.mask_image_path,
        # Weaveora 2026-09-13：生成帧率必须与播放帧率一致。
        # pipeline 用它把「配音时长」换算成帧数（frames ≈ 配音秒数 × video_fps）
        # 并同步截取音频长度；不传则默认 25，会导致非 25fps 的镜成片时长不对。
        video_fps=int(round(getattr(args, "video_fps", 25) or 25)),
        # Weaveora：多人同框时锁住「说话人」那张脸（点选优先，其次定妆照识别）
        target_embedding=target_embedding,
        target_point=target_point,
        # Weaveora：调试画框（逐帧标出锁定的脸 + 是否驱动）
        debug_box=target_debug_box,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--unet_config_path", type=str, default="configs/unet.yaml")
    parser.add_argument("--inference_ckpt_path", type=str, required=True)
    parser.add_argument("--video_path", type=str, required=True)
    parser.add_argument("--audio_path", type=str, required=True)
    parser.add_argument("--video_out_path", type=str, required=True)
    parser.add_argument("--inference_steps", type=int, default=20)
    parser.add_argument("--guidance_scale", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=1247)
    args = parser.parse_args()

    config = OmegaConf.load(args.unet_config_path)

    main(config, args)