#!/usr/bin/env bash
set -e

echo "Starting Security Audit..."

# 1. Check for cargo audit if available
if command -v cargo >/dev/null 2>&1; then
    if cargo audit --version >/dev/null 2>&1; then
        echo "Running cargo audit..."
        cargo audit
    else
        echo "cargo-audit not installed. Skipping rust crate vulnerability check."
        echo "Hint: run 'cargo install cargo-audit' to enable."
    fi
else
    echo "cargo not found. Skipping rust checks."
fi

# 2. Validate AndroidManifest exported components
MANIFEST="android/app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    echo "Checking AndroidManifest.xml for exported components..."
    
    EXPORTED=$(grep -o 'android:name="\.[^"]*".*android:exported="true"' "$MANIFEST" || true)
    if [ -n "$EXPORTED" ]; then
        echo "Exported components found:"
        echo "$EXPORTED"
    else
        echo "No explicitly exported components found in single line check."
    fi
else
    echo "AndroidManifest.xml not found at $MANIFEST"
    exit 1
fi

echo "Security audit completed successfully."
