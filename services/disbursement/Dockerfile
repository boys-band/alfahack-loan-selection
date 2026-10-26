FROM eclipse-temurin:21.0.9_10-jdk-jammy AS build
WORKDIR /build
COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.lockfile ./
COPY src/ src/
# Gradle is always taken from ./gradle-dist (compose additional context); SHA-256 is still verified.
COPY --from=gradle-dist gradle-8.14.3-bin.zip /tmp/gradle-dist/
RUN sed -i 's|^distributionUrl=.*|distributionUrl=file\\:/tmp/gradle-dist/gradle-8.14.3-bin.zip|' gradle/wrapper/gradle-wrapper.properties
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21.0.9_10-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 service && useradd --uid 10001 --gid service --no-create-home service
COPY --from=build /build/build/libs/disbursement.jar ./disbursement.jar
USER 10001:10001
EXPOSE 8006
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/disbursement.jar"]
