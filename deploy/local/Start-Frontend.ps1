$ErrorActionPreference = 'Stop'
$frontendDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../yuoj-frontend'))
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Install Node.js and npm first.' }
if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules'))) {
    throw 'Missing frontend dependencies. Run npm ci in yuoj-frontend first.'
}
Write-Host 'Docker backend must be running. Frontend: http://127.0.0.1:18080 . Press Ctrl+C to stop.'
Push-Location $frontendDirectory
try {
    & npm.cmd run serve:docker
    if ($LASTEXITCODE -ne 0) { throw 'Frontend development server stopped with an error.' }
} finally { Pop-Location }
