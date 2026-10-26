GRADLE ?= ./gradlew
.PHONY: test test-integration build
test:
	$(GRADLE) test
test-integration:
	$(GRADLE) integrationTest
build:
	$(GRADLE) build
.PHONY: docker-build
docker-build:
	docker build -t alfa-decision:local .
