FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src

RUN ./gradlew bootJar -x test --no-daemon


FROM eclipse-temurin:21-jre-alpine

RUN apk upgrade --no-cache \
    && apk add --no-cache docker-cli curl

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl --fail --silent --output /dev/null --max-time 2 http://localhost:8090/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
