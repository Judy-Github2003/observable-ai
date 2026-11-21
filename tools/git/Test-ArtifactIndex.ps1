# Verify docs/project/ARTIFACT_INDEX.csv against local files.
# Only explicit %USERPROFILE% and %LOCALAPPDATA% placeholders are expanded; CSV values
# are never treated as auto-expanded paths. Read-only.
[CmdletBinding()]
param(
    [string]$IndexPath = "C:\自研逻辑模型\docs\project\ARTIFACT_INDEX.csv"
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $IndexPath)) { throw "Index missing: $IndexPath" }
$rows = Import-Csv -LiteralPath $IndexPath
$checked = 0
$failed = 0

foreach ($row in $rows) {
    $raw = [string]$row.relative_or_external_path
    if (-not $raw -or $raw -eq 'UNKNOWN') { continue }
    $expanded = $raw.Replace('%USERPROFILE%', $env:USERPROFILE).Replace('%LOCALAPPDATA%', $env:LOCALAPPDATA)
    if (-not (Test-Path -LiteralPath $expanded)) {
        Write-Output ("FAIL missing " + $row.artifact_id + " " + $raw)
        $failed++
        continue
    }
    $item = Get-Item -LiteralPath $expanded
    if ($item.PSIsContainer) {
        $checked++
        continue
    }
    $expectedSize = 0L
    if (-not [long]::TryParse([string]$row.size_bytes, [ref]$expectedSize)) {
        Write-Output ("FAIL bad size " + $row.artifact_id)
        $failed++
        continue
    }
    if ($item.Length -ne $expectedSize) {
        Write-Output ("FAIL size " + $row.artifact_id + " expected=" + $expectedSize + " actual=" + $item.Length)
        $failed++
        continue
    }
    if ($row.sha256 -and $row.sha256 -ne 'UNKNOWN') {
        $actual = (Get-FileHash -LiteralPath $expanded -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne ([string]$row.sha256).ToLowerInvariant()) {
            Write-Output ("FAIL sha256 " + $row.artifact_id)
            $failed++
            continue
        }
    }
    $checked++
}

if ($failed -gt 0) {
    Write-Output ("ARTIFACT_INDEX_FAIL checked=" + $checked + " failed=" + $failed)
    exit 1
}
Write-Output ("ARTIFACT_INDEX_PASS checked=" + $checked + " rows=" + $rows.Count + " placeholders=USERPROFILE,LOCALAPPDATA")
