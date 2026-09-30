# Week 5/6 experiment runner:
#   Phase A: infra up (now with RabbitMQ + PostgreSQL), boot app
#   Phase B: async burst ingestion of corpus-v2 (frontmatter stripped) = week-6 acceptance test
#   Phase C: retrieval evals on corpus-v2 (hybrid, lexical-only) = experiment 10
#   Phase D: full eval baseline on v2, then full eval with compression = experiment 11
# ASCII only; Chinese questions live in smoke-question.txt (see start-dev.ps1 header).
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$logFile = Join-Path $env:TEMP 'docrag-boot.log'
$errFile = Join-Path $env:TEMP 'docrag-boot-err.log'

function Stop-App {
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}

function Start-Engine {
    docker version --format '{{.Server.Version}}' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { return }
    Write-Host 'docker engine down - launching Docker Desktop...'
    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    $deadline = (Get-Date).AddMinutes(6)
    while ((Get-Date) -lt $deadline) {
        docker version --format '{{.Server.Version}}' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 8
    }
}

function Start-App([string[]]$extraEnv) {
    foreach ($kv in $extraEnv) { $name, $val = $kv -split '=', 2; Set-Item ("Env:" + $name) $val }
    Remove-Item $logFile -ErrorAction SilentlyContinue
    Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList @('-B', '-o', 'spring-boot:run') `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile | Out-Null
    $deadline = (Get-Date).AddMinutes(4); $ok = $false
    while ((Get-Date) -lt $deadline) {
        try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ok = $true; break }
        catch { Start-Sleep -Seconds 5 }
    }
    if ($ok) {
        $deadline = (Get-Date).AddMinutes(2)
        while ((Get-Date) -lt $deadline) {
            $code = curl.exe -s -o NUL -w '%{http_code}' 'http://localhost:9200/doc_chunks/_count'
            if ($code -eq '200') { break }
            Start-Sleep -Seconds 3
        }
    }
    foreach ($kv in $extraEnv) { $name = ($kv -split '=', 2)[0]; Remove-Item ("Env:" + $name) -ErrorAction SilentlyContinue }
    return $ok
}

function Run-Eval([string]$label, [bool]$skipGen) {
    $url = 'http://localhost:8080/api/eval/run?label=' + $label + '&topK=8&skipGeneration=' + $skipGen.ToString().ToLower() + '&casesFile=eval/my-eval.jsonl'
    $resp = Join-Path $env:TEMP ('eval-' + $label + '.json')
    $code = curl.exe -s -o $resp -w '%{http_code}' -X POST $url --max-time 2400
    if ($code -ne '200') { Write-Host ('  EVAL FAILED http=' + $code); return }
    $r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  [' + $label + '] contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
        ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
        ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
    if (-not $skipGen) {
        Write-Host ('  answer: citationAcc=' + [math]::Round($r.meanCitationAccuracy, 3) +
            ' keyPointCov=' + [math]::Round($r.meanKeyPointCoverage, 3) +
            ' refusalAcc=' + [math]::Round($r.refusalAccuracy, 3))
    }
    $fails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
    if ($fails.Count -gt 0) { Write-Host ('  content-miss (' + $fails.Count + '): ' + (($fails | ForEach-Object { $_.id }) -join ' ')) }
    else { Write-Host '  content: ALL labeled cases hit gold' }
    Copy-Item $resp (Join-Path $root ('eval\result-' + $label + '.json')) -Force
}

function Probe-Context([string]$label) {
    $question = (Get-Content (Join-Path $PSScriptRoot 'smoke-question.txt') -Encoding UTF8 -Raw).Trim()
    $payload = @{ question = $question; topK = 8 } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP ('probe-' + $label + '.json')
    [IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
    curl.exe -s -o "$tmp.resp" -X POST 'http://localhost:8080/api/answer' -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp" --max-time 300 | Out-Null
    $a = [IO.File]::ReadAllText("$tmp.resp", [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  probe[' + $label + '] promptContextChars=' + $a.trace.promptContextChars + ' cacheHit=' + $a.trace.cacheHit)
    Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
}

Write-Output '=== Phase A: infra + app ==='
Start-Engine
docker compose up -d 2>&1 | Select-Object -Last 2 | ForEach-Object { $_.ToString() }
$deadline = (Get-Date).AddMinutes(6)
while ((Get-Date) -lt $deadline) {
    $h = docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null
    $h2 = docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null
    $h3 = docker inspect --format '{{.State.Health.Status}}' docrag-rabbit 2>$null
    $h4 = docker inspect --format '{{.State.Health.Status}}' docrag-postgres 2>$null
    if ($h -eq 'healthy' -and $h2 -eq 'healthy' -and $h3 -eq 'healthy' -and $h4 -eq 'healthy') { break }
    Start-Sleep -Seconds 8
}
Write-Output ('health: es=' + (docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null) +
    ' redis=' + (docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null) +
    ' rabbit=' + (docker inspect --format '{{.State.Health.Status}}' docrag-rabbit 2>$null) +
    ' pg=' + (docker inspect --format '{{.State.Health.Status}}' docrag-postgres 2>$null))
Stop-App
if (-not (Start-App @())) { Write-Output 'APP FAILED'; Get-Content $logFile -Tail 30; exit 1 }
Write-Output 'app ready'

Write-Output '=== Phase B: async burst ingestion (corpus-v2, week-6 acceptance) ==='
$files = Get-ChildItem (Join-Path $root 'corpus') -Filter *.md | Where-Object { $_.Name -ne 'README.md' }
$burstStart = Get-Date
$apiTimes = @()
foreach ($f in $files) {
    $content = (Get-Content $f.FullName -Raw -Encoding UTF8).ToString()
    $payload = @{ title = $f.BaseName; content = $content } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP ('ing-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.json')
    [IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
    $t = curl.exe -s -o "$tmp.resp" -w '%{http_code} %{time_total}' -X POST 'http://localhost:8080/api/documents/text' `
        -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp" --max-time 120
    $parts = "$t".Split(' ')
    $apiTimes += [double]$parts[1]
    Write-Output ('  accepted ' + $f.BaseName + ' http=' + $parts[0] + ' apiSeconds=' + $parts[1])
    Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
}
Write-Output ('  API accept latency: mean=' + [math]::Round(($apiTimes | Measure-Object -Average).Average, 3) + 's max=' + [math]::Round(($apiTimes | Measure-Object -Maximum).Maximum, 3) + 's')
Write-Output '  polling until all DONE...'
$deadline = (Get-Date).AddMinutes(25)
$allDone = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    try {
        $docs = Invoke-RestMethod 'http://localhost:8080/api/documents' -TimeoutSec 15
        $pending = @($docs | Where-Object { $_.status -ne 'DONE' })
        if (@($docs).Count -ge 7 -and $pending.Count -eq 0) { $allDone = $true; break }
    } catch { }
}
$wall = [math]::Round(((Get-Date) - $burstStart).TotalSeconds, 1)
$docs = Invoke-RestMethod 'http://localhost:8080/api/documents' -TimeoutSec 15
$docs | ForEach-Object { Write-Output ('  ' + $_.title + ' | ' + $_.status + ' | chunks=' + $_.chunkCount + ' | attempts=' + $_.attempts) }
Write-Output ('  BURST RESULT: allDone=' + $allDone + ' totalWallSeconds=' + $wall)
$esCount = curl.exe -s 'http://localhost:9200/doc_chunks/_count'
Write-Output ('  ES count: ' + $esCount)

Write-Output '=== Phase C: retrieval evals on corpus-v2 (experiment 10) ==='
Run-Eval 'v2-hybrid' $true
Stop-App
if (Start-App @('RAG_ENABLEVECTOR=false')) { Run-Eval 'v2-lexical' $true } else { Write-Output 'lexical boot failed' }

Write-Output '=== Phase D: full evals (experiment 11: compression A/B) ==='
Stop-App
if (Start-App @()) {
    Probe-Context 'no-compression'
    Run-Eval 'v2-hybrid-full' $false
} else { Write-Output 'baseline boot failed' }
Stop-App
if (Start-App @('RAG_ENABLECOMPRESSION=true')) {
    Probe-Context 'compression'
    Run-Eval 'v2-compression-full' $false
} else { Write-Output 'compression boot failed' }

Write-Output '=== cleanup: stop app, restore default config ==='
Stop-App
Write-Output 'DONE'
