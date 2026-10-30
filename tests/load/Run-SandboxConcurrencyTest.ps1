param([string]$JMeterHome,
      [switch]$DatabaseDiagnostics,
      [ValidateSet(4,6,8,12,16,18,20,24)][int[]]$TotalConcurrency = @(4,6,8),
      [ValidateSet(0,2,3)][int]$SandboxInstances = 0,
      [ValidateSet(100,500)][int]$Users = 100,
      [ValidateSet('sum','knapsack','knapsack-full')][string]$Workload = 'sum')
$ErrorActionPreference = 'Stop'
$TotalConcurrency = @($TotalConcurrency | Select-Object -Unique)
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $projectRoot 'deploy/local/Environment.Common.ps1')
$settings = Get-LocalEnvironmentSettings
$queueUrl = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT + '/api/queues/%2F/code_queue'
$headers = Get-LocalRabbitHeaders -Settings $settings
if ($SandboxInstances -eq 0) { $SandboxInstances = [int]$settings.SANDBOX_INSTANCES }
if ($SandboxInstances -notin @(2,3)) { throw 'This comparison requires 2 or 3 configured sandbox instances.' }
if ([int]$settings.SANDBOX_INSTANCES -ne $SandboxInstances) { throw 'Requested instance count differs from saved deployment; start the requested instances first.' }
foreach ($total in $TotalConcurrency) {
    if ($total % $SandboxInstances -ne 0) { throw 'TotalConcurrency must divide evenly across the sandbox instances.' }
}
$instanceNames = @(1..$SandboxInstances | ForEach-Object { 'sandbox-' + $_ })
$sandboxServices = @(1..$SandboxInstances | ForEach-Object { if ($_ -eq 1) { 'code-sandbox' } else { 'code-sandbox-' + $_ } })
$profileArguments = @('--profile','sandbox-scale')
if ($SandboxInstances -eq 3) { $profileArguments += @('--profile','sandbox-scale-3') }
$services = @($sandboxServices) + @('yuoj-backend-judge-service')

function Wait-IdleJudgeQueue {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $queue = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 10
        foreach ($property in @('consumers','messages_ready','messages_unacknowledged')) {
            if ($null -eq $queue.$property) { throw ('Missing queue statistic: ' + $property) }
        }
        if ($queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0) { return $queue }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Queue is still busy; no service will be restarted.'
}

function Get-RunningSlots {
    param([string]$Service)
    $value = (Invoke-LocalServices -DockerArguments ($profileArguments + @('exec','-T',$Service,'printenv','SANDBOX_MAX_CONCURRENT')) | Out-String).Trim()
    if ($value -notmatch '^[1-9][0-9]*$') { throw ('Cannot read execution slots: ' + $Service) }
    return [int]$value
}

