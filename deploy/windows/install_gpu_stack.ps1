<#
=====================================================================
 Weaveora GPU stack installer (Windows) - idempotent, one-shot
 Reinstalls / provisions everything for the local GPU engine:
   models (ModelScope CN mirror, 10-way chunked, resumable)
   ComfyUI (source) + Python 3.13 venv + torch cu126
   WanVideoWrapper (Kijai) + IPAdapter_plus nodes
   extra_model_paths.yaml -> model root
   ComfyUI service on :8188 (background)

 Usage:
   powershell -ExecutionPolicy Bypass -File install_gpu_stack.ps1 -SkipComfyUI   # models only
   powershell -ExecutionPolicy Bypass -File install_gpu_stack.ps1                # full stack
   powershell -ExecutionPolicy Bypass -File install_gpu_stack.ps1 -Background    # detach & silent
 Requirements: node.exe, 7-Zip, Python 3.13 launcher (py), curl.exe on PATH.
=====================================================================
#>
param(
    [string]$ModelRoot = "D:\model",
    [string]$ComfyDir  = "D:\ComfyUI",
    [switch]$SkipComfyUI,
    [switch]$Background,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$dlScript  = Join-Path $scriptDir "gpu_model_downloader.js"
$nodeExe   = "C:\Program Files\nodejs\node.exe"
$pyLauncher = "C:\Users\Administrator\AppData\Local\Programs\Python\Launcher\py.exe"
$sevenZip  = "C:\Program Files\7-Zip\7z.exe"
$curlExe   = "C:\Windows\System32\curl.exe"
$Proxy     = "https://ghfast.top/"   # github accel prefix (direct github is blocked on this host)
$dlDir     = Join-Path $ModelRoot "_dl"
$logFile   = Join-Path $dlDir "install.log"

function Log($m) { $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Write-Host $s; Add-Content -Path $logFile -Value $s }

function Run($cmd, $argsArr, $desc) {
    Log ">> $desc"
    & $cmd @argsArr 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "FAILED: $desc (exit $LASTEXITCODE)" }
}

# ---------- model manifest: name/dir/file/expectedBytes/url ----------
$models = @(
    @{ n='SDXL base 1.0';               dir='checkpoints';      f='sd_xl_base_1.0.safetensors';                                      sz=6938078334; u='https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors' },
    @{ n='Wan2.2 TI2V-5B FP16';         dir='diffusion_models'; f='wan2.2_ti2v_5B_fp16.safetensors';                                  sz=9999658848; u='https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors' },
    @{ n='UMT5-XXL FP8 (scaled)';       dir='text_encoders';    f='umt5_xxl_fp8_e4m3fn_scaled.safetensors';                           sz=6735906897; u='https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors' },
    @{ n='Wan2.2 VAE';                  dir='vae';              f='wan2.2_vae.safetensors';                                           sz=1409400960; u='https://www.modelscope.cn/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files/vae/wan2.2_vae.safetensors' },
    @{ n='IP-Adapter SDXL ViT-H';       dir='ipadapter';        f='ip-adapter_sdxl_vit-h.safetensors';                               sz=698391064;  u='https://www.modelscope.cn/models/AI-ModelScope/IP-Adapter/resolve/master/sdxl_models/ip-adapter_sdxl_vit-h.safetensors' },
    @{ n='CLIP ViT-H-14 LAION2B';       dir='clip_vision';      f='CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors';                     sz=3944552236; u='https://www.modelscope.cn/models/AI-ModelScope/CLIP-ViT-H-14-laion2B-s32B-b79K/resolve/master/model.safetensors' }
)

