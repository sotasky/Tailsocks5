#!/usr/bin/env bash
# Copy this file to scripts/env.local.sh and adjust the values for your machine.
# scripts/env.local.sh is gitignored.

export JAVA_HOME="$HOME/.local/share/jdks/java-17-openjdk"
# Optional: only needed to regenerate android-app/gradlew.
export GRADLE_HOME="$HOME/.local/share/gradle-8.5"

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

export GOROOT="$HOME/.gvm/gos/go1.26.6"
export GOPATH="$HOME/.gvm/pkgsets/go1.26.6/global"
export GOBIN="$HOME/.local/bin"
export CGO_ENABLED=1

export GOPROXY="https://proxy.golang.org,direct"

# Optional: set these if your network requires a proxy.
# export HTTP_PROXY="http://127.0.0.1:17890"
# export HTTPS_PROXY="http://127.0.0.1:17890"
# export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=17890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=17890 -Dhttp.nonProxyHosts=localhost|127.0.0.1"
