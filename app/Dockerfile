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
# `:app` depends on `:plugin-api`, and Gradle refuses to configure a project
# whose directory is not there — so this is not an optimisation but the
# difference between an image that builds and one that does not. Its build file
# comes with the other declarations; its sources follow below.
COPY plugin-api/build.gradle.kts ./plugin-api/
RUN ./gradlew --no-daemon :app:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

# The BlobStore contract. `app/build.gradle.kts` points the protobuf plugin at
# `<root>/proto`, so without this the generator finds no sources, reports
# NO-SOURCE, and the compile fails on a package that was never generated — which
# is what this image did from the day the gRPC client was added until the first
# time anybody built it.
COPY proto/ ./proto/

# The ports a plugin implements and the manifest model the core reads
# (ADR-0064). Apache-2.0 and part of the same build, so the image needs its
# sources as much as the core's.
COPY plugin-api/src ./plugin-api/src
COPY app/src ./app/src

# The shipped item type templates (REQ-CORE-030). `TypeTemplates` reads them
# while the context starts, so an artifact without them is one that refuses to
# start — which is what `api` did inside this image until the file was copied
# here. `.dockerignore` excludes `docs/` wholesale and re-includes this one
# path, so this line names the file rather than the directory: nothing else
# under `docs/` is in the context to copy.
COPY docs/reference/type-templates.yaml ./docs/reference/

# WHICH COMMIT THIS IMAGE IS (REQ-CON-009).
#
# There is no git here -- no binary and no `.git` directory -- so the build
# cannot find out for itself, and an image that reported `unknown` would leave
# the AGPL's source offer pointing at a project rather than at a build. The
# caller passes it: `docker build --build-arg HOMEINV_BUILD_COMMIT=$(git rev-parse --short=12 HEAD)`.
ARG HOMEINV_BUILD_COMMIT=unknown
ENV HOMEINV_BUILD_COMMIT=${HOMEINV_BUILD_COMMIT}

RUN ./gradlew --no-daemon :app:bootJar -x test

# The layered jar, so the runtime image gets the dependencies in one layer and
# the application in another. Without it every release ships 70 MB of unchanged
# libraries again.
RUN java -Djarmode=tools -jar app/build/libs/app-*.jar extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
# PINNED to noble (Ubuntu 24.04 LTS), and not out of caution about newness.
# `eclipse-temurin:25-jre` currently resolves to Ubuntu 26.04, whose archive
# carries libheif's AVIF *decoder* plugin and none of its encoders — no
# `libheif-plugin-aomenc`, `-rav1e` or `-svtenc` at all. ADR-0052 makes AVIF the
# one stored image format, so on that base every image upload failed with
# `heifsave: Unsupported compression` and answered 500. Found by running
# deploy/smoke/journey.sh against the stack.
#
# Revisit when the encoder plugins reappear in a later Ubuntu, or when Temurin
# publishes a Debian-based tag: Debian packages them.
FROM eclipse-temurin:25-jre-noble

# libvips is the ImageProcessor (O29, ADR-0007). Installed here rather than
# bound into the JVM, so a crash while decoding a stranger's upload kills a child
# process instead of the application.
#
# --no-install-recommends: Debian's recommends pull in a large graphics stack
# this application never uses, and every megabyte is also attack surface.
#
# libheif-plugin-aomenc is what lets libvips WRITE AVIF. libvips links libheif,
# and libheif loads its codecs as plugins — without this one `heifsave` exists,
# accepts the request and fails at the last step, which is the worst shape a
# missing dependency can take (ADR-0052, REQ-MED-003, REQ-SEC-041). aomenc rather
# than rav1e or svtenc because it is the reference AV1 encoder and the one the
# quality setting in `VipsImageProcessor` was chosen against.
#
# curl is the health check (app/docker/healthcheck). It asks the management port
# every fifteen seconds, and without it the container can never report healthy —
# so nothing that waits for `api` ever starts. The script said "curl rather than
# a JVM" from the day it was written and nothing installed it; found by starting
# the stack.
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        libvips-tools \
        libheif-plugin-aomenc \
        curl \
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
