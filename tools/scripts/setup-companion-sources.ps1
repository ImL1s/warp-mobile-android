# setup-companion-sources.ps1
# Synchronize and verify companion sources (warp-src and termux-packages) for hermetic builds on Windows.

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.." | Select-Object -ExpandProperty Path

$WarpSrcUrl = "https://github.com/ImL1s/warp.git"
$WarpSrcBranch = "warp-mobile/m0-facade"
$WarpSrcCommit = "0f704dbed0ece066a7d56ee0573c6e3f5cedd6ee"

$TermuxPackagesUrl = "https://github.com/ImL1s/termux-packages.git"
$TermuxPackagesBranch = "warp-mobile/main"
$TermuxPackagesCommit = "398740dfd23637085083f3976baeb3872c06cc45"

$ZshBodySha256 = "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"
$ValidZshShaList = @(
    "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26",
    "70c5f637d09e0c494cb1dc1fbb4668c158b42f27310fcc833637e981bc656347",
    "43fd05527be5348e35dda64f4df477850d70d0862963b8cea7e68e2df809013f",
    "2d22064b8803224c257371a1a6b2c45a0873d9b8cbd3b76754f2e7ba1a9f0a49"
)

Set-Location $RepoRoot

function Test-GitRepo {
    try {
        git -C $RepoRoot rev-parse --git-dir 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

Write-Host "=== Syncing and Verifying Companion Sources ==="

# 1. Sync warp-src
$WarpSrcDir = Join-Path $RepoRoot "warp-src"
$WarpMarker = Join-Path $WarpSrcDir "app\assets\bundled\bootstrap\zsh_body.sh"
if (-not (Test-Path $WarpMarker)) {
    Write-Host "warp-src directory missing or incomplete. Initializing submodule / cloning..."
    if (Test-GitRepo) {
        git submodule update --init --recursive warp-src 2>$null
    }
    if (-not (Test-Path $WarpMarker)) {
        git clone $WarpSrcUrl warp-src --branch $WarpSrcBranch
    }
}

$WarpGitPath = Join-Path $WarpSrcDir ".git"
if (Test-Path $WarpGitPath) {
    $CurrentWarpSha = (git -C $WarpSrcDir rev-parse HEAD).Trim()
    Write-Host "warp-src current commit SHA: $CurrentWarpSha"
    if ($CurrentWarpSha -ne $WarpSrcCommit) {
        Write-Host "warp-src commit mismatch ($CurrentWarpSha != $WarpSrcCommit). Checking out pinned commit..."
        git -C $WarpSrcDir checkout $WarpSrcCommit
        $CurrentWarpSha = (git -C $WarpSrcDir rev-parse HEAD).Trim()
        if ($CurrentWarpSha -ne $WarpSrcCommit) {
            throw "ERROR: warp-src commit SHA mismatch after checkout. Expected $WarpSrcCommit, got $CurrentWarpSha"
        }
    }
    Write-Host "warp-src commit SHA verified ($WarpSrcCommit)"
}

# Verify zsh_body.sh SHA256
$ZshBodyPath = Join-Path $WarpSrcDir "app\assets\bundled\bootstrap\zsh_body.sh"
if (-not (Test-Path $ZshBodyPath)) {
    throw "ERROR: Missing $ZshBodyPath"
}

$ActualZshSha = (Get-FileHash $ZshBodyPath -Algorithm SHA256).Hash.ToLower()
if ($ValidZshShaList -notcontains $ActualZshSha) {
    throw "ERROR: zsh_body.sh SHA256 mismatch. Expected one of: $($ValidZshShaList -join ', '), Actual: $ActualZshSha"
}
Write-Host "zsh_body.sh SHA256 verified ($ActualZshSha)"

# 2. Sync termux-packages
$TermuxPackagesDir = Join-Path $RepoRoot "termux-packages"
if (-not (Test-Path $TermuxPackagesDir)) {
    Write-Host "termux-packages directory missing. Initializing submodule / cloning..."
    if (Test-GitRepo) {
        git submodule update --init --recursive termux-packages 2>$null
    }
    if (-not (Test-Path $TermuxPackagesDir)) {
        git clone $TermuxPackagesUrl termux-packages --branch $TermuxPackagesBranch
    }
}

$TermuxGitPath = Join-Path $TermuxPackagesDir ".git"
if (Test-Path $TermuxGitPath) {
    $CurrentTermuxSha = (git -C $TermuxPackagesDir rev-parse HEAD).Trim()
    Write-Host "termux-packages current commit SHA: $CurrentTermuxSha"
    if ($CurrentTermuxSha -ne $TermuxPackagesCommit) {
        Write-Host "termux-packages commit mismatch ($CurrentTermuxSha != $TermuxPackagesCommit). Checking out pinned commit..."
        git -C $TermuxPackagesDir checkout $TermuxPackagesCommit
        $CurrentTermuxSha = (git -C $TermuxPackagesDir rev-parse HEAD).Trim()
        if ($CurrentTermuxSha -ne $TermuxPackagesCommit) {
            throw "ERROR: termux-packages commit SHA mismatch after checkout. Expected $TermuxPackagesCommit, got $CurrentTermuxSha"
        }
    }
    Write-Host "termux-packages commit SHA verified ($TermuxPackagesCommit)"
}

Write-Host "=== Companion Sources Ready ==="

