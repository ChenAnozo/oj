param(
    [ValidateSet(1,2,4,6,8,12,16)][int]$Consumers = 4,
    [ValidateSet(10,20,50,100)][int]$Users = 10,
    [switch]$Monitor,
    [switch]$ConfigureOnly,
    [string]$JMeterHome
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $projectRoot 'deploy/local/Environment.Common.ps1')
$settings = Get-LocalEnvironmentSettings
$queueUrl = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT + '/api/queues/%2F/code_queue'
$headers = Get-LocalRabbitHeaders -Settings $settings

function Get-JudgeQueueSnapshot {
    $queue = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 10
    foreach ($property in @('consumers','messages_ready','messages_unacknowledged')) {
        if ($null -eq $queue.$property) { throw ('RabbitMQ queue statistics missing: ' + $property) }
    }
    return @{
        consumers=[int]$queue.consumers
        ready=[int]$queue.messages_ready
        unacked=[int]$queue.messages_unacknowledged
        observedAt=[DateTime]::UtcNow.ToString('o')
    }
}

$before = Get-JudgeQueueSnapshot
Write-Host ('Before: consumers=' + $before.consumers + ', Ready=' + $before.ready + ', Unacked=' + $before.unacked)
if ($before.ready -ne 0 -or $before.unacked -ne 0) { throw 'Existing judge tasks are pending. Wait until code_queue is idle before retrying.' }
if ($before.consumers -notin @(1,2,4,6,8,12,16)) { throw 'Unexpected consumer count. Check for other judge instances before comparing.' }

# Recreate this service from the existing image; do not rebuild or restart dependencies.
$oldConsumerSetting = [Environment]::GetEnvironmentVariable('JUDGE_CONSUMERS', 'Process')
try {
    $env:JUDGE_CONSUMERS = [string]$Consumers
    Invoke-LocalServices -DockerArguments @('up','-d','--no-deps','--no-build','yuoj-backend-judge-service')
} finally {
    [Environment]::SetEnvironmentVariable('JUDGE_CONSUMERS', $oldConsumerSetting, 'Process')
}
$deadline = [DateTime]::UtcNow.AddMinutes(3)
$after = $null
$ready = $false
do {
    $containerId = (& docker compose --project-name yuoj-services --env-file (Join-Path $projectRoot 'deploy/local/.env') -f (Join-Path $projectRoot 'docker-compose.service.yml') ps -q yuoj-backend-judge-service | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the judge service.' }
    if ($containerId) {
        $health = (& docker inspect --format '{{.State.Health.Status}}' $containerId | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect judge health.' }
        $after = Get-JudgeQueueSnapshot
        Write-Host ('Judge health=' + $health + '; consumers=' + $after.consumers)
        if ($health -eq 'healthy' -and $after.consumers -eq $Consumers) { $ready = $true; break }
    }
    Start-Sleep -Seconds 5
} while ([DateTime]::UtcNow -lt $deadline)
if (-not $ready) { throw ('Judge did not become healthy with exactly ' + $Consumers + ' consumers within three minutes. Inspect its logs.') }
if ($after.ready -ne 0 -or $after.unacked -ne 0) { throw 'Queue became busy. Test was not started.' }
$localDir = Join-Path $PSScriptRoot '.local'
$null = New-Item -ItemType Directory -Path $localDir -Force
@{before=$before;after=$after;targetConsumers=$Consumers;scope='Judge service only; existing image; fixed consumer count'} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $localDir 'last-consumer-check.json') -Encoding UTF8
if ($ConfigureOnly) {
    Write-Host ('Configured and verified ' + $Consumers + ' consumers. No load test was started.')
    return
}
$loadArguments = @{Users=$Users}
if ($JMeterHome) { $loadArguments.JMeterHome = $JMeterHome }
if ($Monitor) {
    $loadArguments.ExpectedConsumers = $Consumers
    & (Join-Path $PSScriptRoot 'Run-MonitoredLoadTest.ps1') @loadArguments
} else {
    $loadArguments.BaseUrl = 'http://127.0.0.1:' + $settings.GATEWAY_PORT
    & (Join-Path $PSScriptRoot 'Run-LoadTest.ps1') @loadArguments
}
