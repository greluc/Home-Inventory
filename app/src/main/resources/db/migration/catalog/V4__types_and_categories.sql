-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The type system's tables, at the size stage 0 needs them.
--
-- Stage 0 has no *configurable* type system (REQ-CORE-020…032 are all stage 1),
-- but `inventory.item.item_type_version_id` is NOT NULL and REQ-CORE-002 makes
-- the type reference one of the five fixed fields. The tables therefore exist
-- from the start and carry exactly one built-in type per tenant, written at
-- tenant provisioning and not editable. Stage 1 adds `field_definition` and the
-- surface to edit them — new rows and one new table, no migration of these.
--
-- `field_definition` is deliberately absent: nothing at stage 0 reads it, and an
-- empty table documents nothing.

CREATE TABLE catalog.item_type (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)
    key             text NOT NULL,
    parent_id       uuid,
    kind            text NOT NULL CHECK (kind IN ('PHYSICAL','DIGITAL')),
    icon            text,
    -- True for the row every tenant gets at provisioning. It exists so the
    -- stage-1 editor can refuse to delete the type every item points at, rather
    -- than discovering the constraint violation.
    builtin         boolean NOT NULL DEFAULT false,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES catalog.item_type (tenant_id, id)
);

ALTER TABLE catalog.item_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.item_type FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.item_type
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE catalog.item_type_version (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    item_type_id    uuid NOT NULL,
    tenant_id       uuid NOT NULL,
    version_number  int  NOT NULL,
    json_schema     jsonb NOT NULL,
    published_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (item_type_id, version_number),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, item_type_id)
        REFERENCES catalog.item_type (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE catalog.item_type_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.item_type_version FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.item_type_version
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Categories of storage location. 07 §7.4 references this table without giving
-- its DDL, so it follows the shape of the table it sits beside rather than
-- inventing a second convention.
CREATE TABLE catalog.location_category (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    key             text NOT NULL,
    labels          jsonb NOT NULL DEFAULT '{}'::jsonb,
    icon            text,
    -- A category whose locations travel with their contents — a vehicle, a
    -- moving box. Stage 0 seeds stationary ones only; mobile locations are
    -- stage 2.
    is_mobile       boolean NOT NULL DEFAULT false,
    builtin         boolean NOT NULL DEFAULT false,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

ALTER TABLE catalog.location_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.location_category FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.location_category
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON
    catalog.item_type, catalog.item_type_version, catalog.location_category
    TO homeinv_app;
GRANT SELECT ON
    catalog.item_type, catalog.item_type_version, catalog.location_category
    TO homeinv_readonly;
