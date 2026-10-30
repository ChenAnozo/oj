param([string]$JMeterHome)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Run-ConsumerTest.ps1') -Consumers 2 -JMeterHome $JMeterHome
