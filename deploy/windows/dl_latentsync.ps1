# LatentSync 1.6 weights: 10-way Range segmented download + resume, background.
# (ASCII only -- PowerShell 5.1 reads BOM-less .ps1 as ANSI and would corrupt CJK text.)
#
# Rule ref: Weaveora.md section 0.2 "large file download iron rule"
#   - always 10-way Range segments + .meta.json resume (reuse gpu_model_downloader.js)
#   - never block the session: launch with Start-Process -WindowStyle Hidden
#   - progress log only (percent / total size / downloaded / speed), one line per 10s
#
# Start (background, returns immediately):
#   powershell -NoProfile -Command "Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','D:\workspace\Weaveora\deploy\windows\dl_latentsync.ps1' -WindowStyle Hidden -RedirectStandardOutput 'D:\model\_dl\latentsync_dl.log' -RedirectStandardError 'D:\model\_dl\latentsync_dl.err.log'"
#
# Check progress: type D:\model\_dl\latentsync_dl.log

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$node = 'C:\Program Files\nodejs\node.exe'
$dl   = 'D:\workspace\Weaveora\deploy\windows\gpu_model_downloader.js'
$ckpt = 'D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper\checkpoints'
# Mirror priority (2026-09-13 measured):
#   aifasthub.com  = ~1.7 MiB/s single conn, Range 206 -> USE THIS
#   hf-mirror.com  = ~0.08 MiB/s and drops concurrent TLS -> AVOID
$base = 'https://aifasthub.com/ByteDance/LatentSync-1.6/resolve/main'
$vae  = 'https://aifasthub.com/stabilityai/sd-vae-ft-mse/resolve/main'

# Sequential. unet is the big one (5GB); others are small.
$jobs = @(
  @{ url = "$base/latentsync_unet.pt";                 out = "$ckpt\latentsync_unet.pt";                      th = 10 },
  @{ url = "$base/stable_syncnet.pt";                  out = "$ckpt\stable_syncnet.pt";                      th = 10 },
  @{ url = "$base/whisper/tiny.pt";                    out = "$ckpt\whisper\tiny.pt";                        th = 10 },
  @{ url = "$base/config.json";                        out = "$ckpt\config.json";                            th = 4  },
  @{ url = "$vae/diffusion_pytorch_model.safetensors"; out = "$ckpt\vae\diffusion_pytorch_model.safetensors"; th = 10 },
  @{ url = "$vae/config.json";                         out = "$ckpt\vae\config.json";                        th = 4  }
)

Write-Output "[$(Get-Date -Format s)] LatentSync download START"
foreach ($j in $jobs) {
  New-Item -ItemType Directory -Force -Path (Split-Path $j.out) | Out-Null
  & $node $dl $j.url $j.out $j.th
  Write-Output "[$(Get-Date -Format s)] exit=$LASTEXITCODE  $($j.out)"
}
Write-Output "[$(Get-Date -Format s)] ALL_DONE"
