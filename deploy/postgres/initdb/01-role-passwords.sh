#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
set -eu

require() {
    if [ ! -r "$1" ]; then
        echo "FATAL: $1 is not readable. It is a required secret and there is no default." >&2
        exit 1
    fi
    if [ ! -s "$1" ]; then
        echo "FATAL: $1 is empty. A blank password would create a role nothing can use." >&2
        exit 1
    fi
}

APP_PASSWORD_FILE=/run/secrets/db-password
MIGRATION_PASSWORD_FILE=/run/secrets/db-migration-password

require "$APP_PASSWORD_FILE"
require "$MIGRATION_PASSWORD_FILE"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_password="$(cat "$APP_PASSWORD_FILE")" \
     -v migration_password="$(cat "$MIGRATION_PASSWORD_FILE")" <<'SQL'
ALTER ROLE homeinv_app      WITH PASSWORD :'app_password';
ALTER ROLE homeinv_migrator WITH PASSWORD :'migration_password';
SQL

echo "Role passwords set for homeinv_app and homeinv_migrator."
