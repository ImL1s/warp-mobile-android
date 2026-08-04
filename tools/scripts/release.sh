#!/usr/bin/env bash
# release.sh — v1-release packaging script for warp-mobile-android.
#
# Composes the full release artifact set for a given version:
#   - signed (or unsigned) release APK
#   - bootstrap-aarch64.zip with sha256
#   - aggregate SHA256SUMS file covering both
#   - optional gh release create + upload
#
# Solo-dev usage:
#   ./tools/scripts/release.sh 1.0.0
#   ./tools/scripts/release.sh 1.0.0 --upload    # also push to GitHub Releases
#   ./tools/scripts/release.sh 1.0.0 --dry-run   # build artifacts but skip everything else
#
# Prereqs:
#   - cargo + cargo-ndk on PATH
#   - $ANDROID_HOME or $ANDROID_NDK_ROOT
#   - For signed APK: android/keystore.properties populated with credentials
#     (see android/app/build.gradle signingConfig comments). Without it, the
#     APK will be unsigned and named app-release-unsigned.apk — F-Droid path.
#   - For --upload: gh CLI authenticated (gh auth status)
#   - For deterministic bootstrap: tools/scripts/m4-bootstrap-snapshot.sha256
#     pinned to a specific upstream Termux apt revision (see M4-S08).
#
# Output:
#   $REPO/dist/<version>/
#     warp-mobile-<version>.apk        (renamed from app-release[-unsigned].apk)
#     bootstrap-aarch64-<version>.zip
#     SHA256SUMS                       (covers all artifacts; reproducibility check)
#     RELEASE_NOTES.md                 (auto-generated from git log + changelog)
#
# Refs:
#   .omc/m4-artifacts/M4-go-no-go.md §F-Droid — F-Droid signs from source itself
#   .omc/m6-artifacts/M6-go-no-go.md §9 — version scheme
#   metadata/dev.warp.mobile.yml — F-Droid recipe (always builds unsigned)

set -euo pipefail

VERSION="${1:?Usage: $0 <version> [--upload] [--dry-run]
e.g. $0 1.0.0 --upload}"
shift || true

UPLOAD=false
DRY_RUN=false
for arg in "$@"; do
    case "$arg" in
        --upload)  UPLOAD=true ;;
        --dry-run) DRY_RUN=true ;;
        *)         echo "ERROR: unknown arg: $arg" >&2; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$REPO_ROOT/dist/$VERSION"
TAG="v$VERSION"

echo "==> warp-mobile-android release packaging" >&2
echo "    version: $VERSION" >&2
echo "    tag:     $TAG" >&2
echo "    dist:    $DIST_DIR" >&2
echo "    upload:  $UPLOAD" >&2
echo "    dry-run: $DRY_RUN" >&2

# Sanity: confirm we're at a clean tree (release artifacts should match
# committed state). Allow override via env for emergency local testing.
if [[ "${ALLOW_DIRTY_TREE:-0}" != "1" ]]; then
    if ! git -C "$REPO_ROOT" diff --quiet HEAD; then
        echo "ERROR: working tree has uncommitted changes." >&2
        echo "       Commit + tag first, OR set ALLOW_DIRTY_TREE=1 to override." >&2
        exit 1
    fi
fi

# Sanity: confirm the version in build.gradle matches the requested version.
GRADLE_VERSION=$(grep '^[[:space:]]*versionName' "$REPO_ROOT/android/app/build.gradle" | head -1 | sed -E 's/.*"(.+)".*/\1/')
if [[ "$GRADLE_VERSION" != "$VERSION" ]]; then
    echo "ERROR: version mismatch." >&2
    echo "  Requested: $VERSION" >&2
    echo "  build.gradle versionName: $GRADLE_VERSION" >&2
    echo "  Update android/app/build.gradle then retry." >&2
    exit 1
fi

mkdir -p "$DIST_DIR"

# ── Step 1: build bootstrap zip ───────────────────────────────────────────
echo "==> [1/4] building bootstrap-aarch64.zip" >&2
BOOTSTRAP_OUT="$REPO_ROOT/_bootstrap-out"
rm -rf "$BOOTSTRAP_OUT"
"$SCRIPT_DIR/build-bootstrap.sh" aarch64 \
    "$SCRIPT_DIR/m4-bootstrap-packages.txt" \
    "$BOOTSTRAP_OUT" >&2

BOOTSTRAP_SRC="$BOOTSTRAP_OUT/bootstrap-aarch64.zip"
BOOTSTRAP_DST="$DIST_DIR/bootstrap-aarch64-$VERSION.zip"
cp "$BOOTSTRAP_SRC" "$BOOTSTRAP_DST"
echo "    -> $BOOTSTRAP_DST" >&2

# ── Step 2: build release APK ─────────────────────────────────────────────
echo "==> [2/4] building release APK" >&2
(cd "$REPO_ROOT/android" && ./gradlew :app:assembleRelease -q) >&2

# Find the APK — its name depends on whether keystore.properties was populated.
APK_DIR="$REPO_ROOT/android/app/build/outputs/apk/release"
APK_SRC=""
if [[ -f "$APK_DIR/app-release.apk" ]]; then
    APK_SRC="$APK_DIR/app-release.apk"
    SIGN_STATUS="signed"
elif [[ -f "$APK_DIR/app-release-unsigned.apk" ]]; then
    APK_SRC="$APK_DIR/app-release-unsigned.apk"
    SIGN_STATUS="unsigned (F-Droid path)"
