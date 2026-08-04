#!/usr/bin/env bash
# setup-companion-sources.sh
# Synchronize and verify companion sources (warp-src and termux-packages) for hermetic builds.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

WARP_SRC_URL="https://github.com/ImL1s/warp.git"
WARP_SRC_BRANCH="warp-mobile/m0-facade"
WARP_SRC_COMMIT="0f704dbed0ece066a7d56ee0573c6e3f5cedd6ee"

TERMUX_PACKAGES_URL="https://github.com/ImL1s/termux-packages.git"
TERMUX_PACKAGES_BRANCH="warp-mobile/main"
TERMUX_PACKAGES_COMMIT="398740dfd23637085083f3976baeb3872c06cc45"

ZSH_BODY_SHA256="3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"

cd "$REPO_ROOT"

clear_stale_git_lock() {
    local target_dir="$1"
    if [[ -d "$target_dir/.git" || -f "$target_dir/.git" ]]; then
        rm -f "$target_dir/.git/index.lock" 2>/dev/null || true
        rm -f "$target_dir/.git/HEAD.lock" 2>/dev/null || true
        rm -f "$target_dir/.git/refs/heads/*.lock" 2>/dev/null || true
    fi
}

compute_sha256() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        powershell.exe -NoProfile -Command "(Get-FileHash '$file' -Algorithm SHA256).Hash.ToLower()" | tr -d '\r\n'
    fi
}

is_git_repo() {
    git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1
}

echo "=== Syncing and Verifying Companion Sources ==="

# 1. Sync warp-src
if [[ ! -d "$REPO_ROOT/warp-src" || ! -f "$REPO_ROOT/warp-src/app/assets/bundled/bootstrap/zsh_body.sh" ]]; then
    echo "warp-src directory missing or incomplete. Initializing submodule / cloning..."
    clear_stale_git_lock "$REPO_ROOT"
    if command -v git >/dev/null 2>&1 && is_git_repo; then
        git submodule update --init --recursive warp-src || true
    fi
    if [[ ! -d "$REPO_ROOT/warp-src" || ! -f "$REPO_ROOT/warp-src/app/assets/bundled/bootstrap/zsh_body.sh" ]]; then
        git clone "$WARP_SRC_URL" warp-src --branch "$WARP_SRC_BRANCH"
    fi
fi

if [[ -d "$REPO_ROOT/warp-src/.git" || -f "$REPO_ROOT/warp-src/.git" ]]; then
    echo "Checking warp-src commit SHA..."
    clear_stale_git_lock "$REPO_ROOT/warp-src"
    CURRENT_WARP_SHA=$(git -C "$REPO_ROOT/warp-src" rev-parse HEAD || true)
    echo "warp-src current commit SHA: $CURRENT_WARP_SHA"
    if [[ "$CURRENT_WARP_SHA" != "$WARP_SRC_COMMIT" ]]; then
        echo "warp-src commit mismatch ($CURRENT_WARP_SHA != $WARP_SRC_COMMIT). Checking out pinned commit..."
        clear_stale_git_lock "$REPO_ROOT/warp-src"
        git -C "$REPO_ROOT/warp-src" checkout "$WARP_SRC_COMMIT" || {
            echo "ERROR: Failed to checkout warp-src commit $WARP_SRC_COMMIT" >&2
            exit 1
        }
        CURRENT_WARP_SHA=$(git -C "$REPO_ROOT/warp-src" rev-parse HEAD)
        if [[ "$CURRENT_WARP_SHA" != "$WARP_SRC_COMMIT" ]]; then
            echo "ERROR: warp-src commit SHA mismatch after checkout. Expected $WARP_SRC_COMMIT, got $CURRENT_WARP_SHA" >&2
            exit 1
        fi
    fi
    echo "warp-src commit SHA verified ($WARP_SRC_COMMIT)"
fi

# Verify zsh_body.sh SHA256
ZSH_BODY_PATH="$REPO_ROOT/warp-src/app/assets/bundled/bootstrap/zsh_body.sh"
if [[ ! -f "$ZSH_BODY_PATH" ]]; then
    echo "ERROR: Missing $ZSH_BODY_PATH" >&2
    exit 1
fi

ACTUAL_ZSH_SHA=$(compute_sha256 "$ZSH_BODY_PATH")
VALID_SHA_LIST=("$ZSH_BODY_SHA256" "70c5f637d09e0c494cb1dc1fbb4668c158b42f27310fcc833637e981bc656347" "43fd05527be5348e35dda64f4df477850d70d0862963b8cea7e68e2df809013f" "2d22064b8803224c257371a1a6b2c45a0873d9b8cbd3b76754f2e7ba1a9f0a49")
IS_VALID=false
for valid_sha in "${VALID_SHA_LIST[@]}"; do
    if [[ "$ACTUAL_ZSH_SHA" == "$valid_sha" ]]; then
        IS_VALID=true
        break
    fi
done

if [[ "$IS_VALID" == "false" ]]; then
    echo "ERROR: zsh_body.sh SHA256 mismatch:" >&2
    echo "  Expected one of: ${VALID_SHA_LIST[*]}" >&2
    echo "  Actual:          $ACTUAL_ZSH_SHA" >&2
    exit 1
fi
echo "zsh_body.sh SHA256 verified ($ACTUAL_ZSH_SHA)"

# 2. Sync termux-packages
if [[ ! -d "$REPO_ROOT/termux-packages" ]]; then
    echo "termux-packages directory missing. Initializing submodule / cloning..."
    clear_stale_git_lock "$REPO_ROOT"
    if command -v git >/dev/null 2>&1 && is_git_repo; then
        git submodule update --init --recursive termux-packages || true
    fi
    if [[ ! -d "$REPO_ROOT/termux-packages" ]]; then
        git clone "$TERMUX_PACKAGES_URL" termux-packages --branch "$TERMUX_PACKAGES_BRANCH"
    fi
fi

if [[ -d "$REPO_ROOT/termux-packages/.git" || -f "$REPO_ROOT/termux-packages/.git" ]]; then
    echo "Checking termux-packages commit SHA..."
    clear_stale_git_lock "$REPO_ROOT/termux-packages"
    CURRENT_TERMUX_SHA=$(git -C "$REPO_ROOT/termux-packages" rev-parse HEAD || true)
    echo "termux-packages current commit SHA: $CURRENT_TERMUX_SHA"
    if [[ "$CURRENT_TERMUX_SHA" != "$TERMUX_PACKAGES_COMMIT" ]]; then
        echo "termux-packages commit mismatch ($CURRENT_TERMUX_SHA != $TERMUX_PACKAGES_COMMIT). Checking out pinned commit..."
        clear_stale_git_lock "$REPO_ROOT/termux-packages"
        git -C "$REPO_ROOT/termux-packages" checkout "$TERMUX_PACKAGES_COMMIT" || {
            echo "ERROR: Failed to checkout termux-packages commit $TERMUX_PACKAGES_COMMIT" >&2
            exit 1
        }
        CURRENT_TERMUX_SHA=$(git -C "$REPO_ROOT/termux-packages" rev-parse HEAD)
        if [[ "$CURRENT_TERMUX_SHA" != "$TERMUX_PACKAGES_COMMIT" ]]; then
            echo "ERROR: termux-packages commit SHA mismatch after checkout. Expected $TERMUX_PACKAGES_COMMIT, got $CURRENT_TERMUX_SHA" >&2
            exit 1
        fi
    fi
    echo "termux-packages commit SHA verified ($TERMUX_PACKAGES_COMMIT)"
fi

echo "=== Companion Sources Ready ==="
