FROM eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0 AS build
WORKDIR /build
COPY gradle ./gradle
COPY gradlew settings.gradle build.gradle gradle.lockfile ./
COPY src src
# Gradle is always taken from ./gradle-dist (compose additional context); SHA-256 is still verified.
COPY --from=gradle-dist gradle-8.14.3-bin.zip /tmp/gradle-dist/
RUN sed -i 's|^distributionUrl=.*|distributionUrl=file\\:/tmp/gradle-dist/gradle-8.14.3-bin.zip|' gradle/wrapper/gradle-wrapper.properties
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon test shadowJar

FROM eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0
WORKDIR /app
COPY --from=build /build/build/libs/catalog.jar /app/catalog.jar
USER 10001:10001
ENV PORT=8002
EXPOSE 8002
HEALTHCHECK --interval=5s --timeout=7s --start-period=10s --retries=3 CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT}/health/ready" || exit 1
ENTRYPOINT ["java", "-jar", "/app/catalog.jar"]
