import os
import tempfile
import torchaudio
import uuid
import sys
import shutil
from collections.abc import Mapping
from datetime import datetime

# Function to find ComfyUI directories
def get_comfyui_temp_dir():
    """Dynamically find the ComfyUI temp directory"""
    # First check using folder_paths if available
    try:
        import folder_paths
        comfy_dir = os.path.dirname(os.path.dirname(os.path.abspath(folder_paths.__file__)))
        temp_dir = os.path.join(comfy_dir, "temp")
        return temp_dir
    except:
        pass
    
    # Try to locate based on current script location
    try:
        # This script is likely in a ComfyUI custom nodes directory
        current_dir = os.path.dirname(os.path.abspath(__file__))
        # Go up until we find the ComfyUI directory
        potential_dir = current_dir
        for _ in range(5):  # Limit to 5 levels up
            if os.path.exists(os.path.join(potential_dir, "comfy.py")):
                return os.path.join(potential_dir, "temp")
            potential_dir = os.path.dirname(potential_dir)
    except:
        pass
    
    # Return None if we can't find it
    return None

# Function to clean up any ComfyUI temp directories
def cleanup_comfyui_temp_directories():
    """Find and clean up any ComfyUI temp directories"""
    comfyui_temp = get_comfyui_temp_dir()
    if not comfyui_temp:
        print("Could not locate ComfyUI temp directory")
        return
    
    comfyui_base = os.path.dirname(comfyui_temp)
    
    # Check for the main temp directory
    if os.path.exists(comfyui_temp):
        try:
            shutil.rmtree(comfyui_temp)
            print(f"Removed ComfyUI temp directory: {comfyui_temp}")
        except Exception as e:
            print(f"Could not remove {comfyui_temp}: {str(e)}")
            # If we can't remove it, try to rename it
            try:
                backup_name = f"{comfyui_temp}_backup_{uuid.uuid4().hex[:8]}"
                os.rename(comfyui_temp, backup_name)
                print(f"Renamed {comfyui_temp} to {backup_name}")
            except:
                pass
    
    # Find and clean up any backup temp directories
    try:
        all_directories = [d for d in os.listdir(comfyui_base) if os.path.isdir(os.path.join(comfyui_base, d))]
        for dirname in all_directories:
            if dirname.startswith("temp_backup_"):
                backup_path = os.path.join(comfyui_base, dirname)
                try:
                    shutil.rmtree(backup_path)
                    print(f"Removed backup temp directory: {backup_path}")
                except Exception as e:
                    print(f"Could not remove backup dir {backup_path}: {str(e)}")
    except Exception as e:
        print(f"Error cleaning up temp directories: {str(e)}")

# Create a module-level function to set up system-wide temp directory
def init_temp_directories():
    """Initialize global temporary directory settings"""
    # First clean up any existing temp directories
    cleanup_comfyui_temp_directories()
    
    # Generate a unique base directory for this module
    system_temp = tempfile.gettempdir()
    unique_id = str(uuid.uuid4())[:8]
    temp_base_path = os.path.join(system_temp, f"latentsync_{unique_id}")
    os.makedirs(temp_base_path, exist_ok=True)
    
    # Override environment variables that control temp directories
    os.environ['TMPDIR'] = temp_base_path
    os.environ['TEMP'] = temp_base_path
    os.environ['TMP'] = temp_base_path
    
    # Force Python's tempfile module to use our directory
    tempfile.tempdir = temp_base_path
    
    # Final check for ComfyUI temp directory
    comfyui_temp = get_comfyui_temp_dir()
    if comfyui_temp and os.path.exists(comfyui_temp):
        try:
            shutil.rmtree(comfyui_temp)
            print(f"Removed ComfyUI temp directory: {comfyui_temp}")
        except Exception as e:
            print(f"Could not remove {comfyui_temp}, trying to rename: {str(e)}")
            try:
                backup_name = f"{comfyui_temp}_backup_{unique_id}"
                os.rename(comfyui_temp, backup_name)
                print(f"Renamed {comfyui_temp} to {backup_name}")
                # Try to remove the renamed directory as well
                try:
                    shutil.rmtree(backup_name)
                    print(f"Removed renamed temp directory: {backup_name}")
                except:
                    pass
            except:
                print(f"Failed to rename {comfyui_temp}")
    
    print(f"Set up system temp directory: {temp_base_path}")
    return temp_base_path

# Function to clean up everything when the module exits
def module_cleanup():
    """Clean up all resources when the module is unloaded"""
    global MODULE_TEMP_DIR
    
    # Clean up our module temp directory
    if MODULE_TEMP_DIR and os.path.exists(MODULE_TEMP_DIR):
        try:
            shutil.rmtree(MODULE_TEMP_DIR, ignore_errors=True)
            print(f"Cleaned up module temp directory: {MODULE_TEMP_DIR}")
        except:
            pass
    
    # Do a final sweep for any ComfyUI temp directories
    cleanup_comfyui_temp_directories()

# Call this before anything else
MODULE_TEMP_DIR = init_temp_directories()

# Register the cleanup handler to run when Python exits
import atexit
atexit.register(module_cleanup)

