-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Runs once, at database initialisation, as the cluster superuser.
--
-- Roles are here and not in a Flyway migration on purpose. Flyway runs as
-- `homeinv_migrator`, and a migrator that can create roles can create one with
-- BYPASSRLS — which is the single privilege this whole design is built to
-- withhold (07 §7.5). The four roles must therefore exist *before* the migrator
-- first connects, and the migrator must not be able to add a fifth.
--
-- Passwords are not set here. Every role authenticates by the mechanism the
-- deployment configures, and a password in a checked-in file is a password in
-- every clone of the repository (CLAUDE.md, Security).

-- The application. Reads and writes tenant data and nothing else.
--   NOBYPASSRLS is the load-bearing word in this file.
CREATE ROLE homeinv_app        WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

-- Owns the schema. Active only in the one-shot `migrate` service (ADR-0041),
-- never while the application serves traffic.
CREATE ROLE homeinv_migrator   WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

-- Reporting. SELECT only, and still subject to every policy.
CREATE ROLE homeinv_readonly   WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

-- DELETE on the retention-governed tables and nothing else. It exists because
-- REQ-SEC-069 denies the application DELETE on the audit log while REQ-PRIV-010
-- still requires retention to be enforced (ADR-0046).
CREATE ROLE homeinv_housekeeping WITH LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

-- Owns exactly one thing: the function that resolves a user's tenants during
-- login. It exists because SECURITY DEFINER does **not** bypass
-- `FORCE ROW LEVEL SECURITY` — a forced policy applies to the table owner too,
-- so a definer function owned by the migrator is still blocked by it.
--
-- The way out without BYPASSRLS is a role that has a policy of its own on the
-- one table, and nothing else at all. NOLOGIN, so nobody connects as it; the
-- application can EXECUTE the function but can never assume the role.
CREATE ROLE homeinv_bootstrap WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

-- The migrator has to be able to hand ownership of that function over.
GRANT homeinv_bootstrap TO homeinv_migrator;

-- Owns exactly one thing too: the function through which the instance operator
-- sets a tenant's quota. 07 §7.5 names this as the only sanctioned shape for
-- cross-tenant administration — "an explicit, logged SECURITY DEFINER function
-- with its own permission check, never BYPASSRLS" — and it is separate from
-- `homeinv_bootstrap` because that role reads and this one writes. A read-only
-- role that grew a write would be a role nobody could describe in one sentence
-- any more.
--
-- What it can reach is one column of one table that holds no domain data: how
-- much the INSTANCE allocates to a tenant. It cannot read an item, a location or
-- a membership, and the application gates the function on the instance-operator
-- entitlement before calling it (ADR-0057).
CREATE ROLE homeinv_quota WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

GRANT homeinv_quota TO homeinv_migrator;

-- Owns exactly one thing: the function through which the instance operator
-- suspends a tenant and lets it back in (`REQ-SEC-082`, `REQ-TEN-011`).
--
-- Separate from `homeinv_quota` for the same reason `homeinv_quota` is separate
-- from `homeinv_bootstrap`: one role, one sentence. This one may change a
-- tenant's lifecycle state and nothing else -- not a quota, not a membership,
-- not a row of anybody's data -- and the function it owns refuses every state
-- but ACTIVE and SUSPENDED, so it cannot start an erasure or end one either.
--
-- It needs SELECT beside UPDATE because the function reads the current state
-- first: suspending a tenant that is already being erased is refused, and a
-- check needs something to check.
CREATE ROLE homeinv_tenant_state WITH NOLOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;

GRANT homeinv_tenant_state TO homeinv_migrator;

-- The 30-second ceiling of `REQ-SEC-065`, set on the roles rather than in the
-- application's configuration, so that it holds for every connection including
-- the ones a psql session opens. A client can raise its own `statement_timeout`
-- within a session, which is why `homeinv_app` gets the lowest values and the
-- migrator gets none: a migration that rewrites a large table legitimately takes
-- longer than any request may.
--
-- `statement_timeout` bounds the query, which is what actually blocks a request
-- here. `lock_timeout` is lower on purpose — waiting for a lock is waiting for
-- another transaction, and thirty seconds of that turns one slow write into a
-- queue of them. `idle_in_transaction_session_timeout` is the one that protects
-- the database rather than the request: a transaction abandoned mid-flight holds
-- its locks and its snapshot until something closes it.
ALTER ROLE homeinv_app          SET statement_timeout = '30s';
ALTER ROLE homeinv_app          SET lock_timeout = '5s';
ALTER ROLE homeinv_app          SET idle_in_transaction_session_timeout = '60s';

ALTER ROLE homeinv_readonly     SET statement_timeout = '30s';
ALTER ROLE homeinv_readonly     SET lock_timeout = '5s';
ALTER ROLE homeinv_readonly     SET idle_in_transaction_session_timeout = '60s';

-- Housekeeping deletes in batches and may wait a little longer for a lock; it
-- still may not run a single statement for longer than a request may.
ALTER ROLE homeinv_housekeeping SET statement_timeout = '30s';
ALTER ROLE homeinv_housekeeping SET lock_timeout = '15s';
ALTER ROLE homeinv_housekeeping SET idle_in_transaction_session_timeout = '60s';

-- The migrator owns the schema, so it has to be able to create one — including
-- `flyway`, which is where the migration history lives. `NOCREATEDB` is about
-- creating DATABASES and says nothing about schemas inside one, and a role with
-- neither ends up where this deployment ended up on its first real start:
-- "permission denied for database homeinv", from Flyway, before the first
-- migration ran.
--
-- The grant existed in the TEST role script and only there, under a comment
-- saying the migrator must be able to create a schema — so every test passed
-- against a permission production did not give. `current_database()` rather than
-- a literal: the entrypoint runs this against whatever POSTGRES_DB names.
DO $$
BEGIN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO homeinv_migrator', current_database());
END
$$;

-- Nobody may CREATE in the public schema: a table created there by accident
-- would carry no policy at all. USAGE stays, and must - the `ltree` extension
-- lives there, and a role that cannot see the schema cannot use the type its
-- own columns are declared with.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
