# Stop all OpenCall dev services.
Write-Host "[stop-dev] stopping OpenCall services..."
Get-Process -Name "livekit-server" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
foreach ($port in 3000, 4000) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue; Write-Host "[stop-dev] stopped pid $($_.OwningProcess) on :$port" } catch {}
  }
}
Write-Host "[stop-dev] done."