# Now import regular dependencies
import math
import torch
import random
import torchaudio
import folder_paths
import numpy as np
import platform
import subprocess
import importlib.util
import importlib.machinery
import argparse
from omegaconf import OmegaConf
from PIL import Image
from decimal import Decimal, ROUND_UP
import requests

# ══ Weaveora 节点版本与能力自检（2026-09-14）══════════════════════════════════
# 为什么要它：worker 跑在 **API 服务器**、本节点跑在 **GPU 服务器**，两边代码靠手工复制同步。
# 一旦 GPU 机上是旧副本（曾实测：旧 inference.py 不认内联规格 → 退回「取最大脸」→ 两者都驱动
# 同一张脸、画面被毁），从 worker 侧完全看不出异常，只会表现为「效果不对」。
# 所以：节点把版本 + 支持的能力暴露成 HTTP 接口 `/weaveora/version`，worker 跑对口型前先比对，
# 缺能力就直接失败并给出修复指引（而不是静默降级）。
# 改本文件/本仓库任何节点侧文件后，**必须同步 bump 这里的版本号**，并在
# docs/lipsync-setup.md 第九节登记、用 deploy/latentsync-node/apply.sh 重新打补丁。
WEAVEORA_NODE_VERSION = "2026-09-22.1"
WEAVEORA_NODE_FEATURES = [
    "point_lock",      # 支持用户点选人脸（faceHints）
    "inline_spec",     # 锁定规格可用**内联 JSON**传入（跨机不用传文件）
    "track_lock",      # 轨迹锁人：点选只播种，逐帧按轨迹跟住，绝不换人
    "quality_gate",    # 质量闸门：跟丢/脸太小/侧脸 → 该帧不驱动（保留原帧）
    "paste_mask",      # 贴回遮罩收紧到嘴+下巴（保护脸颊与邻脸）
    "fps_pin",         # 帧率：生成=播放=源片 fps
    "debug_box",       # WEAVEORA_DEBUG_BOX=1 → 把选中的脸画框进产物
    "debug_box_frame_coords",  # 调试框画在**原帧坐标**（仿射逆变换），不再锚在左上角
]
_WEAVEORA_VERSION_REGISTERED = [False]


def weaveora_version_payload():
    return {
        "version": WEAVEORA_NODE_VERSION,
        "features": list(WEAVEORA_NODE_FEATURES),
        "node_dir": os.path.dirname(os.path.abspath(__file__)),
        "pid": os.getpid(),
    }


def register_weaveora_version_route():
    """把版本接口挂到 ComfyUI 的 aiohttp 路由上（幂等，失败不拦启动）。"""
    if _WEAVEORA_VERSION_REGISTERED[0]:
        return True
    try:
        from aiohttp import web
        from server import PromptServer

        routes = getattr(PromptServer.instance, "routes", None)
        if routes is None:
            return False

        @routes.get("/weaveora/version")
        async def _weaveora_version(_request):  # noqa: ANN001
            return web.json_response(weaveora_version_payload())

        _WEAVEORA_VERSION_REGISTERED[0] = True
        print("[weaveora] 节点版本接口已就绪：/weaveora/version → %s" % WEAVEORA_NODE_VERSION)
        return True
    except Exception as _e:  # 独立跑脚本（非 ComfyUI）时会到这
        print("[weaveora] 版本接口注册跳过（非 ComfyUI 环境？）: %s" % _e)
        return False


register_weaveora_version_route()
print("[weaveora] LatentSync 节点补丁版本 %s，能力：%s"
      % (WEAVEORA_NODE_VERSION, ",".join(WEAVEORA_NODE_FEATURES)))

# Modify folder_paths module to use our temp directory
if hasattr(folder_paths, "get_temp_directory"):
    original_get_temp = folder_paths.get_temp_directory
    folder_paths.get_temp_directory = lambda: MODULE_TEMP_DIR
else:
    # Add the function if it doesn't exist
    setattr(folder_paths, 'get_temp_directory', lambda: MODULE_TEMP_DIR)

def import_inference_script(script_path):
    """Import a Python file as a module using its file path."""
    if not os.path.exists(script_path):
        raise ImportError(f"Script not found: {script_path}")

    module_name = "latentsync_inference"
    spec = importlib.util.spec_from_file_location(module_name, script_path)
    if spec is None:
        raise ImportError(f"Failed to create module spec for {script_path}")

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module

    try:
        spec.loader.exec_module(module)
    except Exception as e:
        del sys.modules[module_name]
        raise ImportError(f"Failed to execute module: {str(e)}")

    return module

def check_ffmpeg():
    try:
        if platform.system() == "Windows":
            # Check if ffmpeg exists in PATH
            ffmpeg_path = shutil.which("ffmpeg.exe")
            if ffmpeg_path is None:
                # Look for ffmpeg in common locations
                possible_paths = [
                    os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "ffmpeg", "bin"),
                    os.path.join(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)"), "ffmpeg", "bin"),
                    os.path.join(os.path.dirname(os.path.abspath(__file__)), "ffmpeg", "bin"),
                ]
                for path in possible_paths:
                    if os.path.exists(os.path.join(path, "ffmpeg.exe")):
                        # Add to PATH
                        os.environ["PATH"] = path + os.pathsep + os.environ.get("PATH", "")
                        return True
                print("FFmpeg not found. Please install FFmpeg and add it to PATH")
                return False
            return True
        else:
            subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
            return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("FFmpeg not found. Please install FFmpeg")
        return False

