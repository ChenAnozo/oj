param(
    [ValidateSet(10,20,50,100,500)][int]$Users = 100,
    [ValidateSet(1,2,4,6,8,12,16,18,20,24)][int]$ExpectedConsumers = 4,
    [string]$JMeterHome,
    [switch]$DatabaseDiagnostics,
    [string]$MonitorDirectory,
    [string]$ResultDirectory,
    [ValidateSet('sum','knapsack','knapsack-full')][string]$Workload = 'sum'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $projectRoot 'deploy/local/Environment.Common.ps1')
$settings = Get-LocalEnvironmentSettings
$queueUrl = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT + '/api/queues/%2F/code_queue'
$headers = Get-LocalRabbitHeaders -Settings $settings
$before = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 10
foreach ($property in @('consumers','messages_ready','messages_unacknowledged')) {
    if ($null -eq $before.$property) { throw ('Queue statistics missing: ' + $property) }
}
if ($before.consumers -ne $ExpectedConsumers) { throw ('Expected ' + $ExpectedConsumers + ' consumers. Check the deployment before testing.') }
if ($before.messages_ready -ne 0 -or $before.messages_unacknowledged -ne 0) {
    throw 'Existing judge tasks are pending. Wait until code_queue is idle.'
}
& docker stats --no-stream --format '{{json .}}' > $null
if ($LASTEXITCODE -ne 0) { throw 'Docker statistics are unavailable. Test was not started.' }
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-monitor-' + $Users + 'u-' + [Guid]::NewGuid().ToString('N').Substring(0,6)
$monitorDir = if ($MonitorDirectory) { [IO.Path]::GetFullPath($MonitorDirectory) } else { Join-Path $PSScriptRoot ('results/' + $runName) }
if (Test-Path -LiteralPath $monitorDir) { throw 'Monitoring directory already exists; refusing to overwrite an earlier run.' }
$null = New-Item -ItemType Directory -Path $monitorDir -Force
Write-Host ('Monitoring output: ' + $monitorDir)
@{users=$Users; workload=$Workload; databaseDiagnostics=[bool]$DatabaseDiagnostics; expectedConsumers=$ExpectedConsumers; startedAt=[DateTime]::UtcNow.ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $monitorDir 'monitor-run.json') -Encoding UTF8

$diagnosticJob = $null
$diagnosticSince = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
if ($DatabaseDiagnostics) {
    Invoke-LocalContainerScript -Service yuoj-backend-question-service -Business -Script 'test "$JUDGE_DIAGNOSTICS_ENABLED" = true || { echo "Enable JUDGE_DIAGNOSTICS_ENABLED=true and recreate the question service first."; exit 1; }'
    # Fail before load begins if MySQL diagnostics are unavailable.
    $initial = @(& (Join-Path $PSScriptRoot 'Get-DatabaseDiagnosticSample.ps1'))
    foreach ($line in $initial) { $null = $line | ConvertFrom-Json }
    $serverSample = @($initial | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.kind -eq 'server' })
    if ($serverSample.Count -ne 1 -or [int]$serverSample[0].performanceSchema -ne 1) {
        throw 'MySQL performance_schema is unavailable. Test was not started.'
    }
    if ($serverSample[0].statementCurrentEnabled -ne 'YES') {
        Write-Warning 'Current-statement instrumentation is disabled; session digests may be unavailable. Lock and pool sampling will continue.'
    }
    $initial | Set-Content -LiteralPath (Join-Path $monitorDir 'database.jsonl') -Encoding UTF8
    $diagnosticJob = Start-Job -ArgumentList $projectRoot,$monitorDir -ScriptBlock {
        param($projectRoot,$monitorDir)
        $ErrorActionPreference = 'Stop'
        while ($true) {
            try {
                $sample = @(& (Join-Path $projectRoot 'tests/load/Get-DatabaseDiagnosticSample.ps1') 2>$null)
                foreach ($line in $sample) { $null = $line | ConvertFrom-Json }
                $sample | Add-Content -LiteralPath (Join-Path $monitorDir 'database.jsonl') -Encoding UTF8
            } catch {
                ([DateTime]::UtcNow.ToString('o') + ' Database sampling failed; exceptionType=' + $_.Exception.GetType().FullName) |
                    Add-Content -LiteralPath (Join-Path $monitorDir 'database-errors.txt') -Encoding UTF8
            }
            Start-Sleep -Seconds 2
        }
    }
}

