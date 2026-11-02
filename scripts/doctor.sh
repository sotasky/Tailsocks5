#!/usr/bin/env bash
# Check the local prerequisites for building this repo.
set +e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FAIL=0

pass() { printf '  OK    %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; FAIL=1; }

echo "== ts-proxy build doctor =="

if [ -f "$SCRIPT_DIR/env.local.sh" ]; then
  # shellcheck source=env.local.sh
  source "$SCRIPT_DIR/env.local.sh"
  pass "scripts/env.local.sh found"
else
  fail "scripts/env.local.sh missing; run: cp scripts/env.example.sh scripts/env.local.sh"
fi

[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "${GRADLE_HOME:-}" ] && export PATH="$GRADLE_HOME/bin:$PATH"
[ -n "${GOROOT:-}" ] && export PATH="$GOROOT/bin:$PATH"
[ -n "${GOPATH:-}" ] && export PATH="$GOPATH/bin:$PATH"
export PATH="${GOBIN:-$HOME/.local/bin}:$PATH"
[ -n "${ANDROID_HOME:-}" ] && export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo
echo "-- Java --"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_MAJOR=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
  if [ "$JAVA_MAJOR" = "17" ]; then
    pass "JDK 17 at $JAVA_HOME"
  else
    fail "JDK 17 required, found major '$JAVA_MAJOR' at $JAVA_HOME"
  fi
else
  fail "JAVA_HOME/bin/java not found"
fi

echo
echo "-- Android SDK --"
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  pass "ANDROID_HOME=$ANDROID_HOME"
  for p in "platforms/android-34" "build-tools/34.0.0" "ndk/26.1.10909125"; do
    if [ -d "$ANDROID_HOME/$p" ]; then
      pass "$p"
    else
      fail "$p missing under ANDROID_HOME"
    fi
  done
else
  fail "ANDROID_HOME is not set or does not exist"
fi

echo
echo "-- Go --"
if command -v go >/dev/null 2>&1; then
  GO_VERSION=$(go env GOVERSION 2>/dev/null)
  if printf '%s\n%s\n' "go1.26.5" "$GO_VERSION" | sort -V -C 2>/dev/null; then
    pass "$GO_VERSION"
  else
    fail "Go 1.26.5+ required, found ${GO_VERSION:-unknown}"
  fi
else
  fail "go not found on PATH"
fi

echo
echo "-- gomobile --"
for c in gomobile gobind; do
  if command -v "$c" >/dev/null 2>&1; then
    pass "$c"
  else
    fail "$c missing; run: make tools"
  fi
done

echo
echo "-- Gradle --"
if [ -f "$REPO_ROOT/android-app/gradlew" ]; then
  pass "android-app/gradlew present"
elif command -v gradle >/dev/null 2>&1; then
  warn "gradlew missing; make apk will generate it with the installed gradle"
else
  fail "gradlew missing and gradle not on PATH; commit the wrapper or install Gradle 8.5"
fi

echo
echo "-- Build tools --"
for c in make patch unzip; do
  if command -v "$c" >/dev/null 2>&1; then
    pass "$c"
  else
    fail "$c not found"
  fi
done

echo
echo "-- Optional --"
if command -v adb >/dev/null 2>&1; then
  pass "adb"
else
  warn "adb not found (only needed to install/test the APK)"
fi
if [ -f "$REPO_ROOT/android-app/app/libs/tsproxy.aar" ]; then
  pass "AAR present"
else
  warn "AAR missing; run: make aar"
fi

echo
if [ "$FAIL" -ne 0 ]; then
  echo "Doctor found problems."
  exit 1
fi
echo "All required checks passed."
