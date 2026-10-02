# Course-corpus ingestion with backlog-curve drill (roadmap directions A + B).
#   Phase A: boot app (fresh build: /api/search, producer confirms, fusion protection)
#   Phase B: backlog drill - 20 docs, queue-depth sampled every 2s (B acceptance data:
#            accept latency x20, peak backlog, drain time, consumption rate)
#   Phase C: remaining ~170 docs batch upload, poll to DONE (A: corpus-v3)
#   Phase D: ES count + FAILED report
# Ingestion goes through /api/documents/text (JSON, UTF-8 proven path; multipart with
# CJK filenames was exercised by the poison drill).
# ASCII-only script; CJK paths are PASSED AS PARAMETERS (the BOM trap bit twice already).
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$CorpusDir
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
$root = $Root
$corpusDir = $CorpusDir
Set-Location $root
$logFile = Join-Path $env:TEMP 'docrag-boot.log'

function Stop-App {
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'doc-rag|spring-boot' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}
function Start-App {
    Remove-Item $logFile -ErrorAction SilentlyContinue
    Start-Process -FilePath 'D:\dev\maven\bin\mvn.cmd' -ArgumentList @('-B', '-o', 'spring-boot:run') `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $env:TEMP 'docrag-boot-err.log') | Out-Null
    $deadline = (Get-Date).AddMinutes(4); $ok = $false
    while ((Get-Date) -lt $deadline) {
        try { Invoke-RestMethod 'http://localhost:8080/api/admin/status' -TimeoutSec 5 | Out-Null; $ok = $true; break }
        catch { Start-Sleep -Seconds 5 }
    }
    return $ok
}
function Post-Doc([string]$path) {
    # returns "http time_total"
    $base = [IO.Path]::GetFileNameWithoutExtension($path)
    $content = (Get-Content $path -Raw -Encoding UTF8).ToString()
    $payload = @{ title = $base; content = $content } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP ('ing-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.json')
    [IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
    $t = curl.exe -s -o "$tmp.resp" -w '%{http_code} %{time_total}' -X POST 'http://localhost:8080/api/documents/text' `
        -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$tmp" --max-time 120
    Remove-Item $tmp, "$tmp.resp" -ErrorAction SilentlyContinue
    return "$t"
}
function Queue-Depth {
    try {
        $q = curl.exe -s -u docrag:docrag 'http://localhost:15672/api/queues/%2F/docrag.ingest' | ConvertFrom-Json
        return [int]$q.messages
    } catch { return -1 }
}
function Done-Count {
    try {
        $docs = Invoke-RestMethod 'http://localhost:8080/api/documents' -TimeoutSec 20
        return @($docs | Where-Object { $_.status -eq 'DONE' }).Count
    } catch { return -1 }
}

Write-Output '=== Phase A: boot app ==='
Stop-App
if (-not (Start-App)) { Write-Output 'APP FAILED'; Get-Content $logFile -Tail 30; exit 1 }
Write-Output 'app ready'
$baselineDone = Done-Count
Write-Output ("baseline DONE records: " + $baselineDone)

$files = Get-ChildItem $corpusDir -Filter *.md | Where-Object { $_.Name -notmatch 'MIT-6' } | Sort-Object Name
Write-Output ("course files to ingest: " + $files.Count)

Write-Output '=== Phase B: backlog drill (first 20 docs, 2s queue sampling) ==='
$drill = $files[0..19]
$acceptTimes = @()
$drillStart = Get-Date
foreach ($f in $drill) {
    $r = Post-Doc $f.FullName
    $parts = "$r".Split(' ')
    if ($parts[0] -ne '202') { Write-Output ("  REJECT " + $f.Name + " -> " + $r) }
    $acceptTimes += [double]$parts[1]
}
$acceptWall = [math]::Round(((Get-Date) - $drillStart).TotalSeconds, 2)
$sortedAcc = $acceptTimes | Sort-Object
$p99idx = [math]::Min($sortedAcc.Count - 1, [math]::Ceiling($sortedAcc.Count * 0.99) - 1)
Write-Output ("  20 accepts in " + $acceptWall + "s; latency mean=" + [math]::Round(($acceptTimes | Measure-Object -Average).Average * 1000) + "ms max=" + [math]::Round(($acceptTimes | Measure-Object -Maximum).Maximum * 1000) + "ms p99~" + [math]::Round($sortedAcc[$p99idx] * 1000) + "ms")
$curve = @()
$peak = 0
$deadline = (Get-Date).AddMinutes(20)
while ((Get-Date) -lt $deadline) {
    $depth = Queue-Depth
    $done = Done-Count
    $el = [math]::Round(((Get-Date) - $drillStart).TotalSeconds)
    $curve += ($el.ToString() + 's:q=' + $depth + ':done=' + ($done - $baselineDone))
    if ($depth -gt $peak) { $peak = $depth }
    if (($done - $baselineDone) -ge 20 -and $depth -le 0) { break }
    Start-Sleep -Seconds 2
}
$drillWall = [math]::Round(((Get-Date) - $drillStart).TotalSeconds, 1)
Write-Output ("  backlog curve: " + ($curve -join ' '))
Write-Output ("  DRILL: peakQueue=" + $peak + " drainSeconds=" + $drillWall + " rate=" + [math]::Round(20 / [math]::Max($drillWall, 1) * 60, 1) + " docs/min")

Write-Output '=== Phase C: remaining docs batch upload ==='
$rest = $files[20..($files.Count - 1)]
$batchStart = Get-Date
$rej = 0
foreach ($f in $rest) {
    $r = Post-Doc $f.FullName
    if (("$r".Split(' '))[0] -ne '202') { $rej++; Write-Output ("  REJECT " + $f.Name + " -> " + $r) }
}
Write-Output ("  " + $rest.Count + " uploaded in " + [math]::Round(((Get-Date) - $batchStart).TotalSeconds, 1) + "s, rejects=" + $rej + "; polling to DONE...")
$target = $baselineDone + $files.Count
$deadline = (Get-Date).AddMinutes(50)
$finalDone = -1
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 20
    $finalDone = Done-Count
    $depth = Queue-Depth
    if ($finalDone -ge $target -and $depth -le 0) { break }
}
$totalWall = [math]::Round(((Get-Date) - $batchStart).TotalSeconds, 1)
Write-Output ("  INGEST COMPLETE: done=" + $finalDone + "/" + $target + " wallSeconds=" + $totalWall)

Write-Output '=== Phase D: verification ==='
Write-Output ("  ES chunks: " + (curl.exe -s 'http://localhost:9200/doc_chunks/_count'))
$docs = Invoke-RestMethod 'http://localhost:8080/api/documents' -TimeoutSec 30
$failed = @($docs | Where-Object { $_.status -eq 'FAILED' })
Write-Output ("  FAILED records: " + $failed.Count)
$failed | ForEach-Object { Write-Output ("    " + $_.title + ": " + $_.error.Substring(0, [Math]::Min(100, $_.error.Length))) }
$courseDone = @($docs | Where-Object { $_.title -like '20*' -or $_.title -like 'Designing*' -or $_.title -like 'Phoenix*' -or $_.title -like 'The-Art*' })
Write-Output ("  course docs DONE: " + @($courseDone | Where-Object { $_.status -eq 'DONE' }).Count + "  chunks sum: " + (($courseDone | Measure-Object -Property chunkCount -Sum).Sum))
Write-Output 'DONE'
