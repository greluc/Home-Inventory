-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- `updated_at` and `updated_by` for tag assignments (07 §7.1, rule 4).
--
-- An assignment looks like a link that is only ever made or broken, and it is
-- not: merging two tags rewrites `tag_id` on every assignment of the losing tag
-- (REQ-CORE-063), which is an edit with an author. The row bumped its `version`
-- for it and recorded nobody.
--
-- The default on existing rows is the moment this runs, for the reason
-- `V39` gives: a copy of `created_at` needs an UPDATE the migrator cannot make
-- through a FORCEd policy.

ALTER TABLE tagging.tag_assignment
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_by uuid;
