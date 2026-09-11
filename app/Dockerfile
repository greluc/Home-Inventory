# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The application image.
#
# Non-root inside the container AND rootless on the host are two independent
# levels, and this file is responsible for the first one only (ADR-0022). An
# image that runs as root is unsafe even under a rootless runtime, because the
# mapped root can still write everything the container can reach.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper and the dependency declarations first, so a change to source code
# does not invalidate the layer that downloaded the dependencies. It is the
# difference between a ten-second rebuild and a three-minute one.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts ./app/
RUN ./gradlew --no-daemon :app:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY app/src ./app/src
RUN ./gradlew --no-daemon :app:bootJar -x test

# The layered jar, so the runtime image gets the dependencies in one layer and
# the application in another. Without it every release ships 70 MB of unchanged
# libraries again.
RUN java -Djarmode=tools -jar app/build/libs/app-*.jar extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# libvips is the ImageProcessor (O29, ADR-0007). Installed here rather than
# bound into the JVM, so a crash while decoding a stranger's upload kills a child
# process instead of the application.
#
# --no-install-recommends: Debian's recommends pull in a large graphics stack
# this application never uses, and every megabyte is also attack surface.
RUN apt-get update \
    && apt-get install --no-install-recommends -y libvips-tools \
    && rm -rf /var/lib/apt/lists/*

# The uid the service matrix names. A fixed numeric uid rather than a name:
# a rootless runtime maps it through subuid, and the mapping is by number.
RUN groupadd --gid 10001 homeinv \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin homeinv

WORKDIR /app
COPY --from=build --chown=10001:10001 /extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/application/ ./

# The health command the matrix names. A file rather than a shell one-liner in
# the unit, so both runtimes call the same thing and a change is one edit.
COPY --chown=10001:10001 app/docker/healthcheck /usr/bin/healthcheck
RUN chmod 0555 /usr/bin/healthcheck

USER 10001:10001

# Documentation only; the matrix decides what is published, and nothing below
# 1024 ever is (REQ-NFR-061).
EXPOSE 8080 8090

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell
# form the signal reaches /bin/sh, the JVM never learns of it, and every
# deployment waits for the ten-second kill timeout.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]
