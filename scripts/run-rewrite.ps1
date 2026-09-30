# Query-rewrite A/B: boots the app with RAG_ENABLEREWRITE=true (env-var config, the
# comma-args trap is documented in run-rerank.ps1), verifies rewriting is actually live,
# runs the retrieval eval, and diffs per-case content recall against the saved hybrid baseline.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
$env:RAG_ENABLEREWRITE = 'true'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$logFile = Join-Path $env:TEMP 'docrag-boot.log'
$errFile = Join-Path $env:TEMP 'docrag-boot-err.log'

Write-Output '=== containers ==='
# Engine guard: if Docker Desktop is not running, start it and wait (compose would
# otherwise fail silently and the eval would run against an empty/absent index).
$engineUp = $false
$deadline = (Get-Date).AddMinutes(1)
while ((Get-Date) -lt $deadline) {
    docker version --format '{{.Server.Version}}' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $engineUp = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $engineUp) {
    Write-Output 'docker engine down - launching Docker Desktop...'
    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    $deadline = (Get-Date).AddMinutes(6)
    while ((Get-Date) -lt $deadline) {
        docker version --format '{{.Server.Version}}' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $engineUp = $true; break }
        Start-Sleep -Seconds 8
    }
}
if (-not $engineUp) { Write-Output 'DOCKER ENGINE NEVER CAME UP - aborting'; exit 1 }

docker compose start 2>&1 | Select-Object -Last 1 | ForEach-Object { $_.ToString() }
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    $h = docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null
    $h2 = docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null
    if ($h -eq 'healthy' -and $h2 -eq 'healthy') { break }
    Start-Sleep -Seconds 6
}
Write-Output ('es=' + (docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null) +
    ' redis=' + (docker inspect --format '{{.State.Health.Status}}' docrag-redis 2>$null))
# Hard-fail on unhealthy infra: a degraded run produces mislabeled data, worse than no data.
if ((docker inspect --format '{{.State.Health.Status}}' docrag-es 2>$null) -ne 'healthy') {
    Write-Output 'ES NOT HEALTHY - aborting'; exit 1
}
# ES reports healthy internally before the published port is always ready to serve
# (observed after a Docker Desktop cold start), so probe with retries via curl.exe
# (Invoke-RestMethod adds proxy quirks on PS 5.1).
$esCount = -1
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    $raw = curl.exe -s --max-time 10 'http://localhost:9200/doc_chunks/_count'
    if ($raw -match '"count"\s*:\s*(\d+)') { $esCount = [int]$Matches[1]; break }
    Start-Sleep -Seconds 6
}
Write-Output ('ES chunk count = ' + $esCount)
if ($esCount -lt 100) { Write-Output 'CORPUS MISSING/SMALL - aborting (re-ingest needed)'; exit 1 }

Write-Output '=== boot app with rewrite enabled ==='
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
Remove-Item $logFile -ErrorAction SilentlyContinue
Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList @('-B', '-o', 'spring-boot:run') `
    -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile | Out-Null
$deadline = (Get-Date).AddMinutes(4); $ready = $false
while ((Get-Date) -lt $deadline) {
    try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ready = $true; break }
    catch { Start-Sleep -Seconds 5 }
}
if (-not $ready) { Write-Output 'APP FAILED'; Get-Content $logFile -Tail 30; exit 1 }
$status = Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 10
Write-Output ('status.retrieval.enableRewrite = ' + $status.retrieval.enableRewrite)

Write-Output '=== verify rewriting is live (rewrittenQuery must differ) ==='
$question = (Get-Content (Join-Path $PSScriptRoot 'smoke-question.txt') -Encoding UTF8 -Raw).Trim()
$payload = @{ question = $question; topK = 5 } | ConvertTo-Json
$tmp = Join-Path $env:TEMP 'rewrite-verify.json'
[IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
$code = curl.exe -s -o "$tmp.resp" -w '%{http_code}' -X POST 'http://localhost:8080/api/answer' `
    -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp" --max-time 300
$a = [IO.File]::ReadAllText("$tmp.resp", [Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Output ('http=' + $code + ' rewriteMs=' + $a.trace.rewriteMillis)
Write-Output ('question : ' + $a.trace.question)
Write-Output ('rewritten: ' + $a.trace.rewrittenQuery)
Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
if ($a.trace.rewrittenQuery -eq $a.trace.question) {
    Write-Output 'REWRITE NOT ACTIVE (rewrittenQuery == question) - aborting eval. Log tail:'
    Select-String -Path $logFile -Pattern 'rewrite' -SimpleMatch | Select-Object -Last 5 | ForEach-Object { $_.Line }
    exit 1
}

Write-Output '=== retrieval eval: hybrid + rewrite ==='
$resp = Join-Path $env:TEMP 'eval-hybrid-rewrite.json'
$code = curl.exe -s -o $resp -w '%{http_code}' -X POST 'http://localhost:8080/api/eval/run?label=hybrid-rewrite&topK=8&skipGeneration=true&casesFile=eval/my-eval.jsonl' --max-time 1800
if ($code -ne '200') { Write-Output ('EVAL FAILED http=' + $code); exit 1 }
$r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Output ('  [hybrid-rewrite] contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
    ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
    ' srcRecall@8=' + [math]::Round($r.meanRecallAtK, 3) +
    ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
Copy-Item $resp (Join-Path $root 'eval\result-hybrid-rewrite.json') -Force

Write-Output '=== per-case diff vs hybrid-baseline (contentRecall changes only) ==='
$baseFile = Join-Path $root 'eval\result-hybrid-baseline.json'
if (Test-Path $baseFile) {
    $b = [IO.File]::ReadAllText($baseFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $baseById = @{}
    foreach ($c in $b.cases) { $baseById[$c.id] = $c }
    foreach ($c in $r.cases) {
        if ($c.contentRecall -lt 0) { continue }
        $bc = $baseById[$c.id]
        if ($null -ne $bc -and [math]::Abs($bc.contentRecall - $c.contentRecall) -gt 0.001) {
            Write-Output ('  ' + $c.id + ': ' + [math]::Round($bc.contentRecall, 2) + ' -> ' + [math]::Round($c.contentRecall, 2))
        }
    }
    $nowFails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
    Write-Output ('  remaining misses: ' + (($nowFails | ForEach-Object { $_.id }) -join ' '))
} else {
    Write-Output '  baseline file not found, skip diff'
}

Remove-Item Env:RAG_ENABLEREWRITE -ErrorAction SilentlyContinue
Write-Output '=== done (app left running with rewrite env cleared; restart needed to change config) ==='
