-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Tags: tenant-wide labels that apply to items AND to places
-- (REQ-CORE-060…063, 04 §4.3).
--
-- Why a block of its own rather than a column on each: a tag is a thing a tenant
-- creates, renames, colours and merges, and it hangs on two different aggregates.
-- A `text[]` on `item` would make "rename this tag" an update of every row that
-- carries it and "which items are tagged X" a scan, and it could not be attached
-- to a location at all without a second copy of the same idea.

-- The block's own schema. V1 creates only the schemas stage 0 fills and says the
-- rest arrive with the blocks that own them; this is one of those.
CREATE SCHEMA IF NOT EXISTS tagging;
GRANT USAGE ON SCHEMA tagging TO homeinv_app, homeinv_readonly;

CREATE TABLE tagging.tag_group (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)
    key             text NOT NULL CHECK (key ~ '^[a-z][A-Za-z0-9-]*$'),
    labels          jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(labels) = 'object'),
    -- A group whose tags are mutually exclusive: "condition" is one of new, used
    -- or broken, never two of them (REQ-CORE-061). Declared here rather than
    -- checked in code alone, because it is a property of the group.
    exclusive       boolean NOT NULL DEFAULT false,
    display_order   int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

ALTER TABLE tagging.tag_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE tagging.tag_group FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tagging.tag_group
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tagging.tag (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    -- The name IS the identity a person uses, so it is unique per tenant and
    -- case-insensitively so: "Fragile" and "fragile" are one tag, and a tenant
    -- that ends up with both has a merge to do for no reason.
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    tag_group_id    uuid,
    -- REQ-CORE-062. Hex or nothing: a colour a client cannot render is worse
    -- than none, and "red" means a different red in every runtime.
    colour          text CHECK (colour IS NULL OR colour ~ '^#[0-9a-fA-F]{6}$'),
    icon            text,
    -- A merged tag leaves a tombstone pointing at what it became, so a client
    -- holding the old id is redirected rather than answered "not found"
    -- (REQ-CORE-063).
    merged_into     uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, tag_group_id)
        REFERENCES tagging.tag_group (tenant_id, id),
    FOREIGN KEY (tenant_id, merged_into)
        REFERENCES tagging.tag (tenant_id, id)
);

ALTER TABLE tagging.tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE tagging.tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tagging.tag
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Unique among the tags that still exist as themselves: a merged one keeps its
-- name so history reads correctly, and must not block the name it merged into.
CREATE UNIQUE INDEX tag_name_unique ON tagging.tag (tenant_id, lower(name))
    WHERE merged_into IS NULL;
CREATE INDEX tag_by_group ON tagging.tag (tenant_id, tag_group_id)
    WHERE merged_into IS NULL;

CREATE TABLE tagging.tag_assignment (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    tag_id          uuid NOT NULL,
    -- Exactly one target, in two columns rather than a polymorphic pair: a
    -- composite foreign key per kind is one the database checks, and
    -- `(owner_kind, owner_id)` is one it cannot (07 §7.5).
    item_id         uuid,
    location_id     uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    version         bigint NOT NULL DEFAULT 1,
    CONSTRAINT tag_assignment_one_target
        CHECK (num_nonnulls(item_id, location_id) = 1),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, tag_id)
        REFERENCES tagging.tag (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_id)
        REFERENCES locations.location (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE tagging.tag_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tagging.tag_assignment FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tagging.tag_assignment
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- One assignment per tag and target. A merge moves assignments and would
-- otherwise produce a duplicate wherever both tags were on the same thing;
-- the merge relies on this index to notice (REQ-CORE-063).
CREATE UNIQUE INDEX tag_assignment_unique_item ON tagging.tag_assignment
    (tenant_id, tag_id, item_id) WHERE item_id IS NOT NULL;
CREATE UNIQUE INDEX tag_assignment_unique_location ON tagging.tag_assignment
    (tenant_id, tag_id, location_id) WHERE location_id IS NOT NULL;
-- "Which tags does this thing carry", which is every read of an item or a place.
CREATE INDEX tag_assignment_by_item ON tagging.tag_assignment (tenant_id, item_id)
    WHERE item_id IS NOT NULL;
CREATE INDEX tag_assignment_by_location ON tagging.tag_assignment (tenant_id, location_id)
    WHERE location_id IS NOT NULL;

-- DELETE, because unassigning a tag is a deletion and nothing else: an
-- assignment carries no history of its own, and the audit log records that it
-- was removed.
GRANT SELECT, INSERT, UPDATE ON tagging.tag_group, tagging.tag TO homeinv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tagging.tag_assignment TO homeinv_app;
GRANT SELECT ON tagging.tag_group, tagging.tag, tagging.tag_assignment TO homeinv_readonly;
