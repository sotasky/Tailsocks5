#!/usr/bin/env bash
# Shared build environment. Sources the gitignored scripts/env.local.sh.
# Usage: source scripts/env.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Local machines can use scripts/env.local.sh. CI supplies the same values
# through environment variables instead.
if [ -f "$SCRIPT_DIR/env.local.sh" ]; then
  # shellcheck source=env.local.sh
  source "$SCRIPT_DIR/env.local.sh"
fi

: "${JAVA_HOME:?JAVA_HOME is not set (see scripts/env.local.sh)}"
: "${ANDROID_HOME:?ANDROID_HOME is not set (see scripts/env.local.sh)}"

# Go is only needed for AAR/CLI targets; the APK-only CI job can omit it.
if [ -z "${GOROOT:-}" ] && command -v go >/dev/null 2>&1; then
  GOROOT="$(go env GOROOT)"
fi
if [ -z "${GOPATH:-}" ] && command -v go >/dev/null 2>&1; then
  GOPATH="$(go env GOPATH)"
fi
export GOROOT GOPATH

export REPO_ROOT SCRIPT_DIR
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}"
fi
export GOBIN="${GOBIN:-$HOME/.local/bin}"
export CGO_ENABLED="${CGO_ENABLED:-1}"

export PATH="$JAVA_HOME/bin:$GOBIN:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
if [ -n "${GOROOT:-}" ]; then
  export PATH="$GOROOT/bin:$PATH"
fi
if [ -n "${GOPATH:-}" ]; then
  export PATH="$GOPATH/bin:$PATH"
fi
# GRADLE_HOME is optional when the committed wrapper is used.
if [ -n "${GRADLE_HOME:-}" ]; then
  export PATH="$GRADLE_HOME/bin:$PATH"
fi

export BUILD_DIR="$REPO_ROOT/.build"
export LOG_DIR="$BUILD_DIR/logs"
export TS_PATCH_DIR="$BUILD_DIR/tailscale-patched"
export AAR_PATH="$REPO_ROOT/android-app/app/libs/tsproxy.aar"

mkdir -p "$BUILD_DIR" "$LOG_DIR" "$GOBIN"