function Set-TestConcurrency {
    param([int]$Consumers, [int]$Slots)
    $null = Wait-IdleJudgeQueue
    $previousConsumers = $env:JUDGE_CONSUMERS
    $previousSlots = $env:SANDBOX_MAX_CONCURRENT
    try {
        $env:JUDGE_CONSUMERS = [string]$Consumers
        $env:SANDBOX_MAX_CONCURRENT = [string]$Slots
        Invoke-LocalServices -DockerArguments ($profileArguments + @('up','-d','--no-deps','--no-build','--wait','--wait-timeout','180') + $services)
    } finally {
        $env:JUDGE_CONSUMERS = $previousConsumers
        $env:SANDBOX_MAX_CONCURRENT = $previousSlots
    }
    $null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $SandboxInstances
    foreach ($service in $sandboxServices) {
        if ((Get-RunningSlots -Service $service) -ne $Slots) { throw ('Wrong running capacity: ' + $service) }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $queue = Wait-IdleJudgeQueue
        if ($queue.consumers -eq $Consumers) { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('Consumer count did not converge to ' + $Consumers)
}

function Get-ExecutionEvidence {
    param([string]$Directory, [string]$Since, [int]$Slots, [int]$Consumers, [int]$ExpectedUsers = 100)
    $ids = @{}
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $Directory 'load') -Filter 'task-*.json')) {
        $task = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
        if ($task.submissionId -notmatch '^[0-9]+$' -or $ids.ContainsKey([string]$task.submissionId)) { throw 'Missing or duplicate submission ID.' }
        $ids[[string]$task.submissionId] = $true
    }
    if ($ids.Count -ne $ExpectedUsers) { throw ('Expected ' + $ExpectedUsers + ' task records for execution evidence.') }
    $events = @()
    $evidence = @()
    $seen = @{}
    foreach ($service in $sandboxServices) {
        $container = (Invoke-LocalServices -DockerArguments ($profileArguments + @('ps','-q',$service)) | Out-String).Trim()
        if (-not $container) { throw ('Missing container: ' + $service) }
        $lines = @(& docker logs --timestamps --since $Since $container 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ('Cannot export execution evidence: ' + $service) }
        foreach ($line in $lines) {
            if ([string]$line -match '^(\S+).*sandbox_execute(_done)? submission=(\d+) instance=(sandbox-[1-3])\b') {
                $id=$Matches[3]; $instance=$Matches[4]; $done=[bool]$Matches[2]
                if (-not $ids.ContainsKey($id)) { continue }
                $key=$id + '/' + $done
                if ($seen.ContainsKey($key)) { throw ('Duplicate execution event: ' + $id) }
                $seen[$key]=$instance
                $timestamp=[DateTimeOffset]::Parse(($Matches[1] -replace '(\.\d{7})\d+Z','$1Z'))
                $events += [pscustomobject]@{Ticks=$timestamp.UtcDateTime.Ticks;Delta=$(if ($done) {-1} else {1});Instance=$instance}
                $evidence += [string]$line
            }
        }
    }
    $evidence | Set-Content -LiteralPath (Join-Path $Directory 'execution.log') -Encoding UTF8
    if ($events.Count -ne (2 * $ExpectedUsers)) { throw 'Missing execution start/end events.' }
    foreach ($id in $ids.Keys) {
        if ($seen[$id + '/False'] -ne $seen[$id + '/True']) { throw 'Execution start/end instance mismatch.' }
    }
    $active=0; $peak=0
    $instanceActive=@{}
    $instancePeaks=@{}
    foreach ($instance in $instanceNames) { $instanceActive[$instance]=0; $instancePeaks[$instance]=0 }
    foreach ($event in ($events | Sort-Object Ticks,Delta)) {
        if (-not $instanceActive.ContainsKey($event.Instance)) { throw 'Unexpected sandbox instance in execution evidence.' }
        $active += $event.Delta
        $instanceActive[$event.Instance] += $event.Delta
        $peak=[Math]::Max($peak,$active)
        $instancePeaks[$event.Instance]=[Math]::Max($instancePeaks[$event.Instance],$instanceActive[$event.Instance])
        if ($active -lt 0 -or $instanceActive[$event.Instance] -lt 0 -or $instanceActive[$event.Instance] -gt $Slots -or $active -gt $Consumers) {
            throw 'Execution events violate configured concurrency.'
        }
    }
    if ($active -ne 0) { throw 'Unfinished execution intervals.' }
    foreach ($instance in $instanceNames) {
        if ($instanceActive[$instance] -ne 0) { throw ('Unfinished execution intervals: ' + $instance) }
        if ($instancePeaks[$instance] -eq 0) { throw ('Instance did not execute any tasks: ' + $instance) }
    }
    return @{peak=$peak;instancePeaks=$instancePeaks}
}

