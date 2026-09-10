# ComfyUI reverse SSH tunnel + API local-forward (constant, self-healing)
# Remote forward:  175.12.60.225:127.0.0.1:18188 -> this host 127.0.0.1:8188   (ComfyUI)
# Local forward :  this host 127.0.0.1:18080  -> 175.12.60.225:127.0.0.1:8080 (weaveora API)
# 18188 only visible on 175.12.60.225 loopback (single-host whitelist).
# Single-instance mutex; reconnect loop; log to tunnel.log
$ErrorActionPreference = "SilentlyContinue"

$ssh = "C:\Windows\System32\OpenSSH\ssh.exe"
if (-not (Test-Path $ssh)) { $ssh = "C:\Program Files\Git\usr\bin\ssh.exe" }
$key = "C:\Users\Administrator\.ssh\comfy_tunnel_ed25519"
$server = "root@175.12.60.225"
$remote = "127.0.0.1:18188:127.0.0.1:8188"   # -R comfy
$local  = "127.0.0.1:18080:127.0.0.1:8080"   # -L api
$log = Join-Path $PSScriptRoot "tunnel.log"
$mutexName = "Global\ComfyReverseTunnel"

$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(0)) { exit }   # another instance already running
function Log($m) { Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Log "tunnel supervisor started (-R comfy 18188, -L api 18080)"
$up = $false
while ($true) {
    if (-not (Test-Path $key)) { Log "key missing: $key"; Start-Sleep -Seconds 30; continue }
    Log "connecting ssh -R $remote -L $local ($server) ..."
    $up = $true
    & $ssh -i $key -N -R $remote -L $local -p 22 `
        -o ServerAliveInterval=20 -o ServerAliveCountMax=3 `
        -o ExitOnForwardFailure=yes -o ConnectTimeout=10 `
        -o StrictHostKeyChecking=accept-new $server 2>> $log
    Log "ssh exited rc=$LASTEXITCODE; reconnect in 8s"
    Start-Sleep -Seconds 8
}
