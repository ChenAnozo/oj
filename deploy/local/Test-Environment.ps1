. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
$settings = Get-LocalEnvironmentSettings
Invoke-LocalCompose -DockerArguments @('config', '--quiet')
$services = @(Get-LocalComposeServiceStatus)
foreach ($serviceName in @('mysql', 'redis', 'rabbitmq', 'nacos')) {
    $service = @($services | Where-Object { $_.Service -eq $serviceName })
    if ($service.Count -ne 1 -or $service[0].State -ne 'running' -or $service[0].Health -ne 'healthy') {
        throw ('Service is not healthy: ' + $serviceName)
    }
}
Write-Host 'PASS: four containers are running and healthy.'

$schemaQuery = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -Nse "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME"'
$tables = @(Invoke-LocalContainerScript -Service mysql -Script $schemaQuery)
foreach ($tableName in @('post','post_favour','post_thumb','question','question_submit','question_submit_msg_backup','user')) {
    if ($tables -notcontains $tableName) { throw ('Missing initialized table: ' + $tableName) }
}
Write-Host 'PASS: application DB credentials work and all seven schema tables exist.'

foreach ($database in @('1','2')) {
    $pong = Invoke-LocalCompose -DockerArguments @('exec', '-T', 'redis', 'redis-cli', '-n', $database, 'PING')
    if ($pong -ne 'PONG') { throw ('Redis DB ' + $database + ' failed') }
}
Write-Host 'PASS: authenticated Redis PING works for DB 1 and DB 2.'

$rabbitBase = 'http://127.0.0.1:' + $settings.RABBITMQ_HTTP_PORT
$rabbitHeaders = Get-LocalRabbitHeaders -Settings $settings
$queues = Invoke-RestMethod -Uri ($rabbitBase + '/api/queues/%2F') -Headers $rabbitHeaders -TimeoutSec 15
foreach ($queueName in @('code_queue','code_delay_queue','code_timeout_queue')) {
    $queue = @($queues | Where-Object { $_.name -eq $queueName })
    if ($queue.Count -ne 1 -or -not $queue[0].durable) { throw ('Missing durable RabbitMQ queue: ' + $queueName) }
}
$delayQueue = $queues | Where-Object { $_.name -eq 'code_delay_queue' }
if ($delayQueue.arguments.'x-message-ttl' -ne 300000 -or $delayQueue.arguments.'x-dead-letter-exchange' -ne 'code_dlx_exchange' -or $delayQueue.arguments.'x-dead-letter-routing-key' -ne 'code_timeout') {
    throw 'RabbitMQ delay queue configuration differs from the current application.'
}
Write-Host 'PASS: RabbitMQ credentials and application queue topology are ready.'
$bindings = Invoke-RestMethod -Uri ($rabbitBase + '/api/bindings/%2F') -Headers $rabbitHeaders -TimeoutSec 15
foreach ($bindingCheck in @(@{source='code_exchange';destination='code_queue';routing_key='my_routingKey'},@{source='code_dlx_exchange';destination='code_timeout_queue';routing_key='code_timeout'})) {
    $found = @($bindings | Where-Object { $_.source -eq $bindingCheck.source -and $_.destination -eq $bindingCheck.destination -and $_.routing_key -eq $bindingCheck.routing_key })
    if ($found.Count -ne 1) { throw ('Missing RabbitMQ routing binding: ' + $bindingCheck.source) }
}
$amqpConnection = Test-NetConnection -ComputerName 127.0.0.1 -Port ([int]$settings.RABBITMQ_AMQP_PORT) -WarningAction SilentlyContinue -InformationLevel Quiet
if (-not $amqpConnection) { throw 'RabbitMQ AMQP host port is unreachable' }
Write-Host 'PASS: RabbitMQ routing bindings and AMQP TCP port.'

$nacosBase = 'http://127.0.0.1:' + $settings.NACOS_HTTP_PORT + '/nacos'
$readiness = Invoke-WebRequest -UseBasicParsing -Uri ($nacosBase + '/v1/console/health/readiness') -TimeoutSec 15
if ($readiness.StatusCode -ne 200) { throw 'Nacos readiness failed' }
$nacosToken = Get-LocalNacosAccessToken -Settings $settings
try {
    $registryUri = $nacosBase + '/v1/ns/service/list?pageNo=1&pageSize=10&accessToken=' + [Uri]::EscapeDataString($nacosToken)
    $registryResponse = Invoke-RestMethod -Uri $registryUri -TimeoutSec 15
    if ($null -eq $registryResponse.count) { throw 'Unexpected registry response' }
} catch { throw 'Authenticated Nacos registry query failed.' }
$grpcConnection = Test-NetConnection -ComputerName 127.0.0.1 -Port ([int]$settings.NACOS_GRPC_PORT) -WarningAction SilentlyContinue -InformationLevel Quiet
if (-not $grpcConnection) { throw 'Nacos gRPC host port is unreachable' }
Write-Host 'PASS: Nacos readiness, authenticated registry query and gRPC TCP port.'
Invoke-LocalCompose -DockerArguments @('ps')
Write-Host 'Environment checks passed.'
