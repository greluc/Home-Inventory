-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The item (07 §7.3).
--
-- Stage 0 fills the five fixed fields of REQ-CORE-002 and leaves `attributes`
-- empty: the type system that would give it meaning is stage 1. The column
-- exists now because it is the aggregate's own storage, and adding it later
-- would rewrite every row in the largest table in the system.

CREATE TABLE inventory.item (
    -- The client may supply the id, because an offline client must be able to
    -- create an item without asking (ADR-0016, REQ-CORE-001).
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid NOT NULL,
    item_type_version_id uuid NOT NULL,
    name                text NOT NULL CHECK (length(btrim(name)) > 0),
    description         text,
    kind                text NOT NULL CHECK (kind IN ('PHYSICAL','DIGITAL')),
    location_id         uuid,
    quantity            numeric(19,4) NOT NULL DEFAULT 1 CHECK (quantity >= 0),
    quantity_unit       text,
    lifecycle_state     text NOT NULL DEFAULT 'ACTIVE',
    attributes          jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- GENERATED, so they cannot drift from the row they describe. Two columns,
    -- one per shipped UI language: 'simple' does no stemming at all, and this is
    -- the only full-text mechanism at stage 0 (ADR-0047, REQ-SRCH-001).
    search_vector_de    tsvector GENERATED ALWAYS AS (
                            to_tsvector('german',
                                coalesce(name, '') || ' ' || coalesce(description, ''))
                        ) STORED,
    search_vector_en    tsvector GENERATED ALWAYS AS (
                            to_tsvector('english',
                                coalesce(name, '') || ' ' || coalesce(description, ''))
                        ) STORED,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid, updated_by uuid,
    deleted_at          timestamptz,
    version             bigint NOT NULL DEFAULT 1,
    CONSTRAINT physical_needs_location
        CHECK (kind <> 'PHYSICAL' OR location_id IS NOT NULL OR deleted_at IS NOT NULL),
    UNIQUE (tenant_id, id),
    -- Composite, so a reference cannot cross a tenant boundary even though
    -- foreign key checks bypass row security. This is the gap RLS does not
    -- close, and the reason every cross-aggregate key carries the tenant
    -- (07 §7.5).
    CONSTRAINT item_location_same_tenant
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES locations.location (tenant_id, id),
    CONSTRAINT item_type_version_same_tenant
        FOREIGN KEY (tenant_id, item_type_version_id)
        REFERENCES catalog.item_type_version (tenant_id, id)
);

ALTER TABLE inventory.item ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.item
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX item_attributes_gin ON inventory.item USING gin (attributes jsonb_path_ops);
CREATE INDEX item_tenant_updated ON inventory.item (tenant_id, updated_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX item_search_fts_de ON inventory.item USING gin (search_vector_de);
CREATE INDEX item_search_fts_en ON inventory.item USING gin (search_vector_en);

-- Cursor pagination sorts by (created_at, id) — REQ-SRCH-009 forbids offsets,
-- and without this index the keyset scan degrades into the sort it replaces.
CREATE INDEX item_cursor ON inventory.item (tenant_id, created_at, id)
    WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON inventory.item TO homeinv_app;
GRANT SELECT ON inventory.item TO homeinv_readonly;