# All preflight checks run before changing any deployment setting.
$null = & docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. No deployment setting was changed.' }
$originalQueue = Wait-IdleJudgeQueue
$originalSlots = Get-RunningSlots -Service code-sandbox
foreach ($service in $sandboxServices) {
    if ((Get-RunningSlots -Service $service) -ne $originalSlots) { throw 'This comparison requires identical slot settings across instances.' }
}
$originalConsumers = [int]$originalQueue.consumers
if ($originalConsumers -notin @(1,2,4,6,8,12,16,18,20,24)) { throw 'Unexpected starting consumer count.' }
$null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $SandboxInstances
$null = Get-Command python -ErrorAction Stop
if ($JMeterHome -and -not (Test-Path -LiteralPath (Join-Path $JMeterHome 'bin/jmeter.bat'))) { throw 'Invalid JMeterHome.' }
$matrixDir = Join-Path $PSScriptRoot ('results/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-concurrency-' + ($TotalConcurrency -join '-') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6))
$null = New-Item -ItemType Directory -Path $matrixDir
@{users=$Users;instances=$SandboxInstances;concurrency=@($TotalConcurrency);roundsPerSetting=1;originalConsumers=$originalConsumers;originalSlotsPerInstance=$originalSlots;workload=$Workload;scope='Fixed-batch concurrency comparison; not sustained capacity'} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $matrixDir 'comparison-run.json') -Encoding UTF8
$rows = @()
$changed = $false
try {
    foreach ($total in $TotalConcurrency) {
        $slots=[int]($total/$SandboxInstances)
        Write-Host ('Testing consumers=' + $total + '; slots per sandbox=' + $slots + '; users=' + $Users + '; rounds=1')
        $changed=$true
        Set-TestConcurrency -Consumers $total -Slots $slots
        $caseDir=Join-Path $matrixDir ([string]$total + '-concurrent')
        $null=New-Item -ItemType Directory -Path $caseDir
        $since=[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        $arguments=@{Users=$Users;Workload=$Workload;ExpectedConsumers=$total;MonitorDirectory=(Join-Path $caseDir 'monitor');ResultDirectory=(Join-Path $caseDir 'load')}
        if ($DatabaseDiagnostics) { $arguments.DatabaseDiagnostics=$true }
        if ($JMeterHome) { $arguments.JMeterHome=$JMeterHome }
        & (Join-Path $PSScriptRoot 'Run-MonitoredLoadTest.ps1') @arguments
        $null=Wait-IdleJudgeQueue
        $evidence=Get-ExecutionEvidence -Directory $caseDir -Since $since -Slots $slots -Consumers $total -ExpectedUsers $Users
        $summary=Get-Content -Raw -LiteralPath (Join-Path $caseDir 'load/summary.json') | ConvertFrom-Json
        if (-not $summary.allPassed) { throw 'Load test did not fully pass.' }
        $rowValues=[ordered]@{configuredConcurrency=$total;slotsPerInstance=$slots;observedPeak=$evidence.peak}
        for ($index=1; $index -le $SandboxInstances; $index++) {
            $rowValues['sandbox' + $index + 'Peak']=$evidence.instancePeaks['sandbox-' + $index]
        }
        $rowValues.accepted=$summary.accepted
        $rowValues.meanAcceptedMs=$summary.meanAcceptedMs
        $rowValues.p95AcceptedMs=$summary.p95AcceptedMs
        $row=[pscustomobject]$rowValues
        $rows += $row
        $rows | Export-Csv -LiteralPath (Join-Path $matrixDir 'comparison.csv') -NoTypeInformation -Encoding UTF8
        ConvertTo-Json -InputObject @($rows) -Depth 4 | Set-Content -LiteralPath (Join-Path $matrixDir 'comparison.json') -Encoding UTF8
        if ($evidence.peak -lt $total) { Write-Warning ('Configured ' + $total + ' slots but observed peak=' + $evidence.peak + '; do not claim full utilization.') }
    }
} finally {
    if ($changed) {
        # Never restart a service while judge messages are still in flight.
        try {
            Set-TestConcurrency -Consumers $originalConsumers -Slots $originalSlots
            Write-Host ('Restored consumers=' + $originalConsumers + '; slots per sandbox=' + $originalSlots)
        } catch {
            Write-Warning 'Automatic restoration failed; deployment may retain the last test setting. No busy queue was intentionally restarted.'
            Write-Warning $_.Exception.Message
        }
    }
    if ($rows.Count) { $rows | Format-Table -AutoSize | Out-Host }
    Write-Host ('Concurrency comparison output: ' + $matrixDir)
}
