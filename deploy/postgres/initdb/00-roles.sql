-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later

CREATE ROLE homeinv_app        WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

CREATE ROLE homeinv_migrator   WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

CREATE ROLE homeinv_readonly   WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

CREATE ROLE homeinv_housekeeping WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

CREATE ROLE homeinv_bootstrap WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

GRANT homeinv_bootstrap TO homeinv_migrator;

CREATE ROLE homeinv_quota WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

GRANT homeinv_quota TO homeinv_migrator;

CREATE ROLE homeinv_tenant_state WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

GRANT homeinv_tenant_state TO homeinv_migrator;

ALTER ROLE homeinv_app          SET statement_timeout = '30s';
ALTER ROLE homeinv_app          SET lock_timeout = '5s';
ALTER ROLE homeinv_app          SET idle_in_transaction_session_timeout = '60s';

ALTER ROLE homeinv_readonly     SET statement_timeout = '30s';
ALTER ROLE homeinv_readonly     SET lock_timeout = '5s';
ALTER ROLE homeinv_readonly     SET idle_in_transaction_session_timeout = '60s';

ALTER ROLE homeinv_housekeeping SET statement_timeout = '30s';
ALTER ROLE homeinv_housekeeping SET lock_timeout = '15s';
ALTER ROLE homeinv_housekeeping SET idle_in_transaction_session_timeout = '60s';

DO $$
BEGIN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO homeinv_migrator', current_database());
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
