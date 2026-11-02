SHELL := /bin/bash

REPO_ROOT := $(patsubst %/,%,$(dir $(realpath $(lastword $(MAKEFILE_LIST)))))
AAR := $(REPO_ROOT)/android-app/app/libs/tsproxy.aar

.DEFAULT_GOAL := help

.PHONY: help tools doctor cli ts-proxy aar apk release all check test env clean

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*## "; printf "Usage:\n  make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_-]+:.*## / { printf "  %-16s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

tools: ## Install gomobile/gobind into GOBIN
	@source scripts/env.sh && \
		go install golang.org/x/mobile/cmd/gomobile@latest && \
		go install golang.org/x/mobile/cmd/gobind@latest && \
		gomobile init

doctor: ## Check local build prerequisites
	@scripts/doctor.sh

cli: ## Build the ts-proxy CLI
	@source scripts/env.sh && cd "$(REPO_ROOT)" && go build -ldflags "-s -w" -trimpath -o ts-proxy .

ts-proxy: cli ## Alias for cli

aar: ## Build the Go/Tailscale Android library (AAR)
	@scripts/build-aar.sh

apk: ## Build debug APK (requires AAR)
	@test -f "$(AAR)" || { echo "ERROR: $(AAR) missing; run 'make aar' first." >&2; exit 1; }
	@scripts/build-apk.sh debug

release: ## Build release APK (requires AAR)
	@test -f "$(AAR)" || { echo "ERROR: $(AAR) missing; run 'make aar' first." >&2; exit 1; }
	@scripts/build-apk.sh release

all: aar apk ## Build AAR and debug APK

check: ## Compile and vet Go packages
	@source scripts/env.sh && cd "$(REPO_ROOT)" && go build ./... && go vet ./...

test: ## Run Go tests (there are currently no test files)
	@source scripts/env.sh && cd "$(REPO_ROOT)" && go test ./...

env: ## Print resolved toolchain paths and versions
	@source scripts/env.sh && \
		echo "JAVA_HOME=$$JAVA_HOME" && \
		java -version 2>&1 | head -n 1 && \
		( if [ -n "$${GRADLE_HOME:-}" ] && [ -x "$$GRADLE_HOME/bin/gradle" ]; then \
			gradle --version 2>/dev/null | grep -E '^Gradle |^JVM '; \
		else \
			echo "Gradle: wrapper ($(REPO_ROOT)/android-app/gradlew)"; \
		fi ) && \
		go version && \
		echo "ANDROID_HOME=$$ANDROID_HOME" && \
		echo "ANDROID_NDK_HOME=$$ANDROID_NDK_HOME"

clean: ## Remove build outputs and generated AAR
	rm -rf .build android-app/build android-app/app/build android-app/.gradle
	rm -f "$(AAR)" android-app/app/libs/*-sources.jar ts-proxy
