# Weaveora GPU 常驻任务修复（幂等）
# 问题：ComfyTunnelHeartbeat / ComfyWorkerHeartbeat 的 Action.Command 被 schtasks 写成
#       "-WindowStyle"（缺 powershell.exe 前缀）→ 每 10 分钟静默失败(0x80070002)，重启后无法自愈。
# 另：ComfyTunnel/ComfyWorker 只有 BootTrigger + InteractiveToken，重启后错过启动时机不再触发。
# 本脚本：① 重建 4 个任务，Execute 一律为 powershell.exe；② 主任务补 AtLogOn 触发。
$ErrorActionPreference = "Continue"
$sid = "S-1-5-21-76743959-3632852079-1463324553-500"   # Administrator
$tunnel = "D:\ComfyUI\_setup\tunnel_comfy.ps1"
$worker = "D:\ComfyUI\_setup\worker_win.ps1"
$comfy  = "D:\ComfyUI\_setup\comfy_win.ps1"
$wsltts = "D:\ComfyUI\_setup\wsl_tts_win.ps1"

function New-Act([string]$script) {
  New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument ("-WindowStyle Hidden -ExecutionPolicy Bypass -File `"{0}`"" -f $script)
}
$set = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -Hidden `
  -MultipleInstances IgnoreNew
$prin = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Highest

# --- 主任务：开机 + 登录时启动（互斥锁保证单实例，可安全叠加） ---
foreach ($item in @(@("ComfyTunnel", $tunnel), @("ComfyWorker", $worker), @("ComfyUI", $comfy), @("ComfyTTS", $wsltts))) {
  $name = $item[0]; $script = $item[1]
  $trig = @((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -AtLogOn -User $sid))
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Register-ScheduledTask -TaskName $name -Action (New-Act $script) -Trigger $trig `
    -Settings $set -Principal $prin -Force | Out-Null
  Write-Host "OK  $name  (AtStartup + AtLogOn)"
}

# --- 心跳任务：每 10 分钟兜底拉起（这是本次故障的修复点） ---
foreach ($item in @(@("ComfyTunnelHeartbeat", $tunnel), @("ComfyWorkerHeartbeat", $worker), @("ComfyUIHeartbeat", $comfy), @("ComfyTTSHeartbeat", $wsltts))) {
  $name = $item[0]; $script = $item[1]
  $rep = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
    -RepetitionInterval (New-TimeSpan -Minutes 10)
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Register-ScheduledTask -TaskName $name -Action (New-Act $script) -Trigger $rep `
    -Settings $set -Principal $prin -Force | Out-Null
  Write-Host "OK  $name  (every 10 min)"
}
