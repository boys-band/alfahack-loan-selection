.PHONY: build test style-check style-fix setup

build:
	./gradlew build

test:
	./gradlew test

style-check:
	npm run format:check

style-fix:
	npm run format

setup:
	npm ci
	npm --prefix frontend ci
