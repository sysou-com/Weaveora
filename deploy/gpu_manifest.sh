#!/usr/bin/env bash
# =============================================================================
# Weaveora GPU 盒 —— 模型/权重清单扫描器（用于打镜像前盘点、以及新实例恢复后核对）
#
# 用法（在 GPU 盒上以 root 跑）：
#     bash gpu_manifest.sh                 # 输出到 stdout
#     bash gpu_manifest.sh > /tmp/gpu_manifest.txt
#
# 为什么要这个脚本：平台每次重开容器，**端口会变、/root 会被重置（authorized_keys 与密码都换）**，
# 但同一镜像的主机键不变（实测 ED25519 SHA256:WEeKFd90sfhAztcvbci9bH98kReJlkMNgvlkskxTnJA）。
# 所以「环境变了 → 重新上传」这件事必须有一份**可复现的清单**，而不是靠记忆。
#
# 输出分 6 段：A 体积概览 / B 模型真文件 / C 软链与真身 / D 各子系统 / E venv与代码 / F 挂载与内存
# ⚠️ 打镜像时最关键的判断：**B 里出现的才是"必须进镜像的真文件"；C 里的软链是薄壳，恢复后要重建。**
# =============================================================================
set -uo pipefail
ROOT=${WEAVEORA_ROOT:-/opt/weaveora}
DATA=${WEAVEORA_DATA:-/addDisk/weaveora}

echo "########## F. 环境（先看这个）##########"
date; hostname; uptime
echo "--- 挂载 ---"; df -h / "$(dirname "$DATA")" 2>/dev/null | sort -u
echo "--- 内存（★ 启动 flag 必须按这个配：--cache-ram 别超过 内存 - 模型常驻 - talk常驻）---"
free -h
echo "--- 内存压力（full avg10 长期 >5 就会明显变慢）---"; cat /proc/pressure/memory 2>/dev/null
echo "--- GPU ---"; nvidia-smi --query-gpu=name,memory.total,memory.used --format=csv,noheader 2>/dev/null
echo

echo "########## E. 代码与 venv（体积，不逐个文件列）##########"
du -sh "$ROOT"/* 2>/dev/null | sort -h
echo "--- ComfyUI 版本 / git ---"
cat "$ROOT/ComfyUI/comfyui_version.py" 2>/dev/null | grep -v '^#'
git -C "$ROOT/ComfyUI" log -1 --format='%h %ad %s' --date=iso 2>/dev/null
echo "--- 工作流 JSON（随代码部署，重建别漏）---"
ls -la "$ROOT/workflows/" 2>/dev/null
echo "--- 启动脚本 / 关键 py ---"
ls -la "$ROOT"/{services_up.sh,warmup.sh,edge_proxy.py,stub_worker.py,comfy_client.py,audio_client.py} 2>/dev/null
echo

echo "########## D. 各子系统模型目录（体积）##########"
for d in "$ROOT/models" "$ROOT/echo_mimic_v3" "$ROOT/latentsync" "$ROOT/audio" "$ROOT/face" "$ROOT/talk"; do
  [ -e "$d" ] && du -sh "$d" 2>/dev/null
done
for d in /root/.cache/huggingface /root/.insightface /root/.cache/torch; do
  [ -e "$d" ] && du -sh "$d" 2>/dev/null
done
echo

echo "########## B. 模型真文件（>20MB）—— 这些是镜像里必须有的 ##########"
# 排除 venv / site-packages / __pycache__（那些是环境，不是权重）
find "$ROOT" "$(dirname "$DATA")" /root/.cache/huggingface /root/.insightface \
  \( -path '*/venv/*' -o -path '*/envs/*' -o -path '*/site-packages/*' -o -path '*__pycache__*' \) -prune -o \
  -type f -size +20M -printf '%12s  %TY-%Tm-%Td %TH:%TM  %p\n' 2>/dev/null | sort -rn
echo

echo "########## C. 软链清单 —— 真身在别处，恢复后必须重建 ##########"
# 关键：同名文件若在多处出现，用「按 name 分组的 size 对比」就能看出有没有多副本/指错
find "$ROOT" \( -path '*/venv/*' -o -path '*/envs/*' \) -prune -o -type l -printf '%p -> %l\n' 2>/dev/null | sort
echo
echo "--- ★ 同名权重多副本检测（同名出现 >1 次且大小不同 = 用错权重的经典原因）---"
find "$ROOT" "$(dirname "$DATA")" \( -path '*/venv/*' -o -path '*/envs/*' \) -prune -o \
  -type f -name '*.safetensors' -printf '%f\t%s\t%p\n' 2>/dev/null \
  | sort | awk -F'\t' '{n[$1]++; s[$1]=s[$1]" "$2} END{for(k in n) if(n[k]>1) printf "  %-58s 份数=%s 大小=%s\n", k, n[k], s[k]}' | sort
echo

echo "########## A. ComfyUI 实际识别到的模型（以运行实例为准，含它解析到的路径）##########"
GW=${WEAVEORA_GATEWAY:-http://127.0.0.1:8001}
echo "--- 运行实例的 argv（★ 必须是 $ROOT/ComfyUI/main.py）---"
curl -s -m 10 "$GW/system_stats" | python3 -c 'import json,sys;d=json.load(sys.stdin)["system"];print(d.get("argv"));print("ver:",d.get("comfyui_version"),"py:",d.get("python_version"))' 2>/dev/null
echo "--- 模型搜索路径 ---"
curl -s -m 10 "$GW/experiment/models" | python3 -c '
import json,sys
for f in json.load(sys.stdin):
    if f.get("name") in ("diffusion_models","text_encoders","vae","loras","upscale_models","checkpoints"):
        print("  %-18s %s"%(f["name"], f.get("folders")))
' 2>/dev/null
echo "--- 每个模型的 pathIndex / 大小 / mtime ---"
for f in diffusion_models text_encoders vae loras upscale_models; do
  echo "  [$f]"
  curl -s -m 15 "$GW/experiment/models/$f" | python3 -c '
import json,sys,time
for it in json.load(sys.stdin):
    print("     pi=%-2s %9.2fGB  %s  %s"%(it.get("pathIndex"),(it.get("size") or 0)/1073741824.0,
          time.strftime("%m-%d %H:%M", time.localtime(it.get("modified") or 0)), it.get("name")))
' 2>/dev/null
done
echo
echo "########## 扫描完成 ##########"
