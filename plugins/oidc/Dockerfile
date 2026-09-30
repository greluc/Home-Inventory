# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts ./app/
COPY plugin-api/build.gradle.kts ./plugin-api/
COPY plugins/oidc/build.gradle.kts ./plugins/oidc/
RUN mkdir -p api/clients/kotlin
RUN ./gradlew --no-daemon :plugins:oidc:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY proto/ ./proto/
COPY plugins/oidc/src ./plugins/oidc/src

RUN ./gradlew --no-daemon :plugins:oidc:installDist

FROM eclipse-temurin:25-jre-noble

RUN useradd --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin homeinv

COPY --from=build --chown=10001:10001 /src/plugins/oidc/build/install/oidc/lib /opt/homeinv/lib

USER 10001:10001

EXPOSE 8204

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-cp", "/opt/homeinv/lib/*", \
    "de.greluc.homeinv.plugin.oidc.Main"]
