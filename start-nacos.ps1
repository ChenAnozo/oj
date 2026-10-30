param([string]$NacosHome = $env:NACOS_HOME)
$port = 8848
$conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
if ($conn) {
    Write-Host "[INFO] Nacos is already running on port $port"
} else {
    Write-Host "[INFO] Starting Nacos standalone..."
    if (-not $NacosHome -or -not (Test-Path -LiteralPath (Join-Path $NacosHome 'bin/startup.cmd'))) {
        throw 'Set NACOS_HOME or pass -NacosHome pointing to a local Nacos installation.'
    }
    Start-Process cmd -ArgumentList '/c', 'startup.cmd -m standalone' -WorkingDirectory (Join-Path $NacosHome 'bin') -WindowStyle Hidden
}
Start-Sleep -Seconds 2
