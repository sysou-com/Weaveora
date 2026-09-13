# Weaveora comfy worker supervisor (this host) - constant, self-healing
#   MODE=comfy, API via local-forward tunnel 127.0.0.1:18080 -> server :8080,
#   ComfyUI direct 127.0.0.1:8188. Single-instance mutex, log to worker.log
$ErrorActionPreference = "SilentlyContinue"
$python = "D:\ComfyUI\venv\Scripts\python.exe"
$workDir = "D:\workspace\Weaveora\worker"
$log = Join-Path $PSScriptRoot "worker.log"
$mutexName = "Global\WeaveoraWinWorker"

$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(0)) { exit }
function Log($m) { Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

$env:WEAVEORA_WORKER_MODE = "comfy"
$env:WEAVEORA_API_BASE = "http://127.0.0.1:18080"
$env:WEAVEORA_COMFY_URL = "http://127.0.0.1:8188"
$env:WEAVEORA_COMFY_FALLBACK_TXT2IMG = "0"
$env:WEAVEORA_WORKER_NAME = "win-comfy-worker"
$env:WEAVEORA_WORKER_TOKEN = "yIMVaL8M35aAqsvdy2oTbqjeukM75FD-IQnuFr0OdmI"
# P7 配乐：ACE-Step 1.5 走本机 ComfyUI 原生节点（all-in-one 权重）
$env:WEAVEORA_MUSIC_ENGINE = "comfy"
$env:WEAVEORA_MUSIC_CKPT_NAME = "ace_step_1.5_turbo_aio.safetensors"
# P7 配音：CosyVoice2（WSL2 :8091）。未起服务时 voice 任务会失败并给出提示。
$env:WEAVEORA_TTS_URL = "http://127.0.0.1:8091"
# P13 对口型：本机 LatentSync 1.6（ComfyUI-LatentSyncWrapper）工作流，见 docs/lipsync-setup.md
$env:WEAVEORA_LIPSYNC_WORKFLOW = "D:\ComfyUI\_setup\lipsync_workflow_api.json"
# 原生 LoadVideo 的输入键是 file（VHS_LoadVideo 才是 video）——不设会用默认 "video" 而注入失败
$env:WEAVEORA_LIPSYNC_VIDEO_INPUT = "file"
# 3070 Ti 8GB 实测：512x512 / 16帧一块约 75s，约 2.5 分钟/秒视频；1800s 够 5s 片段
$env:WEAVEORA_LIPSYNC_TIMEOUT = "1800"

Log "worker supervisor started (mode=comfy api=18080 comfy=8188)"
while ($true) {
    Log "starting stub_worker.py ..."
    Push-Location $workDir
    & $python stub_worker.py 2>&1 | ForEach-Object { Add-Content -Path $log -Value ("    " + $_) }
    Pop-Location
    Log "worker exited rc=$LASTEXITCODE; restart in 6s"
    Start-Sleep -Seconds 6
}
