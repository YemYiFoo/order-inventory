. "$PSScriptRoot/common.ps1"
Push-Location $ProjectRoot
try { docker compose --profile second down; Assert-Native 'Stop project containers (volumes preserved)' } finally { Pop-Location }
