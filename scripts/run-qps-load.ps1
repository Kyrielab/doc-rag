# Retrieval-layer QPS load test (roadmap direction C).
# Targets GET /api/search (retrieval-only: no LLM, no cache) at concurrency 1/4/8,
# then measures the cached-answer path for contrast. Questions come from
# eval/course-eval.jsonl so the mix matches real usage. ASCII only.
param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $Root
Set-Location $root

# extract questions (skip refusal cases - they short-circuit retrieval-less paths differently)
$questions = @(Get-Content (Join-Path $root 'eval\course-eval.jsonl') -Encoding UTF8 |
    Where-Object { $_ -match '"question"' -and $_ -notmatch '"shouldRefuse":true' } |
    ForEach-Object { if ($_ -match '"question"\s*:\s*"([^"]+)"') { $Matches[1] } })
Write-Output ('question pool: ' + $questions.Count)

function Run-Level([int]$concurrency, [int]$total) {
    $perWorker = [math]::Ceiling($total / $concurrency)
    $jobs = @()
    for ($w = 0; $w -lt $concurrency; $w++) {
        $idx = @()
        for ($k = 0; $k -lt $perWorker; $k++) { $idx += ($w + $k * $concurrency) % $questions.Count }
        $slice = @($questions[$idx])
        $outFile = Join-Path $env:TEMP ('qps-w' + $w + '.csv')
        $jobs += Start-Job -ScriptBlock {
            param($qs, $out)
            $lines = @()
            foreach ($q in $qs) {
                $enc = [uri]::EscapeDataString($q)
                $t = curl.exe -s -o NUL -w '%{http_code},%{time_total}' --max-time 60 ('http://localhost:8080/api/search?q=' + $enc + '&topK=8')
                $lines += "$t"
            }
            [IO.File]::WriteAllLines($out, $lines)
        } -ArgumentList $slice, $outFile
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Wait-Job $jobs | Out-Null
    $sw.Stop()
    $jobs | Remove-Job
    $results = @()
    for ($w = 0; $w -lt $concurrency; $w++) {
        $f = Join-Path $env:TEMP ('qps-w' + $w + '.csv')
        if (Test-Path $f) { $results += Get-Content $f | Where-Object { $_ -match ',' } }
    }
    $times = $results | ForEach-Object { [double](($_ -split ',')[1]) }
    $okCount = @($results | Where-Object { ($_ -split ',')[0] -eq '200' }).Count
    $sorted = $times | Sort-Object
    $p50 = $sorted[[math]::Floor($sorted.Count * 0.5)]
    $p95 = $sorted[[math]::Min($sorted.Count - 1, [math]::Floor($sorted.Count * 0.95))]
    $wall = $sw.Elapsed.TotalSeconds
    Write-Output ('  c=' + $concurrency + ': n=' + $results.Count + ' ok=' + $okCount +
        ' wall=' + [math]::Round($wall, 1) + 's QPS=' + [math]::Round($okCount / $wall, 2) +
        ' p50=' + [math]::Round($p50, 3) + 's p95=' + [math]::Round($p95, 3) + 's max=' + [math]::Round(($sorted | Select-Object -Last 1), 3) + 's')
}

Write-Output '=== stage breakdown of one search (bottleneck attribution) ==='
$enc = [uri]::EscapeDataString($questions[0])
$r = curl.exe -s --max-time 60 ('http://localhost:8080/api/search?q=' + $enc + '&topK=8') | ConvertFrom-Json
Write-Output ('  lexical=' + $r.lexicalMillis + 'ms vector=' + $r.vectorMillis + 'ms fusion=' + $r.fusionMillis + 'ms rewrite=' + $r.rewriteMillis + 'ms fusedCount=' + $r.fusedCount)

Write-Output '=== QPS levels (24 requests each) ==='
Run-Level 1 24
Run-Level 4 24
Run-Level 8 24

Write-Output '=== cached answer path contrast (1 generation call + cache hit) ==='
$payload = @{ question = $questions[1]; topK = 8 } | ConvertTo-Json
$tmp = Join-Path $env:TEMP 'qps-cache.json'
[IO.File]::WriteAllText($tmp, $payload, [Text.UTF8Encoding]::new($false))
$t1 = curl.exe -s -o NUL -w '%{time_total}' -X POST 'http://localhost:8080/api/answer' -H 'Content-Type: application/json; charset=utf-8' --data-binary ('@' + $tmp) --max-time 120
$t2 = curl.exe -s -o NUL -w '%{time_total}' -X POST 'http://localhost:8080/api/answer' -H 'Content-Type: application/json; charset=utf-8' --data-binary ('@' + $tmp) --max-time 120
Write-Output ('  first(generate+cache-write)=' + $t1 + 's  second(cache-hit)=' + $t2 + 's')
Remove-Item $tmp -ErrorAction SilentlyContinue
Write-Output 'DONE'
