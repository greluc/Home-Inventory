-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- `updated_at` and `updated_by` for the two version tables (07 §7.1, rule 4).
--
-- Rule 4 has demanded `version` plus the four audit columns of every domain
-- table since the chapter was written, and said "a migration check enforces it".
-- Nothing did until 2026-09-13, and these two tables were among the five the
-- check found when it was finally written.
--
-- They are domain tables and not "issued, never edited": a version row is
-- updated twice over its life. `published_at` is stamped when a draft is
-- published, and `json_schema` is regenerated when a field is removed from a
-- version that had already been published — the tenant asked for the values to
-- be destroyed, and a schema still demanding a key nothing may carry would
-- refuse every subsequent write. Both are somebody's doing, and until now the
-- row did not record whose.
--
-- Existing rows get the moment this migration runs rather than a copy of
-- `created_at`, and that is a limit rather than a choice: copying needs an
-- UPDATE across every tenant, and the migrator is NOBYPASSRLS against FORCEd
-- policies, so the copy would match no row and report success. DDL is not
-- subject to a policy, which is why the column default reaches every row and the
-- backfill would not.

ALTER TABLE catalog.item_type_version
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_by uuid;

ALTER TABLE catalog.location_category_version
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_by uuid;
