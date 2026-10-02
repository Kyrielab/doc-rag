# Eval matrix on corpus-v3 (course corpus) with eval set v1.5.
# Retrieval-only (zero LLM cost) across 6 configs, then one full generation eval.
# The roadmap's "ordering thesis": rerank/rewrite showed zero gain on the saturated
# 7-doc corpus; this run tests whether they show up on 190 docs / ~4600 chunks.
# ASCII only. Run AFTER run-course-ingest.ps1 completes.
param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
$root = $Root
Set-Location $root
$logFile = Join-Path $env:TEMP 'docrag-boot.log'

function Stop-App {
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}
function Start-App([string[]]$extraEnv) {
    foreach ($kv in $extraEnv) { $name, $val = $kv -split '=', 2; Set-Item ("Env:" + $name) $val }
    Remove-Item $logFile -ErrorAction SilentlyContinue
    Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList @('-B', '-o', 'spring-boot:run') `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $env:TEMP 'docrag-boot-err.log') | Out-Null
    $deadline = (Get-Date).AddMinutes(4); $ok = $false
    while ((Get-Date) -lt $deadline) {
        try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ok = $true; break }
        catch { Start-Sleep -Seconds 5 }
    }
    foreach ($kv in $extraEnv) { $name = ($kv -split '=', 2)[0]; Remove-Item ("Env:" + $name) -ErrorAction SilentlyContinue }
    return $ok
}
function Run-Eval([string]$label, [bool]$skipGen) {
    $url = 'http://localhost:8080/api/eval/run?label=' + $label + '&topK=8&skipGeneration=' + $skipGen.ToString().ToLower() + '&casesFile=eval/course-eval.jsonl'
    $resp = Join-Path $env:TEMP ('eval-' + $label + '.json')
    $code = curl.exe -s -o $resp -w '%{http_code}' -X POST $url --max-time 2400
    if ($code -ne '200') { Write-Host ('  EVAL FAILED http=' + $code); return }
    $r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  [' + $label + '] contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
        ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
        ' srcRecall=' + [math]::Round($r.meanRecallAtK, 3) +
        ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
    if (-not $skipGen) {
        Write-Host ('  answer: citationAcc=' + [math]::Round($r.meanCitationAccuracy, 3) +
            ' keyPointCov=' + [math]::Round($r.meanKeyPointCoverage, 3) +
            ' refusalAcc=' + [math]::Round($r.refusalAccuracy, 3))
    }
    $fails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
    if ($fails.Count -gt 0) { Write-Host ('  content-miss (' + $fails.Count + '): ' + (($fails | ForEach-Object { $_.id }) -join ' ')) }
    Copy-Item $resp (Join-Path $root ('eval\result-' + $label + '.json')) -Force
}

# sanity: corpus size before evals
Write-Output ('ES chunks: ' + (curl.exe -s 'http://localhost:9200/doc_chunks/_count'))

Write-Output '=== 1/6 v15-hybrid (baseline) ==='
Stop-App
if (Start-App @()) { Run-Eval 'v15-hybrid' $true } else { Write-Output 'boot fail'; exit 1 }

Write-Output '=== 2/6 v15-lexical ==='
Stop-App
if (Start-App @('RAG_ENABLEVECTOR=false')) { Run-Eval 'v15-lexical' $true }

Write-Output '=== 3/6 v15-vector ==='
Stop-App
if (Start-App @('RAG_ENABLELEXICAL=false')) { Run-Eval 'v15-vector' $true }

Write-Output '=== 4/6 v15-protect2 (fusion single-path protection n=2) ==='
Stop-App
if (Start-App @('RAG_FUSIONPROTECTTOPN=2')) { Run-Eval 'v15-protect2' $true }

Write-Output '=== 5/6 v15-rerank ==='
Stop-App
if (Start-App @('RAG_ENABLERERANK=true')) { Run-Eval 'v15-rerank' $true }

Write-Output '=== 6/6 v15-rewrite ==='
Stop-App
if (Start-App @('RAG_ENABLEREWRITE=true')) { Run-Eval 'v15-rewrite' $true }

Write-Output '=== full generation eval (default config) ==='
Stop-App
if (Start-App @()) { Run-Eval 'v15-hybrid-full' $false }

Write-Output '=== leaving app on default config, running ==='
Stop-App
Start-App @() | Out-Null
Write-Output 'DONE'
