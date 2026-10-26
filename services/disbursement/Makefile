SHELL := /bin/bash
# A Homebrew Java 21 may not be registered with macOS java_home.
JAVA_HOME ?= $(shell if [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then printf /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; elif command -v /usr/libexec/java_home >/dev/null; then /usr/libexec/java_home -v 21; fi)
export JAVA_HOME

.PHONY: test test-integration build run
test:
	./gradlew --no-daemon test
test-integration:
	bash tests/run-integration.sh
build:
	./gradlew --no-daemon bootJar
run:
	./gradlew --no-daemon bootRun
