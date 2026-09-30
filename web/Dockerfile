# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM node:26-alpine AS build

WORKDIR /src

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./

RUN npm run build

FROM nginxinc/nginx-unprivileged:alpine

COPY --from=build /src/dist /usr/share/nginx/html
COPY --from=build /src/nginx/default.conf /etc/nginx/conf.d/default.conf

COPY web/docker/healthcheck /healthcheck
COPY web/docker/nginx-check.conf /tmp/nginx-check.conf

USER root
RUN chmod 0555 /healthcheck     && sed 's|server api:8080;|server 127.0.0.1:8080;|' /etc/nginx/conf.d/default.conf          > /tmp/check-site.conf     && nginx -t -c /tmp/nginx-check.conf     && rm /tmp/check-site.conf /tmp/nginx-check.conf

USER 101:101

EXPOSE 8080
