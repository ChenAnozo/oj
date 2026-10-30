. (Join-Path $PSScriptRoot 'Environment.Common.ps1')

function New-LocalSecret {
    $secretBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($secretBytes).Replace('-', '').ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $script:LocalEnvFile)) {
    $tokenBytes = [System.Text.Encoding]::UTF8.GetBytes((New-LocalSecret))
    $values = [ordered]@{
        MYSQL_ROOT_PASSWORD = New-LocalSecret
        MYSQL_PASSWORD = New-LocalSecret
        REDIS_PASSWORD = New-LocalSecret
        RABBITMQ_PASSWORD = New-LocalSecret
        NACOS_AUTH_TOKEN = [Convert]::ToBase64String($tokenBytes)
        NACOS_AUTH_IDENTITY_VALUE = New-LocalSecret
        NACOS_ADMIN_PASSWORD = New-LocalSecret
        MYSQL_PORT = '13306'
        REDIS_PORT = '16379'
        RABBITMQ_AMQP_PORT = '15673'
        RABBITMQ_HTTP_PORT = '15674'
        NACOS_HTTP_PORT = '18848'
        NACOS_GRPC_PORT = '19848'
    }
    $envLines = @('# Local rehearsal credentials. Do not commit or share this file.')
    foreach ($key in $values.Keys) { $envLines += ($key + '=' + $values[$key]) }
    [System.IO.File]::WriteAllText($script:LocalEnvFile, ($envLines -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Generated independent credentials in deploy/local/.env (values not printed).'
}

$settings = Get-LocalEnvironmentSettings
if ([int]$settings.NACOS_GRPC_PORT -ne ([int]$settings.NACOS_HTTP_PORT + 1000)) {
    throw 'Nacos host gRPC port must equal its host HTTP port + 1000.'
}
Invoke-LocalCompose -DockerArguments @('config', '--quiet')
Invoke-LocalCompose -DockerArguments @('up', '-d', '--wait', '--wait-timeout', '300')

# Import topology after the default user exists. Boot-time definitions import can
# suppress RabbitMQ default-user creation on an empty node.
$rabbitBase = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT
$rabbitHeaders = Get-LocalRabbitHeaders -Settings $settings
$managementReady = $false
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    try {
        $null = Invoke-RestMethod -Uri ($rabbitBase + '/api/overview') -Headers $rabbitHeaders -TimeoutSec 5
        $managementReady = $true
        break
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $managementReady) { throw 'RabbitMQ management API is not ready or credentials do not match the existing volume.' }
$definitions = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'rabbitmq-definitions.json')
$null = Invoke-RestMethod -Method Post -Uri ($rabbitBase + '/api/definitions') -Headers $rabbitHeaders -ContentType 'application/json' -Body $definitions -TimeoutSec 30

# On restart, verify the saved admin password before attempting first-time setup.
try { $null = Get-LocalNacosAccessToken -Settings $settings }
catch {
    $adminUri = 'http://127.0.0.1:' + $settings.NACOS_HTTP_PORT + '/nacos/v1/auth/users/admin'
    try {
        $null = Invoke-RestMethod -Method Post -Uri $adminUri -Body @{password=$settings.NACOS_ADMIN_PASSWORD} -TimeoutSec 15
    } catch { throw 'Nacos admin initialization failed. Keep the existing .env and check the persisted credentials; no volume was deleted.' }
    $null = Get-LocalNacosAccessToken -Settings $settings
}

& (Join-Path $PSScriptRoot 'Test-Environment.ps1')