# ---------- 0. background self-restart ----------
if ($Background) {
    $args2 = @("-File", $PSCommandPath)
    if ($SkipComfyUI) { $args2 += "-SkipComfyUI" }
    if ($Force) { $args2 += "-Force" }
    New-Item -ItemType Directory -Force -Path $dlDir | Out-Null
    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList $args2 -WindowStyle Hidden `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $dlDir "install.err.log") -PassThru
    Write-Host "Installer started in background (PID $($proc.Id)). Log: $logFile"
    exit 0
}

# ---------- 1. environment check ----------
New-Item -ItemType Directory -Force -Path $dlDir | Out-Null
foreach ($m in $models) { New-Item -ItemType Directory -Force -Path (Join-Path $ModelRoot $m.dir) | Out-Null }
foreach ($t in @(@('node', $nodeExe), @('7z', $sevenZip), @('py', $pyLauncher))) {
    if (-not (Test-Path $t[1])) { throw "Missing $($t[0]): $($t[1])" }
}
Log "Env ok. modelRoot=$ModelRoot comfyDir=$ComfyDir"

# ---------- 2. models download (resumable, 10-way per file) ----------
$localJs = Join-Path $dlDir "gpu_model_downloader.js"
Copy-Item -Force $dlScript $localJs
$i = 0
foreach ($m in $models) {
    $i++
    $out = Join-Path $ModelRoot (Join-Path $m.dir $m.f)
    $done = "$out.done"
    if ((Test-Path $done) -and -not $Force) {
        Log "[$i/$($models.Count)] SKIP (done) $($m.f)"
        continue
    }
    Log "[$i/$($models.Count)] downloading $($m.n) -> $out (10-way, resumable)"
    & $nodeExe $localJs $m.u $out "10" 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { Log "WARN download exit=$LASTEXITCODE for $($m.f); will be resumed on next run" }
}
# verify
Log "== verify sizes =="
$okAll = $true
foreach ($m in $models) {
    $p = Join-Path $ModelRoot (Join-Path $m.dir $m.f)
    if (Test-Path $p) {
        $len = (Get-Item $p).Length
        $ok = ($len -eq $m.sz); if (-not $ok) { $okAll = $false }
        Log ("{0}  {1}  {2:N0}/{3:N0} bytes" -f $(if($ok){'OK '}else{'BAD'}), $m.f, $len, $m.sz)
    } else { $okAll = $false; Log "MISSING $($m.f)" }
}
if (-not $okAll) { Log "MODELS_INCOMPLETE (rerun to resume)" } else { Log "MODELS_OK ($($models.Count) files)" }

# ---------- 3. ComfyUI stack (optional) ----------
if ($SkipComfyUI) { Log "DONE (models only). ComfyUI skipped by flag."; exit 0 }

$mainPy = Join-Path $ComfyDir "main.py"
$venvPy = Join-Path $ComfyDir "venv\Scripts\python.exe"
$setups = Join-Path $ComfyDir "_setup"
New-Item -ItemType Directory -Force -Path $setups | Out-Null

# 3a. ComfyUI source
if (-not (Test-Path $mainPy)) {
    Log "Downloading ComfyUI master.zip ..."
    & $curlExe -sL --max-time 600 -C - -o (Join-Path $setups "comfyui_master.zip") "$($Proxy)https://github.com/Comfy-Org/ComfyUI/archive/refs/heads/master.zip"
    New-Item -ItemType Directory -Force -Path (Join-Path $setups "cx") | Out-Null
    & $sevenZip x (Join-Path $setups "comfyui_master.zip") "-o$($setups)\cx" -y | Out-Null
    Copy-Item -Recurse -Force (Join-Path $setups "cx\ComfyUI-master\*") $ComfyDir
    Remove-Item -Recurse -Force (Join-Path $setups "cx")
    Log "ComfyUI source extracted to $ComfyDir"
} else { Log "ComfyUI source exists, skip" }

# 3b. venv
if (-not (Test-Path $venvPy)) {
    Log "Creating venv (Python 3.13)..."
    Run $pyLauncher @("-3.13", "-m", "venv", (Join-Path $ComfyDir "venv")) "create venv"
} else { Log "venv exists, skip" }

# 3c. torch cu126 (skip if already cuda-ready)
$torchOk = & $venvPy -c "import torch;print(torch.cuda.is_available())" 2>$null
if ($torchOk -ne "True") {
    Log "Installing torch 2.7.1+cu126 (torchvision/torchaudio) from pytorch index ..."
    Run $venvPy @("-m","pip","install","--index-url","https://download.pytorch.org/whl/cu126","torch==2.7.1","torchvision==0.22.1","torchaudio==2.7.1") "pip install torch cu126"
} else { Log "torch cu126 ready, skip" }

# 3d. ComfyUI requirements (official pypi, retry-safe)
Log "Installing ComfyUI requirements.txt ..."
Run $venvPy @("-m","pip","install","-r",(Join-Path $ComfyDir "requirements.txt"),"--timeout","60","--retries","8") "pip comfyui requirements"

# 3e. custom nodes
$nodesDir = Join-Path $ComfyDir "custom_nodes"
New-Item -ItemType Directory -Force -Path $nodesDir | Out-Null
$wanDir = Join-Path $nodesDir "ComfyUI-WanVideoWrapper"
if (-not (Test-Path (Join-Path $wanDir "__init__.py"))) {
    Log "Downloading ComfyUI-WanVideoWrapper ..."
    & $curlExe -sL --max-time 300 -o (Join-Path $setups "wanwrapper.zip") "$($Proxy)https://github.com/kijai/ComfyUI-WanVideoWrapper/archive/refs/heads/main.zip"
    New-Item -ItemType Directory -Force -Path (Join-Path $setups "ww") | Out-Null
    & $sevenZip x (Join-Path $setups "wanwrapper.zip") "-o$($setups)\ww" -y | Out-Null
    Move-Item (Join-Path $setups "ww\ComfyUI-WanVideoWrapper-main") $wanDir
    Remove-Item -Recurse -Force (Join-Path $setups "ww")
    Run $venvPy @("-m","pip","install","-r",(Join-Path $wanDir "requirements.txt"),"--timeout","60","--retries","8") "pip wanwrapper requirements"
} else { Log "WanVideoWrapper exists, skip" }

$ipaDir = Join-Path $nodesDir "ComfyUI_IPAdapter_plus"
if (-not (Test-Path (Join-Path $ipaDir "__init__.py"))) {
    Log "Downloading ComfyUI_IPAdapter_plus ..."
    & $curlExe -sL --max-time 300 -o (Join-Path $setups "ipadapter.zip") "$($Proxy)https://github.com/cubiq/ComfyUI_IPAdapter_plus/archive/refs/heads/main.zip"
    New-Item -ItemType Directory -Force -Path (Join-Path $setups "ipa") | Out-Null
    & $sevenZip x (Join-Path $setups "ipadapter.zip") "-o$($setups)\ipa" -y | Out-Null
    Move-Item (Join-Path $setups "ipa\ComfyUI_IPAdapter_plus-main") $ipaDir
    Remove-Item -Recurse -Force (Join-Path $setups "ipa")
} else { Log "IPAdapter_plus exists, skip" }

# 3f. extra_model_paths.yaml
$extraYaml = Join-Path $ComfyDir "extra_model_paths.yaml"
if (-not (Test-Path $extraYaml)) {
    Log "Writing extra_model_paths.yaml -> $ModelRoot"
    $yaml = "# Weaveora model mapping`nweaveora:`n    base_path: $($ModelRoot.Replace('\','/'))`n    checkpoints: checkpoints`n    diffusion_models: diffusion_models`n    text_encoders: text_encoders`n    vae: vae`n    ipadapter: ipadapter`n    clip_vision: clip_vision`n    loras: loras`n    upscale_models: upscale_models`n    embeddings: embeddings`n"
    Set-Content -Path $extraYaml -Value $yaml -Encoding UTF8
} else { Log "extra_model_paths.yaml exists, skip" }

# 3g. start ComfyUI on 8188 if not running
$health = try { (Invoke-WebRequest -Uri "http://127.0.0.1:8188/system_stats" -TimeoutSec 3 -UseBasicParsing).StatusCode } catch { 0 }
if ($health -ne 200) {
    Log "Starting ComfyUI (port 8188, background) ..."
    Start-Process -FilePath $venvPy -ArgumentList "main.py","--port","8188" -WorkingDirectory $ComfyDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $ComfyDir "comfy.log") -RedirectStandardError (Join-Path $ComfyDir "comfy.err.log")
    Start-Sleep -Seconds 20
} else { Log "ComfyUI already running on 8188, skip" }

Log "ALL_DONE. GUI: http://127.0.0.1:8188  | models: $ModelRoot  | comfy: $ComfyDir"
