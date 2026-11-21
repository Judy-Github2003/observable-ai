# Fixed copy of the RECOVERY-01 Git preflight.
# Original: ObservableAI_Recovery_Kit_2026-11-16/02_GIT_POLICY/scripts/Preflight-GitRecovery.ps1
# Change (OA-NEXT-01, 2026-11-17): native git stdout/stderr are captured as data and
# success is decided ONLY by the numeric exit code. The original failed because
# `git bundle verify` prints "is okay" on stderr and $ErrorActionPreference='Stop'
# promoted that stderr to a terminating error.
# The script never writes inside the repository, never deletes or rewrites bundles,
# and creates a new timestamped backup directory outside the repository.
[CmdletBinding()]
param(
    [string]$RepoPath = "C:\自研逻辑模型",
    [string]$BackupRoot = "C:\dsh-lab\recovery-backups",
    [string]$VerifyBundleOnly = ""
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git executable not found in PATH. No repository changes were made."
}
if (-not (Test-Path -LiteralPath $RepoPath)) {
    throw "Repository path does not exist: $RepoPath"
}
$repoRoot = (Resolve-Path -LiteralPath $RepoPath).Path
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.git'))) {
    throw "No .git marker at repository root. Do NOT run git init. Stop and report."
}
$top = (& git -C $repoRoot rev-parse --show-toplevel 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "git rev-parse failed. Stop." }
$topResolved = (Resolve-Path -LiteralPath $top).Path
if ($topResolved.TrimEnd('\') -ne $repoRoot.TrimEnd('\')) {
    throw "Git top-level differs from requested RepoPath. Requested=$repoRoot; top=$topResolved"
}

# Capture native command output without letting stderr become a terminating error.
function Invoke-GitCaptured {
    param(
        [Parameter(Mandatory = $true)][string[]]$GitArgs,
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][string]$ErrFile
    )
    $previous = $ErrorActionPreference
    $exitCode = -1
    try {
        $ErrorActionPreference = 'Continue'
        & git -C $repoRoot @GitArgs 1> $OutFile 2> $ErrFile
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        throw "git $($GitArgs -join ' ') failed exit=$exitCode; see $OutFile and $ErrFile"
    }
    return $exitCode
}

# Verify-only mode: used by the self-test and by manual checks of an existing bundle.
if ($VerifyBundleOnly) {
    if (-not (Test-Path -LiteralPath $VerifyBundleOnly -PathType Leaf)) {
        throw "Bundle file missing: $VerifyBundleOnly"
    }
    $bundlePath = (Resolve-Path -LiteralPath $VerifyBundleOnly).Path
    $logDir = Split-Path -Parent $bundlePath
    $stdout = Join-Path $logDir 'bundle-verify.stdout.txt'
    $stderr = Join-Path $logDir 'bundle-verify.stderr.txt'
    Invoke-GitCaptured -GitArgs @('bundle', 'verify', $bundlePath) -OutFile $stdout -ErrFile $stderr | Out-Null
    Write-Output "BUNDLE_VERIFY_PASS exit=0"
    Write-Output "LOGS $stdout $stderr"
    return
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $BackupRoot ("observable-ai-pre-recovery-" + $stamp)
$suffix = 0
while (Test-Path -LiteralPath $outDir) {
    $suffix++
    $outDir = Join-Path $BackupRoot ("observable-ai-pre-recovery-" + $stamp + "-" + $suffix)
}
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

# Read-only repository facts.
Invoke-GitCaptured -GitArgs @('status', '--short', '--branch') -OutFile (Join-Path $outDir 'status.txt') -ErrFile (Join-Path $outDir 'status.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('status', '--porcelain=v2', '--branch') -OutFile (Join-Path $outDir 'status-porcelain-v2.txt') -ErrFile (Join-Path $outDir 'status-v2.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('rev-parse', 'HEAD') -OutFile (Join-Path $outDir 'head.txt') -ErrFile (Join-Path $outDir 'head.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('rev-parse', '--show-toplevel') -OutFile (Join-Path $outDir 'repo-root.txt') -ErrFile (Join-Path $outDir 'root.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('branch', '-vv') -OutFile (Join-Path $outDir 'branches.txt') -ErrFile (Join-Path $outDir 'branches.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('remote', '-v') -OutFile (Join-Path $outDir 'remotes.txt') -ErrFile (Join-Path $outDir 'remotes.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('log', '-20', '--oneline', '--decorate') -OutFile (Join-Path $outDir 'log-20.txt') -ErrFile (Join-Path $outDir 'log.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('rev-list', '--count', 'HEAD') -OutFile (Join-Path $outDir 'commit-count.txt') -ErrFile (Join-Path $outDir 'count.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('ls-files', '--others', '--exclude-standard') -OutFile (Join-Path $outDir 'untracked-files.txt') -ErrFile (Join-Path $outDir 'untracked.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('ls-files', '-ci', '--exclude-standard') -OutFile (Join-Path $outDir 'ignored-but-tracked.txt') -ErrFile (Join-Path $outDir 'ignored.stderr.txt') | Out-Null

# Diffs may contain private material; they are written outside the repository.
Invoke-GitCaptured -GitArgs @('diff', '--binary', 'HEAD') -OutFile (Join-Path $outDir 'tracked-working-tree.patch') -ErrFile (Join-Path $outDir 'tracked.patch.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('diff', '--cached', '--binary') -OutFile (Join-Path $outDir 'staged.patch') -ErrFile (Join-Path $outDir 'staged.patch.stderr.txt') | Out-Null

# Bundle stores committed refs only; it does not include uncommitted/untracked files.
$bundle = Join-Path $outDir 'observable-ai-all-refs.bundle'
Invoke-GitCaptured -GitArgs @('bundle', 'create', $bundle, '--all') -OutFile (Join-Path $outDir 'bundle-create.log') -ErrFile (Join-Path $outDir 'bundle-create.stderr.txt') | Out-Null
Invoke-GitCaptured -GitArgs @('bundle', 'verify', $bundle) -OutFile (Join-Path $outDir 'bundle-verify.stdout.txt') -ErrFile (Join-Path $outDir 'bundle-verify.stderr.txt') | Out-Null

$summary = @"
Observable AI Git recovery preflight (OA-NEXT-01 fixed copy)
Timestamp: $(Get-Date -Format o)
Repo: $repoRoot
Git top-level: $topResolved
Backup: $outDir
Repository mutation: NONE
Bundle: $bundle
Bundle includes committed refs only. Review tracked diff and untracked inventory separately.
Native git stderr is captured as data; success is decided by the exit code.
Treat this backup directory as sensitive; patches may contain private source or accidental secrets.
"@
$summary | Out-File -LiteralPath (Join-Path $outDir 'SUMMARY.txt') -Encoding utf8

Write-Output "PREFLIGHT COMPLETE"
Write-Output "Repository: $repoRoot"
Write-Output "Backup directory: $outDir"
Write-Output "No branch, index, working-tree, or remote changes were made."
