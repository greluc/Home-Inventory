-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The index side table of variant D (07 §7.3, ADR-0004, REQ-CORE-013,
-- REQ-CORE-032).
--
-- JSONB stays the source of truth. This table mirrors the fields a tenant marked
-- `searchable`, `sortable` or `facetable`, written by the application in the same
-- transaction as the item, so an attribute filter is transactionally exact — and
-- stays exact while OpenSearch is down, or absent, which in the `minimal` profile
-- it permanently is.
--
-- It is not a cache. A cache may be stale; this may not, and that is the whole
-- difference between it and the search index beside it.

CREATE TABLE inventory.item_attr_index (
    tenant_id   uuid    NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)
    item_id     uuid    NOT NULL,
    field_key   text    NOT NULL,
    -- One column per storage class rather than one `text` column with a cast at
    -- query time: a cast in the predicate is an index nobody uses, and this
    -- table exists to be used.
    num_value   numeric(38,10),
    text_value  text,
    date_value  timestamptz,
    bool_value  boolean,
    ref_value   uuid,
    -- ISO 4217 for `money`, the unit symbol for `quantity` (REQ-CORE-032).
    -- Without it `SUM(num_value)` would add euros to dollars and grams to
    -- kilograms and return a plausible wrong number; with it, every aggregation
    -- over such a field groups by the unit and a mixed total is never produced.
    unit_value  text,
    PRIMARY KEY (item_id, field_key),
    CONSTRAINT item_attr_index_item_same_tenant
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE inventory.item_attr_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.item_attr_index FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.item_attr_index
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Partial, one per storage class: a field is of one type, so every row has one
-- of these set and the others null, and a full index would be mostly nulls.
CREATE INDEX iai_num  ON inventory.item_attr_index (tenant_id, field_key, num_value)
    WHERE num_value IS NOT NULL;
CREATE INDEX iai_text ON inventory.item_attr_index (tenant_id, field_key, text_value)
    WHERE text_value IS NOT NULL;
CREATE INDEX iai_date ON inventory.item_attr_index (tenant_id, field_key, date_value)
    WHERE date_value IS NOT NULL;
CREATE INDEX iai_ref  ON inventory.item_attr_index (tenant_id, field_key, ref_value)
    WHERE ref_value IS NOT NULL;
CREATE INDEX iai_unit ON inventory.item_attr_index (tenant_id, field_key, unit_value, num_value)
    WHERE unit_value IS NOT NULL;
-- "Everything this item projects", which is what a write deletes before it
-- inserts and what a soft deletion removes (REQ-CORE-013).
CREATE INDEX iai_item ON inventory.item_attr_index (tenant_id, item_id);

-- DELETE, unlike anywhere else in this schema. A projection is derived data with
-- no history of its own: a write replaces an item's rows, and trashing an item
-- removes them in the same transaction so a trashed item stops answering filters
-- for the whole retention period (07 §7.3).
GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.item_attr_index TO homeinv_app;
GRANT SELECT ON inventory.item_attr_index TO homeinv_readonly;

-- The same shape guard the location tree gets in V14, for the same reason and
-- from the same function (07 §7.3, validation place three).
ALTER TABLE inventory.item
    ADD CONSTRAINT item_attributes_shape
        CHECK (catalog.attribute_shape_is_valid(attributes));
