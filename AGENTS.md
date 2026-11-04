# AGENTS.md

Android app that runs Tailscale in userspace and exposes a local SOCKS5 proxy (default `127.0.0.1:1080`) so Clash/Mihomo can keep the single Android VPN slot. Go core + Kotlin/Compose UI.

## Layout / entrypoints

- `main.go` — standalone CLI (`ts-proxy`), flag-driven TCP/UDP/SOCKS forwarding. Not used by the Android app.
- `tsproxy/` — shared Go library (SOCKS5 serve/forward, TCP/UDP forwarders, delayed UDP binding).
- `mobile/` — the gomobile-bound package. Despite the folder name, its Go package is `tsproxy`, so Kotlin calls `tsproxy.Tsproxy.start(...)` / `.stop()` / `.getLogs()`.
- `android-app/` — app `com.tsproxy.android`: service in `service/TsProxyService.kt`, Compose UI/state in `ui/MainActivity.kt` + `ui/MainViewModel.kt`, settings in DataStore (`tsproxy_prefs`).

## Commands

构建命令与环境准备见 README「从源码编译」，`make help` 列出全部目标。
Agent 需要注意：改 Go 代码后必须先 `make aar` 再 `make apk`；仓库没有测试，`go test ./...` 只做编译检查。

## Local build environment

`AGENTS.local.md` (gitignored) holds machine-specific agent notes; if it exists, read it before building. Keep this file portable so every clone gets the same project guidance.

## Build gotchas

- `-checklinkname=0` is required: Tailscale uses `go:linkname` and newer Go rejects it otherwise.
- Never run `go mod tidy` as part of the AAR build. It removes `golang.org/x/mobile/bind`, which gomobile needs but no checked-in source imports. CI deliberately omits tidy.
- `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`; declare repositories in `pluginManagement`/`dependencyResolutionManagement`, not in module build files.

## Tailscale patching

Both CI and local builds use `make aar`: it copies `tailscale.com` into `.build/tailscale-patched`, applies `android-patch/patch.sh`, and binds against that copy via a workspace-local `go mod edit -replace` (not committed).

Non-obvious invariants:

- `tsproxy/setup.go` `init()` must register `anet.Interfaces` with netmon before tsnet starts (Android SDK≥30 netlink restrictions).
- `mobile/tsproxy.go` `init()` must set `TMPDIR`/`TS_LOGS_DIR` and load `time.Local` from the Android TZ/ZONEINFO sources before anything uses time.
- `mobile/taildrop.go` must keep its blank `tailscale.com/feature/taildrop` import; tsnet does not link the feature itself.

## App notes

- The Go side is process-global and single-instance: `Start` no-ops while running, and `Stop` (including `tsp.StopSocks5`) must complete before a new `Start` can rebind the port.
- One tsnet directory per account (`filesDir/accounts/<uuid>`); switching accounts stops the node before starting the other one. Logout deletes the account directory after the node stops; an empty account list is persisted as `[]` and not re-seeded.
- The foreground service (`specialUse`) holds a 24h wakelock. Removing the task (swiping the app from recents) is an explicit close: the proxy stops and the notification + Quick Settings tile turn off. Logs go to logcat and `filesDir/tsproxy_crash.log`.
- Taildrop works in both directions only while the tailnet's Send Files option is enabled; received files are staged by tsnet, then published to `Download/Tailsocks5` or a SAF folder.
- 版本规则：`versionName` 是应用自己的 semver（`major.minor.patch`，与 `tailscale.com` 版本无关）；`versionCode` 不手写，由 `android-app/app/build.gradle.kts` 按 `major*10000 + minor*100 + patch` 推导，`minor`/`patch` 必须小于 100。发版只改 `versionName`，再打 `vX.Y.Z` tag。
- `android-app/signing/keystore.p12` is intentionally committed with a fixed password so debug/release signatures stay stable. Do not regenerate it or re-add it to `.gitignore`; changing it forces users to uninstall.
- `overwrite-tailscale.js` is the full FlClash/Mihomo override; README contains a condensed copy. Keep proxy/group/rules/DNS behavior consistent when editing either.
