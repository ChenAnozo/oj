param([string]$ZipPath)
$ErrorActionPreference = 'Stop'
$version = '5.6.3'
$tools = Join-Path $PSScriptRoot '.tools'
$destination = Join-Path $tools ('apache-jmeter-' + $version)
if (Test-Path -LiteralPath (Join-Path $destination 'bin/jmeter.bat')) {
    Write-Output $destination
    return
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 8+ is required. Run java -version first.' }
$null = New-Item -ItemType Directory -Path $tools -Force
$filename = 'apache-jmeter-' + $version + '.zip'
$source = 'https://archive.apache.org/dist/jmeter/binaries/' + $filename
$checksumFile = Join-Path $tools ($filename + '.sha512')
if (-not $ZipPath) {
    $ZipPath = Join-Path $tools $filename
    Invoke-WebRequest -UseBasicParsing -Uri $source -OutFile $ZipPath -TimeoutSec 120
}
if (-not (Test-Path -LiteralPath $ZipPath -PathType Leaf)) { throw 'JMeter ZIP file not found.' }
Invoke-WebRequest -UseBasicParsing -Uri ($source + '.sha512') -OutFile $checksumFile -TimeoutSec 30
$checksumText = Get-Content -LiteralPath $checksumFile -Raw
$match = [regex]::Match($checksumText, '(?i)\b[a-f0-9]{128}\b')
if (-not $match.Success) { throw 'Cannot parse the official SHA-512 checksum.' }
$actual = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA512).Hash
if ($actual -ne $match.Value) { throw 'JMeter ZIP checksum mismatch. Nothing was extracted.' }
Expand-Archive -LiteralPath $ZipPath -DestinationPath $tools
Write-Output $destination
