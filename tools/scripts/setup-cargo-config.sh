#!/usr/bin/env bash
# Generate .cargo/config.toml from .cargo/config.toml.template
# Reads ANDROID_NDK_ROOT from environment (load via `direnv allow` or `source .envrc`).
set -euo pipefail

cd "$(dirname "$0")/../.."

bash tools/scripts/setup-companion-sources.sh

if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
  echo "ERROR: ANDROID_NDK_ROOT not set. Run 'direnv allow' or 'source .envrc' first." >&2
  exit 1
fi

if [ ! -d "$ANDROID_NDK_ROOT" ]; then
  echo "ERROR: ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT does not exist." >&2
  exit 1
fi

# Detect host tag for NDK prebuilt path
case "$(uname -s)-$(uname -m)" in
  Darwin-x86_64)  HOST_TAG="darwin-x86_64" ;;
  Darwin-arm64)   HOST_TAG="darwin-x86_64" ;;  # NDK ships only x86_64 prebuilt; runs via Rosetta on Apple Silicon
  Linux-x86_64)   HOST_TAG="linux-x86_64" ;;
  *)              echo "ERROR: unsupported host $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

if [ ! -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG" ]; then
  echo "ERROR: NDK toolchain not found at $ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG" >&2
  exit 1
fi

# Render the template
sed \
  -e "s|\${ANDROID_NDK_ROOT}|$ANDROID_NDK_ROOT|g" \
  -e "s|\${HOST_TAG}|$HOST_TAG|g" \
  .cargo/config.toml.template \
  > .cargo/config.toml

echo "Generated .cargo/config.toml with HOST_TAG=$HOST_TAG"
echo "Linker: $(grep '^linker' .cargo/config.toml)"
