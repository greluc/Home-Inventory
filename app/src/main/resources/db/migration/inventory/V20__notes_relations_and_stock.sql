-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Three things an item gains at stage 1: notes, relations to other items, and a
-- minimum stock (REQ-CORE-014, REQ-CORE-006, REQ-CORE-008).

-- ---------------------------------------------------------------------------
-- Notes (REQ-CORE-014)
-- ---------------------------------------------------------------------------
--
-- A column of its own rather than a field of the type system, because every item
-- has one whatever its type says: "where did I put the receipt" is not a property
-- of being a book. `description` is the one-line summary a listing shows; this is
-- the place a person writes a paragraph.
--
-- Limited Markdown, and the limitation is enforced where the value is written,
-- not where it is rendered: HTML is stripped server-side (REQ-CORE-014), so a
-- client that renders the text as HTML anyway cannot be made to execute
-- somebody's script.

ALTER TABLE inventory.item
    ADD COLUMN notes text,
    -- Long enough for a page of notes and short enough that nobody stores a file
    -- in it. The limit is stated here as well as in the request validation
    -- because a write that bypasses the application must not be the way around it.
    ADD CONSTRAINT item_notes_length CHECK (notes IS NULL OR length(notes) <= 20000);

-- ---------------------------------------------------------------------------
-- Minimum stock (REQ-CORE-008)
-- ---------------------------------------------------------------------------
--
-- An item with a minimum IS a consumable; there is no separate flag, because a
-- flag and a threshold would be two ways of saying the same thing and one of
-- them would eventually be wrong.

ALTER TABLE inventory.item
    ADD COLUMN minimum_stock numeric(19,4)
        CHECK (minimum_stock IS NULL OR minimum_stock >= 0);

-- "Which consumables are below their minimum" — the question a reminder asks,
-- and the reason this is an index rather than a scan.
CREATE INDEX item_below_minimum ON inventory.item (tenant_id)
    WHERE minimum_stock IS NOT NULL AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Relations between items (REQ-CORE-006)
-- ---------------------------------------------------------------------------
--
-- Directed, and read from both ends. "The lens is an accessory of the camera" is
-- one row; the camera's page shows it as "accessories" and the lens's page as
-- "accessory of", which is the same row read the other way round. Storing both
-- directions would be two rows that can disagree.

CREATE TABLE inventory.item_relation (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)
    source_id     uuid NOT NULL,
    target_id     uuid NOT NULL,
    relation_type text NOT NULL CHECK (relation_type IN
                      ('ACCESSORY_OF', 'PART_OF', 'REPLACEMENT_FOR', 'RELATED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    version       bigint NOT NULL DEFAULT 1,
    -- An item is not an accessory of itself. Checked here rather than only in the
    -- application, because it is a property of the row and not of a use case.
    CONSTRAINT item_relation_not_self CHECK (source_id <> target_id),
    UNIQUE (tenant_id, id),
    -- One relation of a kind between two items. Asked for twice, the second is
    -- the first (REQ-CORE-006).
    UNIQUE (tenant_id, source_id, target_id, relation_type),
    CONSTRAINT item_relation_source_same_tenant
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT item_relation_target_same_tenant
        FOREIGN KEY (tenant_id, target_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE inventory.item_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.item_relation FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.item_relation
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Both ends are read, so both are indexed. The unique constraint above already
-- covers the source; this is the other direction.
CREATE INDEX item_relation_by_target ON inventory.item_relation (tenant_id, target_id);

-- DELETE, because removing a relation is a deletion and nothing else: it carries
-- no history of its own, and the audit log records that it was removed.
GRANT SELECT, INSERT, DELETE ON inventory.item_relation TO homeinv_app;
GRANT SELECT ON inventory.item_relation TO homeinv_readonly;
