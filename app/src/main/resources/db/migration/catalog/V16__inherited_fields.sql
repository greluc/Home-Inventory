-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Inheritance, materialised (REQ-CORE-024, decided 2026-09-13 by @greluc).
--
-- A type may inherit from another and may only tighten what it inherits. Two
-- ways of realising that were on the table: resolve the parent chain on every
-- read, or copy the parent's fields into the child's version when that version
-- is published. The copy won, and the reason is REQ-CORE-025.
--
-- Resolved at read, a published version is not frozen: the parent publishes a
-- new version, and the child's schema changes underneath items already written.
-- "A type change devalues no existing items" would then rest on a rule every
-- future query has to remember, which is the same trap 07 §7.3 describes for the
-- attribute index. Copied at publish, a version is what a version is meant to
-- be — a complete snapshot. One table holds the answer, the generated schema and
-- `fields()` agree by construction, and a client rendering a form sees exactly
-- the set it must fill in.
--
-- The price is the same definition stored more than once. That is not
-- duplication of a fact: each copy belongs to a different frozen version, and
-- they are allowed to differ, because the child may have tightened it.

-- The target of the composite reference below. `field_definition` had no such
-- key, because until now nothing referenced a field.
ALTER TABLE catalog.field_definition ADD CONSTRAINT field_definition_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE catalog.field_definition
    -- The definition this one was copied from, or null when the version declares
    -- it itself. An editor needs the distinction: an inherited field may be
    -- tightened and may not be removed, because removing it would loosen the
    -- type below what it inherits.
    ADD COLUMN inherited_from uuid,
    ADD CONSTRAINT field_definition_inherited_same_tenant
        FOREIGN KEY (tenant_id, inherited_from)
        REFERENCES catalog.field_definition (tenant_id, id);

CREATE INDEX field_definition_inherited ON catalog.field_definition
    (tenant_id, inherited_from) WHERE inherited_from IS NOT NULL;
