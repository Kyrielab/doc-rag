# Week-4 experiment runner: restarts the app under each config, re-ingests when the
# chunking changes, runs the retrieval eval, and saves raw reports to eval\result-*.json.
# ASCII only (Windows PowerShell reads BOM-less .ps1 as ANSI - proven trap).
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;$env:PATH"
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

function Wait-Index {
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline) {
        $code = curl.exe -s -o NUL -w '%{http_code}' 'http://localhost:9200/doc_chunks/_count'
        if ($code -eq '200') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Start-App([string[]]$extraArgs) {
    $mvnArgs = @('-B', '-o', 'spring-boot:run')
    if ($extraArgs.Count -gt 0) {
        $mvnArgs += ('-Dspring-boot.run.arguments=' + ($extraArgs -join ','))
    }
    Remove-Item $logFile -ErrorAction SilentlyContinue
    Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList $mvnArgs -WorkingDirectory $root `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError $errFile | Out-Null
    $deadline = (Get-Date).AddMinutes(4); $ok = $false
    while ((Get-Date) -lt $deadline) {
        try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ok = $true; break }
        catch { Start-Sleep -Seconds 5 }
    }
    if ($ok) { $ok = Wait-Index }
    return $ok
}

function Ingest-Corpus {
    $files = Get-ChildItem (Join-Path $root 'corpus') -Filter *.md | Where-Object { $_.Name -ne 'README.md' }
    $total = 0
    foreach ($f in $files) {
        $content = (Get-Content $f.FullName -Raw -Encoding UTF8).ToString()
        $payload = @{ title = $f.BaseName; content = $content } | ConvertTo-Json
        $tmp = Join-Path $env:TEMP ('ing-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.json')
        [IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
        $respFile = $tmp + '.resp'
        $code = curl.exe -s -o $respFile -w '%{http_code}' -X POST 'http://localhost:8080/api/documents/text' `
            -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp" --max-time 900
        if ($code -eq '200') {
            $r = [IO.File]::ReadAllText($respFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $total += $r.chunkCount
            Write-Host ('  ingested ' + $f.BaseName + ' chunks=' + $r.chunkCount)
        } else {
            Write-Host ('  INGEST FAILED(' + $code + ') ' + $f.BaseName)
        }
        Remove-Item $tmp, $respFile -ErrorAction SilentlyContinue
    }
    Write-Host ('  corpus total chunks=' + $total)
}

function Run-Eval([string]$label) {
    $url = 'http://localhost:8080/api/eval/run?label=' + $label + '&topK=8&skipGeneration=true&casesFile=eval/my-eval.jsonl'
    $resp = Join-Path $env:TEMP ('eval-' + $label + '.json')
    $code = curl.exe -s -o $resp -w '%{http_code}' -X POST $url --max-time 1200
    if ($code -ne '200') {
        Write-Host ('  EVAL FAILED http=' + $code)
        return
    }
    $r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  [' + $label + '] srcRecall@8=' + [math]::Round($r.meanRecallAtK, 3) +
        ' contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
        ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
        ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
    $fails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
    if ($fails.Count -gt 0) {
        Write-Host ('  content-miss (' + $fails.Count + '): ' + (($fails | ForEach-Object { $_.id }) -join ' '))
    } else {
        Write-Host '  content: ALL labeled cases hit gold chunks'
    }
    Copy-Item $resp (Join-Path $root ('eval\result-' + $label + '.json')) -Force
}

function Drop-Index {
    curl.exe -s -o NUL -X DELETE 'http://localhost:9200/doc_chunks'
    Write-Host '  index dropped (will be recreated on boot)'
}

$matrix = @(
    @{ label = 'rerank-dashscope'; args = @('--rag.enable-rerank=true', '--rerank.provider=dashscope', '--rerank.model=gte-rerank-v2') },
    @{ label = 'rrf-k10';          args = @('--rag.rrf-k=10') },
    @{ label = 'rrf-k100';         args = @('--rag.rrf-k=100') },
    @{ label = 'vecweight2';       args = @('--rag.vec-weight=2.0') },
    @{ label = 'minscore-020';     args = @('--rag.min-vector-score=0.2') },
    @{ label = 'minscore-050';     args = @('--rag.min-vector-score=0.5') },
    @{ label = 'chunk-300';        args = @('--rag.chunk-size=300'); dropIndex = $true; reingest = $true },
    @{ label = 'chunk-800';        args = @('--rag.chunk-size=800'); dropIndex = $true; reingest = $true },
    @{ label = 'restore-500';      args = @(); dropIndex = $true; reingest = $true; noEval = $true }
)

$i = 0
foreach ($cfg in $matrix) {
    $i++
    Write-Output ('=== [' + $i + '/' + $matrix.Count + '] ' + $cfg.label + ' ===')
    Stop-App
    if ($cfg.dropIndex) { Drop-Index }
    if (-not (Start-App $cfg.args)) { Write-Output ('APP FAILED TO START: ' + $cfg.label); continue }
    if ($cfg.reingest) { Ingest-Corpus }
    if (-not $cfg.noEval) { Run-Eval $cfg.label }
}

Write-Output '=== experiment run complete; app left on default config ==='