def check_and_install_dependencies():
    if not check_ffmpeg():
        raise RuntimeError("FFmpeg is required but not found")

    required_packages = [
        'omegaconf',
        'pytorch_lightning',
        'transformers',
        'accelerate',
        'huggingface_hub',
        'einops',
        'diffusers',
        'ffmpeg'  # PyPI 包名是 ffmpeg-python，但 import 名是 ffmpeg；写错会每次实例化都跑 pip
    ]

    # Create a flag file to remember we've already installed packages
    user_home = os.path.expanduser("~")
    dependencies_installed_flag = os.path.join(user_home, ".latentsync16_dependencies_installed")
    
    # Skip installation if we've already done it before
    if os.path.exists(dependencies_installed_flag):
        print("Dependencies already installed from previous run")
        return
        
    def is_package_installed(package_name):
        return importlib.util.find_spec(package_name) is not None

    def install_package(package):
        python_exe = sys.executable
        try:
            subprocess.check_call([python_exe, '-m', 'pip', 'install', package],
                                    stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE)
            print(f"Successfully installed {package}")
        except subprocess.CalledProcessError as e:
            print(f"Error installing {package}: {str(e)}")
            raise RuntimeError(f"Failed to install required package: {package}")

    missing_packages = []
    for package in required_packages:
        if not is_package_installed(package):
            missing_packages.append(package)
    
    if missing_packages:
        print(f"Installing missing dependencies: {', '.join(missing_packages)}")
        for package in missing_packages:
            try:
                install_package(package)
            except Exception as e:
                print(f"Warning: Failed to install {package}: {str(e)}")
                raise
    else:
        print("All dependencies are already installed")
    
    # Create flag file to remember we've installed packages
    try:
        with open(dependencies_installed_flag, "w") as f:
            f.write("Dependencies installed on " + str(datetime.now()))
        print("Recorded dependencies installation status")
    except:
        print("Failed to create dependencies flag file - might reinstall next time")

def normalize_path(path):
    """Normalize path to handle spaces and special characters"""
    return os.path.normpath(path).replace('\\', '/')

def get_ext_dir(subpath=None, mkdir=False):
    """Get extension directory path, optionally with a subpath"""
    # Get the directory containing this script
    dir = os.path.dirname(os.path.abspath(__file__))
    
    # Special case for temp directories
    if subpath and ("temp" in subpath.lower() or "tmp" in subpath.lower()):
        # Use our global temp directory instead
        global MODULE_TEMP_DIR
        sub_temp = os.path.join(MODULE_TEMP_DIR, subpath)
        if mkdir and not os.path.exists(sub_temp):
            os.makedirs(sub_temp, exist_ok=True)
        return sub_temp
    
    if subpath is not None:
        dir = os.path.join(dir, subpath)

    if mkdir and not os.path.exists(dir):
        os.makedirs(dir, exist_ok=True)
    
    return dir

def download_model(url, save_path):
    """Download a model from a URL and save it to the specified path."""
    os.makedirs(os.path.dirname(save_path), exist_ok=True)
    # NOTE (Weaveora 2026-09-13): always pass a timeout. A bare requests.get on an
    # unreachable host (huggingface.co from CN) hangs forever and blocks the whole
    # ComfyUI session -- see Weaveora.md section 0.2.
    response = requests.get(url, stream=True, timeout=(10, 60))
    response.raise_for_status()
    with open(save_path, "wb") as f:
        for chunk in response.iter_content(chunk_size=8192):
            f.write(chunk)

def pre_download_models():
    """Pre-download all required models and cache them properly.

    NOTE (Weaveora 2026-09-13): the original implementation unconditionally pulled
    s3fd weights from huggingface.co on first node instantiation. huggingface.co is not
    reachable from CN and the call had no timeout, so it hung the session forever
    (Weaveora.md section 0.2). This file is NOT used by the inference pipeline at all,
    so we skip it by default and require an explicit opt-in to download it.
    """
    models = {
        "s3fd-e19a316812.pth": "https://huggingface.co/vinthony/SadTalker/resolve/main/hub/checkpoints/s3fd-619a316812.pth",
        # Add other models here
    }

    # Use a persistent location for model cache instead of temporary directory
    # This ensures models are downloaded only once across all runs
    user_home = os.path.expanduser("~")
    persistent_cache_dir = os.path.join(user_home, ".latentsync16_models")
    os.makedirs(persistent_cache_dir, exist_ok=True)

    if os.environ.get("WEAVEORA_ALLOW_S3FD_DOWNLOAD") != "1":
        print("[weaveora] skipped s3fd pre-download (offline-safe default; "
              "set WEAVEORA_ALLOW_S3FD_DOWNLOAD=1 only if you really need it)")
        return persistent_cache_dir

    for model_name, url in models.items():
        save_path = os.path.join(persistent_cache_dir, model_name)
        if not os.path.exists(save_path):
            print(f"Model {model_name} not found in cache. Downloading...")
            try:
                download_model(url, save_path)
                print(f"Successfully downloaded {model_name} to {save_path}")
            except Exception as e:
                print(f"Error downloading {model_name}: {str(e)}")
                print(f"You may need to download it manually from {url}")
        else:
            print(f"Model {model_name} already exists in cache at {save_path}")
    
    # Return the cache directory so we can use it later
    return persistent_cache_dir

