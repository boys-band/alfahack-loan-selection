GRADLE ?= ./gradlew
DOCKER_BUILD_ARGS ?=
.PHONY: test test-integration build image
test:
	$(GRADLE) test

test-integration:
	$(GRADLE) test integrationTest

build:
	$(GRADLE) build

image:
	docker build $(DOCKER_BUILD_ARGS) -t alfa-applications:2.0.0 .
