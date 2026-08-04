#!/usr/bin/env bash
# test-all-e2e.sh [--unit-only] [<device-serial>]
# Master E2E runner for warp-mobile-android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env-setup.sh"

cd "$REPO_ROOT"
ARTIFACT_DIR="$REPO_ROOT/.omc/e2e-artifacts"
MASTER_SUMMARY_JSON="$ARTIFACT_DIR/summary.json"

mkdir -p "$ARTIFACT_DIR"

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

TIER_ARGS=()
if [[ "$UNIT_ONLY" == "true" ]]; then
    TIER_ARGS+=("--unit-only")
fi
if [[ -n "$SERIAL" ]]; then
    TIER_ARGS+=("$SERIAL")
fi

echo "=================================================="
echo " Starting Master E2E Execution (unit_only=$UNIT_ONLY)"
echo "=================================================="

TIERS=("test-tier1.sh" "test-tier2.sh" "test-tier3.sh" "test-tier4.sh")
TIER_RESULTS=()

for tier_script in "${TIERS[@]}"; do
    echo ""
    echo "--- Executing $tier_script ---"
    TOTAL=$((TOTAL + 1))
    if bash "$SCRIPT_DIR/$tier_script" "${TIER_ARGS[@]}"; then
        echo "--- $tier_script: PASSED ---"
        PASSED=$((PASSED + 1))
        TIER_RESULTS+=("$tier_script=PASSED")
    else
        echo "--- $tier_script: FAILED ---" >&2
        FAILED=$((FAILED + 1))
        TIER_RESULTS+=("$tier_script=FAILED")
    fi
    if [[ "${OSTYPE:-}" == "msys" || "${OSTYPE:-}" == "win32" || "${OSTYPE:-}" == "cygwin" ]]; then
        if [[ -d "$REPO_ROOT/android" ]]; then
            (cd "$REPO_ROOT/android" && ./gradlew.bat --stop 2>/dev/null || true)
        fi
        taskkill //F //IM java.exe 2>/dev/null || true
    else
        if [[ -d "$REPO_ROOT/android" ]]; then
            (cd "$REPO_ROOT/android" && ./gradlew --stop 2>/dev/null || true)
        fi
    fi
    sleep 5
done

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
OVERALL_STATUS="PASSED"
if [[ $FAILED -gt 0 ]]; then
    OVERALL_STATUS="FAILED"
fi

JOINED_RESULTS=""
for item in "${TIER_RESULTS[@]}"; do
    tier_name="${item%%=*}"
    tier_status="${item#*=}"
    if [[ -n "$JOINED_RESULTS" ]]; then
        JOINED_RESULTS="${JOINED_RESULTS}, "
    fi
    JOINED_RESULTS="${JOINED_RESULTS}\"${tier_name}\": \"${tier_status}\""
done

TIMESTAMP="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
PY_CMD="python"
if python3 --version >/dev/null 2>&1; then
    PY_CMD="python3"
elif python --version >/dev/null 2>&1; then
    PY_CMD="python"
fi
$PY_CMD "tools/scripts/write-summary.py" "$OVERALL_STATUS" "$UNIT_ONLY" "$TOTAL" "$PASSED" "$FAILED" "$DURATION" "$TIMESTAMP" "${TIER_RESULTS[@]}" || cat > ".omc/e2e-artifacts/summary.json" <<EOF
{
  "suite": "Master E2E Test Suite",
  "overall_status": "$OVERALL_STATUS",
  "unit_only": $UNIT_ONLY,
  "total_tiers": $TOTAL,
  "passed_tiers": $PASSED,
  "failed_tiers": $FAILED,
  "duration_seconds": $DURATION,
  "timestamp": "$TIMESTAMP",
  "tier_details": { $JOINED_RESULTS }
}
EOF

echo ""
echo "=================================================="
echo " Master E2E Summary"
echo " Overall Status : $OVERALL_STATUS"
echo " Tiers Passed   : $PASSED / $TOTAL"
echo " Duration       : ${DURATION}s"
echo " Summary JSON   : $MASTER_SUMMARY_JSON"
echo "=================================================="

if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
