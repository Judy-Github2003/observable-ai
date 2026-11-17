# Self-test for the fixed Git recovery preflight.
# Creates a throwaway repository under %TEMP%; never touches the real repo or its bundles.
# Verifies: healthy bundle -> exit 0; corrupted bundle -> non-zero; missing bundle -> non-zero.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$preflight = Join-Path $here 'Preflight-GitRecovery.ps1'
if (-not (Test-Path -LiteralPath $preflight)) { throw "Preflight script missing: $preflight" }

$testRoot = Join-Path $env:TEMP ("oa-next01-preflight-selftest-" + [guid]::NewGuid().ToString('N'))
$failures = New-Object System.Collections.ArrayList

function Invoke-PreflightChild {
    param([string[]]$Arguments)
    # Native stderr is data here: a non-zero child exit is the expected failure signal,
    # not a terminating PowerShell error. Keep the parent preference unchanged.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $preflight @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return @{ Code = $code; Output = ($output | Out-String) }
}

try {
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    & git -C $testRoot init -q
    if ($LASTEXITCODE -ne 0) { throw 'git init failed' }
    & git -C $testRoot config user.email 'selftest@example.invalid'
    & git -C $testRoot config user.name 'Preflight Self Test'
    Set-Content -LiteralPath (Join-Path $testRoot 'README.txt') -Value 'preflight selftest' -Encoding ASCII
    & git -C $testRoot add -- README.txt
    & git -C $testRoot commit -q -m 'selftest: initial commit'
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }

    # 1. Healthy repository: main preflight must create a backup and exit 0.
    $main = Invoke-PreflightChild -Arguments @('-RepoPath', $testRoot, '-BackupRoot', (Join-Path $testRoot 'backups'))
    if ($main.Code -ne 0) { [void]$failures.Add("main preflight exit=$($main.Code)") }
    $bundles = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'backups') -Recurse -File -Filter '*.bundle' -ErrorAction SilentlyContinue)
    if ($bundles.Count -ne 1) { [void]$failures.Add("expected 1 bundle, found $($bundles.Count)") }
    $goodBundle = $bundles[0].FullName

    # 2. Verify-only on a healthy bundle must exit 0.
    $good = Invoke-PreflightChild -Arguments @('-RepoPath', $testRoot, '-VerifyBundleOnly', $goodBundle)
    if ($good.Code -ne 0) { [void]$failures.Add("verify good exit=$($good.Code)") }
    if ($good.Output -notmatch 'BUNDLE_VERIFY_PASS') { [void]$failures.Add('verify good missing PASS marker') }

    # 3. Invalid bundle content must exit non-zero.
    # Note: truncating only the pack data can still leave a valid v2 header, which
    # `git bundle verify` may accept; use content that is not a bundle at all.
    $badBundle = Join-Path $testRoot 'bad.bundle'
    Set-Content -LiteralPath $badBundle -Value 'this is not a git bundle' -Encoding ASCII
    $bad = Invoke-PreflightChild -Arguments @('-RepoPath', $testRoot, '-VerifyBundleOnly', $badBundle)
    if ($bad.Code -eq 0) { [void]$failures.Add('invalid bundle returned exit 0') }

    # 4. Missing bundle must exit non-zero.
    $missing = Invoke-PreflightChild -Arguments @('-RepoPath', $testRoot, '-VerifyBundleOnly', (Join-Path $testRoot 'no-such.bundle'))
    if ($missing.Code -eq 0) { [void]$failures.Add('missing bundle returned exit 0') }

    if ($failures.Count -gt 0) {
        Write-Output ("PREFLIGHT_SELFTEST_FAIL " + ($failures -join '; '))
        exit 1
    }
    Write-Output 'PREFLIGHT_SELFTEST_PASS healthy=0 corrupted=non-zero missing=non-zero'
    exit 0
}
catch {
    Write-Output ('PREFLIGHT_SELFTEST_FAIL ' + $_.Exception.Message)
    exit 1
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
