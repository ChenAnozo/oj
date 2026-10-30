param([string]$JMeterHome)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Run-ConsumerTest.ps1') -Consumers 4 -JMeterHome $JMeterHome
