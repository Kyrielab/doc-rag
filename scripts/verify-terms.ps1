# Verifies candidate eval terms against the corpus. ASCII only (see start-dev.ps1 header).
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$corpusDir = Join-Path $root 'corpus'
$termsFile = Join-Path $PSScriptRoot 'eval-terms.txt'

$terms = Get-Content $termsFile -Encoding UTF8 | Where-Object { $_.Trim() -ne '' -and -not $_.Trim().StartsWith('#') }
$files = Get-ChildItem $corpusDir -Filter *.md | Where-Object { $_.Name -ne 'README.md' }

$contents = @{}
foreach ($f in $files) {
    $contents[$f.Name] = (Get-Content $f.FullName -Raw -Encoding UTF8).ToString().ToLowerInvariant()
}
Write-Output ('corpus files: ' + $files.Count)

foreach ($t in $terms) {
    $term = $t.Trim().ToLowerInvariant()
    $hits = @()
    foreach ($k in $contents.Keys) {
        if ($contents[$k].Contains($term)) { $hits += $k }
    }
    Write-Output ($t.Trim() + ' => ' + $hits.Count + ' : ' + ($hits -join ','))
}
