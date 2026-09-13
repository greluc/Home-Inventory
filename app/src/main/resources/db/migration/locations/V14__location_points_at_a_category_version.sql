-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A location references a category VERSION, the way an item references a type
-- version (REQ-CORE-025, REQ-CORE-041, ADR-0020).
--
-- Categories became versioned in V13 and this is the other half: without it a
-- category's fields could be tightened under locations already written, which is
-- the one thing versioning exists to prevent. `category_id` goes, rather than
-- staying beside the new column — two references to the same thing are two
-- things that can disagree, and the version already names its category.
--
-- The API is unchanged by this. A client picks a CATEGORY, because that is what
-- a person chooses; the application resolves it to the published version at the
-- moment of the write, exactly as it does for an item's type.

-- ---------------------------------------------------------------------------
-- 1. Every existing category gets its first version
-- ---------------------------------------------------------------------------
--
-- Published immediately: these are the shipped categories, they carry no fields
-- yet, and a draft nothing may reference would leave every existing location
-- with nowhere to point.

INSERT INTO catalog.location_category_version
    (location_category_id, tenant_id, version_number, json_schema, published_at, created_by)
SELECT c.id, c.tenant_id, 1, '{}'::jsonb, now(), c.created_by
FROM catalog.location_category c;

-- ---------------------------------------------------------------------------
-- 2. The column, backfilled, then made mandatory
-- ---------------------------------------------------------------------------

ALTER TABLE locations.location ADD COLUMN category_version_id uuid;

UPDATE locations.location l
SET category_version_id = v.id
FROM catalog.location_category_version v
WHERE v.tenant_id = l.tenant_id
  AND v.location_category_id = l.category_id
  AND v.version_number = 1;

ALTER TABLE locations.location ALTER COLUMN category_version_id SET NOT NULL;

ALTER TABLE locations.location
    ADD CONSTRAINT location_category_version_same_tenant
        FOREIGN KEY (tenant_id, category_version_id)
        REFERENCES catalog.location_category_version (tenant_id, id);

-- ---------------------------------------------------------------------------
-- 3. The old reference goes
-- ---------------------------------------------------------------------------

ALTER TABLE locations.location DROP COLUMN category_id;

CREATE INDEX location_category_version ON locations.location
    (tenant_id, category_version_id);

-- ---------------------------------------------------------------------------
-- 4. The shape of `attributes`, at the level the database can hold it
-- ---------------------------------------------------------------------------
--
-- 07 §7.3 names three places validation happens and calls the redundancy
-- deliberate: the client against the shipped schema, the application against the
-- same schema, and the database against the basic shape — "protection against
-- writes that bypass the application". The third one was missing on both tables
-- that carry attributes, which made the sentence describe an intention.
--
-- It cannot check a field against its definition; a CHECK constraint sees one
-- row and no catalogue. It checks what is true of every attribute set whatever
-- the type says: an object, keys shaped like field keys, and a size a person
-- could have typed. The 64 KiB is this project's number and nothing external's —
-- the largest declared field is `multiline`, and an attribute set that exceeds
-- this is a file being smuggled into a text column rather than a description.

ALTER TABLE locations.location
    ADD CONSTRAINT location_attributes_shape
        CHECK (catalog.attribute_shape_is_valid(attributes));
