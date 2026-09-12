# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The web container: the application shell and every security header.
#
# ADR-0038 withdrew the option of letting the operator's reverse proxy emit the
# headers. A header written into somebody else's proxy config cannot be tested
# here, and a policy nobody tests drifts until the first `unsafe-inline` appears
# to make something work. So the policy lives in this repository, is generated
# from the built bundle, and is served by this container.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM node:26-alpine AS build

WORKDIR /src

# The lockfile first: a source change must not invalidate the layer that
# installed the dependencies.
COPY web/package.json web/package-lock.json ./
# `npm ci` and not `npm install`: it installs exactly the lockfile and fails if
# the two disagree, which is the difference between a reproducible build and one
# that happens to work today (REQ-SEC-074).
RUN npm ci

COPY web/ ./

# No `COPY design-system/`: nothing in this build reads it. It was copied to
# `/design-system` for the fonts and icons the client will bundle from there
# rather than from a CDN — and until something imports them it was a directory
# the root `.dockerignore` excludes, so the build failed on a path it did not
# need. It comes back with the code that reads it, and with the `.dockerignore`
# entry in the same commit.

# The build also regenerates nginx/default.conf from the bundle it just produced,
# so the policy and the hashes it names cannot disagree.
RUN npm run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:alpine

COPY --from=build /src/dist /usr/share/nginx/html
COPY --from=build /src/nginx/default.conf /etc/nginx/conf.d/default.conf

# The health command the service matrix names for this service.
COPY web/docker/healthcheck /healthcheck
COPY web/docker/nginx-check.conf /tmp/nginx-check.conf

# Fails the build rather than the first request if the generated configuration is
# not valid nginx.
#
# The upstream is rewritten to a literal address for the check, and only for the
# check. `nginx -t` parses the configuration exactly as a start would, upstream
# name resolution included, and `api` does not resolve inside a build — so
# without the substitution this would fail on the one thing a build cannot
# verify. What it does verify is every other line, which is what a build can.
USER root
RUN chmod 0555 /healthcheck     && sed 's|server api:8080;|server 127.0.0.1:8080;|' /etc/nginx/conf.d/default.conf          > /tmp/check-site.conf     && nginx -t -c /tmp/nginx-check.conf     && rm /tmp/check-site.conf /tmp/nginx-check.conf

# This base image already runs as an unprivileged user and listens above 1024,
# which is what the deployment requires (REQ-NFR-061, REQ-SEC-085). The stock
# nginx image does neither: it starts as root and binds port 80, and making it
# behave would mean undoing its entrypoint.
USER 101:101

EXPOSE 8080
