# Rerank experiment via ENV-VAR config injection.
# Why not -Dspring-boot.run.arguments=a,b,c: comma-joined program args do NOT survive the
# PowerShell -> cmd -> Maven chain on Windows (observed: the whole comma string bound to the
# first argument). Spring relaxed binding reads env vars directly: RAG_ENABLERERANK -> rag.enable-rerank.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
# Rerank config (the point of this script)
$env:RAG_ENABLERERANK = 'true'
$env:RERANK_PROVIDER = 'dashscope'
$env:RERANK_MODEL = 'gte-rerank-v2'

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

Write-Output '=== stopping current app ==='
Stop-App

Write-Output '=== booting with rerank env config ==='
Remove-Item $logFile -ErrorAction SilentlyContinue
Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList @('-B', '-o', 'spring-boot:run') `
    -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile | Out-Null
$deadline = (Get-Date).AddMinutes(4); $ready = $false
while ((Get-Date) -lt $deadline) {
    try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ready = $true; break }
    catch { Start-Sleep -Seconds 5 }
}
if (-not $ready) { Write-Output 'APP FAILED TO START'; Get-Content $logFile -Tail 30; exit 1 }

# --- MANDATORY verification: is rerank actually wired? A silently-inactive reranker would
# --- produce a mislabeled "rerank" eval that is really plain hybrid. Check before paying.
$status = Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 10
Write-Output ('status.retrieval.enableRerank = ' + $status.retrieval.enableRerank)
$question = (Get-Content (Join-Path $PSScriptRoot 'smoke-question.txt') -Encoding UTF8 -Raw).Trim()
$payload = @{ question = $question; topK = 5 } | ConvertTo-Json
$tmp = Join-Path $env:TEMP 'rerank-verify.json'
[IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
$code = curl.exe -s -o "$tmp.resp" -w '%{http_code}' -X POST 'http://localhost:8080/api/answer' `
    -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp"
$a = [IO.File]::ReadAllText("$tmp.resp", [Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Output ('verify call: http=' + $code + ' rerankMillis=' + $a.trace.rerankMillis + ' rerankSkipped="' + $a.trace.rerankSkipped + '"')
Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
if ($status.retrieval.enableRerank -ne $true -or $a.trace.rerankSkipped) {
    Write-Output 'RERANK NOT ACTIVE - aborting eval to avoid mislabeled data. Boot log tail:'
    Get-Content $logFile -Tail 25
    exit 1
}

Write-Output '=== running retrieval eval with rerank ==='
$resp = Join-Path $env:TEMP 'eval-hybrid-rerank.json'
$code = curl.exe -s -o $resp -w '%{http_code}' -X POST 'http://localhost:8080/api/eval/run?label=hybrid-rerank&topK=8&skipGeneration=true&casesFile=eval/my-eval.jsonl' --max-time 1800
if ($code -ne '200') { Write-Output ('EVAL FAILED http=' + $code); exit 1 }
$r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Output ('  [hybrid-rerank] srcRecall@8=' + [math]::Round($r.meanRecallAtK, 3) +
    ' contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
    ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
    ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
$fails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
if ($fails.Count -gt 0) {
    Write-Output ('  content-miss (' + $fails.Count + '): ' + (($fails | ForEach-Object { $_.id }) -join ' '))
} else {
    Write-Output '  content: ALL labeled cases hit gold chunks'
}
Copy-Item $resp (Join-Path $root 'eval\result-hybrid-rerank.json') -Force

# Per-case rerank latency from the verify call is in the trace; eval mean already includes rerank stage.
Write-Output '=== done; clearing rerank env ==='
Remove-Item Env:RAG_ENABLERERANK, Env:RERANK_PROVIDER, Env:RERANK_MODEL -ErrorAction SilentlyContinue
