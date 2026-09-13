-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The configurable type system (REQ-CORE-020…032, REQ-CORE-041…047, ADR-0020).
--
-- Stage 0 shipped `item_type` and `item_type_version` with one built-in row per
-- tenant and no way to edit them; V4 said so and said `field_definition` was
-- deliberately absent because nothing read it. This is the table it named, plus
-- the two things a field needs to be more than a name: a value list to draw an
-- `enum` from, and a version to hang off.
--
-- WHY LOCATION CATEGORIES ARE VERSIONED TOO (decided 2026-09-13, @greluc).
--
-- `inventory.item` references an item type VERSION, so a type change devalues no
-- existing item (REQ-CORE-025). `locations.location` referenced a category
-- directly, and REQ-CORE-041 gives categories their own fields — which would
-- have made a category the one configurable thing whose every edit reached back
-- into data already written, tightening a constraint under rows nobody was
-- looking at. Categories are now versioned the same way, they carry their field
-- definitions on the version, and `locations.location` points at the version
-- (V14). One shape, one generator, one validator.

-- ---------------------------------------------------------------------------
-- Value lists: the source an `enum` field draws from
-- ---------------------------------------------------------------------------
--
-- Independent objects rather than a list inside one field's `constraints`,
-- because REQ-CORE-029 asks for exactly that: "condition" is used by the book
-- type and the tool type and the furniture type, and three copies of it would be
-- three things to edit and two chances to forget.

CREATE TABLE catalog.value_list (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)
    key             text NOT NULL CHECK (length(btrim(key)) > 0),
    -- Multilingual, like every other name a tenant chooses: the server does not
    -- know which language the reader prefers and must not pick one (REQ-NFR-032).
    labels          jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(labels) = 'object'),
    -- True for the lists provisioning writes. A built-in list may be extended
    -- and its entries relabelled; it may not be deleted, because a shipped type
    -- template points at it.
    builtin         boolean NOT NULL DEFAULT false,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

