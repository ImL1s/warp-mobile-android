#!/usr/bin/env bash
# env-setup.sh
# Shared environment setup for warp-mobile-android test runner scripts.
# Auto-detects cargo, JDK 17, ANDROID_HOME, and verifies companion sources.

set -euo pipefail

ENV_SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ENV_SETUP_DIR/../.." && pwd)"
cd "$REPO_ROOT"

if command -v cygpath >/dev/null 2>&1; then
    REPO_ROOT_MIXED="$(cygpath -m "$REPO_ROOT")"
else
    REPO_ROOT_MIXED="$REPO_ROOT"
fi

export ENV_SETUP_DIR
export REPO_ROOT
export REPO_ROOT_MIXED

detect_cargo() {
    if command -v cargo >/dev/null 2>&1; then
        echo "[env-setup] Cargo is already in PATH: $(command -v cargo)"
        return 0
    fi

    local cargo_candidates=()
    if [[ -n "${CARGO_HOME:-}" ]]; then
        cargo_candidates+=("${CARGO_HOME}/bin")
    fi
    if [[ -n "${HOME:-}" ]]; then
        cargo_candidates+=("${HOME}/.cargo/bin")
    fi
    if [[ -n "${USERPROFILE:-}" ]]; then
        cargo_candidates+=("${USERPROFILE}/.cargo/bin")
        if command -v cygpath >/dev/null 2>&1; then
            cargo_candidates+=("$(cygpath -u "${USERPROFILE}")/.cargo/bin")
        fi
    fi

    for candidate in "${cargo_candidates[@]}"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            if [[ -x "$candidate/cargo" || -x "$candidate/cargo.exe" ]]; then
                export PATH="$candidate:$PATH"
                echo "[env-setup] Auto-detected and added cargo to PATH from: $candidate"
                return 0
            fi
        fi
    done

    echo "[env-setup] WARNING: cargo directory not found in candidate paths." >&2
}

detect_jdk17() {
    if [[ -d "C:/Program Files/Zulu/zulu-17" && ( -x "C:/Program Files/Zulu/zulu-17/bin/javac" || -x "C:/Program Files/Zulu/zulu-17/bin/javac.exe" ) ]]; then
        export JAVA_HOME="C:/Program Files/Zulu/zulu-17"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "[env-setup] Auto-detected Windows JDK 17: $JAVA_HOME"
        return 0
    fi

    if [[ -n "${JAVA_HOME:-}" ]]; then
        local check_home="$JAVA_HOME"
        if [[ "$check_home" =~ ^/mnt/c/(.*) ]]; then
            check_home="C:/${BASH_REMATCH[1]}"
        elif [[ "$check_home" =~ ^/c/(.*) ]]; then
            check_home="C:/${BASH_REMATCH[1]}"
        fi
        if [[ -x "${check_home}/bin/javac" || -x "${check_home}/bin/javac.exe" ]]; then
            local version_str
            version_str="$("${check_home}/bin/javac" -version 2>&1 || true)"
            if [[ "$version_str" =~ (17|21)\.[0-9]+ ]]; then
                export JAVA_HOME="$check_home"
                echo "[env-setup] JAVA_HOME is already set to JDK 17+: $JAVA_HOME"
                return 0
            fi
        fi
    fi

    local candidates=(
        "C:/Program Files/Zulu/zulu-17"
        "/c/Program Files/Zulu/zulu-17"
        "/mnt/c/Program Files/Zulu/zulu-17"
        "C:/Program Files/Java/jdk-17"
        "/c/Program Files/Java/jdk-17"
        "C:/Program Files/Eclipse Adoptium/jdk-17.0.12.7-hotspot"
        "/c/Program Files/Eclipse Adoptium/jdk-17.0.12.7-hotspot"
        "/usr/lib/jvm/java-17-openjdk-amd64"
        "/usr/lib/jvm/java-17-openjdk"
        "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"
        "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"
    )

    for cand in "${candidates[@]}"; do
        local check_cand="$cand"
        if [[ "$check_cand" =~ ^/mnt/c/(.*) ]]; then
            check_cand="C:/${BASH_REMATCH[1]}"
        elif [[ "$check_cand" =~ ^/c/(.*) ]]; then
            check_cand="C:/${BASH_REMATCH[1]}"
        fi
        if [[ -d "$check_cand" && ( -x "$check_cand/bin/javac" || -x "$check_cand/bin/javac.exe" ) ]]; then
            export JAVA_HOME="$check_cand"
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "[env-setup] Auto-detected and set JAVA_HOME to: $JAVA_HOME"
            return 0
        fi
    done

    echo "[env-setup] WARNING: JDK 17 candidate directory not found. Using existing PATH environment." >&2
}

detect_android_home() {
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        local check_home="$ANDROID_HOME"
        if [[ "$check_home" =~ ^/mnt/c/(.*) ]]; then
            check_home="C:/${BASH_REMATCH[1]}"
        elif [[ "$check_home" =~ ^/c/(.*) ]]; then
            check_home="C:/${BASH_REMATCH[1]}"
        fi
        if [[ -d "${check_home}" ]]; then
            export ANDROID_HOME="$check_home"
            echo "[env-setup] ANDROID_HOME is already set to: $ANDROID_HOME"
            return 0
        fi
    fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        local check_root="$ANDROID_SDK_ROOT"
        if [[ "$check_root" =~ ^/mnt/c/(.*) ]]; then
            check_root="C:/${BASH_REMATCH[1]}"
        elif [[ "$check_root" =~ ^/c/(.*) ]]; then
            check_root="C:/${BASH_REMATCH[1]}"
        fi
        if [[ -d "${check_root}" ]]; then
            export ANDROID_HOME="$check_root"
            echo "[env-setup] Auto-detected ANDROID_HOME from ANDROID_SDK_ROOT: $ANDROID_HOME"
            return 0
        fi
    fi

    local candidates=(
        "${LOCALAPPDATA:-}/Android/Sdk"
        "C:/Users/${USER:-}/AppData/Local/Android/Sdk"
        "/c/Users/${USER:-}/AppData/Local/Android/Sdk"
        "C:/Android/sdk"
        "${HOME:-}/Android/Sdk"
        "${HOME:-}/Library/Android/sdk"
        "/usr/lib/android-sdk"
    )

    for cand in "${candidates[@]}"; do
        if [[ -n "$cand" ]]; then
            local check_cand="$cand"
            if [[ "$check_cand" =~ ^/mnt/c/(.*) ]]; then
                check_cand="C:/${BASH_REMATCH[1]}"
            elif [[ "$check_cand" =~ ^/c/(.*) ]]; then
                check_cand="C:/${BASH_REMATCH[1]}"
            fi
            if [[ -d "$check_cand" ]]; then
                export ANDROID_HOME="$check_cand"
                export ANDROID_SDK_ROOT="$ANDROID_HOME"
                echo "[env-setup] Auto-detected ANDROID_HOME to: $ANDROID_HOME"
                return 0
            fi
        fi
    done

    echo "[env-setup] WARNING: ANDROID_HOME candidate directory not found." >&2
}

ensure_companion_sources() {
    if [[ -f "$ENV_SETUP_DIR/setup-companion-sources.sh" ]]; then
        bash "$ENV_SETUP_DIR/setup-companion-sources.sh"
    fi
}

detect_cargo
detect_jdk17
detect_android_home
ensure_companion_sources
