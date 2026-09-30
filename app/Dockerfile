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
RUN ./gradlew --no-daemon :app:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY proto/ ./proto/

COPY plugin-api/src ./plugin-api/src
COPY app/src ./app/src

COPY docs/reference/type-templates.yaml ./docs/reference/

ARG HOMEINV_BUILD_COMMIT=unknown
ENV HOMEINV_BUILD_COMMIT=${HOMEINV_BUILD_COMMIT}

RUN ./gradlew --no-daemon :app:bootJar -x test

RUN java -Djarmode=tools -jar app/build/libs/app-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        libvips-tools \
        libheif-plugin-aomenc \
        curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 homeinv \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin homeinv

WORKDIR /app
COPY --from=build --chown=10001:10001 /extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/application/ ./

COPY --chown=10001:10001 app/docker/healthcheck /usr/bin/healthcheck
RUN chmod 0555 /usr/bin/healthcheck

USER 10001:10001

EXPOSE 8080 8090

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]
