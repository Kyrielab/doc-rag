# Start dev environment: containers -> app -> key validity check.
# ASCII only on purpose: Windows PowerShell reads BOM-less .ps1 as ANSI,
# so non-ASCII literals live in UTF-8 data files instead (proven trap).
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
Write-Output ('key length=' + $(if ($env:LLM_API_KEY) { $env:LLM_API_KEY.Length } else { 0 }))

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Output '=== starting containers ==='
docker compose start 2>&1 | Select-Object -Last 2 | ForEach-Object { $_.ToString() }
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    $h = docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null
    $h2 = docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null
    if ($h -eq 'healthy' -and $h2 -eq 'healthy') { break }
    Start-Sleep -Seconds 6
}
Write-Output ('es=' + (docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null) +
    ' redis=' + (docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null))

Write-Output '=== corpus still in ES volume? ==='
try {
    $cnt = (Invoke-RestMethod 'http://localhost:9200/doc_chunks/_count' -TimeoutSec 10).count
    Write-Output ('ES chunks=' + $cnt)
} catch { Write-Output ('ES count failed: ' + $_.Exception.Message) }

Write-Output '=== booting app ==='
$boot = Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' `
    -ArgumentList '-B', '-o', 'spring-boot:run' `
    -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $env:TEMP 'docrag-boot.log') `
    -RedirectStandardError (Join-Path $env:TEMP 'docrag-boot-err.log')
$deadline = (Get-Date).AddMinutes(4); $ready = $false
while ((Get-Date) -lt $deadline) {
    try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ready = $true; break }
    catch { Start-Sleep -Seconds 5 }
}
Write-Output ('app ready: ' + $ready)
if (-not $ready) { exit 1 }

Write-Output '=== API key validity (live vector + generation call) ==='
$question = (Get-Content (Join-Path $PSScriptRoot 'keytest-question.txt') -Encoding UTF8 -Raw).Trim()
$payload = @{ question = $question; topK = 3 } | ConvertTo-Json
$tmp = Join-Path $env:TEMP 'keytest.json'
[IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
$code = curl.exe -s -o "$tmp.resp" -w '%{http_code}' -X POST 'http://localhost:8080/api/answer' `
    -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp"
Write-Output ('http=' + $code)
if ($code -eq '200') {
    $a = [IO.File]::ReadAllText("$tmp.resp", [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Output ('refused=' + $a.refused + ' citationAcc=' + [math]::Round($a.citationAccuracy, 2) +
        ' lex=' + $a.trace.lexicalHits + ' vec=' + $a.trace.vectorHits + ' totalMs=' + $a.trace.totalMillis)
    $head = $a.answer
    if ($head.Length -gt 150) { $head = $head.Substring(0, 150) + '...' }
    Write-Output ('A(head): ' + $head)
    if ($a.trace.vectorHits -eq 0) { Write-Output 'WARNING: vector path returned 0 - key may be invalid (embedding call failed)' }
} else {
    $body = [IO.File]::ReadAllText("$tmp.resp", [Text.Encoding]::UTF8)
    Write-Output ('resp: ' + $body.Substring(0, [Math]::Min(300, $body.Length)))
}
Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
