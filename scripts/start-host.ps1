. "$PSScriptRoot/common.ps1"
$values = Read-LocalConfig
$previous = @{}
foreach ($name in @('DB_URL','DB_USER','DB_PASSWORD')) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($values['MYSQL_PORT'])/order_inventory?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:DB_USER = 'order_app'
$env:DB_PASSWORD = $values['DB_PASSWORD']
Push-Location $ProjectRoot
try {
    & java -jar target/order-inventory.jar --spring.profiles.active=local "--server.port=$($values['APP_PORT'])"
    Assert-Native 'Host application'
} finally {
    Pop-Location
    foreach ($name in @('DB_URL','DB_USER','DB_PASSWORD')) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
