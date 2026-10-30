param([switch]$Exercise, [switch]$Frontend, [switch]$Website)
. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
$settings = Get-LocalEnvironmentSettings
if ($Frontend -and $Website) { throw 'Choose either -Frontend or -Website.' }
$gateway = 'http://127.0.0.1:' + $settings.GATEWAY_PORT
if ($Website) {
    $websitePort = if ($settings.WEBSITE_PORT) { $settings.WEBSITE_PORT } else { '18081' }
    $gateway = 'http://127.0.0.1:' + $websitePort
} elseif ($Frontend) {
    $gateway = 'http://127.0.0.1:18080'
}
if ($Frontend -or $Website) {
    $page = Invoke-WebRequest -UseBasicParsing -Uri ($gateway + '/user/login') -Headers @{Accept='text/html'} -TimeoutSec 30
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'id="app"') { throw 'Frontend page is unavailable' }
    Write-Host ('PASS: frontend page and history routing at ' + $gateway)
}
$sandbox = 'http://127.0.0.1:' + $settings.SANDBOX_PORT
$apiSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-ApplicationApi {
    param([string]$Method, [string]$Path, $Body)
    $request = @{Method=$Method;Uri=($gateway + $Path);WebSession=$apiSession;TimeoutSec=45}
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))
    }
    $response = Invoke-RestMethod @request
    if ($null -eq $response.code -or $response.code -ne 0) {
        throw ('Application API failed: ' + $Path + '; code=' + $response.code + '; message=' + $response.message)
    }
    return $response.data
}

$services = @(Get-LocalComposeServiceStatus -Business)
foreach ($serviceName in @('yuoj-backend-gateway','yuoj-backend-user-service','yuoj-backend-question-service','yuoj-backend-judge-service','code-sandbox')) {
    $found = @($services | Where-Object { $_.Service -eq $serviceName })
    if ($found.Count -ne 1 -or $found[0].State -ne 'running' -or $found[0].Health -ne 'healthy') { throw ('Unhealthy business service: ' + $serviceName) }
}
$null = Invoke-ApplicationApi -Method Post -Path '/api/question/list/page/vo' -Body @{current=1;pageSize=1}
Write-Host 'PASS: five business containers are healthy and gateway question API works.'
if ($Website) {
    $websiteContainer = @($services | Where-Object { $_.Service -eq 'frontend' })
    if ($websiteContainer.Count -ne 1 -or $websiteContainer[0].State -ne 'running' -or $websiteContainer[0].Health -ne 'healthy') { throw 'Nginx frontend is not healthy' }
    $health = Invoke-WebRequest -UseBasicParsing -Uri ($gateway + '/healthz') -TimeoutSec 10
    if ($health.Content.Trim() -ne 'ok') { throw 'Nginx health endpoint failed' }
    $missingAssetIs404 = $false
    try { $null = Invoke-WebRequest -UseBasicParsing -Uri ($gateway + '/missing-deployment-check.js') -TimeoutSec 10 }
    catch { $missingAssetIs404 = [int]$_.Exception.Response.StatusCode -eq 404 }
    if (-not $missingAssetIs404) { throw 'Missing static assets must return 404, not the SPA page' }
    Write-Host 'PASS: Nginx frontend health, SPA routing and missing static asset response.'
}

$token = Get-LocalNacosAccessToken -Settings $settings
foreach ($serviceName in @('yuoj-backend-gateway','yuoj-backend-user-service','yuoj-backend-question-service','yuoj-backend-judge-service')) {
    try {
        $uri = 'http://127.0.0.1:' + $settings.NACOS_HTTP_PORT + '/nacos/v1/ns/instance/list?healthyOnly=true&serviceName=' + $serviceName + '&accessToken=' + [Uri]::EscapeDataString($token)
        $registered = Invoke-RestMethod -Uri $uri -TimeoutSec 15
        if (@($registered.hosts).Count -lt 1) { throw 'No healthy registered instance' }
    } catch { throw ('Nacos registration check failed: ' + $serviceName) }
}
Write-Host 'PASS: all four backend applications have healthy Nacos registrations.'
$expectedSandboxes = if ($settings.SANDBOX_INSTANCES) { [int]$settings.SANDBOX_INSTANCES } else { 1 }
$null = Wait-LocalSandboxInstances -Settings $settings -ExpectedCount $expectedSandboxes
for ($instanceNumber = 2; $instanceNumber -le $expectedSandboxes; $instanceNumber++) {
    $extraService = 'code-sandbox-' + $instanceNumber
    $extraSandbox = @($services | Where-Object { $_.Service -eq $extraService })
    if ($extraSandbox.Count -ne 1 -or $extraSandbox[0].State -ne 'running' -or $extraSandbox[0].Health -ne 'healthy') { throw ('Unhealthy sandbox: ' + $extraService) }
}
Write-Host ('PASS: ' + $expectedSandboxes + ' healthy sandbox instance(s) in Nacos.')
if (-not $Exercise) {
    Write-Host 'Read-only checks passed. Use -Exercise for temporary registration, login and actual Java/C++ judging.'
    return
}