def get_latentsync_config_path(cur_dir):
    """Automatically detect the best config file for LatentSync version"""
    # Try 1.6 config first (512x512)
    config_512 = os.path.join(cur_dir, "configs", "unet", "stage2_512.yaml")
    if os.path.exists(config_512):
        print("Using LatentSync 1.6 config (512x512)")
        return config_512
    
    # Fallback to 1.5 config (256x256)
    config_256 = os.path.join(cur_dir, "configs", "unet", "stage2.yaml")
    if os.path.exists(config_256):
        print("Using LatentSync 1.5 config (256x256)")
        return config_256
    
    # If neither exists, default to 1.6
    print("Config files not found, defaulting to LatentSync 1.6 config")
    return config_512

def setup_models():
    """Setup and pre-download all required models."""
    # Pre-download additional models to a persistent location
    persistent_cache_dir = pre_download_models()

    # Existing setup logic for LatentSync models
    cur_dir = get_ext_dir()
    ckpt_dir = os.path.join(cur_dir, "checkpoints")
    whisper_dir = os.path.join(ckpt_dir, "whisper")
    os.makedirs(ckpt_dir, exist_ok=True)
    os.makedirs(whisper_dir, exist_ok=True)

    # Create a temp_downloads directory in our system temp
    temp_downloads = os.path.join(MODULE_TEMP_DIR, "downloads")
    os.makedirs(temp_downloads, exist_ok=True)
    
    unet_path = os.path.join(ckpt_dir, "latentsync_unet.pt")
    whisper_path = os.path.join(whisper_dir, "tiny.pt")

    # Check if models exist in the persistent cache first and copy them if needed
    cache_unet_path = os.path.join(persistent_cache_dir, "latentsync_unet.pt")
    cache_whisper_path = os.path.join(persistent_cache_dir, "whisper/tiny.pt")
    
    if os.path.exists(cache_unet_path) and not os.path.exists(unet_path):
        print(f"Copying unet model from cache {cache_unet_path} to {unet_path}")
        shutil.copy2(cache_unet_path, unet_path)
    
    if os.path.exists(cache_whisper_path) and not os.path.exists(whisper_path):
        print(f"Copying whisper model from cache {cache_whisper_path} to {whisper_path}")
        os.makedirs(os.path.dirname(whisper_path), exist_ok=True)
        shutil.copy2(cache_whisper_path, whisper_path)

    # Only download if models aren't in the working directory and weren't in the cache
    if not (os.path.exists(unet_path) and os.path.exists(whisper_path)):
        print("Downloading required LatentSync 1.6 model checkpoints... This may take a while.")
        try:
            from huggingface_hub import snapshot_download
            
            # Download to the persistent cache first
            snapshot_download(repo_id="ByteDance/LatentSync-1.6",
                            allow_patterns=["latentsync_unet.pt", "whisper/tiny.pt"],
                            local_dir=persistent_cache_dir, 
                            local_dir_use_symlinks=False,
                            cache_dir=temp_downloads)
            
            # Then copy to the working directory if needed
            if not os.path.exists(unet_path) and os.path.exists(os.path.join(persistent_cache_dir, "latentsync_unet.pt")):
                shutil.copy2(os.path.join(persistent_cache_dir, "latentsync_unet.pt"), unet_path)
            
            cache_whisper_tiny = os.path.join(persistent_cache_dir, "whisper/tiny.pt")
            if not os.path.exists(whisper_path) and os.path.exists(cache_whisper_tiny):
                os.makedirs(os.path.dirname(whisper_path), exist_ok=True)
                shutil.copy2(cache_whisper_tiny, whisper_path)
                
            print("LatentSync 1.6 model checkpoints downloaded successfully!")
        except Exception as e:
            print(f"Error downloading models: {str(e)}")
            print("\nPlease download models manually:")
            print("1. Visit: https://huggingface.co/ByteDance/LatentSync-1.6")
            print("2. Download: latentsync_unet.pt and whisper/tiny.pt")
            print(f"3. Place them in: {ckpt_dir}")
            print(f"   with whisper/tiny.pt in: {whisper_dir}")
            raise RuntimeError("Model download failed. See instructions above.")

