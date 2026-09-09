# Downloads the OFFICIAL LiveKit server binary for Windows from GitHub releases (§5: official repos only; §104: not vendored).
param(
  [string]$DestDir = "$PSScriptRoot\bin"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
$exe = Join-Path $DestDir "livekit-server.exe"
if (Test-Path $exe) {
  Write-Host "[livekit] binary already present: $exe"
  & $exe --version
  exit 0
}

Write-Host "[livekit] resolving latest release from official repo (livekit/livekit)..."
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/livekit/livekit/releases/latest"
$asset = $null
foreach ($a in $rel.assets) {
  if ($a.name -like "*windows_amd64.zip") { $asset = $a; break }
}
if (-not $asset) {
  Write-Host "[livekit] no windows_amd64 asset in latest release. Download manually from https://github.com/livekit/livekit/releases"
  exit 1
}

$zip = Join-Path $env:TEMP $asset.name
Write-Host "[livekit] downloading $($asset.browser_download_url)"
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $DestDir -Force
Remove-Item $zip -ErrorAction SilentlyContinue

# binary may be nested in a folder inside the zip
$found = Get-ChildItem $DestDir -Recurse -Filter "livekit-server.exe" | Select-Object -First 1
if (-not $found) {
  Write-Host "[livekit] livekit-server.exe not found after extraction"
  exit 1
}
if ($found.FullName -ne $exe) { Move-Item $found.FullName $exe -Force }

Write-Host "[livekit] installed: $exe"
& $exe --version
