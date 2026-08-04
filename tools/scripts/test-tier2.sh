#!/usr/bin/env bash
# test-tier2.sh [--unit-only] [<device-serial>]
# Tier 2 Boundary / Edge Case test runner for warp-mobile-android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env-setup.sh"

cd "$REPO_ROOT"
ARTIFACT_DIR="$REPO_ROOT/.omc/e2e-artifacts"
SUMMARY_JSON="$ARTIFACT_DIR/tier2-summary.json"
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
echo " Running Tier 2: Boundary/Edge Case Tests"
echo "=========================================="

# Tier 2 Boundary unit assertions
echo "[Tier 2] Verifying boundary condition fixtures & assertion helpers..."
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

if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier2UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
    echo "[Tier 2] Kotlin boundary unit tests: PASSED"
    PASSED=$((PASSED + 1))
else
    echo "[Tier 2] Retrying Kotlin boundary unit tests..." >&2
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
    if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier2UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
        echo "[Tier 2] Kotlin boundary unit tests: PASSED (on retry)"
        PASSED=$((PASSED + 1))
    else
        echo "[Tier 2] Kotlin boundary unit tests: FAILED" >&2
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

# Device ADB Tier 2 scripts if serial given and not unit-only
if [[ "$UNIT_ONLY" == "false" && -n "$SERIAL" ]]; then
    TIER2_SCRIPTS=(
        "test-pty-reattach.sh"
        "test-fgs-clean-kill.sh"
        "test-zsh-asset.sh"
        "test-pkg-install.sh"
        "test-bootstrap-install.sh"
    )
    for script in "${TIER2_SCRIPTS[@]}"; do
        if [[ -f "$SCRIPT_DIR/$script" ]]; then
            echo "[Tier 2] Running device script $script on $SERIAL..."
            TOTAL=$((TOTAL + 1))
            if bash "$SCRIPT_DIR/$script" "$SERIAL"; then
                echo "[Tier 2] $script: PASSED"
                PASSED=$((PASSED + 1))
            else
                echo "[Tier 2] $script: FAILED" >&2
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
  "tier": 2,
  "name": "Boundary/Edge Case",
  "status": "$STATUS",
  "total": $TOTAL,
  "passed": $PASSED,
  "failed": $FAILED,
  "duration_seconds": $DURATION,
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

echo "Tier 2 complete: $PASSED/$TOTAL passed ($STATUS) in ${DURATION}s."
if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