# Independent PowerShell process samples while JMeter runs in the foreground.
# Credentials stay inside the process and are never written to the results.
$job = Start-Job -ArgumentList $projectRoot,$monitorDir -ScriptBlock {
    param($projectRoot,$monitorDir)
    $ErrorActionPreference = 'Stop'
    . (Join-Path $projectRoot 'deploy/local/Environment.Common.ps1')
    $settings = Get-LocalEnvironmentSettings
    $queueUrl = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT + '/api/queues/%2F/code_queue'
    $headers = Get-LocalRabbitHeaders -Settings $settings
    $queueFile = Join-Path $monitorDir 'queue.csv'
    $containerFile = Join-Path $monitorDir 'containers.csv'
    $culture = [Globalization.CultureInfo]::InvariantCulture
    while ($true) {
        $stage = 'RabbitMQ query'
        try {
            $queue = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 5
            foreach ($property in @('consumers','messages_ready','messages_unacknowledged')) {
                if ($null -eq $queue.$property) { throw ('Queue statistics missing: ' + $property) }
            }
            [pscustomobject]@{
                observedAt=[DateTime]::UtcNow.ToString('o')
                consumers=$queue.consumers; ready=$queue.messages_ready
                unacked=$queue.messages_unacknowledged
            } | Export-Csv -LiteralPath $queueFile -Append -NoTypeInformation -Encoding UTF8
            $stage = 'Docker stats query'
            $stats = @(& docker stats --no-stream --format '{{json .}}' 2>$null)
            if ($LASTEXITCODE -ne 0) { throw 'Docker statistics sampling failed.' }
            foreach ($line in $stats) {
                $stage = 'Docker stats parsing/export'
                if (-not $line.Trim()) { continue }
                $item = $line | ConvertFrom-Json
                [pscustomobject]@{
                    observedAt=[DateTime]::UtcNow.ToString('o'); name=$item.Name
                    cpuPercent=[double]::Parse($item.CPUPerc.TrimEnd('%'),$culture)
                    memoryPercent=[double]::Parse($item.MemPerc.TrimEnd('%'),$culture)
                    memoryUsage=$item.MemUsage; pids=$item.PIDs
                } | Export-Csv -LiteralPath $containerFile -Append -NoTypeInformation -Encoding UTF8
            }
        } catch {
            # Do not persist raw HTTP errors, which could contain request details.
            ([DateTime]::UtcNow.ToString('o') + ' Sampling failed; stage=' + $stage + '; exceptionType=' + $_.Exception.GetType().FullName) |
                Add-Content -LiteralPath (Join-Path $monitorDir 'monitor-errors.txt') -Encoding UTF8
        }
        Start-Sleep -Seconds 2
    }
}
try {
    $loadArguments = @{Users=$Users; Workload=$Workload; BaseUrl=('http://127.0.0.1:' + $settings.GATEWAY_PORT)}
    if ($ResultDirectory) { $loadArguments.ResultDirectory = $ResultDirectory }
    if ($JMeterHome) { $loadArguments.JMeterHome = $JMeterHome }
    & (Join-Path $PSScriptRoot 'Run-LoadTest.ps1') @loadArguments
} finally {
    if ($null -ne $diagnosticJob) {
        Stop-Job -Job $diagnosticJob
        Receive-Job -Job $diagnosticJob
        Remove-Job -Job $diagnosticJob
        try {
            Invoke-LocalServices -DockerArguments @('logs','--no-color','--timestamps','--since',$diagnosticSince,
                'yuoj-backend-question-service','yuoj-backend-gateway','yuoj-backend-judge-service') |
                Set-Content -LiteralPath (Join-Path $monitorDir 'submission-diagnostics.log') -Encoding UTF8
        } catch { Write-Warning 'Diagnostic service log export failed.' }
        if (Test-Path -LiteralPath (Join-Path $monitorDir 'database-errors.txt')) {
            Write-Warning 'Some database samples failed; inspect database-errors.txt.'
        }
    }
    Stop-Job -Job $job
    Receive-Job -Job $job
    Remove-Job -Job $job
    try {
        $after = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 5
        Write-Host ('After: consumers=' + $after.consumers + ', Ready=' + $after.messages_ready + ', Unacked=' + $after.messages_unacknowledged)
    } catch {
        Write-Warning 'Cannot read the final queue state.'
    }
    $queueFile = Join-Path $monitorDir 'queue.csv'
    if (Test-Path -LiteralPath $queueFile) {
        $rows = @(Import-Csv -LiteralPath $queueFile)
        $peakReady = ($rows | ForEach-Object { [int]$_.ready } | Measure-Object -Maximum).Maximum
        $peakUnacked = ($rows | ForEach-Object { [int]$_.unacked } | Measure-Object -Maximum).Maximum
        Write-Host ('Observed queue peaks: Ready=' + $peakReady + ', Unacked=' + $peakUnacked)
    }
    $containerFile = Join-Path $monitorDir 'containers.csv'
    if (Test-Path -LiteralPath $containerFile) {
        $summary = @(Import-Csv -LiteralPath $containerFile | Group-Object name | ForEach-Object {
            $group = $_
            $cpu = ($group.Group | ForEach-Object { [double]::Parse($_.cpuPercent,[Globalization.CultureInfo]::InvariantCulture) } | Measure-Object -Maximum).Maximum
            $memory = ($group.Group | ForEach-Object { [double]::Parse($_.memoryPercent,[Globalization.CultureInfo]::InvariantCulture) } | Measure-Object -Maximum).Maximum
            [pscustomobject]@{container=$group.Name; peakCpuPercent=$cpu; peakMemoryPercent=$memory}
        })
        $summary | Export-Csv -LiteralPath (Join-Path $monitorDir 'container-peaks.csv') -NoTypeInformation -Encoding UTF8
        $summary | Format-Table -AutoSize | Out-Host
    }
    if (Test-Path -LiteralPath (Join-Path $monitorDir 'monitor-errors.txt')) {
        Write-Warning 'Some monitoring samples failed; inspect monitor-errors.txt.'
    }
    Write-Host ('Monitoring output: ' + $monitorDir)
}
