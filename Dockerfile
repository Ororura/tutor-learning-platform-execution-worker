FROM gradle:8.14.3-jdk21-alpine AS build
WORKDIR /workspace
COPY . .
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache docker-cli
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
