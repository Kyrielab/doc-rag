# Week-7 experiment runner: SSE/TTFT probe, DLQ drill, eval-set v1.4 reruns,
# small concurrent load test, actuator/Prometheus verification.
# ASCII only; the Chinese probe question lives in smoke-question.txt.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:PATH = "D:\dev\jdk-21\bin;D:\dev\maven\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$env:LLM_API_KEY = [Environment]::GetEnvironmentVariable('LLM_API_KEY', 'User')
$root = Split-Path -Parent $PSScriptRoot
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
function Run-Eval([string]$label, [bool]$skipGen) {
    $url = 'http://localhost:8080/api/eval/run?label=' + $label + '&topK=8&skipGeneration=' + $skipGen.ToString().ToLower() + '&casesFile=eval/my-eval.jsonl'
    $resp = Join-Path $env:TEMP ('eval-' + $label + '.json')
    $code = curl.exe -s -o $resp -w '%{http_code}' -X POST $url --max-time 2400
    if ($code -ne '200') { Write-Host ('  EVAL FAILED http=' + $code); return }
    $r = [IO.File]::ReadAllText($resp, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ('  [' + $label + '] contentRecall@8=' + [math]::Round($r.meanContentRecall, 3) +
        ' srcRecall@8=' + [math]::Round($r.meanRecallAtK, 3) +
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

Write-Output '=== boot app ==='
Stop-App
if (-not (Start-App)) { Write-Output 'APP FAILED'; Get-Content $logFile -Tail 30; exit 1 }
Write-Output 'app ready'

Write-Output '=== 1) actuator endpoints ==='
$code = curl.exe -s -o "$env:TEMP\prom.txt" -w '%{http_code}' 'http://localhost:8080/actuator/prometheus'
Write-Output ('  /actuator/prometheus http=' + $code)

Write-Output '=== 2) SSE streaming probe (TTFT) ==='
$question = (Get-Content (Join-Path $PSScriptRoot 'smoke-question.txt') -Encoding UTF8 -Raw).Trim()
$enc = [uri]::EscapeDataString($question)
$raw = Join-Path $env:TEMP 'sse-probe.txt'
$t0 = Get-Date
curl.exe -s -N --max-time 180 -o $raw ('http://localhost:8080/api/answer/stream?q=' + $enc + '&topK=8')
$wallMs = [math]::Round(((Get-Date) - $t0).TotalMilliseconds)
$stream = [IO.File]::ReadAllText($raw, [Text.Encoding]::UTF8)
$tokenCount = ([regex]::Matches($stream, '(?m)^event:token')).Count
$doneMatch = [regex]::Match($stream, '(?ms)^event:done\r?\ndata:(.+?)(\r?\n\r?\n|$)')
if ($doneMatch.Success) {
    $done = $doneMatch.Groups[1].Value.Trim() | ConvertFrom-Json
    Write-Output ('  tokens=' + $tokenCount + ' ttftMillis=' + $done.trace.ttftMillis + ' totalMillis=' + $done.trace.totalMillis + ' wallMs=' + $wallMs)
    Write-Output ('  refused=' + $done.refused + ' citationAcc=' + [math]::Round($done.citationAccuracy, 2) + ' contextChars=' + $done.trace.promptContextChars)
    Write-Output ('  answer(head)=' + $done.answer.Substring(0, [Math]::Min(80, $done.answer.Length)))
} else {
    Write-Output '  NO done EVENT - stream head:'
    Write-Output ($stream.Substring(0, [Math]::Min(400, $stream.Length)))
}

Write-Output '=== 3) DLQ drill (poison message: unsupported file type) ==='
$poison = Join-Path $env:TEMP 'poison.bin'
[IO.File]::WriteAllBytes($poison, (1..64 | ForEach-Object { [byte](Get-Random -Max 256) }))
$code = curl.exe -s -o "$env:TEMP\poison-resp.json" -w '%{http_code}' -X POST 'http://localhost:8080/api/documents/upload' -F ('file=@' + $poison + ';filename=poison.bin')
$acc = [IO.File]::ReadAllText("$env:TEMP\poison-resp.json", [Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Output ('  accepted http=' + $code + ' docId=' + $acc.docId + ' status=' + $acc.status)
$deadline = (Get-Date).AddMinutes(4); $final = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $docs = Invoke-RestMethod 'http://localhost:8080/api/documents' -TimeoutSec 15
    $final = $docs | Where-Object { $_.docId -eq $acc.docId }
    if ($final -and $final.status -eq 'FAILED') { break }
}
if ($final) {
    Write-Output ('  final: status=' + $final.status + ' attempts=' + $final.attempts + ' error=' + $final.error.Substring(0, [Math]::Min(120, $final.error.Length)))
} else { Write-Output '  poison record never reached FAILED' }
$dlq = curl.exe -s -u docrag:docrag 'http://localhost:15672/api/queues/%2F/docrag.ingest.dlq' | ConvertFrom-Json
Write-Output ('  DLQ messages=' + $dlq.messages)
curl.exe -s -o NUL -X DELETE ('http://localhost:8080/api/documents/' + $acc.docId)
Write-Output '  poison record cleaned up'

Write-Output '=== 4) eval v1.4: retrieval + full ==='
Run-Eval 'v14-hybrid' $true
Run-Eval 'v14-hybrid-full' $false

Write-Output '=== 5) load test (4 workers x 12 questions, cache flushed first) ==='
docker exec docrag-redis redis-cli FLUSHDB | Out-Null
$questions = @(Get-Content (Join-Path $root 'eval\my-eval.jsonl') -Encoding UTF8 |
    Where-Object { $_ -match '"question"' } |
    ForEach-Object { if ($_ -match '"question"\s*:\s*"([^"]+)"') { $Matches[1] } })
Write-Output ('  question pool=' + $questions.Count)
$jobs = @()
for ($w = 0; $w -lt 4; $w++) {
    # PS 5.1 cannot pipe inside an array index expression; build the index list first.
    $idx = @($w..($questions.Count - 1) | Where-Object { $_ % 4 -eq $w })
    $slice = @($questions[$idx])
    $outFile = Join-Path $env:TEMP ('load-w' + $w + '.csv')
    $jobs += Start-Job -ScriptBlock {
        param($qs, $out)
        $lines = @()
        foreach ($q in $qs) {
            $payload = @{ question = $q; topK = 8 } | ConvertTo-Json
            $tmp = [IO.Path]::GetTempFileName()
            [IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
            $t = curl.exe -s -o NUL -w '%{http_code},%{time_total}' -X POST 'http://localhost:8080/api/answer' -H 'Content-Type: application/json; charset=utf-8' --data-binary ('@' + $tmp) --max-time 120
            Remove-Item $tmp -ErrorAction SilentlyContinue
            $lines += $t
        }
        [IO.File]::WriteAllLines($out, $lines)
    } -ArgumentList $slice, $outFile
}
$loadStart = Get-Date
Wait-Job $jobs | Out-Null
$loadWall = ((Get-Date) - $loadStart).TotalSeconds
$jobs | Remove-Job
$results = @()
for ($w = 0; $w -lt 4; $w++) {
    $f = Join-Path $env:TEMP ('load-w' + $w + '.csv')
    if (Test-Path $f) { $results += Get-Content $f | Where-Object { $_ -match ',' } }
}
$times = $results | ForEach-Object { [double]($_ -split ',')[1] }
$codes = $results | ForEach-Object { ($_ -split ',')[0] }
$okCount = @($codes | Where-Object { $_ -eq '200' }).Count
$sorted = $times | Sort-Object
$p50 = $sorted[[math]::Floor($sorted.Count * 0.5)]
$p95 = $sorted[[math]::Min($sorted.Count - 1, [math]::Floor($sorted.Count * 0.95))]
Write-Output ('  requests=' + $results.Count + ' ok=' + $okCount + ' wallSeconds=' + [math]::Round($loadWall, 1) + ' throughput=' + [math]::Round($okCount / $loadWall, 2) + ' req/s')
Write-Output ('  latency p50=' + [math]::Round($p50, 2) + 's p95=' + [math]::Round($p95, 2) + 's max=' + [math]::Round(($sorted | Select-Object -Last 1), 2) + 's')

Write-Output '=== 6) prometheus metrics sample (after load) ==='
curl.exe -s 'http://localhost:8080/actuator/prometheus' -o "$env:TEMP\prom2.txt"
Select-String -Path "$env:TEMP\prom2.txt" -Pattern '^rag_answer_seconds_count|^rag_ttft_millis_count|^llm_tokens_total|^rag_context_chars_count|^http_server_requests_seconds_count\{.*answer' | Select-Object -First 12 | ForEach-Object { Write-Output ('  ' + $_.Line) }

Write-Output '=== week-7 run complete (app left running) ==='
