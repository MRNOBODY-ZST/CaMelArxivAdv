# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /workspace/backend
COPY backend/gradle ./gradle
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null
COPY backend/src ./src
RUN ./gradlew --no-daemon --console=plain --info bootJar

FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S camel && adduser -S -G camel -h /app camel
WORKDIR /app
COPY --from=builder --chown=camel:camel /workspace/backend/build/libs/*.jar /app/application.jar

USER camel
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]
