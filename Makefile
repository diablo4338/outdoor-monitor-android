CLIENT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
PROJECT_DIR := $(abspath $(CLIENT_DIR)/..)

-include $(PROJECT_DIR)/docker/.env

APK_VERSION_CODE ?= $(shell expr 2000000000 + $$(git -C $(CLIENT_DIR) rev-list --count HEAD))
APK_VERSION_NAME ?= 65.0.$(shell git -C $(CLIENT_DIR) rev-list --count HEAD)
APK_API_HOST ?= 10.0.2.2
APK_API_BASE_URL := http://$(APK_API_HOST):$(API_PORT)
APK_POLL_INTERVAL_SECONDS ?= 10
APK_GIT_COMMIT ?= $(shell git -C $(CLIENT_DIR) rev-parse HEAD)
APK_PUBLISHED_AT ?=
APK_GOOGLE_CLIENT_ID := $(if $(strip $(APK_GOOGLE_CLIENT_ID)),$(APK_GOOGLE_CLIENT_ID),$(shell sed -n 's/^GOOGLE_WEB_CLIENT_ID=//p' $(CLIENT_DIR)/local.properties 2>/dev/null))

.PHONY: help apk-build

help:
	@printf '%s\n' \
		'Targets:' \
		'  apk-build         Build a debug APK for testing'

apk-build:
	@APK_GOOGLE_CLIENT_ID='$(APK_GOOGLE_CLIENT_ID)' docker buildx build --file $(CLIENT_DIR)/docker/Dockerfile.apk --target artifact --output type=local,dest=$(PROJECT_DIR)/build --build-arg BUILD_VARIANT=debug --build-arg VERSION_NAME=$(APK_VERSION_NAME) --build-arg VERSION_CODE=$(APK_VERSION_CODE) --build-arg RELEASE_API_BASE_URL=$(APK_API_BASE_URL) --build-arg POLL_INTERVAL_SECONDS=$(APK_POLL_INTERVAL_SECONDS) --build-arg GIT_COMMIT=$(APK_GIT_COMMIT) --build-arg PUBLISHED_AT=$(APK_PUBLISHED_AT) --secret id=google_web_client_id,env=APK_GOOGLE_CLIENT_ID $(CLIENT_DIR)
