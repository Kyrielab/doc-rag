# A/B retrieval evaluation runner. ASCII only (see start-dev.ps1 header for why).
# Restarts the app under different retrieval configs and runs eval/my-eval.jsonl each time.
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
    return $ok
}

function Run-Eval([string]$label, [bool]$skipGen) {
    $url = 'http://localhost:8080/api/eval/run?label=' + $label + '&topK=8&skipGeneration=' +
        $skipGen.ToString().ToLower() + '&casesFile=eval/my-eval.jsonl'
    $resp = Join-Path $env:TEMP ('eval-' + $label + '.json')
    $code = curl.exe -s -o $resp -w '%{http_code}' -X POST $url
    if ($code -ne '200') {
        Write-Host ('  EVAL FAILED http=' + $code)
        Write-Host ([IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8).Substring(0, 300))
        return
    }
    $r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  [' + $label + '] srcRecall@8=' + [math]::Round($r.meanRecallAtK, 3) +
        ' contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
        ' contentHit@8=' + [math]::Round($r.contentHitRate, 3) +
        ' meanMs=' + [math]::Round($r.meanTotalMillis, 0) + ' p95Ms=' + $r.p95TotalMillis)
    if (-not $skipGen) {
        Write-Host ('  answer metrics: citationAcc=' + [math]::Round($r.meanCitationAccuracy, 3) +
            ' keyPointCov=' + [math]::Round($r.meanKeyPointCoverage, 3) +
            ' refusalAcc=' + [math]::Round($r.refusalAccuracy, 3))
    }
    # Content-level misses are the discriminating signal; source-level misses are rare on a small corpus.
    $fails = @($r.cases | Where-Object { $_.contentRecall -ge 0 -and $_.contentRecall -lt 1.0 })
    if ($fails.Count -gt 0) {
        Write-Host ('  content-miss cases (' + $fails.Count + '): ' +
            (($fails | ForEach-Object { $_.id + '(' + [math]::Round($_.contentRecall, 2) + ')' }) -join ' '))
    } else {
        Write-Host '  content: all labeled cases retrieved their gold chunks'
    }
    $srcFails = @($r.cases | Where-Object { $_.recallAtK -lt 1.0 })
    if ($srcFails.Count -gt 0) {
        Write-Host ('  source-miss cases (' + $srcFails.Count + '): ' +
            (($srcFails | ForEach-Object { $_.id }) -join ' '))
    }
    Copy-Item $resp (Join-Path $root ('eval\result-' + $label + '.json')) -Force
}

Write-Output '=== Config 1/3: hybrid (BM25 + vector + RRF) ==='
Stop-App
if (Start-App @()) { Run-Eval 'hybrid-baseline' $true } else { Write-Output 'app failed to start (hybrid)' }

Write-Output '=== Config 2/3: lexical-only ==='
Stop-App
if (Start-App @('--rag.enable-vector=false')) { Run-Eval 'lexical-only' $true } else { Write-Output 'app failed to start (lexical)' }

Write-Output '=== Config 3/3: vector-only ==='
Stop-App
if (Start-App @('--rag.enable-lexical=false')) { Run-Eval 'vector-only' $true } else { Write-Output 'app failed to start (vector)' }

Write-Output '=== restore hybrid config, leave app running ==='
Stop-App
if (Start-App @()) { Write-Output 'hybrid restored, app running on :8080' } else { Write-Output 'app failed to start (restore)' }
