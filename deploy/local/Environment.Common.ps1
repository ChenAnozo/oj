$ErrorActionPreference = 'Stop'
$script:LocalEnvironmentDirectory = $PSScriptRoot
$script:LocalProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:LocalEnvFile = Join-Path $PSScriptRoot '.env'
$script:LocalComposeFile = Join-Path $script:LocalProjectRoot 'docker-compose.env.yml'
$script:LocalServiceComposeFile = Join-Path $script:LocalProjectRoot 'docker-compose.service.yml'

function Invoke-LocalServices {
    param([string[]]$DockerArguments)
    & docker compose --project-name yuoj-services --env-file $script:LocalEnvFile -f $script:LocalServiceComposeFile @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw ('Business Compose failed: ' + ($DockerArguments -join ' ')) }
}

function Get-LocalComposeServiceStatus {
    param([switch]$Business)
    # Read only ASCII status fields. Full `ps --format json` includes truncated
    # Unicode commands/mounts that Windows PowerShell 5.1 can decode incorrectly.
    $arguments = @('ps','--all','--format','{{.Service}}|{{.State}}|{{.Health}}')
    $lines = if ($Business) {
        Invoke-LocalServices -DockerArguments $arguments
    } else {
        Invoke-LocalCompose -DockerArguments $arguments
    }
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        $fields = ([string]$line).Split([char]'|')
        if ($fields.Count -ne 3 -or $fields[0] -notmatch '^[A-Za-z0-9_.-]+$') {
            throw 'Unexpected Docker Compose service-status output.'
        }
        [pscustomobject]@{Service=$fields[0];State=$fields[1];Health=$fields[2]}
    }
}

function Get-LocalEnvironmentSettings {
    if (-not (Test-Path -LiteralPath $script:LocalEnvFile)) {
        throw 'Missing deploy/local/.env. Run Start-Environment.ps1 first.'
    }
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $script:LocalEnvFile) {
        if ($line -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
    return $settings
}

function Get-LocalSandboxInstances {
    param([hashtable]$Settings, [string]$AccessToken)
    if (-not $AccessToken) { $AccessToken = Get-LocalNacosAccessToken -Settings $Settings }
    $uri = 'http://127.0.0.1:' + $Settings.NACOS_HTTP_PORT + '/nacos/v1/ns/instance/list?healthyOnly=true&serviceName=yuoj-code-sandbox&accessToken=' + [Uri]::EscapeDataString($AccessToken)
    $registry = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    return @($registry.hosts | Where-Object { $_.healthy -and $_.enabled })
}

function Wait-LocalSandboxInstances {
    param([hashtable]$Settings, [int]$ExpectedCount, [int]$TimeoutSeconds = 90)
    $accessToken = Get-LocalNacosAccessToken -Settings $Settings
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $instances = @(Get-LocalSandboxInstances -Settings $Settings -AccessToken $accessToken)
        $names = @($instances | ForEach-Object { $_.metadata.'sandbox-instance' } | Sort-Object -Unique)
        $expectedNames = @(1..$ExpectedCount | ForEach-Object { 'sandbox-' + $_ })
        if ($instances.Count -eq $ExpectedCount -and ($names -join ',') -eq ($expectedNames -join ',')) { return $instances }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('Nacos sandbox registration did not converge to ' + $ExpectedCount + ' healthy instance(s).')
}

function Invoke-LocalCompose {
    param([string[]]$DockerArguments)
    & docker compose --project-name yuoj-local --env-file $script:LocalEnvFile -f $script:LocalComposeFile @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw ('Docker Compose failed: ' + ($DockerArguments -join ' ')) }
}

function Invoke-LocalContainerScript {
    param([Parameter(Mandatory=$true)][string]$Service,
          [Parameter(Mandatory=$true)][string]$Script, [switch]$Business)
    $projectName = if ($Business) { 'yuoj-services' } else { 'yuoj-local' }
    $composeFile = if ($Business) { $script:LocalServiceComposeFile } else { $script:LocalComposeFile }
    $previousEncoding = $OutputEncoding
    try {
        $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        # Send the script on stdin, preserving SQL/shell quotes. The fixed
        # bootstrap strips CR from PowerShell 5.1's native pipeline line endings.
        $Script | & docker compose --project-name $projectName --env-file $script:LocalEnvFile -f $composeFile exec -T $Service sh -c 'tr -d ''\r'' | sh -es'
        if ($LASTEXITCODE -ne 0) { throw ('Container script failed: ' + $projectName + '/' + $Service) }
    } finally { $OutputEncoding = $previousEncoding }
}

function Get-LocalRabbitHeaders {
    param([hashtable]$Settings)
    $credentialBytes = [System.Text.Encoding]::UTF8.GetBytes('yuoj:' + $Settings.RABBITMQ_PASSWORD)
    return @{ Authorization = 'Basic ' + [Convert]::ToBase64String($credentialBytes) }
}

function Get-LocalNacosAccessToken {
    param([hashtable]$Settings)
    $login = Invoke-RestMethod -Method Post -Uri ('http://127.0.0.1:' + $Settings.NACOS_HTTP_PORT + '/nacos/v1/auth/login') -Body @{username='nacos';password=$Settings.NACOS_ADMIN_PASSWORD} -TimeoutSec 15
    if (-not $login.accessToken) { throw 'Nacos login did not return an access token.' }
    return $login.accessToken
}
