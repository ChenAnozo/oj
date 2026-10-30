param(
    [ValidateSet(1,5,10,20,50,100,500)][int]$Users = 1,
    [string]$BaseUrl = 'http://127.0.0.1:18101',
    [string]$JMeterHome,
    [switch]$PrepareOnly,
    [switch]$Gui,
    [switch]$Fresh,
    [ValidateRange(10,600)][int]$TimeoutSeconds = 360,
    [string]$ResultDirectory,
    [ValidateSet('sum','knapsack','knapsack-full')][string]$Workload = 'sum'
)
$ErrorActionPreference = 'Stop'
if ($PrepareOnly -and $Gui) { throw 'Choose either -PrepareOnly or -Gui.' }
$python = (Get-Command python -ErrorAction Stop).Source
$helper = Join-Path $PSScriptRoot 'loadtest.py'
$uri = [Uri]$BaseUrl
if ($uri.Scheme -notin @('http','https') -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
    throw 'BaseUrl must be an HTTP(S) origin, for example http://127.0.0.1:18101.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')

# Check connectivity before creating fixtures or downloading JMeter.
try {
    $health = Invoke-RestMethod -Method Post -Uri ($BaseUrl + '/api/question/list/page/vo') -ContentType 'application/json' -Body '{"current":1,"pageSize":1}' -TimeoutSec 10
    if ($null -eq $health.code -or $health.code -ne 0) { throw 'Question API returned a business error.' }
} catch { throw ('Gateway unavailable at ' + $BaseUrl + '. Start the existing deployment or set -BaseUrl. ' + $_.Exception.Message) }

& $python $helper build --workload $Workload
if ($LASTEXITCODE -ne 0) { throw 'JMeter plan generation failed.' }
$fixtureUsers = [Math]::Max(10, $Users)
$prepareArgs = @($helper,'prepare','--base-url',$BaseUrl,'--users',([string]$fixtureUsers),'--workload',$Workload)
if ($Fresh) { $prepareArgs += '--fresh' }
& $python @prepareArgs
if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation failed. No submission test was started.' }
if ($PrepareOnly) {
    Write-Host ('Prepared at least ' + $fixtureUsers + ' test accounts, one question, accounts.csv and judge.jmx.')
    return
}

if (-not $JMeterHome) { $JMeterHome = $env:JMETER_HOME }
if (-not $JMeterHome) {
    $onPath = Get-Command jmeter.bat -ErrorAction SilentlyContinue
    if ($onPath) { $JMeterHome = Split-Path (Split-Path $onPath.Source -Parent) -Parent }
}
if (-not $JMeterHome) { $JMeterHome = & (Join-Path $PSScriptRoot 'Install-JMeter.ps1') }
$jmeter = Join-Path $JMeterHome 'bin/jmeter.bat'
if (-not (Test-Path -LiteralPath $jmeter)) { throw 'jmeter.bat not found. Set -JMeterHome to the extracted JMeter directory.' }
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Users + 'u-' + [Guid]::NewGuid().ToString('N').Substring(0,6)
$runDir = if ($ResultDirectory) { [IO.Path]::GetFullPath($ResultDirectory) } else { Join-Path $PSScriptRoot ('results/' + $runName) }
if (Test-Path -LiteralPath $runDir) { throw 'Result directory already exists; refusing to overwrite an earlier run.' }
$null = New-Item -ItemType Directory -Path $runDir -Force
$jmeterArgs = @(
    '-t',(Join-Path $PSScriptRoot 'judge.jmx'),
    ('-Jhost=' + $uri.DnsSafeHost),('-Jport=' + $uri.Port),('-Jprotocol=' + $uri.Scheme),
    ('-Jusers=' + $Users),'-Jramp=1','-Jpoll_ms=1000',
    ('-Jtimeout_seconds=' + $TimeoutSeconds),
    ('-Jaccounts_file=' + (Join-Path $PSScriptRoot '.local/accounts.csv')),
    ('-Jresult_dir=' + $runDir),'-j',(Join-Path $runDir 'jmeter.log')
)
if ($Gui) {
    Write-Host 'GUI mode: inspect the plan, then click Start. Each run submits real code.'
    & $jmeter @jmeterArgs
    return
}

Write-Host 'Waiting 6 seconds to avoid the configured per-user submission cooldown.'
Start-Sleep -Seconds 6
$jmeterArgs += @('-n','-l',(Join-Path $runDir 'samples.jtl'),'-e','-o',(Join-Path $runDir 'html'))
$started = [DateTime]::UtcNow
& $jmeter @jmeterArgs
$jmeterExit = $LASTEXITCODE
$metadata = @{
    users=$Users; baseUrl=$BaseUrl; workload=$Workload;
    startedAt=$started.ToString('o'); finishedAt=[DateTime]::UtcNow.ToString('o');
    jmeterExitCode=$jmeterExit;
    scope='One submission per account. A fixed-batch experiment, not sustained capacity.'
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'run.json') -Encoding UTF8
& $python $helper report $runDir --users $Users
$reportExit = $LASTEXITCODE
Write-Host ('Results: ' + $runDir)
if ($jmeterExit -ne 0 -or $reportExit -ne 0) { throw 'Test did not fully pass. Inspect summary.json, task-*.json and jmeter.log.' }
Write-Host 'PASS: every requested user received an Accepted verdict.'
