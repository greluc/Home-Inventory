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

-- Nobody may CREATE in the public schema: a table created there by accident
-- would carry no policy at all. USAGE stays, and must - the `ltree` extension
-- lives there, and a role that cannot see the schema cannot use the type its
-- own columns are declared with.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
