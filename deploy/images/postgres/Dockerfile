# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/postgres@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2

COPY --chmod=0444 deploy/postgres/initdb/00-roles.sql /docker-entrypoint-initdb.d/00-roles.sql
COPY --chmod=0555 deploy/postgres/initdb/01-role-passwords.sh /docker-entrypoint-initdb.d/01-role-passwords.sh

RUN mkdir -p /var/lib/homeinv/wal-archive \
    && chown 70:70 /var/lib/homeinv/wal-archive \
    && chmod 0700 /var/lib/homeinv/wal-archive

USER 70:70
