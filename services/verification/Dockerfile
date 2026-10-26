FROM eclipse-temurin:21.0.9_10-jdk-jammy@sha256:41c8a28993a03b5dd042ce1afc7973c9a98bfbbbd6336eb1e32e673b1718d99c AS build
WORKDIR /build
COPY gradlew settings.gradle build.gradle gradle.lockfile ./
COPY gradle ./gradle
COPY src ./src
# Gradle is always taken from ./gradle-dist (compose additional context); SHA-256 is still verified.
COPY --from=gradle-dist gradle-8.14.3-bin.zip /tmp/gradle-dist/
RUN sed -i 's|^distributionUrl=.*|distributionUrl=file\\:/tmp/gradle-dist/gradle-8.14.3-bin.zip|' gradle/wrapper/gradle-wrapper.properties
RUN chmod +x gradlew && ./gradlew --no-daemon test installDist

FROM eclipse-temurin:21.0.9_10-jre-jammy@sha256:cc11c035bb25cc709d7b1e3f43cbff15d69e06e2dba23eec431b64627d27e705
RUN groupadd --gid 10001 verification && useradd --uid 10001 --gid 10001 --no-create-home verification
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/build/install/verification/ ./
COPY --chown=10001:10001 THIRD_PARTY_NOTICES /app/THIRD_PARTY_NOTICES
COPY --chown=10001:10001 licenses /app/licenses
USER 10001:10001
ENV PORT=8003
EXPOSE 8003
ENTRYPOINT ["/app/bin/verification"]
