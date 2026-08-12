#!/usr/bin/env bash
# submit-fdroid-mr.sh — prepare / push an fdroiddata MR for Warp Mobile.
#
# Requires a GitLab account that can fork fdroid/fdroiddata and open MRs.
# Auth: set GITLAB_TOKEN (api scope) or run `glab auth login` first.
#
# Usage:
#   ./tools/scripts/submit-fdroid-mr.sh           # dry-run: validate local recipe
#   ./tools/scripts/submit-fdroid-mr.sh --push    # fork (if needed), push branch, open MR

set -euo pipefail

PUSH=false
for arg in "$@"; do
  case "$arg" in
    --push) PUSH=true ;;
    *) echo "Usage: $0 [--push]" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
META_SRC="$REPO_ROOT/metadata/dev.warp.mobile.yml"
WORK="${TMPDIR:-/tmp}/fdroiddata-warp-mobile-$$"

if [[ ! -f "$META_SRC" ]]; then
  echo "ERROR: missing $META_SRC" >&2
  exit 1
fi

echo "==> Using recipe: $META_SRC"
echo "    CurrentVersion: $(grep '^CurrentVersion:' "$META_SRC" | awk '{print $2}')"

if [[ "$PUSH" != "true" ]]; then
  echo "==> dry-run only. Re-run with --push after GITLAB_TOKEN / glab auth is available."
  exit 0
fi

if ! command -v glab >/dev/null 2>&1 && [[ -z "${GITLAB_TOKEN:-}" ]]; then
  echo "ERROR: need glab CLI or GITLAB_TOKEN for --push" >&2
  exit 1
fi

git clone --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$WORK"
cp "$META_SRC" "$WORK/metadata/dev.warp.mobile.yml"
cd "$WORK"
git checkout -b add-warp-mobile
git add metadata/dev.warp.mobile.yml
git -c user.email="${GIT_AUTHOR_EMAIL:-release@warp.mobile}" \
    -c user.name="${GIT_AUTHOR_NAME:-Warp Mobile Release}" \
    commit -m "New app: Warp Mobile (dev.warp.mobile)"

if command -v glab >/dev/null 2>&1; then
  glab repo fork --remote=true || true
  git push -u origin add-warp-mobile
  glab mr create --fill --yes \
    --title "New app: Warp Mobile (dev.warp.mobile)" \
    --description "Adds AGPL-3.0 Warp Terminal Android port with Termux runtime. Recipe lives upstream at https://github.com/ImL1s/warp-mobile-android/blob/main/metadata/dev.warp.mobile.yml"
else
  echo "Push the branch add-warp-mobile from $WORK to your fdroiddata fork, then open an MR."
fi
