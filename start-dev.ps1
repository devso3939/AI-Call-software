# OpenCall AI — one-click dev startup (Windows).
# Checks prerequisites, fetches LiveKit binary, migrates DB, starts API + Web + LiveKit.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Fail($msg) { Write-Host "[start-dev] FAIL: $msg" -ForegroundColor Red; exit 1 }
function Check($name, $cmd) {
  try { $v = Invoke-Expression $cmd 2>$null; Write-Host "[start-dev] OK  $name ($v)"; return $true }
  catch { Write-Host "[start-dev] MISSING  $name"; return $false }
}

Write-Host "=== OpenCall AI dev startup ===" -ForegroundColor Cyan

$nodeOk = Check "Node.js" "node --version"
if (-not $nodeOk) { Fail "Node.js >= 20 is required: https://nodejs.org" }

# 0) local env file
if (-not (Test-Path ".env.local")) {
  Copy-Item ".env.example" ".env.local"
  Write-Host "[start-dev] created .env.local from template (safe local defaults)"
}

# 1) deps
if (-not (Test-Path "node_modules")) {
  Write-Host "[start-dev] installing npm dependencies (first run)..."
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { Fail "npm install failed" }
}

# 2) LiveKit binary
& powershell -ExecutionPolicy Bypass -File "infra/livekit/fetch-livekit.ps1"
if ($LASTEXITCODE -ne 0) { Fail "LiveKit fetch failed" }
$LK = "$root\infra\livekit\bin\livekit-server.exe"

# 3) stop previous instances
Write-Host "[start-dev] stopping previous instances (if any)..."
Get-Process -Name "livekit-server" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

foreach ($port in 3000, 4000, 7880) {
  $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($conn) { Write-Host "[start-dev] WARNING: port $port already in use (pid $($conn.OwningProcess))" }
}

# 4) start LiveKit
Write-Host "[start-dev] starting LiveKit (ws://127.0.0.1:7880)..."
Start-Process -FilePath $LK -ArgumentList "--dev" -WindowStyle Minimized
Start-Sleep -Seconds 2

# 5) start API
Write-Host "[start-dev] starting API (:4000)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev:api > logs-api.txt 2>&1" -WindowStyle Minimized

# 6) start Web
Write-Host "[start-dev] starting Web (:3000)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev:web > logs-web.txt 2>&1" -WindowStyle Minimized

# 7) wait for health
Write-Host "[start-dev] waiting for health checks..."
$deadline = (Get-Date).AddSeconds(90)
$apiOk = $false; $webOk = $false; $lkOk = $false
while ((Get-Date) -lt $deadline -and -not ($apiOk -and $webOk -and $lkOk)) {
  Start-Sleep -Seconds 2
  if (-not $apiOk) { try { $r = Invoke-RestMethod "http://127.0.0.1:4000/health" -TimeoutSec 2; if ($r.ok) { $apiOk = $true; Write-Host "[start-dev] API healthy" -ForegroundColor Green } } catch {} }
  if (-not $webOk) { try { $r = Invoke-WebRequest "http://localhost:3000" -TimeoutSec 2 -UseBasicParsing; if ($r.StatusCode -eq 200) { $webOk = $true; Write-Host "[start-dev] Web healthy" -ForegroundColor Green } } catch {} }
  if (-not $lkOk) { $c = Get-NetTCPConnection -LocalPort 7880 -State Listen -ErrorAction SilentlyContinue; if ($c) { $lkOk = $true; Write-Host "[start-dev] LiveKit listening" -ForegroundColor Green } }
}

Write-Host ""
if ($apiOk -and $webOk -and $lkOk) {
  Write-Host "=== OpenCall AI is running ===" -ForegroundColor Green
  Write-Host "  Web:     http://localhost:3000"
  Write-Host "  API:     http://127.0.0.1:4000/health"
  Write-Host "  LiveKit: ws://127.0.0.1:7880"
  Write-Host ""
  Write-Host "Test: open http://localhost:3000 in TWO browser profiles, register alice & bob, call each other."
  Write-Host "Logs: logs-api.txt / logs-web.txt in repo root. Stop with stop-dev.ps1"
} else {
  Write-Host "[start-dev] some services not healthy yet — check logs-api.txt / logs-web.txt" -ForegroundColor Yellow
}
