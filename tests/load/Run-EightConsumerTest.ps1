param([string]$JMeterHome)
$arguments = @{Consumers=8; Users=100; Monitor=$true}
if ($JMeterHome) { $arguments.JMeterHome = $JMeterHome }
& (Join-Path $PSScriptRoot 'Run-ConsumerTest.ps1') @arguments
