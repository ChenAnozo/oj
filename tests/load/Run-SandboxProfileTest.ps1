param([string]$JMeterHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $projectRoot 'deploy/local/Environment.Common.ps1')
$python = (Get-Command python -ErrorAction Stop).Source
$maven = (Get-Command mvn -ErrorAction Stop).Source

# Build and validate the changed sandbox before touching running containers.
Push-Location (Join-Path $projectRoot 'yuoj-code-sandbox')
try {
    & $maven -o package
    if ($LASTEXITCODE -ne 0) { throw 'Sandbox build/tests failed. Deployment was not changed.' }
} finally { Pop-Location }

# Verify idle queue and apply six consumers without submitting any tasks.
& (Join-Path $PSScriptRoot 'Run-ConsumerTest.ps1') -Consumers 6 -ConfigureOnly
Invoke-LocalServices -DockerArguments @('build','code-sandbox')
Invoke-LocalServices -DockerArguments @('up','-d','--no-deps','--no-build','--wait','--wait-timeout','180','code-sandbox')

$profileDir = Join-Path $PSScriptRoot ('results/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-sandbox-profile-' + [Guid]::NewGuid().ToString('N').Substring(0,6))
$null = New-Item -ItemType Directory -Path $profileDir -Force
$since = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
@{users=100; consumers=6; since=$since; workload='C++ sum; three cases'; scope='Startup image validation; per-submission image inspection removed; container lifecycle unchanged'} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $profileDir 'profile-run.json') -Encoding UTF8
try {
    $arguments = @{Users=100;ExpectedConsumers=6}
    if ($JMeterHome) { $arguments.JMeterHome = $JMeterHome }
    & (Join-Path $PSScriptRoot 'Run-MonitoredLoadTest.ps1') @arguments
} finally {
    # Export only timing lines, never source code, inputs or authentication details.
    $lines = @(& docker compose --project-name yuoj-services --env-file (Join-Path $projectRoot 'deploy/local/.env') -f (Join-Path $projectRoot 'docker-compose.service.yml') logs --no-color --since $since code-sandbox)
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Cannot export sandbox timing logs.'
    } else {
        $lines | Where-Object { $_ -match 'sandbox_timing task=' } |
            Set-Content -LiteralPath (Join-Path $profileDir 'sandbox-timing.log') -Encoding UTF8
        if (Test-Path -LiteralPath (Join-Path $profileDir 'sandbox-timing.log')) {
            & $python (Join-Path $PSScriptRoot 'sandbox_timing.py') (Join-Path $profileDir 'sandbox-timing.log') --output-dir $profileDir --expected 100
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Timing sample count is incomplete. Inspect the profile files before comparing.' }
        } else { Write-Warning 'No timing lines found. Check the deployed sandbox image and logging configuration.' }
    }
    Write-Host ('Sandbox profiling output: ' + $profileDir)
}
