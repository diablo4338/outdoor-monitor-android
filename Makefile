VERSION_NAME := $(word 2,$(MAKECMDGOALS))
VERSION_CODE := $(shell test -f ../docker/releases/latest.json && \
	sed -n 's/^[[:space:]]*"version_code":[[:space:]]*\([0-9][0-9]*\),*$$/\1/p' \
	../docker/releases/latest.json | awk '{ print $$1 + 1 }' || echo 1)
APK_OUTPUT_DIR := build/local-apk
RELEASES_DIR := ../docker/releases
PYTHON := $(abspath ../.venv/bin/python)

.PHONY: apk-local-build apk-test-publish

apk-local-build:
	@test -n "$(VERSION_NAME)" || (echo "Usage: make apk-local-build 1.2.5" >&2; exit 2)
	@case "$(VERSION_NAME)" in *[!0-9A-Za-z.+-]*) echo "Invalid version: $(VERSION_NAME)" >&2; exit 2;; esac
	VERSION_NAME="$(VERSION_NAME)" VERSION_CODE="$(VERSION_CODE)" ./gradlew --no-daemon --console=plain :app:assembleDebug
	mkdir -p "$(APK_OUTPUT_DIR)"
	cp app/build/outputs/apk/debug/app-debug.apk "$(APK_OUTPUT_DIR)/outdoor-monitor-$(VERSION_NAME)-$(VERSION_CODE)-debug.apk"
	$(PYTHON) scripts/publish-local-release.py \
		--apk "$(APK_OUTPUT_DIR)/outdoor-monitor-$(VERSION_NAME)-$(VERSION_CODE)-debug.apk" \
		--releases-dir "$(RELEASES_DIR)" \
		--version-name "$(VERSION_NAME)" \
		--version-code "$(VERSION_CODE)"
	@echo "Published $(VERSION_NAME) ($(VERSION_CODE)) to $(RELEASES_DIR)"

apk-test-publish: apk-local-build

ifneq ($(VERSION_NAME),)
$(VERSION_NAME):
	@:
endif
