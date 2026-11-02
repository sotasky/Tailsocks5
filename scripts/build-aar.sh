#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

command -v go >/dev/null 2>&1 || {
  echo "ERROR: go not found. Run 'make doctor'." >&2
  exit 1
}
[ -n "${ANDROID_NDK_HOME:-}" ] || {
  echo "ERROR: ANDROID_NDK_HOME is not set. Run 'make doctor'." >&2
  exit 1
}

command -v gomobile >/dev/null 2>&1 || {
  echo "ERROR: gomobile not found. Run 'make tools' first." >&2
  exit 1
}
command -v gobind >/dev/null 2>&1 || {
  echo "ERROR: gobind not found. Run 'make tools' first." >&2
  exit 1
}

cd "$REPO_ROOT"
mkdir -p "$BUILD_DIR/tmp" "$(dirname "$AAR_PATH")"
BACKUP=$(mktemp -d "$BUILD_DIR/tmp/go-mod.XXXXXX")
LOG="$LOG_DIR/aar-build.log"

restore_gomod() {
  cp "$BACKUP/go.mod" "$REPO_ROOT/go.mod"
  cp "$BACKUP/go.sum" "$REPO_ROOT/go.sum"
  rm -rf "$BACKUP"
}
trap restore_gomod EXIT

cp "$REPO_ROOT/go.mod" "$REPO_ROOT/go.sum" "$BACKUP/"

echo "== Preparing patched Tailscale source =="
go mod download tailscale.com
TS_ORIG=$(go list -m -f '{{.Dir}}' tailscale.com)
if [ -z "$TS_ORIG" ] || [ ! -d "$TS_ORIG" ]; then
  echo "ERROR: cannot locate the tailscale.com module source" >&2
  exit 1
fi
echo "original: $TS_ORIG"
rm -rf "$TS_PATCH_DIR"
cp -r "$TS_ORIG" "$TS_PATCH_DIR"
chmod -R u+w "$TS_PATCH_DIR"
"$REPO_ROOT/android-patch/patch.sh" "$TS_PATCH_DIR"
echo "patched: $TS_PATCH_DIR"

echo
echo "== Adding gomobile bind dependency =="
go get golang.org/x/mobile/bind@latest
go mod edit -replace "tailscale.com=$TS_PATCH_DIR"

echo
echo "== Building AAR =="
START=$(date +%s)
if ! gomobile bind \
  -ldflags="-checklinkname=0 -s -w" \
  -target=android \
  -androidapi=26 \
  -o "$AAR_PATH" \
  ./mobile/ > "$LOG" 2>&1; then
  echo "ERROR: gomobile bind failed"
  tail -n 60 "$LOG"
  exit 1
fi
END=$(date +%s)
echo "AAR build OK in $((END-START))s"
tail -n 20 "$LOG"

echo
echo "== AAR contents =="
ls -lh "$AAR_PATH"
unzip -l "$AAR_PATH" | grep -E 'classes\.jar|jni/.+\.so' | head -20
