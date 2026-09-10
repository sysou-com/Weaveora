# Weaveora WSL TTS(CosyVoice2) supervisor — Windows 侧持有
#   wsl.exe 进程存活 = WSL 虚拟机存活；/data/audio/wsl_run_tts.sh 自身也有 pidfile 锁与重拉循环。
#   单实例互斥；日志 D:\ComfyUI\_setup\tts_win.log
$ErrorActionPreference = "SilentlyContinue"
$log = Join-Path $PSScriptRoot "tts_win.log"
$mutexName = "Global\WeaveoraWslTts"
$distro = "Ubuntu"
$inner = "/data/audio/wsl_run_tts.sh"

$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(0)) { exit }
function Log($m) { Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Log "wsl tts supervisor started (distro=$distro)"
while ($true) {
    Log "starting: wsl -d $distro -u root bash $inner"
    & wsl.exe -d $distro -u root bash $inner 2>&1 | ForEach-Object { Add-Content -Path $log -Value ("    " + $_) }
    Log "wsl command exited rc=$LASTEXITCODE; restart in 15s"
    Start-Sleep -Seconds 15
}
