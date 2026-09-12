# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# PostgreSQL with this deployment's initialisation scripts baked in.
#
# Why an image of our own for a stock database:
#
# The scripts in `deploy/postgres/initdb/` are what create the five roles the
# whole isolation design rests on — `homeinv_app` NOBYPASSRLS above all
# (ADR-0003, 07 §7.5). They have to be inside the container before its first
# start, and the two ways of getting a file in there are a bind mount, which
# ADR-0022 forbids without exception, and baking it into an image.
#
# The files are static, so baking costs nothing at run time and gains a digest:
# the role definitions are then pinned with the image rather than read from
# whatever happens to be on the operator's disk at first start.
#
# Nothing else changes. No configuration is edited here — the settings the
# matrix carries are passed as `-c` arguments, so they stay visible in the
# generated descriptions rather than hiding in a layer.

# Pinned to the digest, not the tag. This image IS the deployment's PostgreSQL,
# and the integration tests run this same base — `ProductionImages` reads the
# `FROM` below — so an unpinned tag here would mean the tests and the deployment
# both float, which is what REQ-NFR-027 forbids. The tag is kept in the comment
# because a bare digest tells a reader nothing:
#
#     docker.io/library/postgres:18-alpine, resolved 2026-09-12
FROM docker.io/library/postgres@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2

# 00 creates the roles; 01 gives the two that log in their passwords, read from
# the mounted secrets. They run in name order, once, at first initialisation.
COPY --chmod=0444 deploy/postgres/initdb/00-roles.sql /docker-entrypoint-initdb.d/00-roles.sql
COPY --chmod=0555 deploy/postgres/initdb/01-role-passwords.sh /docker-entrypoint-initdb.d/01-role-passwords.sh

# The uid the deployment runs this as, stated here as well as in the matrix.
#
# `deploy/services.yaml` has carried `user: "70:70"` from the start — the image's
# own postgres account, and not 10001, because initdb refuses to run as a uid it
# does not own the data directory as. Saying it only there left the image itself
# defaulting to root, which is REQ-SEC-084's concern and which trivy reports as
# DS-0002: an image is a thing somebody can run without our compose file.
#
# 70:70 and not the name: a rootless runtime maps the id through subuid, and the
# mapping is by number (ADR-0022).
USER 70:70
