# AGENTS.md

Android app that runs Tailscale in userspace and exposes a local SOCKS5 proxy (default `127.0.0.1:1080`) so Clash/Mihomo can keep the single Android VPN slot. Go core + Kotlin/Compose UI.

## Layout / entrypoints

- `main.go` — standalone CLI (`ts-proxy`), flag-driven TCP/UDP/SOCKS forwarding. Not used by the Android app.
- `tsproxy/` — shared Go library (SOCKS5 serve/forward, TCP/UDP forwarders, delayed UDP binding).
- `mobile/` — the gomobile-bound package. Despite the folder name, its Go package is `tsproxy`, so Kotlin calls `tsproxy.Tsproxy.start(...)` / `.stop()` / `.getLogs()`.
- `android-app/` — app `com.tsproxy.android`: service in `service/TsProxyService.kt`, Compose UI/state in `ui/MainActivity.kt` + `ui/MainViewModel.kt`, settings in DataStore (`tsproxy_prefs`).
- `android-patch/` — patches applied to the downloaded `tailscale.com` module source during builds.
- `scripts/` — committed build helpers. `env.example.sh` is the template; `env.local.sh` is gitignored. `build-aar.sh` and `build-apk.sh` are called by `make`.
- `.build/` — gitignored local build workspace: patched Tailscale copy, logs, temporary `go.mod` backups.
- `Makefile` — entrypoint for CLI/AAR/APK builds; `make help` lists targets.
- `CLAUDE.md` — one-line pointer to `AGENTS.md` for Claude-based agents; keep the canonical guidance in `AGENTS.md`.
- `.github/workflows/android.yml` — only CI. On `v*` tags or manual dispatch it runs `make tools`/`make aar`, then `make apk`/`make release` (no build on ordinary pushes/PRs); tags publish a GitHub release.

## Commands

Run from the repo root. On a new machine, copy the env template and edit the local paths; `scripts/env.local.sh` is gitignored:

```bash
cp scripts/env.example.sh scripts/env.local.sh
source scripts/env.sh
make help
```

Targets:

- `make tools` — install `gomobile`/`gobind` into `GOBIN` and run `gomobile init`
- `make doctor` — check JDK 17, Android SDK/NDK, Go, gomobile, and wrapper
- `make aar` — Go/Tailscale -> `android-app/app/libs/tsproxy.aar` (always a full rebuild)
- `make apk` — debug APK (requires an existing AAR; run `make aar` after Go changes)
- `make release` — release APK
- `make all` — AAR + debug APK
- `make cli` — build the root `ts-proxy` CLI (plain `make` now shows help)
- `make check` — `go build ./...` + `go vet ./...`
- `make test` — `go test ./...` (there are no test files)
- `make env` — print resolved toolchain paths and versions
- `make clean` — remove `.build/`, Gradle outputs, the AAR, and the CLI binary

Under the hood, `make aar` copies and patches Tailscale into `.build/tailscale-patched`, temporarily adds `golang.org/x/mobile/bind`, runs `gomobile bind`, and restores `go.mod`/`go.sum`. `make apk` generates the Gradle wrapper if missing and runs `assembleDebug`.

Toolchain: Go 1.27.3+ (pinned in `go.mod`/CI), Java 17, Gradle 8.5 (provided by the committed wrapper), AGP 8.2.2, Kotlin 1.9.22 with Compose compiler 1.5.8, NDK 26.1.10909125, compileSdk 34 / minSdk 26.

There are no tests in this repo; `go test ./...` only compiles packages. Do not invent test commands.

## Local build environment

Machine-specific paths, JDK/SDK/NDK locations, proxy settings, and Go version manager paths belong in `scripts/env.local.sh` (gitignored). Start from `scripts/env.example.sh`. `AGENTS.local.md` (also gitignored) holds machine-specific agent notes; if it exists, read it before building. Keep this file portable so every clone gets the same project guidance.

## Build gotchas

- `-checklinkname=0` is required: Tailscale uses `go:linkname` and newer Go rejects it otherwise.
- Never run `go mod tidy` as part of the AAR build. It removes `golang.org/x/mobile/bind`, which gomobile needs but no checked-in source imports. CI deliberately omits tidy.
- The Gradle wrapper is committed (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`). `GRADLE_HOME` is optional and only needed to regenerate it.
- A clean checkout cannot build the APK until `android-app/app/libs/tsproxy.aar` exists (it is gitignored).
- `.build/` is gitignored and holds the patched Tailscale copy, logs, and temporary `go.mod` backups.
- `make clean` deletes the AAR; run `make aar` before `make apk` again.
- `make` defaults to help; use `make cli` for the CLI, not plain `make`.
- `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`; declare repositories in `pluginManagement`/`dependencyResolutionManagement`, not in module build files.

## Tailscale patching

Both CI and local builds use `make aar`, which copies the `tailscale.com` module into `.build/tailscale-patched`, patches it, then runs `go mod edit -replace tailscale.com=...` for that workspace only (not committed). `android-patch/patch.sh` is the helper.

Patch logic lives in `android-patch/patch.sh`; CI and local builds both call it through `make aar`.

- `cert.go.patch` + `disabled_stubs.go.patch` enable the ACME localapi on Android.
- The `tsnet/tsnet.go` sed patch adds the `android` fallback for `os.Executable()`.
- `tsproxy/setup.go` `init()` registers `anet.Interfaces` with netmon before tsnet starts, bypassing Android SDK≥30 netlink restrictions. Keep the init and its ordering intact.
- `mobile/tsproxy.go` `init()` sets `TMPDIR`/`TS_LOGS_DIR`, discovers Android TZ/ZONEINFO, and explicitly loads `time.Local`. Setting `TZ` alone is too late because Go initializes `time.Local` before `init()`.

## App / release notes

- The Go side is process-global and single-instance: `Start` no-ops while running, and `Stop` (including `tsp.StopSocks5`) must complete before a new `Start` can rebind the port.
- The foreground service (`specialUse`) holds a 24h wakelock and restarts itself after task removal. Logs go to logcat and `filesDir/tsproxy_crash.log`, shown in the UI.
- `versionCode` / `versionName` in `android-app/app/build.gradle.kts` track the `tailscale.com` version (currently `10405` / `1.104.5`); bump both when upgrading Tailscale.
- `android-app/signing/keystore.p12` is intentionally committed with a fixed password so debug/release signatures stay stable. Do not regenerate it or re-add it to `.gitignore`; changing it forces users to uninstall.
- `overwrite-tailscale.js` is the full FlClash/Mihomo override; README contains a condensed copy. Keep proxy/group/rules/DNS behavior consistent when editing either.
- README is in Chinese. When it disagrees with config/CI, trust config/CI (e.g. README says Go 1.24+, but `go.mod` and CI require 1.27.3).
