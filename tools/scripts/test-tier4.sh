#!/usr/bin/env bash
# test-tier4.sh [--unit-only] [<device-serial>]
# Tier 4 Real-World Workload test runner for warp-mobile-android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env-setup.sh"

cd "$REPO_ROOT"
ARTIFACT_DIR="$REPO_ROOT/.omc/e2e-artifacts"
SUMMARY_JSON="$ARTIFACT_DIR/tier4-summary.json"
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
echo " Running Tier 4: Real-World Workload Tests"
echo "=========================================="

echo "[Tier 4] Running workload unit verification..."
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

if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier4UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
    echo "[Tier 4] Kotlin workload unit tests: PASSED"
    PASSED=$((PASSED + 1))
else
    echo "[Tier 4] Retrying Kotlin workload unit tests..." >&2
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
    if $GRADLE_CMD testDebugUnitTest --tests "dev.warp.mobile.test.Tier4UnitTest" --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --rerun-tasks; then
        echo "[Tier 4] Kotlin workload unit tests: PASSED (on retry)"
        PASSED=$((PASSED + 1))
    else
        echo "[Tier 4] Kotlin workload unit tests: FAILED" >&2
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

# Device ADB Tier 4 scripts if serial given and not unit-only
if [[ "$UNIT_ONLY" == "false" && -n "$SERIAL" ]]; then
    TIER4_SCRIPTS=(
        "test-30min-idle-stress.sh"
        "test-frame-capture-stress.sh"
        "test-rotation-stress.sh"
    )
    for script in "${TIER4_SCRIPTS[@]}"; do
        if [[ -f "$SCRIPT_DIR/$script" ]]; then
            echo "[Tier 4] Running device script $script on $SERIAL..."
            TOTAL=$((TOTAL + 1))
            if bash "$SCRIPT_DIR/$script" "$SERIAL"; then
                echo "[Tier 4] $script: PASSED"
                PASSED=$((PASSED + 1))
            else
                echo "[Tier 4] $script: FAILED" >&2
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

cd "$REPO_ROOT"
ISO_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date +%Y-%m-%dT%H:%M:%SZ)"
PY_CMD="python"
if command -v python3 >/dev/null 2>&1; then PY_CMD="python3"; fi
$PY_CMD "tools/scripts/write-tier-summary.py" 4 "Real-World Workload" "$STATUS" $TOTAL $PASSED $FAILED $DURATION "$ISO_TS" || cat > "$SUMMARY_JSON" <<EOF
{
  "tier": 4,
  "name": "Real-World Workload",
  "status": "$STATUS",
  "total": $TOTAL,
  "passed": $PASSED,
  "failed": $FAILED,
  "duration_seconds": $DURATION,
  "timestamp": "$ISO_TS"
}
EOF

echo "Tier 4 complete: $PASSED/$TOTAL passed ($STATUS) in ${DURATION}s."
if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
