# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# `plugin-oidc` (ADR-0072).
#
# The one plugin image with a JVM in it, and the reason is written down rather
# than assumed: ID-token validation is where an audited library outweighs an
# image size, because a subtle verification bug written twice is how a federated
# login becomes an authentication bypass. The other four plugins are `scratch`
# plus a static binary and stay that way.
#
# It is also why REQ-NFR-009 carries two per-plugin adders since 2026-09-21
# (ADR-0075): 64 MB of reservation for a Rust plugin, 192 MB for this one. A JRE
# with gRPC, Netty and Nimbus does not live in the first figure, and metaspace,
# the code cache and the direct buffers all sit outside what
# `-XX:MaxRAMPercentage` bounds.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper and the dependency declarations first, so a change to source code
# does not invalidate the layer that downloaded the dependencies.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# Gradle refuses to configure a build whose project directories are not there,
# so every project in `settings.gradle.kts` needs at least its build file —
# the same reason `app/Dockerfile` copies `plugin-api/build.gradle.kts`.
COPY app/build.gradle.kts ./app/
COPY plugin-api/build.gradle.kts ./plugin-api/
COPY plugins/oidc/build.gradle.kts ./plugins/oidc/
RUN ./gradlew --no-daemon :plugins:oidc:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

# The contract itself. `plugins/oidc/build.gradle.kts` points the protobuf
# plugin at `<root>/proto`, so without this the generator finds no sources and
# the compile fails on a package that was never generated.
COPY proto/ ./proto/
COPY plugins/oidc/src ./plugins/oidc/src

# `installDist` rather than a fat jar: the application plugin writes a `lib/`
# with one jar per dependency, which is a layer that changes only when a
# dependency does. A shaded jar would be one file that changes on every source
# edit, and one more plugin in the build to produce it.
RUN ./gradlew --no-daemon :plugins:oidc:installDist

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
#
# `-jre-noble` and not `scratch`: a JVM needs a libc and a JRE needs its own
# layout. It is the same base `app/` runs on, so the two share layers on any
# host that runs both.
FROM eclipse-temurin:25-jre-noble

# The uid the service matrix names. Numeric, because a rootless runtime maps it
# through subuid by number and the name in /etc/passwd is not what it reads.
RUN useradd --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin homeinv

COPY --from=build --chown=10001:10001 /src/plugins/oidc/build/install/oidc/lib /opt/homeinv/lib

USER 10001:10001

# Documentation only; the matrix decides what is published, and a plugin never
# is — it is reachable from its own segment and nowhere else (ADR-0037).
EXPOSE 8204

# The same two flags `app/` runs with. `ExitOnOutOfMemoryError` is deliberate: a
# federated login that answers slowly and sometimes is worse than one that is
# plainly down, and a container that exits is one the runtime restarts and the
# operator sees.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-cp", "/opt/homeinv/lib/*", \
    "de.greluc.homeinv.plugin.oidc.Main"]
