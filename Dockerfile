# syntax=docker/dockerfile:1

# Build and package the server.
#
# Two stages, because the JDK, the Maven repository and the source tree are all
# build-time concerns: shipping them would multiply the image size and hand
# anyone who pulls it the toolchain as well as the application.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

# The poms first, on their own layer. Dependencies change far less often than
# code does, so resolving them again on every source edit is wasted minutes.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY app/pom.xml app/
COPY modules/connection/pom.xml modules/connection/
COPY modules/execution/pom.xml modules/execution/

RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY app/src app/src
COPY modules/connection/src modules/connection/src
COPY modules/execution/src modules/execution/src

# Tests are not run here. The suite brings up Postgres through Testcontainers,
# which needs a Docker daemon this build does not have — and a container build
# is the wrong place to find out a test fails. CI runs them as their own job,
# and this image is only built once they pass.
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre AS runtime

# Not root. Nothing here needs to write outside its own working directory, and a
# container that cannot install anything is one less thing an exploit can use.
RUN groupadd --system orknux && useradd --system --gid orknux --create-home orknux

WORKDIR /app

COPY --from=build --chown=orknux:orknux /build/app/target/orknux-app-*.jar app.jar

# Somewhere the server can write, under the working directory the defaults are
# relative to. WORKDIR creates /app as root, and this image runs as orknux, so
# `data/secret.key` and `data/attachments` - both defaults - landed on a path
# their own process could not create. The server answered that by generating no
# key at all and failing the first credential somebody saved, which is the exact
# failure the generated key exists to prevent.
#
# It makes the defaults work. It does not make them right: this is the
# container's own layer and it goes when the container is replaced, so anything
# meant to outlive a `docker pull` still wants a volume over it.
RUN mkdir -p /app/data && chown orknux:orknux /app/data

USER orknux

EXPOSE 8080

# Containers get a share of the host, not the host: this lets the JVM see the
# cgroup limit rather than the machine's memory and size its heap to the wrong
# number.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Shell form, so JAVA_OPTS is expanded rather than passed as one literal
# argument. `exec` keeps the JVM as PID 1, which is what makes `docker stop`
# reach it and the graceful shutdown in application.yml mean anything.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
