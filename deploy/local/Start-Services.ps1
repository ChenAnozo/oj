param([switch]$Build, [ValidateRange(0,3)][int]$SandboxInstances = 0)
. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
& (Join-Path $PSScriptRoot 'Start-Environment.ps1')
& (Join-Path $PSScriptRoot 'Update-JudgeCaseStorage.ps1')
$settings = Get-LocalEnvironmentSettings
if ($SandboxInstances -eq 0) {
    $SandboxInstances = if ($settings.SANDBOX_INSTANCES) { [int]$settings.SANDBOX_INSTANCES } else { 1 }
}
if ($SandboxInstances -notin @(1,2,3)) { throw 'SANDBOX_INSTANCES must be 1, 2 or 3.' }

$volumeName = 'yuoj-local-sandbox-work'
$null = & docker volume create --label com.yuoj.purpose=local-sandbox $volumeName
if ($LASTEXITCODE -ne 0) { throw 'Cannot create sandbox workspace volume' }
$mountpoint = & docker volume inspect $volumeName --format '{{.Mountpoint}}'
if ($LASTEXITCODE -ne 0 -or -not $mountpoint.StartsWith('/')) { throw 'Cannot resolve Linux sandbox volume mountpoint' }

$settings = Get-LocalEnvironmentSettings
$newValues = [ordered]@{}
$newValues.SANDBOX_INSTANCES = [string]$SandboxInstances
for ($instanceNumber = 2; $instanceNumber -le $SandboxInstances; $instanceNumber++) {
    $extraVolume = 'yuoj-local-sandbox-work-' + $instanceNumber
    $null = & docker volume create --label com.yuoj.purpose=local-sandbox $extraVolume
    if ($LASTEXITCODE -ne 0) { throw ('Cannot create sandbox workspace: ' + $extraVolume) }
    $extraMountpoint = & docker volume inspect $extraVolume --format '{{.Mountpoint}}'
    if ($LASTEXITCODE -ne 0 -or -not $extraMountpoint.StartsWith('/')) { throw ('Cannot resolve workspace: ' + $extraVolume) }
    $newValues['SANDBOX' + $instanceNumber + '_HOST_WORKSPACE_ROOT'] = $extraMountpoint.Trim()
}
if (-not $settings.SANDBOX_AUTH_SECRET) {
    $secretBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
    $newValues.SANDBOX_AUTH_SECRET = [BitConverter]::ToString($secretBytes).Replace('-', '').ToLowerInvariant()
}
$newValues.SANDBOX_HOST_WORKSPACE_ROOT = $mountpoint.Trim()
if (-not $settings.GATEWAY_PORT) { $newValues.GATEWAY_PORT = '18101' }
if (-not $settings.SANDBOX_PORT) { $newValues.SANDBOX_PORT = '18090' }
$envLines = @([System.IO.File]::ReadAllLines($script:LocalEnvFile))
foreach ($key in $newValues.Keys) {
    $envLines = @($envLines | Where-Object { -not $_.StartsWith($key + '=') })
    $envLines += $key + '=' + $newValues[$key]
}
[System.IO.File]::WriteAllText($script:LocalEnvFile, ($envLines -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))

$runtimeImages = @(
    'eclipse-temurin:8-jre-jammy@sha256:49b31e882495abeb1118de417ee696bdc929d2de238730920f1468025fc393a4',
    'alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8'
)
foreach ($image in $runtimeImages) {
    & docker image inspect $image --format '{{.Id}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        & docker pull $image
        if ($LASTEXITCODE -ne 0) { throw ('Cannot prepare runtime image: ' + $image) }
    }
}
$profileArguments = @()
if ($SandboxInstances -ge 2) { $profileArguments += @('--profile','sandbox-scale') }
if ($SandboxInstances -ge 3) { $profileArguments += @('--profile','sandbox-scale-3') }
Invoke-LocalServices -DockerArguments ($profileArguments + @('config', '--quiet'))
if ($Build) {
    foreach ($serviceName in @('yuoj-backend-user-service','yuoj-backend-question-service','yuoj-backend-judge-service','yuoj-backend-gateway','code-sandbox')) {
        Invoke-LocalServices -DockerArguments @('build', $serviceName)
    }
}
for ($instanceNumber = $SandboxInstances + 1; $instanceNumber -le 3; $instanceNumber++) {
    Invoke-LocalServices -DockerArguments @('--profile','sandbox-scale','--profile','sandbox-scale-3','stop',('code-sandbox-' + $instanceNumber))
}
Invoke-LocalServices -DockerArguments ($profileArguments + @('up', '-d', '--wait', '--wait-timeout', '300'))
$settings = Get-LocalEnvironmentSettings
$null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $SandboxInstances
Write-Host ('PASS: ' + $SandboxInstances + ' sandbox instance(s) registered in Nacos.')
Write-Host 'Business containers are ready. Run Test-Services.ps1 for API and judging checks.'