class LatentSyncNode:
    def __init__(self):
        # Make sure our temp directory is the current one
        global MODULE_TEMP_DIR
        if not os.path.exists(MODULE_TEMP_DIR):
            os.makedirs(MODULE_TEMP_DIR, exist_ok=True)
        
        # Ensure ComfyUI temp doesn't exist
        comfyui_temp = "D:\\ComfyUI_windows\\temp"
        if os.path.exists(comfyui_temp):
            backup_name = f"{comfyui_temp}_backup_{uuid.uuid4().hex[:8]}"
            try:
                os.rename(comfyui_temp, backup_name)
            except:
                pass
        
        check_and_install_dependencies()
        setup_models()

    @classmethod
    def INPUT_TYPES(s):
        return {"required": {
                    "images": ("IMAGE",),
                    "audio": ("AUDIO", ),
                    # Weaveora 2026-09-13 新增：LatentSync 的生成帧率（合成时也必须用同一个值）。
                    # 它决定「按配音时长生成多少帧」（frames ≈ 配音秒数 × fps）。
                    # 工作流应把它接到源片的 fps（GetVideoComponents.fps），保证
                    # 生成帧率 = 播放帧率 = 源片帧率；留 25 = 模型原生帧率。
                    "fps": ("FLOAT", {"default": 25.0, "min": 1.0, "max": 120.0, "step": 1.0}),
                    "seed": ("INT", {"default": 1247}),
                    "lips_expression": ("FLOAT", {"default": 1.5, "min": 1.0, "max": 3.0, "step": 0.1}),
                    "inference_steps": ("INT", {"default": 20, "min": 1, "max": 999, "step": 1}),
                 },
                "optional": {
                    # Weaveora：说话人定妆照的人脸特征文件（JSON 数组，512 维）。
                    # 给了就**锁人**（只驱动相似度最高的那张脸）；不给就用上游默认（取最大脸）。
                    # 为什么需要：多人同框时「最大脸」会在镜头中途换人 → 画面坏掉。
                    "target_embedding_path": ("STRING", {"default": "", "multiline": False}),
                 }}

    CATEGORY = "LatentSyncNode"

    RETURN_TYPES = ("IMAGE", "AUDIO")
    RETURN_NAMES = ("images", "audio") 
    FUNCTION = "inference"

    def process_batch(self, batch, use_mixed_precision=False):
        with torch.cuda.amp.autocast(enabled=use_mixed_precision):
            processed_batch = batch.float() / 255.0
            if len(processed_batch.shape) == 3:
                processed_batch = processed_batch.unsqueeze(0)
            if processed_batch.shape[0] == 3:
                processed_batch = processed_batch.permute(1, 2, 0)
            if processed_batch.shape[-1] == 4:
                processed_batch = processed_batch[..., :3]
            return processed_batch

    def inference(self, images, audio, seed, lips_expression=1.5, inference_steps=20, fps=25.0,
                  target_embedding_path=""):
        # Use our module temp directory
        global MODULE_TEMP_DIR
        register_weaveora_version_route()  # 万一导入时 ComfyUI 的 server 还没就绪
        # Weaveora：把本次「锁定规格」打进节点日志（跨机排查时这是唯一能看到节点侧输入的窗口）
        if target_embedding_path:
            _spec = str(target_embedding_path)
            _kind = "内联JSON" if _spec.lstrip().startswith("{") else "文件路径(旧协议)"
            print("[weaveora] 收到锁定规格（%s，%d 字符）：%s"
                  % (_kind, len(_spec), _spec[:120] + ("…" if len(_spec) > 120 else "")), flush=True)
            if _kind.startswith("文件路径"):
                print("[weaveora] 警告：worker 传的是文件路径而不是内联 JSON —— 跨机时节点读不到，"
                      "会退回「取最大脸」。请更新 API 服务器上的 worker。", flush=True)
        else:
            print("[weaveora] 本次未收到锁定规格 → 走「最大脸」策略", flush=True)
        # 输出帧率（与生成帧率一致）：见 INPUT_TYPES.fps 注释
        _fps = float(fps or 25.0)
        if _fps < 1.0:
            _fps = 25.0
        
        # Get GPU capabilities and memory
        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        BATCH_SIZE = 4
        use_mixed_precision = False
        if torch.cuda.is_available():
            gpu_mem = torch.cuda.get_device_properties(0).total_memory
            # Convert to GB
            gpu_mem_gb = gpu_mem / (1024 ** 3)

            # Dynamically adjust batch size based on GPU memory
            if gpu_mem_gb > 20:  # High-end GPUs
                BATCH_SIZE = 32
                enable_tf32 = True
                use_mixed_precision = True
            elif gpu_mem_gb > 8:  # Mid-range GPUs
                BATCH_SIZE = 16
                enable_tf32 = False
                use_mixed_precision = True
            else:  # Lower-end GPUs
                BATCH_SIZE = 8
                enable_tf32 = False
                use_mixed_precision = False

            # Set performance options based on GPU capability
            torch.backends.cudnn.benchmark = True
            if enable_tf32:
                torch.backends.cuda.matmul.allow_tf32 = True
                torch.backends.cudnn.allow_tf32 = True

            # Clear GPU cache before processing
            torch.cuda.empty_cache()
            # Weaveora 2026-09-13: upstream hard-capped this process at 80% of VRAM
            # (on an 8 GiB card that is only 6.5 GiB reserved), which made LatentSync
            # 512x512/16f OOM even though the GPU was otherwise idle. This process is the
            # only GPU consumer, so make the cap opt-in and default to "no cap".
            _mem_frac = os.environ.get("WEAVEORA_LATENTSYNC_MEM_FRACTION", "").strip()
            if _mem_frac:
                try:
                    torch.cuda.set_per_process_memory_fraction(float(_mem_frac))
                    print(f"[weaveora] cuda memory fraction = {_mem_frac}")
                except (TypeError, ValueError):
                    print(f"[weaveora] ignored invalid WEAVEORA_LATENTSYNC_MEM_FRACTION={_mem_frac!r}")

        # Create a run-specific subdirectory in our temp directory
        run_id = ''.join(random.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(5))
        temp_dir = os.path.join(MODULE_TEMP_DIR, f"run_{run_id}")
        os.makedirs(temp_dir, exist_ok=True)
        
        # Ensure ComfyUI temp doesn't exist again (in case something recreated it)
        comfyui_temp = "D:\\ComfyUI_windows\\temp"
        if os.path.exists(comfyui_temp):
            backup_name = f"{comfyui_temp}_backup_{uuid.uuid4().hex[:8]}"
            try:
                os.rename(comfyui_temp, backup_name)
            except:
                pass
        
        temp_video_path = None
        output_video_path = None
        audio_path = None

        try:
            # Create temporary file paths in our system temp directory
            temp_video_path = os.path.join(temp_dir, f"temp_{run_id}.mp4")
            output_video_path = os.path.join(temp_dir, f"latentsync_{run_id}_out.mp4")
            audio_path = os.path.join(temp_dir, f"latentsync_{run_id}_audio.wav")
            
            # Get the extension directory
            cur_dir = os.path.dirname(os.path.abspath(__file__))
            
            # Process input frames
            if isinstance(images, list):
                frames = torch.stack(images).to(device)
            else:
                frames = images.to(device)
 
            frames_cpu = frames.cpu()  
            del frames  
            torch.cuda.empty_cache()  

            frames = (frames_cpu * 255).to(torch.uint8)

            # Process audio with device awareness
            waveform = audio["waveform"].to(device)
            sample_rate = audio["sample_rate"]
            if waveform.dim() == 3:
                waveform = waveform.squeeze(0)

            if sample_rate != 16000:
                new_sample_rate = 16000
                resampler = torchaudio.transforms.Resample(
                    orig_freq=sample_rate,
                    new_freq=new_sample_rate
                ).to(device)
                waveform_16k = resampler(waveform)
                waveform, sample_rate = waveform_16k, new_sample_rate

            # Package resampled audio
            resampled_audio = {
                "waveform": waveform.unsqueeze(0),
                "sample_rate": sample_rate
            }
            
            # Move waveform to CPU for saving
            waveform_cpu = waveform.cpu()
            torchaudio.save(audio_path, waveform_cpu, sample_rate)

            # Move frames to CPU for saving to video
            frames_cpu = frames.cpu()
            # ★ 保持 torchvision.io 的**局部绑定**（函数后面 io.read_video 还要用）。
            #   踩过的坑：只在 except 里 import 会让 `io` 变成局部名；PyAV 成功时不走 except
            #   → 后面 io.read_video 直接 UnboundLocalError: cannot access local variable 'io'。
            import torchvision.io as io
            # ★ Weaveora 2026-09-15 修复：优先用 PyAV 写输入帧。
            # 旧实现先走 torchvision.io.write_video → 它内部调 imageio，而本机 imageio 2.37 + av 18
            # 会让 imageio 选中 pyav 插件，该插件**不接受 macro_block_size** →
            #   TypeError: PyAVPlugin.write() got an unexpected keyword argument 'macro_block_size'
            # 对口型因此 100% 失败（依赖检查/模型加载/推理都正常，只在写视频时崩）。
            try:
                import av as _av
                _c = _av.open(temp_video_path, mode='w')
                _s = _c.add_stream('libx264', rate=int(round(_fps)))
                _h, _w = int(frames_cpu.shape[1]), int(frames_cpu.shape[2])
                _s.width, _s.height = _w - (_w % 2), _h - (_h % 2)
                try:
                    _s.pix_fmt = 'yuv420p'
                except Exception:
                    pass
                for _f in frames_cpu:
                    _arr = _f.numpy()[:_s.height, :_s.width]
                    _c.mux(_s.encode(_av.VideoFrame.from_ndarray(_arr, format='rgb24')))
                _c.mux(_s.encode(None))
                _c.close()
            except Exception as _e:
                print('[weaveora] PyAV 写输入帧失败，回退 torchvision：%s' % _e, flush=True)
                io.write_video(temp_video_path, frames_cpu, fps=int(round(_fps)), video_codec='h264')
            if False:
                # Check if the error is specifically about macro_block_size
                if "macro_block_size" in str(e):
                    import imageio
                    # Use imageio with macro_block_size parameter
                    imageio.mimsave(temp_video_path, frames_cpu.numpy(), fps=25, codec='h264', macro_block_size=1)
                else:
                    # Fall back to original PyAV code for other TypeError issues
                    import av
                    container = av.open(temp_video_path, mode='w')
                    stream = container.add_stream('h264', rate=25)
                    stream.width = frames_cpu.shape[2]
                    stream.height = frames_cpu.shape[1]

                    for frame in frames_cpu:
                        frame = av.VideoFrame.from_ndarray(frame.numpy(), format='rgb24')
                        packet = stream.encode(frame)
                        container.mux(packet)

                    packet = stream.encode(None)
                    container.mux(packet)
                    container.close()

            # Define paths to required files and configs
            inference_script_path = os.path.join(cur_dir, "scripts", "inference.py")
            config_path = get_latentsync_config_path(cur_dir)
            scheduler_config_path = os.path.join(cur_dir, "configs")
            ckpt_path = os.path.join(cur_dir, "checkpoints", "latentsync_unet.pt")
            whisper_ckpt_path = os.path.join(cur_dir, "checkpoints", "whisper", "tiny.pt")

            # Create config and args
            config = OmegaConf.load(config_path)

            # Set the correct mask image path
            mask_image_path = os.path.join(cur_dir, "latentsync", "utils", "mask.png")
            # Make sure the mask image exists
            if not os.path.exists(mask_image_path):
                # Try to find it in the utils directory directly
                alt_mask_path = os.path.join(cur_dir, "utils", "mask.png")
                if os.path.exists(alt_mask_path):
                    mask_image_path = alt_mask_path
                else:
                    print(f"Warning: Could not find mask image at expected locations")

            # Set mask path in config
            if hasattr(config, "data") and hasattr(config.data, "mask_image_path"):
                config.data.mask_image_path = mask_image_path

            args = argparse.Namespace(
                unet_config_path=config_path,
                inference_ckpt_path=ckpt_path,
                video_path=temp_video_path,
                audio_path=audio_path,
                video_out_path=output_video_path,
                seed=seed,
                inference_steps=inference_steps,
                guidance_scale=lips_expression,  # Using lips_expression for the guidance_scale
                scheduler_config_path=scheduler_config_path,
                whisper_ckpt_path=whisper_ckpt_path,
                device=device,
                batch_size=BATCH_SIZE,
                use_mixed_precision=use_mixed_precision,
                temp_dir=temp_dir,
                mask_image_path=mask_image_path,
                # Weaveora：把 fps 透传给 pipeline（不传就会吃默认 25）
                video_fps=_fps,
                # Weaveora：说话人人脸特征文件（JSON），管线据此「锁人」
                target_embedding_path=(target_embedding_path or ""),
            )

            # Set PYTHONPATH to include our directories 
            package_root = os.path.dirname(cur_dir)
            if package_root not in sys.path:
                sys.path.insert(0, package_root)
            if cur_dir not in sys.path:
                sys.path.insert(0, cur_dir)

            # Clean GPU cache before inference
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                
            # Check and prevent ComfyUI temp creation again
            if os.path.exists(comfyui_temp):
                try:
                    os.rename(comfyui_temp, f"{comfyui_temp}_backup_{uuid.uuid4().hex[:8]}")
                except:
                    pass

            # Import the inference module
            inference_module = import_inference_script(inference_script_path)
            
            # Monkey patch any temp directory functions in the inference module
            if hasattr(inference_module, 'get_temp_dir'):
                inference_module.get_temp_dir = lambda *args, **kwargs: temp_dir
                
            # Create subdirectories that the inference module might expect
            inference_temp = os.path.join(temp_dir, "temp")
            os.makedirs(inference_temp, exist_ok=True)
            
            # Run inference
            inference_module.main(config, args)

            # Clean GPU cache after inference
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

            # Verify output file exists
            if not os.path.exists(output_video_path):
                raise FileNotFoundError(f"Output video not found at: {output_video_path}")
            
            # Read the processed video - ensure it's loaded as CPU tensor
            processed_frames = io.read_video(output_video_path, pts_unit='sec')[0]
            processed_frames = processed_frames.float() / 255.0

            # Ensure audio is on CPU before returning
            if torch.cuda.is_available():
                if hasattr(resampled_audio["waveform"], 'device') and resampled_audio["waveform"].device.type == 'cuda':
                    resampled_audio["waveform"] = resampled_audio["waveform"].cpu()
                if hasattr(processed_frames, 'device') and processed_frames.device.type == 'cuda':
                    processed_frames = processed_frames.cpu()

            return (processed_frames, resampled_audio)

        except Exception as e:
            print(f"Error during inference: {str(e)}")
            import traceback
            traceback.print_exc()
            raise

        finally:
            # Clean up temporary files individually
            for path in [temp_video_path, output_video_path, audio_path]:
                if path and os.path.exists(path):
                    try:
                        os.remove(path)
                        print(f"Removed temporary file: {path}")
                    except Exception as e:
                        print(f"Failed to remove {path}: {str(e)}")

            # Remove temporary run directory
            if temp_dir and os.path.exists(temp_dir):
                try:
                    shutil.rmtree(temp_dir, ignore_errors=True)
                    print(f"Removed run temporary directory: {temp_dir}")
                except Exception as e:
                    print(f"Failed to remove temp run directory: {str(e)}")

            # Clean up any ComfyUI temp directories again (in case they were created during execution)
            cleanup_comfyui_temp_directories()

            # Final GPU cache cleanup
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

