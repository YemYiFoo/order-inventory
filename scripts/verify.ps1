. "$PSScriptRoot/common.ps1"
Push-Location $ProjectRoot
try { & .\mvnw.cmd -B clean verify; Assert-Native 'Full verification' } finally { Pop-Location }
