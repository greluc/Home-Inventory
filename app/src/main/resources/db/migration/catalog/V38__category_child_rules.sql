-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- What a category permits underneath it (REQ-CORE-047).
--
-- "A category may restrict its permitted child categories (optional)" — the
-- optional is load-bearing: a category with no rows here permits everything, and
-- that is the state every shipped category starts in. A tenant that wants "only
-- a shelf goes in a room" says so by adding rows; one that does not is never
-- asked.
--
-- A table rather than an array column on `location_category`. An array of ids
-- cannot carry a foreign key, and 07 §7.5 asks for composite, tenant-qualified
-- references between tenant-scoped tables precisely so that a rule cannot come
-- to name a category of another tenant.
--
-- It belongs to the CATEGORY and not to its version. A version freezes the field
-- schema of what a location stores (ADR-0020); this says where a location may
-- sit, which is not data on the location at all — and a tenant tightening the
-- rule must not have to publish a new version of everything underneath it.

CREATE TABLE catalog.location_category_child (
    tenant_id           uuid NOT NULL,
    parent_category_id  uuid NOT NULL,
    child_category_id   uuid NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    PRIMARY KEY (tenant_id, parent_category_id, child_category_id),
    CONSTRAINT location_category_child_parent_same_tenant
        FOREIGN KEY (tenant_id, parent_category_id)
        REFERENCES catalog.location_category (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT location_category_child_child_same_tenant
        FOREIGN KEY (tenant_id, child_category_id)
        REFERENCES catalog.location_category (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE catalog.location_category_child ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.location_category_child FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.location_category_child
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- No `version` and no audit columns beyond `created_*`: this is a rule that
-- exists or does not, and there is nothing to edit in one row (07 §7.1, rule 4 —
-- the "issued, never edited" kind). Withdrawing a rule is a DELETE, which is why
-- the grant below has one: a rule is configuration, not a record of an event,
-- and a tombstoned restriction would still have to be read to be ignored.
GRANT SELECT, INSERT, DELETE ON catalog.location_category_child TO homeinv_app;
GRANT SELECT ON catalog.location_category_child TO homeinv_readonly;
