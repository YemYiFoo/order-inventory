. "$PSScriptRoot/common.ps1"
Push-Location $ProjectRoot
try {
    foreach ($command in @('java','git','docker')) { Get-Command $command -ErrorAction Stop | Out-Null }
    docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $desktop = Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'
        if (-not (Test-Path -LiteralPath $desktop)) { throw 'Docker engine unavailable; start Docker Desktop.' }
        Start-Process -FilePath $desktop -WindowStyle Hidden
        $ready = $false
        for ($i=0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 2
            docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        }
        if (-not $ready) { throw 'Docker Desktop needs interactive attention; open it and wait for the Linux engine.' }
    }
    if (-not (Test-Path -LiteralPath '.env')) {
        $config = "DB_PASSWORD=$([Guid]::NewGuid().ToString('N'))`nMYSQL_ROOT_PASSWORD=$([Guid]::NewGuid().ToString('N'))`nAPP_PORT=18080`nAPP2_PORT=18081`nMYSQL_PORT=13306`n"
        [IO.File]::WriteAllText((Join-Path $ProjectRoot '.env'),$config,[Text.UTF8Encoding]::new($false))
    }
    docker compose up -d --wait mysql
    Assert-Native 'Start project MySQL'
    Write-Host 'MySQL ready. Existing .env and data preserved. Build with .\scripts\verify.ps1.'
} finally { Pop-Location }
