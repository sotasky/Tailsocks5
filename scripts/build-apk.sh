#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

VARIANT="${1:-debug}"
case "$VARIANT" in
  debug) TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 1
    ;;
esac

APP_DIR="$REPO_ROOT/android-app"
LOG="$LOG_DIR/apk-build.log"

if [ ! -f "$AAR_PATH" ]; then
  echo "ERROR: AAR not found: $AAR_PATH" >&2
  echo "Run 'make aar' first." >&2
  exit 1
fi

cd "$APP_DIR"

echo "== Java =="
java -version 2>&1 | head -2

if [ ! -f "$APP_DIR/gradlew" ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "== Generating Gradle wrapper 8.5 =="
    gradle wrapper --gradle-version 8.5
  else
    echo "ERROR: android-app/gradlew is missing and gradle is not on PATH." >&2
    echo "Commit the Gradle wrapper or install Gradle 8.5." >&2
    exit 1
  fi
fi
chmod +x "$APP_DIR/gradlew" 2>/dev/null || true

echo "== Building $VARIANT APK =="
START=$(date +%s)
if ! bash "$APP_DIR/gradlew" -p "$APP_DIR" "$TASK" --no-daemon > "$LOG" 2>&1; then
  echo "ERROR: $TASK failed"
  tail -n 80 "$LOG"
  exit 1
fi
END=$(date +%s)
echo "APK build OK in $((END-START))s"
tail -n 20 "$LOG"

echo
echo "== APK output =="
find "$APP_DIR/app/build/outputs/apk/$VARIANT" -name '*.apk' -exec ls -lh {} \;
