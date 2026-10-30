param([switch]$Website, [ValidateRange(2,6)][int]$ParallelUsers = 6, [switch]$CheckInstanceExit,
      [ValidateRange(0,6)][int]$ExpectedPeak = 0)
. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
if ($ExpectedPeak -gt $ParallelUsers) { throw 'ExpectedPeak cannot exceed ParallelUsers.' }
$settings = Get-LocalEnvironmentSettings
$instanceCount = [int]$settings.SANDBOX_INSTANCES
if ($instanceCount -notin @(2,3)) { throw 'Start two or three sandbox instances first.' }
if ($ParallelUsers -lt $instanceCount) { throw 'ParallelUsers must cover every sandbox instance.' }
$sandboxServices = @(1..$instanceCount | ForEach-Object { if ($_ -eq 1) { 'code-sandbox' } else { 'code-sandbox-' + $_ } })
$expectedInstances = @(1..$instanceCount | ForEach-Object { 'sandbox-' + $_ })
$scaleProfiles = @('--profile','sandbox-scale','--profile','sandbox-scale-3')
$lastService = $sandboxServices[-1]
$null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $instanceCount
$port = if ($Website) { if ($settings.WEBSITE_PORT) { $settings.WEBSITE_PORT } else { '18081' } } else { $settings.GATEWAY_PORT }
$gateway = 'http://127.0.0.1:' + $port
$since = [DateTime]::UtcNow.AddSeconds(-1).ToString('o')
$judgeContainerId = (Invoke-LocalServices -DockerArguments @('ps','-q','yuoj-backend-judge-service') | Out-String).Trim()
$fixtures = @()
$questionId = 0L
$restoreLast = $false
$success = $false

function Invoke-ScaleApi {
    param($Session, [string]$Method, [string]$Path, $Body)
    $request = @{WebSession=$Session;Method=$Method;Uri=($gateway + $Path);TimeoutSec=30}
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    $result = Invoke-RestMethod @request
    if ($null -eq $result.code -or $result.code -ne 0) { throw ('Scale API failed: ' + $Path + '; ' + $result.message) }
    return $result.data
}

function Wait-ScaleResult {
    param($Session, [string]$SubmissionId)
    for ($attempt=0; $attempt -lt 120; $attempt++) {
        $result = Invoke-ScaleApi -Session $Session -Method Get -Path ('/api/question/question_submit/get/vo?id=' + $SubmissionId)
        if ($result.status -ge 2) {
            if ($result.status -ne 2 -or $result.judgeInfo.message -ne 'Accepted') {
                throw ('Scale submission failed: ' + $SubmissionId + '; ' + ($result | ConvertTo-Json -Depth 5 -Compress))
            }
            return
        }
        Start-Sleep -Seconds 1
    }
    throw ('Scale submission timed out: ' + $SubmissionId)
}

