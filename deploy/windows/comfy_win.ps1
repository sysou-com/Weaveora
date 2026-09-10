# Weaveora ComfyUI 服务 supervisor（本机常驻，单实例 + 崩溃自愈）
#   :8188 监听本机；供 SSH 反向隧道 18188 与 worker 使用。
#   日志：comfy.log / comfy.err.log（保持 docs/gpu-server-setup.md 记录的路径）
$ErrorActionPreference = "SilentlyContinue"
$python = "D:\ComfyUI\venv\Scripts\python.exe"
$dir = "D:\ComfyUI"
$log = Join-Path $PSScriptRoot "comfy_supervisor.log"
$mutexName = "Global\WeaveoraComfyUI"

$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(0)) { exit }   # 已有实例在跑
function Log($m) { Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Log "comfy supervisor started (:8188)"
while ($true) {
    Log "starting ComfyUI main.py --port 8188 ..."
    $p = Start-Process -FilePath $python -ArgumentList 'main.py', '--port', '8188' `
        -WorkingDirectory $dir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $dir "comfy.log") `
        -RedirectStandardError  (Join-Path $dir "comfy.err.log") -PassThru
    $p.WaitForExit()
    Log "ComfyUI exited rc=$($p.ExitCode); restart in 10s"
    Start-Sleep -Seconds 10
}
