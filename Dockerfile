# syntax=docker/dockerfile:1
# Shared multi-stage build for every EPOS Spring Boot service.
# The compose file selects the Maven module via --build-arg SERVICE_NAME,
# e.g. SERVICE_NAME=auth-service. Build context is the repo root so the
# parent pom and all module poms are available to the reactor.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SERVICE_NAME
WORKDIR /build
COPY pom.xml ./
COPY microservices ./microservices
# The cache mount shares the local Maven repository across service builds,
# so dependencies are downloaded once instead of per image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl :${SERVICE_NAME} -am -DskipTests clean package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
ARG SERVICE_NAME
# curl is used by the compose healthchecks to probe /actuator/health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/microservices/${SERVICE_NAME}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