ALTER TABLE catalog.value_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.value_list FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.value_list
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE catalog.value_list_entry (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    value_list_id   uuid NOT NULL,
    -- What lands in `item.attributes`. Stable: relabelling an entry must not
    -- rewrite every item that chose it, which is the whole point of separating
    -- the value from its label.
    value           text NOT NULL CHECK (length(btrim(value)) > 0),
    labels          jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(labels) = 'object'),
    display_order   int NOT NULL DEFAULT 0,
    -- An archived entry stays valid for the items that already carry it and is
    -- not offered for new ones. Deleting it would invalidate written data, which
    -- ADR-0020 forbids for exactly the reason it forbids dropping a field.
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (value_list_id, value),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, value_list_id)
        REFERENCES catalog.value_list (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE catalog.value_list_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.value_list_entry FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.value_list_entry
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX value_list_entry_list ON catalog.value_list_entry
    (tenant_id, value_list_id, display_order, value);

-- ---------------------------------------------------------------------------
-- Location category versions
-- ---------------------------------------------------------------------------
--
-- The mirror of `catalog.item_type_version`, column for column, because a
-- category and a type are the same idea pointed at different things.

CREATE TABLE catalog.location_category_version (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    location_category_id uuid NOT NULL,
    tenant_id            uuid NOT NULL,
    version_number       int  NOT NULL CHECK (version_number > 0),
    -- Generated from the field definitions below, never hand-written
    -- (REQ-CORE-027). Empty object until the first field exists, which is a
    -- schema that accepts an empty attribute set and nothing else.
    json_schema          jsonb NOT NULL DEFAULT '{}'::jsonb
                         CHECK (jsonb_typeof(json_schema) = 'object'),
    -- An unpublished version is a draft: editable, and referenced by nothing.
    -- Publishing freezes it, and only a published version may be referenced.
    published_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    version              bigint NOT NULL DEFAULT 1,
    UNIQUE (location_category_id, version_number),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, location_category_id)
        REFERENCES catalog.location_category (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE catalog.location_category_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.location_category_version FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.location_category_version
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- `item_type_version.json_schema` was NOT NULL with no default and no shape
-- check, which V4 could afford while provisioning wrote the only row. Both
-- halves now come from the same generator, so both tables state the same thing.
ALTER TABLE catalog.item_type_version
    ALTER COLUMN json_schema SET DEFAULT '{}'::jsonb,
    ADD CONSTRAINT item_type_version_schema_is_object
        CHECK (jsonb_typeof(json_schema) = 'object'),
    ADD CONSTRAINT item_type_version_number_positive
        CHECK (version_number > 0);

-- ---------------------------------------------------------------------------
-- Field definitions
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.field_definition (
    id                           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                    uuid NOT NULL,
    -- Exactly one owner. Two nullable references rather than one polymorphic
    -- pair, so the database keeps its foreign keys: a polymorphic `owner_id`
    -- with an `owner_kind` beside it is a reference the database cannot check,
    -- and every such column in this schema is a composite key for precisely
    -- that reason (07 §7.5).
    item_type_version_id         uuid,
    location_category_version_id uuid,
    key                          text NOT NULL CHECK (key ~ '^[a-z][A-Za-z0-9]*$'),
    -- The closed list of REQ-CORE-022. Closed is the point: ADR-0020 draws the
    -- line at declarative field definitions, and a type the validator does not
    -- know is the first step across it.
    data_type                    text NOT NULL CHECK (data_type IN (
                                     'text', 'multiline', 'integer', 'decimal',
                                     'money', 'boolean', 'date', 'datetime',
                                     'enum', 'multi-enum', 'url', 'email',
                                     'quantity', 'reference', 'secret', 'file')),
    labels                       jsonb NOT NULL DEFAULT '{}'::jsonb
                                 CHECK (jsonb_typeof(labels) = 'object'),
    help_texts                   jsonb NOT NULL DEFAULT '{}'::jsonb
                                 CHECK (jsonb_typeof(help_texts) = 'object'),
    required                     boolean NOT NULL DEFAULT false,
    default_value                jsonb,
    -- {"pattern":"…","min":0,"max":10,"minLength":1,"maxLength":80,"unit":"g",
    --  "referenceKind":"item"} — the declarative half of ADR-0020's "permitted"
    -- column, and nothing that evaluates.
    constraints                  jsonb CHECK (constraints IS NULL
                                     OR jsonb_typeof(constraints) = 'object'),
    -- The source of an `enum` or `multi-enum` field (REQ-CORE-029).
    value_list_id                uuid,
    -- One condition over ONE other field of the same item (REQ-CORE-028):
    -- {"field":"hasWarranty","operator":"equals","value":true}. No conjunction,
    -- no nesting, no reference to another item — those are the loops and the
    -- database access ADR-0020 excludes.
    visibility                   jsonb CHECK (visibility IS NULL
                                     OR jsonb_typeof(visibility) = 'object'),
    field_group                  text,
    display_order                int NOT NULL DEFAULT 0,
    searchable                   boolean NOT NULL DEFAULT false,
    sortable                     boolean NOT NULL DEFAULT false,
    facetable                    boolean NOT NULL DEFAULT false,
    -- Encrypted at rest and gated by a permission (ADR-0019). A `secret` field
    -- is sensitive by definition; the flag exists so an ordinary field can be
    -- too — a purchase price a tenant does not want every member to read.
    sensitive                    boolean NOT NULL DEFAULT false,
    -- Hidden, and its values kept (REQ-CORE-026). Final removal is a separate
    -- operation that first shows what it would touch.
    deprecated_at                timestamptz,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    created_by                   uuid, updated_by uuid,
    version                      bigint NOT NULL DEFAULT 1,
    CONSTRAINT field_definition_one_owner
        CHECK (num_nonnulls(item_type_version_id, location_category_version_id) = 1),
    -- `enum` and `multi-enum` need a list; nothing else may name one, because a
    -- value list on a `date` field is a definition that reads as meaning
    -- something and means nothing.
    CONSTRAINT field_definition_value_list_only_for_enums
        CHECK ((data_type IN ('enum', 'multi-enum')) = (value_list_id IS NOT NULL)),
    UNIQUE (item_type_version_id, key),
    UNIQUE (location_category_version_id, key),
    FOREIGN KEY (tenant_id, item_type_version_id)
        REFERENCES catalog.item_type_version (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_category_version_id)
        REFERENCES catalog.location_category_version (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, value_list_id)
        REFERENCES catalog.value_list (tenant_id, id)
);

ALTER TABLE catalog.field_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.field_definition FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.field_definition
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The order a form is rendered in, which is the order every read of a version
-- wants: group, then position, then key.
CREATE INDEX field_definition_by_item_type_version ON catalog.field_definition
    (tenant_id, item_type_version_id, field_group, display_order, key)
    WHERE item_type_version_id IS NOT NULL;
CREATE INDEX field_definition_by_category_version ON catalog.field_definition
    (tenant_id, location_category_version_id, field_group, display_order, key)
    WHERE location_category_version_id IS NOT NULL;
-- "Which definitions draw on this value list" — asked before a list is archived.
CREATE INDEX field_definition_by_value_list ON catalog.field_definition
    (tenant_id, value_list_id) WHERE value_list_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The shape every attribute set has, whatever its type says
-- ---------------------------------------------------------------------------
--
-- The third of the three validation places 07 §7.3 names, and the only one that
-- still holds when a write bypasses the application. It cannot consult a field
-- definition — a CHECK constraint sees one row and no catalogue — so it checks
-- what is true of every attribute set: an object, keys shaped like field keys,
-- and a size a person could have typed.
--
-- IMMUTABLE and written in SQL because a CHECK constraint may call nothing else.
-- It lives in `catalog` because the key shape is the catalog's rule; `inventory`
-- and `locations` already reference this schema for their type and category
-- keys, so this adds no dependency that was not there (04 §4.5).

CREATE FUNCTION catalog.attribute_shape_is_valid(attrs jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE RETURNS NULL ON NULL INPUT AS $$
    SELECT jsonb_typeof(attrs) = 'object'
       AND octet_length(attrs::text) <= 65536
       AND NOT EXISTS (
               SELECT 1 FROM jsonb_object_keys(attrs) AS k
               WHERE k !~ '^[a-z][A-Za-z0-9]*$');
$$;

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
--
-- DELETE on `field_definition` and on nothing else here. REQ-CORE-026 makes the
-- final removal of a deprecated field a real operation that shows its blast
-- radius first, and a right the application does not hold is a right it cannot
-- be tricked into using. Types, versions, lists and entries are archived rather
-- than removed, so they need no such grant.

GRANT SELECT, INSERT, UPDATE ON
    catalog.value_list, catalog.value_list_entry,
    catalog.location_category_version, catalog.field_definition
    TO homeinv_app;
GRANT DELETE ON catalog.field_definition TO homeinv_app;
GRANT SELECT ON
    catalog.value_list, catalog.value_list_entry,
    catalog.location_category_version, catalog.field_definition
    TO homeinv_readonly;
