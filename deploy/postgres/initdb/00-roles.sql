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

-- Nobody gets the public schema. A table created there by accident would carry
-- no policy at all.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
