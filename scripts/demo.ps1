param([string]$BaseUrl = '')
. "$PSScriptRoot/common.ps1"
Add-Type -AssemblyName System.Net.Http
if (-not $BaseUrl) { $values=Read-LocalConfig; $BaseUrl="http://127.0.0.1:$($values['APP_PORT'])" }
$client=[Net.Http.HttpClient]::new()
function Invoke-Demo([string]$Method,[string]$Path,[string]$User,[string]$Key,[string]$Body,[int]$Expected) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),"$BaseUrl$Path")
    if ($User) {
        $token=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:Demo-only-123!"))
        $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',$token)
    }
    if ($Key) { $request.Headers.Add('Idempotency-Key',$Key) }
    if ($Body) { $request.Content=[Net.Http.StringContent]::new($Body,[Text.Encoding]::UTF8,'application/json') }
    $response=$null
    try {
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        $text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne $Expected) { throw "$Method $Path expected $Expected, got $([int]$response.StatusCode): $text" }
        Write-Host "$Method $Path -> $Expected"
        return ($text | ConvertFrom-Json)
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
try {
    $catalogue=Invoke-Demo GET '/api/products' '' '' '' 200
    foreach ($id in @(101,102)) {
        $product=$catalogue.items | Where-Object id -eq $id
        if ($product.availableStock -lt 2) { throw 'Demo stock exhausted. Existing data was preserved; use a separate fresh project-local environment for another demo.' }
    }
    $key='demo-'+[Guid]::NewGuid().ToString('N')
    $body='{"items":[{"productId":101,"quantity":2},{"productId":102,"quantity":1}]}'
    $placed=Invoke-Demo POST '/api/orders' customer-a $key $body 201
    $replay=Invoke-Demo POST '/api/orders' customer-a $key $body 200
    if ($placed.id -ne $replay.id) { throw 'Replay returned a different order' }
    Invoke-Demo POST '/api/orders' customer-a ($key+'-rollback') '{"items":[{"productId":101,"quantity":1},{"productId":102,"quantity":1000}]}' 409 | Out-Null
    Invoke-Demo GET "/api/orders/$($placed.id)" customer-b '' '' 404 | Out-Null
    Invoke-Demo POST "/api/admin/orders/$($placed.id)/ship" customer-a '' '' 403 | Out-Null
    Invoke-Demo POST "/api/orders/$($placed.id)/cancel" customer-a '' '' 200 | Out-Null
    Invoke-Demo POST "/api/orders/$($placed.id)/cancel" customer-a '' '' 200 | Out-Null
    $ship=Invoke-Demo POST '/api/orders' customer-a ($key+'-ship') '{"items":[{"productId":101,"quantity":1}]}' 201
    Invoke-Demo POST "/api/admin/orders/$($ship.id)/ship" admin '' '' 200 | Out-Null
    Invoke-Demo POST "/api/admin/orders/$($ship.id)/ship" admin '' '' 200 | Out-Null
    Invoke-Demo POST "/api/orders/$($ship.id)/cancel" customer-a '' '' 409 | Out-Null
    Write-Host 'Demo passed. One shipped unit remains consumed; no data was reset.'
} finally { $client.Dispose() }
