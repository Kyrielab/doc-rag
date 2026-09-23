# doc-rag smoke test: boots the app, asks a fresh question, prints answer + trace,
# and surfaces any retriever degradation from the log.
# Usage:  pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\smoke-test.ps1 [-KeepRunning]
param(
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
if (-not $env:LLM_API_KEY) { Write-Output 'FATAL: LLM_API_KEY not set at user level'; exit 1 }

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$logFile = Join-Path $env:TEMP 'docrag-boot.log'
$errFile = Join-Path $env:TEMP 'docrag-boot-err.log'

# 1) Stop any previous instance
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
    ForEach-Object { Write-Output ("stopping old app pid=" + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

# 2) Boot
Remove-Item $logFile, $errFile -ErrorAction SilentlyContinue
$boot = Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' `
    -ArgumentList '-B', '-o', 'spring-boot:run' `
    -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile
Write-Output ("boot pid=" + $boot.Id)

$deadline = (Get-Date).AddMinutes(4)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ready = $true; break }
    catch { Start-Sleep -Seconds 5 }
}
if (-not $ready) {
    Write-Output 'APP FAILED TO START; last log lines:'
    Get-Content $logFile -Tail 40 -ErrorAction SilentlyContinue
    exit 1
}
Write-Output 'app is up'

# 3) Ask a question that is not in the answer cache.
# The question lives in a UTF-8 data file and is read with an explicit encoding, so this
# script keeps working even if a future edit strips the .ps1 BOM (that happened once and
# silently turned the question into mojibake).
$questionFile = Join-Path $PSScriptRoot 'smoke-question.txt'
$question = (Get-Content -Path $questionFile -Encoding UTF8 -Raw).Trim()
$q = @{ question = $question; topK = 5 } | ConvertTo-Json
$qb = [System.Text.Encoding]::UTF8.GetBytes($q)
Write-Output "=== Q: $question ==="
try {
    $raw = Invoke-WebRequest -Method Post 'http://localhost:8080/api/answer' `
        -ContentType 'application/json; charset=utf-8' -Body $qb -UseBasicParsing -TimeoutSec 180
    $ans = [System.Text.Encoding]::UTF8.GetString($raw.RawContentStream.ToArray()) | ConvertFrom-Json
    Write-Output ('A: ' + $ans.answer)
    Write-Output ('refused=' + $ans.refused + ' citationAccuracy=' + $ans.citationAccuracy)
    $t = $ans.trace
    Write-Output ('trace: lex=' + $t.lexicalHits + ' vec=' + $t.vectorHits + ' fused=' + $t.fusedCount +
        ' lexMs=' + $t.lexicalMillis + ' vecMs=' + $t.vectorMillis + ' genMs=' + $t.generateMillis +
        ' totalMs=' + $t.totalMillis + ' CACHE_HIT=' + $t.cacheHit)
    if ($t.cacheHit) { Write-Output 'WARNING: answer came from cache - this run did NOT exercise the live pipeline' }
}
catch {
    Write-Output ('ANSWER FAILED: ' + $_.Exception.Message)
}

# 4) Surface retriever degradation, with the real ES error body if present
Write-Output '=== retriever warnings in log ==='
$warns = Select-String -Path $logFile -Pattern 'retrieval failed' -ErrorAction SilentlyContinue | Select-Object -Last 4
if ($warns) {
    $warns | ForEach-Object { Write-Output ($_.Line.Substring(0, [Math]::Min(1500, $_.Line.Length))) }
}
else {
    Write-Output '(none - both retrievers healthy)'
}

if (-not $KeepRunning) {
    Write-Output '=== stopping app ==='
    Stop-Process -Id $boot.Id -Force -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
else {
    Write-Output 'app left running on http://localhost:8080'
}
