#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Gives the two roles that log in their passwords, from the mounted secrets.
#
# `00-roles.sql` deliberately sets none: a password in a checked-in file is a
# password in every clone of the repository. It is therefore also a script that
# leaves the cluster in a state nothing can connect to, which is why this one
# exists and runs straight after it.
#
# Both values are read from files and passed to psql as *variables*, never
# interpolated into SQL text here — a password containing a quote would
# otherwise end the string and change the statement.
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

# homeinv_readonly, homeinv_housekeeping and homeinv_bootstrap deliberately keep
# no password. The first two are for a human or a job that brings its own
# credential; the third is NOLOGIN and exists only to own one function.
echo "Role passwords set for homeinv_app and homeinv_migrator."
