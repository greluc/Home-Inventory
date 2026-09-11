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

FROM docker.io/library/postgres:18-alpine

# 00 creates the roles; 01 gives the two that log in their passwords, read from
# the mounted secrets. They run in name order, once, at first initialisation.
COPY --chmod=0444 deploy/postgres/initdb/00-roles.sql /docker-entrypoint-initdb.d/00-roles.sql
COPY --chmod=0555 deploy/postgres/initdb/01-role-passwords.sh /docker-entrypoint-initdb.d/01-role-passwords.sh