function Get-ScaleRoutes {
    param([string[]]$SubmissionIds)
    $routes = @{}
    $lines = @(& docker logs --since $since $judgeContainerId 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read judge routing evidence.' }
    foreach ($line in $lines) {
        if ([string]$line -match 'sandbox_call submission=(\d+) instance=(sandbox-\d+)\b') {
            if ($SubmissionIds -contains $Matches[1]) { $routes[$Matches[1]] = $Matches[2] }
        }
    }
    if ($routes.Count -ne $SubmissionIds.Count) { throw 'Missing per-submission sandbox routing evidence.' }
    return $routes
}

$javaCode = 'public class Main { public static void main(String[] args) throws Exception { java.util.Scanner in = new java.util.Scanner(System.in); Thread.sleep(2000); System.out.println(in.nextLong() + in.nextLong()); } }'
$cppCode = '#include <iostream>
#include <unistd.h>
int main() { long long a,b; std::cin >> a >> b; sleep(2); std::cout << a+b << std::endl; }'

try {
    for ($i=0; $i -lt $ParallelUsers; $i++) {
        $account = 'sandbox_scale_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
        $password = [Guid]::NewGuid().ToString('N')
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $userId = [long](Invoke-ScaleApi -Session $session -Method Post -Path '/api/user/register' -Body @{userAccount=$account;userPassword=$password;checkPassword=$password})
        $fixtures += [pscustomobject]@{Account=$account;UserId=$userId;Session=$session}
        $null = Invoke-ScaleApi -Session $session -Method Post -Path '/api/user/login' -Body @{userAccount=$account;userPassword=$password}
    }
    $questionId = [long](Invoke-ScaleApi -Session $fixtures[0].Session -Method Post -Path '/api/question/add' -Body @{
        title='Sandbox scale smoke';content='Add two integers.';answer='a+b';tags=@('sandbox-scale')
        judgeConfig=@{timeLimit=10000;memoryLimit=131072;stackLimit=10000}
        judgeCase=@(@{input='1 2';output='3'},@{input='4 5';output='9'})
    })

    # Separate sessions avoid the existing per-user one-in-flight restriction.
    $pool = [RunspaceFactory]::CreateRunspacePool(1, $ParallelUsers)
    $workers = @()
    $batch = @()
    $pool.Open()
    try {
        for ($i=0; $i -lt $ParallelUsers; $i++) {
            $language = if ($i % 2 -eq 0) { 'java' } else { 'cpp' }
            $code = if ($language -eq 'java') { $javaCode } else { $cppCode }
            $worker = [PowerShell]::Create()
            $worker.RunspacePool = $pool
            $null = $worker.AddScript(@'
param($base, $session, $questionId, $language, $code)
$ErrorActionPreference = 'Stop'
$body = @{questionId=$questionId;language=$language;code=$code} | ConvertTo-Json -Compress
$response = Invoke-RestMethod -Method Post -Uri ($base + '/api/question/question_submit/do') -WebSession $session -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
if ($null -eq $response.code -or $response.code -ne 0) { throw ('Concurrent submission failed: ' + $response.message) }
[string]$response.data
'@).AddArgument($gateway).AddArgument($fixtures[$i].Session).AddArgument($questionId).AddArgument($language).AddArgument($code)
            $workers += [pscustomobject]@{Worker=$worker;Pending=$worker.BeginInvoke();Index=$i;Language=$language}
        }
        foreach ($worker in $workers) {
            $output = @($worker.Worker.EndInvoke($worker.Pending))
            if ($worker.Worker.Streams.Error.Count -gt 0 -or $output.Count -ne 1 -or [string]$output[0] -notmatch '^\d+$') { throw 'Invalid concurrent submission response.' }
            $batch += [pscustomobject]@{Id=[string]$output[0];Language=$worker.Language;Session=$fixtures[$worker.Index].Session}
        }
    } finally {
        foreach ($worker in $workers) { if (-not $worker.Pending.IsCompleted) { $worker.Worker.Stop() }; $worker.Worker.Dispose() }
        $pool.Close(); $pool.Dispose()
    }
    foreach ($submission in $batch) { Wait-ScaleResult -Session $submission.Session -SubmissionId $submission.Id }
    $batchIds = @($batch | ForEach-Object { $_.Id })
    $routes = Get-ScaleRoutes -SubmissionIds $batchIds
    $usedInstances = @($routes.Values | Sort-Object -Unique)
    if (($usedInstances -join ',') -ne ($expectedInstances -join ',')) { throw 'Every sandbox instance must execute actual submissions.' }
    Write-Host ('PASS: ' + $ParallelUsers + ' concurrent users, Java/C++ Accepted, routed to all ' + $instanceCount + ' sandboxes.')
    foreach ($submission in $batch) { Write-Host ('  submission=' + $submission.Id + ' language=' + $submission.Language + ' instance=' + $routes[$submission.Id]) }

    # Execution start/end intervals prove overlap, rather than inferring it from total duration.
    $events = @()
    $capacities = @{}
    foreach ($service in $sandboxServices) {
        $container = (Invoke-LocalServices -DockerArguments ($scaleProfiles + @('ps','-q',$service)) | Out-String).Trim()
        $instance = if ($service -eq 'code-sandbox') { 'sandbox-1' } else { $service.Replace('code-sandbox-','sandbox-') }
        $capacityText = (& docker exec $container printenv SANDBOX_MAX_CONCURRENT | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $capacityText -notmatch '^[1-9][0-9]*$') { throw ('Cannot read capacity for ' + $instance) }
        $capacities[$instance] = [int]$capacityText
        $lines = @(& docker logs --timestamps --since $since $container 2>&1)
        foreach ($line in $lines) {
            if ([string]$line -match '^(\S+).*sandbox_execute(_done)? submission=(\d+) instance=(sandbox-\d+)\b') {
                if ($batchIds -contains $Matches[3]) {
                    $timestamp = [DateTimeOffset]::Parse(($Matches[1] -replace '(\.\d{7})\d+Z','$1Z'))
                    $delta = if ($Matches[2]) { -1 } else { 1 }
                    $events += [pscustomobject]@{Ticks=$timestamp.UtcDateTime.Ticks;Delta=$delta;Instance=$Matches[4]}
                }
            }
        }
    }
    if ($events.Count -ne 2 * $batch.Count) { throw 'Missing execution interval evidence.' }
    $active=0; $peak=0
    foreach ($event in ($events | Sort-Object Ticks,Delta)) { $active += $event.Delta; $peak = [Math]::Max($peak,$active) }
    if ($peak -lt 2 -or $active -ne 0) { throw 'Executions did not overlap or did not finish.' }
    Write-Host ('PASS: execution logs prove overlapping work; peak active=' + $peak)
    foreach ($instance in $capacities.Keys) {
        $instanceActive=0; $instancePeak=0
        foreach ($event in ($events | Where-Object { $_.Instance -eq $instance } | Sort-Object Ticks,Delta)) {
            $instanceActive += $event.Delta
            $instancePeak = [Math]::Max($instancePeak,$instanceActive)
        }
        if ($instanceActive -ne 0 -or $instancePeak -gt $capacities[$instance]) {
            throw ('Execution slot limit violated for ' + $instance)
        }
        Write-Host ('PASS: ' + $instance + ' peak=' + $instancePeak + '; configured slots=' + $capacities[$instance])
    }
    if ($ExpectedPeak -gt 0 -and $peak -ne $ExpectedPeak) {
        throw ('Expected execution peak ' + $ExpectedPeak + ', observed ' + $peak)
    }

    if ($CheckInstanceExit) {
        # All in-flight work has finished before this graceful exit test.
        $restoreLast = $true
        Invoke-LocalServices -DockerArguments ($scaleProfiles + @('stop','--timeout','45',$lastService))
        $null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount ($instanceCount - 1)
        Start-Sleep -Seconds 2
        $submissionId = [string](Invoke-ScaleApi -Session $fixtures[0].Session -Method Post -Path '/api/question/question_submit/do' -Body @{questionId=$questionId;language='java';code=$javaCode})
        Wait-ScaleResult -Session $fixtures[0].Session -SubmissionId $submissionId
        $remainingRoutes = Get-ScaleRoutes -SubmissionIds @($submissionId)
        if ($remainingRoutes[$submissionId] -notin $expectedInstances[0..($instanceCount - 2)]) { throw 'New work did not use a remaining sandbox.' }
        Write-Host ('PASS: after ' + $lastService + ' deregistration, new work uses ' + $remainingRoutes[$submissionId] + ' and is Accepted.')
    }
    foreach ($service in $sandboxServices) {
        if ($service -eq $lastService -and $restoreLast) { continue }
        Invoke-LocalContainerScript -Business -Service $service -Script 'test -z "$(find /workspace -mindepth 1 -maxdepth 1 -print -quit)"'
    }
    $success = $true
} finally {
    if ($restoreLast) {
        Invoke-LocalServices -DockerArguments ($scaleProfiles + @('up','-d','--no-deps','--wait','--wait-timeout','180',$lastService))
        $null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $instanceCount
        Invoke-LocalContainerScript -Business -Service $lastService -Script 'test -z "$(find /workspace -mindepth 1 -maxdepth 1 -print -quit)"'
        Write-Host ('PASS: ' + $lastService + ' restored; all ' + $instanceCount + ' instances registered.')
    }
    if ($success) {
        foreach ($fixture in $fixtures) {
            $null = Invoke-ScaleApi -Session $fixture.Session -Method Post -Path '/api/user/logout'
            if ($fixture.UserId -le 0 -or $fixture.Account -notmatch '^sandbox_scale_[a-f0-9]{12}$') { throw 'Invalid fixture ownership.' }
        }
        if ($questionId -le 0) { throw 'Invalid temporary question.' }
        $userIds = ($fixtures | ForEach-Object { [string]$_.UserId }) -join ','
        $ownerId = $fixtures[0].UserId
        $sql = 'DELETE FROM question_submit WHERE questionId=' + $questionId + ' AND userId IN (' + $userIds + '); DELETE FROM question WHERE id=' + $questionId + ' AND userId=' + $ownerId + '; DELETE FROM user WHERE id IN (' + $userIds + ');'
        $command = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -e "' + $sql + '"'
        Invoke-LocalContainerScript -Service mysql -Script $command
        Write-Host 'PASS: only temporary scale-test accounts, question and submissions were cleaned.'
    } else { Write-Warning 'Scale test failed; its uniquely named temporary records were retained for diagnosis.' }
}
