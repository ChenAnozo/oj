param([switch]$Build)
. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
& (Join-Path $PSScriptRoot 'Start-Services.ps1')
if ($Build) {
    $frontendDirectory = Join-Path $script:LocalProjectRoot 'yuoj-frontend'
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules'))) {
        throw 'Missing frontend dependencies. Run npm ci in yuoj-frontend first.'
    }
    Push-Location $frontendDirectory
    try {
        & npm.cmd run build:container
        if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
    } finally { Pop-Location }
    Invoke-LocalServices -DockerArguments @('--profile','website','build','frontend')
} else {
    & docker image inspect yuoj-frontend:local --format '{{.Id}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Missing website image. Run Start-Website.ps1 -Build first.' }
}
Invoke-LocalServices -DockerArguments @('--profile','website','up','-d','--no-deps','--wait','--wait-timeout','120','frontend')
$settings = Get-LocalEnvironmentSettings
$websitePort = if ($settings.WEBSITE_PORT) { $settings.WEBSITE_PORT } else { '18081' }
Write-Host ('Website ready: http://127.0.0.1:' + $websitePort)
Write-Host 'Run Test-Services.ps1 -Website -Exercise to verify Nginx, login and judging.'