$javaCode = 'public class Main { public static void main(String[] args) { java.util.Scanner in = new java.util.Scanner(System.in); System.out.println(in.nextLong() + in.nextLong()); } }'
$cppCode = '#include <iostream>
int main() { long long a, b; std::cin >> a >> b; std::cout << a + b << std::endl; }'
$sandboxHeaders = @{auth=$settings.SANDBOX_AUTH_SECRET}
$forbidden = $false
try {
    $null = Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($sandbox + '/executeCode') -ContentType 'application/json' -Body (@{language='java';code=$javaCode;inputList=@('1 2')} | ConvertTo-Json) -TimeoutSec 10
} catch { $forbidden = [int]$_.Exception.Response.StatusCode -eq 403 }
if (-not $forbidden) { throw 'Sandbox did not reject a missing authentication header' }
foreach ($case in @(@{language='java';code=$javaCode},@{language='cpp';code=$cppCode})) {
    $body = @{language=$case.language;code=$case.code;inputList=@('1 2','4 5')} | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri ($sandbox + '/executeCode') -Headers $sandboxHeaders -ContentType 'application/json' -Body $body -TimeoutSec 60
    if ($response.status -ne 2 -or ($response.outputList -join ',') -ne '3,9') { throw ('Sandbox execution failed: ' + $case.language + '; ' + ($response | ConvertTo-Json -Depth 5 -Compress)) }
}
Write-Host 'PASS: sandbox auth, Docker execution, shared workspace and Java/C++ stdin/output.'

$account = 'deploy_smoke_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$password = [Guid]::NewGuid().ToString('N')
$userId = [long](Invoke-ApplicationApi -Method Post -Path '/api/user/register' -Body @{userAccount=$account;userPassword=$password;checkPassword=$password})
$null = Invoke-ApplicationApi -Method Post -Path '/api/user/login' -Body @{userAccount=$account;userPassword=$password}
$loginUser = Invoke-ApplicationApi -Method Get -Path '/api/user/get/login'
if ([long]$loginUser.id -ne $userId) { throw 'Login session did not persist through gateway' }
$questionId = [long](Invoke-ApplicationApi -Method Post -Path '/api/question/add' -Body @{
    title='Deployment smoke test';content='Read two integers and print their sum.';tags=@('deployment-smoke');answer='a+b'
    judgeCase=@(@{input='1 2';output='3'},@{input='4 5';output='9'})
    judgeConfig=@{timeLimit=10000;memoryLimit=131072;stackLimit=10000}
})
$lastSubmit = [DateTime]::MinValue
foreach ($case in @(@{language='java';code=$javaCode},@{language='cpp';code=$cppCode})) {
    $elapsed = ([DateTime]::UtcNow - $lastSubmit).TotalSeconds
    if ($elapsed -lt 6) { Start-Sleep -Milliseconds ([int]((6 - $elapsed) * 1000)) }
    $submissionId = [long](Invoke-ApplicationApi -Method Post -Path '/api/question/question_submit/do' -Body @{questionId=$questionId;language=$case.language;code=$case.code})
    $lastSubmit = [DateTime]::UtcNow
    $completed = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $submission = Invoke-ApplicationApi -Method Get -Path ('/api/question/question_submit/get/vo?id=' + $submissionId)
        if ($submission.status -ge 2) {
            if ($submission.status -ne 2 -or $submission.judgeInfo.message -ne 'Accepted') {
                throw ('End-to-end judging failed: ' + $case.language + '; ' + ($submission | ConvertTo-Json -Depth 6 -Compress))
            }
            $completed = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $completed) { throw ('Judging did not finish: ' + $case.language) }
    Write-Host ('PASS: gateway -> session -> question -> RabbitMQ -> judge -> sandbox -> result: ' + $case.language + ' Accepted')
    if ($case.language -eq 'java') {
        $socket = [System.Net.WebSockets.ClientWebSocket]::new()
        $cancellation = [System.Threading.CancellationTokenSource]::new(10000)
        try {
            $socket.Options.Cookies = $apiSession.Cookies
            $socketUri = [Uri]($gateway.Replace('http://','ws://') + '/api/question/ws/judge?questionSubmitId=' + $submissionId)
            $null = $socket.ConnectAsync($socketUri, $cancellation.Token).GetAwaiter().GetResult()
            $buffer = New-Object byte[] 8192
            $received = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cancellation.Token).GetAwaiter().GetResult()
            if ($received.Count -le 0 -or $received.MessageType -ne [System.Net.WebSockets.WebSocketMessageType]::Text) { throw 'No WebSocket status message' }
            $message = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $received.Count) | ConvertFrom-Json
            if ([long]$message.questionSubmitId -ne $submissionId) { throw 'Unexpected WebSocket submission ID' }
            Write-Host 'PASS: gateway WebSocket upgrade and current judging status message.'
        } finally {
            $socket.Abort()
            $socket.Dispose()
            $cancellation.Dispose()
        }
    }
}
$null = Invoke-ApplicationApi -Method Post -Path '/api/user/logout'
# Only remove records whose numeric IDs and unique account were created above.
# On a failed test, retain its fixtures for diagnosis instead of broad cleanup.
if ($userId -le 0 -or $questionId -le 0 -or $account -notmatch '^deploy_smoke_[a-f0-9]{12}$') { throw 'Invalid smoke fixture identifiers' }
$cleanupSql = 'DELETE FROM question_submit WHERE userId=' + $userId + ' AND questionId=' + $questionId + '; DELETE FROM question WHERE id=' + $questionId + ' AND userId=' + $userId + '; DELETE FROM user WHERE id=' + $userId + ';'
$cleanupCommand = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -e "' + $cleanupSql + '"'
Invoke-LocalContainerScript -Service mysql -Script $cleanupCommand
Write-Host 'PASS: login, shared session, both end-to-end judges and logout; temporary DB fixtures cleaned.'
