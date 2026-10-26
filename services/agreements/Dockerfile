FROM eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0 AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew ./
COPY settings.gradle build.gradle gradle.lockfile ./
COPY src ./src
# Gradle is always taken from ./gradle-dist (compose additional context); SHA-256 is still verified.
COPY --from=gradle-dist gradle-8.14.3-bin.zip /tmp/gradle-dist/
RUN sed -i 's|^distributionUrl=.*|distributionUrl=file\\:/tmp/gradle-dist/gradle-8.14.3-bin.zip|' gradle/wrapper/gradle-wrapper.properties
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
WORKDIR /app
ENV PORT=8005
COPY --from=build /workspace/build/install/agreements/ ./
COPY THIRD_PARTY_NOTICES ./THIRD_PARTY_NOTICES
USER 65532:65532
EXPOSE 8005
ENTRYPOINT ["/app/bin/agreements"]
