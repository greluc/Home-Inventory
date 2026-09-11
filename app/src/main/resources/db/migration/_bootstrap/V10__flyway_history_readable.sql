-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Lets the application read which migrations have run.
--
-- `api` and `worker` validate the schema version at startup and refuse to serve
-- against one this build does not expect (ADR-0041, REQ-SEC-101, 05 §5.11). The
-- fact they compare against lives in Flyway's own history table, in Flyway's own
-- schema, owned by `homeinv_migrator` — and until this migration `homeinv_app`
-- could not see it at all.
--
-- SELECT on one table, and USAGE on the schema that holds it. Reading which
-- migrations have run is not a privilege: it is metadata about the schema the
-- role already works in. What the role still cannot do is write to that table,
-- create anything in that schema, or reach anything else in it — so it cannot
-- claim a migration ran that did not.
GRANT USAGE ON SCHEMA flyway TO homeinv_app;
GRANT SELECT ON flyway.flyway_schema_history TO homeinv_app;