class VideoLengthAdjuster:
    @classmethod
    def INPUT_TYPES(s):
        return {
            "required": {
                "images": ("IMAGE",),
                "audio": ("AUDIO",),
                "mode": (["normal", "pingpong", "loop_to_audio"], {"default": "normal"}),
                "fps": ("FLOAT", {"default": 25.0, "min": 1.0, "max": 120.0}),
                "silent_padding_sec": ("FLOAT", {"default": 0.5, "min": 0.1, "max": 3.0, "step": 0.1}),
            }
        }

    CATEGORY = "LatentSyncNode"
    RETURN_TYPES = ("IMAGE", "AUDIO")
    RETURN_NAMES = ("images", "audio")
    FUNCTION = "adjust"

    def adjust(self, images, audio, mode, fps=25.0, silent_padding_sec=0.5):
        waveform = audio["waveform"].squeeze(0)
        sample_rate = int(audio["sample_rate"])
        original_frames = [images[i] for i in range(images.shape[0])] if isinstance(images, torch.Tensor) else images.copy()

        if mode == "normal":
            # Add silent padding to the audio and then trim video to match
            audio_duration = waveform.shape[1] / sample_rate
            
            # Add silent padding to the audio
            silence_samples = math.ceil(silent_padding_sec * sample_rate)
            silence = torch.zeros((waveform.shape[0], silence_samples), dtype=waveform.dtype)
            padded_audio = torch.cat([waveform, silence], dim=1)
            
            # Calculate required frames based on the padded audio
            padded_audio_duration = (waveform.shape[1] + silence_samples) / sample_rate
            required_frames = int(padded_audio_duration * fps)
            
            if len(original_frames) > required_frames:
                # Trim video frames to match padded audio duration
                adjusted_frames = original_frames[:required_frames]
            else:
                # If video is shorter than padded audio, keep all video frames
                # and trim the audio accordingly
                adjusted_frames = original_frames
                required_samples = int(len(original_frames) / fps * sample_rate)
                padded_audio = padded_audio[:, :required_samples]
            
            return (
                torch.stack(adjusted_frames),
                {"waveform": padded_audio.unsqueeze(0), "sample_rate": sample_rate}
            )
            
            # This return statement is no longer needed as it's handled in the updated code

        elif mode == "pingpong":
            video_duration = len(original_frames) / fps
            audio_duration = waveform.shape[1] / sample_rate
            if audio_duration <= video_duration:
                required_samples = int(video_duration * sample_rate)
                silence = torch.zeros((waveform.shape[0], required_samples - waveform.shape[1]), dtype=waveform.dtype)
                adjusted_audio = torch.cat([waveform, silence], dim=1)

                return (
                    torch.stack(original_frames),
                    {"waveform": adjusted_audio.unsqueeze(0), "sample_rate": sample_rate}
                )

            else:
                silence_samples = math.ceil(silent_padding_sec * sample_rate)
                silence = torch.zeros((waveform.shape[0], silence_samples), dtype=waveform.dtype)
                padded_audio = torch.cat([waveform, silence], dim=1)
                total_duration = (waveform.shape[1] + silence_samples) / sample_rate
                target_frames = math.ceil(total_duration * fps)
                reversed_frames = original_frames[::-1][1:-1]  # Remove endpoints
                frames = original_frames + reversed_frames
                while len(frames) < target_frames:
                    frames += frames[:target_frames - len(frames)]
                return (
                    torch.stack(frames[:target_frames]),
                    {"waveform": padded_audio.unsqueeze(0), "sample_rate": sample_rate}
                )

        elif mode == "loop_to_audio":
            # Add silent padding then simple loop
            silence_samples = math.ceil(silent_padding_sec * sample_rate)
            silence = torch.zeros((waveform.shape[0], silence_samples), dtype=waveform.dtype)
            padded_audio = torch.cat([waveform, silence], dim=1)
            total_duration = (waveform.shape[1] + silence_samples) / sample_rate
            target_frames = math.ceil(total_duration * fps)

            frames = original_frames.copy()
            while len(frames) < target_frames:
                frames += original_frames[:target_frames - len(frames)]
            
            return (
                torch.stack(frames[:target_frames]),
                {"waveform": padded_audio.unsqueeze(0), "sample_rate": sample_rate}
            )



# Node Mappings for ComfyUI
NODE_CLASS_MAPPINGS = {
    "LatentSyncNode": LatentSyncNode,
    "VideoLengthAdjuster": VideoLengthAdjuster,
}

# Display Names for ComfyUI
NODE_DISPLAY_NAME_MAPPINGS = {
    "LatentSyncNode": "LatentSync1.6 Node（Weaveora %s）" % WEAVEORA_NODE_VERSION,
    "VideoLengthAdjuster": "Video Length Adjuster",
}