else
    echo "ERROR: no APK found at $APK_DIR" >&2
    ls -la "$APK_DIR" >&2 || true
    exit 1
fi
APK_DST="$DIST_DIR/warp-mobile-$VERSION.apk"
cp "$APK_SRC" "$APK_DST"
APK_SIZE=$(du -h "$APK_DST" | cut -f1)
echo "    -> $APK_DST ($SIGN_STATUS, $APK_SIZE)" >&2

# ── Step 3: aggregate SHA256SUMS + release notes ──────────────────────────
echo "==> [3/4] generating SHA256SUMS + RELEASE_NOTES.md" >&2
(cd "$DIST_DIR" && shasum -a 256 \
    "warp-mobile-$VERSION.apk" \
    "bootstrap-aarch64-$VERSION.zip" \
    > SHA256SUMS)

# Extract the matching changelog entry (e.g. fastlane/.../changelogs/100.txt
# for version 1.0.0 — versionCode is the file name).
GRADLE_VERCODE=$(grep '^[[:space:]]*versionCode' "$REPO_ROOT/android/app/build.gradle" | head -1 | awk '{print $2}')
CHANGELOG_FILE="$REPO_ROOT/fastlane/metadata/android/en-US/changelogs/$GRADLE_VERCODE.txt"
if [[ -f "$CHANGELOG_FILE" ]]; then
    CHANGELOG_BODY=$(cat "$CHANGELOG_FILE")
else
    CHANGELOG_BODY="(no changelog file at $CHANGELOG_FILE)"
fi

LAST_TAG=$(git -C "$REPO_ROOT" describe --tags --abbrev=0 2>/dev/null || echo "")
if [[ -n "$LAST_TAG" && "$LAST_TAG" != "$TAG" ]]; then
    GIT_LOG=$(git -C "$REPO_ROOT" log "$LAST_TAG..HEAD" --oneline | head -50)
    GIT_RANGE_NOTE="Commits since \`$LAST_TAG\`:"
else
    GIT_LOG=$(git -C "$REPO_ROOT" log --oneline | head -50)
    GIT_RANGE_NOTE="Latest 50 commits:"
fi

cat > "$DIST_DIR/RELEASE_NOTES.md" <<RN
# Warp Mobile Android $VERSION

$CHANGELOG_BODY

## Artifacts

- \`warp-mobile-$VERSION.apk\` — Android release APK ($SIGN_STATUS, $APK_SIZE)
- \`bootstrap-aarch64-$VERSION.zip\` — Termux runtime bundle (zsh + GNU coreutils + APT)
- \`SHA256SUMS\` — checksums for both artifacts above

Verify a download with:
\`\`\`
shasum -a 256 -c SHA256SUMS
\`\`\`

## Install

Download the APK + sideload (Settings → Apps → Special access → Install unknown apps).
Tested device class: Galaxy S24 Ultra / Pixel 7+ (Snapdragon 8 Gen 1+ / Adreno 730+, API 33+).
Minimum supported: API 31 + Adreno 6xx (per Plan Amendment 3).

## $GIT_RANGE_NOTE

\`\`\`
$GIT_LOG
\`\`\`

## Source

Built from \`$(git -C "$REPO_ROOT" rev-parse HEAD)\` on \`$(date -u +%Y-%m-%dT%H:%M:%SZ)\`.
F-Droid recipe: [\`metadata/dev.warp.mobile.yml\`](../../metadata/dev.warp.mobile.yml)
RN

echo "    -> $DIST_DIR/SHA256SUMS" >&2
echo "    -> $DIST_DIR/RELEASE_NOTES.md" >&2

# ── Step 4: upload (or summarize) ─────────────────────────────────────────
echo "==> [4/4] gh release" >&2
if $DRY_RUN; then
    echo "    DRY-RUN: skipping gh release create + upload" >&2
    echo "" >&2
    echo "    Artifacts ready at $DIST_DIR/" >&2
    ls -lh "$DIST_DIR/" >&2
elif $UPLOAD; then
    if ! command -v gh >/dev/null 2>&1; then
        echo "ERROR: gh CLI not found on PATH; install from https://cli.github.com/" >&2
        exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo "ERROR: gh CLI not authenticated. Run: gh auth login" >&2
        exit 1
    fi
    if ! git -C "$REPO_ROOT" rev-parse "$TAG" >/dev/null 2>&1; then
        echo "ERROR: git tag $TAG does not exist locally. Create it with:" >&2
        echo "       git tag -a $TAG -m \"$VERSION\"" >&2
        echo "       git push origin $TAG" >&2
        exit 1
    fi
    if ! git -C "$REPO_ROOT" ls-remote --tags origin "$TAG" | grep -q "$TAG"; then
        echo "ERROR: tag $TAG not on origin. Push it first: git push origin $TAG" >&2
        exit 1
    fi
    (cd "$REPO_ROOT" && gh release create "$TAG" \
        --title "Warp Mobile $VERSION" \
        --notes-file "$DIST_DIR/RELEASE_NOTES.md" \
        "$DIST_DIR/warp-mobile-$VERSION.apk" \
        "$DIST_DIR/bootstrap-aarch64-$VERSION.zip" \
        "$DIST_DIR/SHA256SUMS")
    echo "    -> https://github.com/ImL1s/warp-mobile-android/releases/tag/$TAG" >&2
else
    echo "    Artifacts ready (no upload requested)" >&2
    ls -lh "$DIST_DIR/" >&2
    echo "" >&2
    echo "    To publish: $0 $VERSION --upload" >&2
fi

echo "==> done." >&2
