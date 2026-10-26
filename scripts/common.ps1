$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
function Assert-Native([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed (exit $LASTEXITCODE)" }
}
function Read-LocalConfig {
    $path = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Run scripts/setup.ps1 first.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($path, [Text.Encoding]::UTF8)) {
        if ($line -match '^([A-Z0-9_]+)=(.*)$') { $values[$matches[1]] = $matches[2] }
    }
    return $values
}
