-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Instance-wide groundwork: extensions, the per-block schemas, and the grants
-- that give `homeinv_app` exactly what it needs and nothing more.
--
-- `_bootstrap` is not a building block, which is why its name starts with an
-- underscore. Schemas and grants belong to no block — every block needs them to
-- exist before its own first migration runs. The alternative, putting them in
-- whichever block happens to migrate first, would make that block's directory
-- the place where instance-wide decisions hide.

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
-- `ltree` carries the materialised location path (07 §7.4). Without it the
-- subtree query degrades to recursion, which is the thing the design avoids.
--
-- SCHEMA public is not decoration. An extension lands in whatever the running
-- role's default schema happens to be, and the migrator's is Flyway's own — so
-- without this the `ltree` type would be `flyway.ltree`, reachable only by
-- whoever has that schema on their search_path. It has to live somewhere every
-- role can see it, and `public` is the one schema that is always there.
CREATE EXTENSION IF NOT EXISTS ltree SCHEMA public;

-- `uuidv7()` is built into PostgreSQL 18 and needs no extension (ADR-0016).
-- If this fails, the server is older than the one this project supports, and
-- failing here is better than failing on the first INSERT.
DO $$
BEGIN
    PERFORM uuidv7();
EXCEPTION WHEN undefined_function THEN
    RAISE EXCEPTION
        'uuidv7() is missing: PostgreSQL 18 or newer is required (ADR-0016, 02 Constraints)';
END
$$;

-- ---------------------------------------------------------------------------
-- One schema per building block (07 §7.1, rule 6)
-- ---------------------------------------------------------------------------
-- Stage 0 creates only the schemas stage 0 fills. An empty schema passes every
-- check and documents nothing; the rest arrive with the blocks that own them.
CREATE SCHEMA IF NOT EXISTS tenancy;
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS locations;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS media;
CREATE SCHEMA IF NOT EXISTS audit;

-- Cross-cutting infrastructure. Owned by a mechanism rather than by a block,
-- which is why each has its own schema and belongs to none (07 §7.8).
CREATE SCHEMA IF NOT EXISTS outbox;
CREATE SCHEMA IF NOT EXISTS idempotency;

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
-- USAGE lets the role resolve names in the schema; it does not grant access to
-- anything in it. Table rights are granted per table by each block's migration,
-- so a new table is unreachable until somebody says what may be done with it.
GRANT USAGE ON SCHEMA
    tenancy, identity, catalog, locations, inventory, media, audit,
    outbox, idempotency
    TO homeinv_app, homeinv_readonly;

GRANT USAGE ON SCHEMA audit TO homeinv_housekeeping;

-- The application must never gain DDL rights, not even on its own schemas
-- (07 §7.5). Ownership stays with the migrator, which is inactive while the
-- application serves traffic (ADR-0041).
