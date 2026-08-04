#!/usr/bin/env bash
# test-tier1.sh [--unit-only] [<device-serial>]
# Tier 1 Feature Coverage test runner for warp-mobile-android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env-setup.sh"

cd "$REPO_ROOT"
ARTIFACT_DIR="$REPO_ROOT/.omc/e2e-artifacts"
SUMMARY_JSON="$ARTIFACT_DIR/tier1-summary.json"
JUNIT_REPORT_DIR="$ARTIFACT_DIR/junit-reports"

mkdir -p "$ARTIFACT_DIR" "$JUNIT_REPORT_DIR"

UNIT_ONLY=false
SERIAL=""

for arg in "$@"; do
    if [[ "$arg" == "--unit-only" ]]; then
        UNIT_ONLY=true
    elif [[ -z "$SERIAL" ]]; then
        SERIAL="$arg"
    fi
done

START_TIME=$(date +%s)
FAILED=0
PASSED=0
TOTAL=0

echo "=========================================="
echo " Running Tier 1: Feature Coverage Tests"
echo "=========================================="

# 1. Rust host crate unit tests
echo "[Tier 1] Running Rust host unit tests..."
TOTAL=$((TOTAL + 1))
if cargo test -p warp-mobile-android-host --manifest-path "$REPO_ROOT/Cargo.toml" -- --test-threads=1; then
    echo "[Tier 1] Rust host unit tests: PASSED"
    PASSED=$((PASSED + 1))
else
    echo "[Tier 1] Rust host unit tests: FAILED" >&2
    FAILED=$((FAILED + 1))
fi

# 2. Rust AI crate unit tests
echo "[Tier 1] Running Rust AI unit tests..."
TOTAL=$((TOTAL + 1))
if cargo test -p warp_ai_mobile --manifest-path "$REPO_ROOT/Cargo.toml"; then
    echo "[Tier 1] Rust AI unit tests: PASSED"
    PASSED=$((PASSED + 1))
else
    echo "[Tier 1] Rust AI unit tests: FAILED" >&2
    FAILED=$((FAILED + 1))
fi

# 3. Android Kotlin unit tests
echo "[Tier 1] Running Android Kotlin Tier 1 unit tests..."
TOTAL=$((TOTAL + 1))
cd "$REPO_ROOT/android"
GRADLE_CMD="./gradlew"
if [[ -f "./gradlew.bat" ]]; then
    if [[ "${OSTYPE:-}" == "msys" || "${OSTYPE:-}" == "win32" || "${OSTYPE:-}" == "cygwin" ]]; then
        GRADLE_CMD="./gradlew.bat"
    elif [[ -x "/mnt/c/Windows/System32/cmd.exe" ]]; then
        GRADLE_CMD="/mnt/c/Windows/System32/cmd.exe /c gradlew.bat"
    else
        GRADLE_CMD="./gradlew.bat"
    fi
fi

if [[ "${OSTYPE:-}" == "msys" || "${OSTYPE:-}" == "win32" || "${OSTYPE:-}" == "cygwin" ]]; then
    $GRADLE_CMD --stop 2>/dev/null || true
    taskkill //F //IM java.exe 2>/dev/null || true
else
    $GRADLE_CMD --stop 2>/dev/null || true
fi

rm -rf "$REPO_ROOT/android/app/build/intermediates" 2>/dev/null || true
mkdir -p "$REPO_ROOT/android/app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/single"
mkdir -p "$REPO_ROOT/android/app/build/tmp/kotlin-classes/debug/META-INF"

if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier1UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
    echo "[Tier 1] Kotlin unit tests: PASSED"
    PASSED=$((PASSED + 1))
else
    echo "[Tier 1] Retrying Kotlin unit tests..." >&2
    if [[ "${OSTYPE:-}" == "msys" || "${OSTYPE:-}" == "win32" || "${OSTYPE:-}" == "cygwin" ]]; then
        $GRADLE_CMD --stop 2>/dev/null || true
        taskkill //F //IM java.exe 2>/dev/null || true
    else
        $GRADLE_CMD --stop 2>/dev/null || true
    fi
    sleep 5
    rm -rf "$REPO_ROOT/android/app/build/intermediates" 2>/dev/null || true
    mkdir -p "$REPO_ROOT/android/app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/single"
    mkdir -p "$REPO_ROOT/android/app/build/tmp/kotlin-classes/debug/META-INF"
    if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier1UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
        echo "[Tier 1] Kotlin unit tests: PASSED (on retry)"
        PASSED=$((PASSED + 1))
    else
        echo "[Tier 1] Kotlin unit tests: FAILED" >&2
        FAILED=$((FAILED + 1))
    fi
fi

if [[ "${OSTYPE:-}" == "msys" || "${OSTYPE:-}" == "win32" || "${OSTYPE:-}" == "cygwin" ]]; then
    $GRADLE_CMD --stop 2>/dev/null || true
    taskkill //F //IM java.exe 2>/dev/null || true
else
    $GRADLE_CMD --stop 2>/dev/null || true
fi

if [[ -d "$REPO_ROOT/android/app/build/test-results/testDebugUnitTest" ]]; then
    cp -r "$REPO_ROOT/android/app/build/test-results/testDebugUnitTest/"*.xml "$JUNIT_REPORT_DIR/" 2>/dev/null || true
fi
cd "$REPO_ROOT"

# 4. Device ADB Tier 1 scripts if serial given and not unit-only
if [[ "$UNIT_ONLY" == "false" && -n "$SERIAL" ]]; then
    TIER1_SCRIPTS=(
        "test-block-model.sh"
        "test-ansi-color.sh"
        "test-ime-input-pty.sh"
        "test-pty-resize.sh"
        "test-font-render.sh"
    )
    for script in "${TIER1_SCRIPTS[@]}"; do
        if [[ -f "$SCRIPT_DIR/$script" ]]; then
            echo "[Tier 1] Running device script $script on $SERIAL..."
            TOTAL=$((TOTAL + 1))
            if bash "$SCRIPT_DIR/$script" "$SERIAL"; then
                echo "[Tier 1] $script: PASSED"
                PASSED=$((PASSED + 1))
            else
                echo "[Tier 1] $script: FAILED" >&2
                FAILED=$((FAILED + 1))
            fi
        fi
    done
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
STATUS="PASSED"
if [[ $FAILED -gt 0 ]]; then
    STATUS="FAILED"
fi

cat > "$SUMMARY_JSON" <<EOF
{
  "tier": 1,
  "name": "Feature Coverage",
  "status": "$STATUS",
  "total": $TOTAL,
  "passed": $PASSED,
  "failed": $FAILED,
  "duration_seconds": $DURATION,
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

echo "Tier 1 complete: $PASSED/$TOTAL passed ($STATUS) in ${DURATION}s."
if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
