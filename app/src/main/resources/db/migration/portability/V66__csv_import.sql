-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-PORT-001, REQ-PORT-002, REQ-PORT-008.
--
-- The other kind of import. An archive comes from this application and needs no
-- mapping; a CSV comes from somewhere else and is nothing but mapping. Both are
-- an `import_job`, because a job is a job -- uploaded, queued, read by the
-- worker, reported on -- and a second table would duplicate the state machine,
-- the claiming and the report for one column's worth of difference.

ALTER TABLE portability.import_job
    -- What was uploaded. `ARCHIVE` is this application's own ZIP; `CSV` is a
    -- file from another system, read through a mapping profile.
    ADD COLUMN kind text NOT NULL DEFAULT 'ARCHIVE'
        CHECK (kind IN ('ARCHIVE', 'CSV')),

    -- Which profile maps its columns. A built-in one is named by key
    -- (`homebox`, `inventree`) and lives in code; a tenant's own is a row in
    -- `mapping_profile` and is named by the same column, by its key.
    ADD COLUMN profile_key text
        CHECK (profile_key IS NULL OR length(profile_key) BETWEEN 1 AND 60),

    -- A CSV without a profile cannot be read, and an archive with one would be
    -- ignoring it. Refused here rather than discovered by the worker, so the
    -- request that made the mistake is the one that hears about it.
    ADD CONSTRAINT import_job_csv_needs_a_profile
        CHECK ((kind = 'CSV') = (profile_key IS NOT NULL));

COMMENT ON COLUMN portability.import_job.kind IS
    'ARCHIVE (this application''s own ZIP) or CSV (another system''s file, read through a profile).';

-- -----------------------------------------------------------------------------
-- MAPPING PROFILES
--
-- A tenant's own. The two that SHIP -- Homebox and InvenTree (REQ-PORT-002) --
-- are constants in code rather than rows: they are the same on every instance,
-- a row per tenant per profile would be provisioning writing the same thing a
-- thousand times, and a built-in nobody can edit is exactly what a constant is.
-- A tenant that needs one of them changed copies it into a row of its own.
-- -----------------------------------------------------------------------------
CREATE TABLE portability.mapping_profile (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    key             text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]*$' AND length(key) <= 60),
    name            text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),

    -- Where the file comes from, for the record and for REQ-PORT-008's
    -- provenance: 'homebox', 'inventree', 'spreadsheet', whatever the tenant
    -- calls it.
    source          text NOT NULL CHECK (length(btrim(source)) BETWEEN 1 AND 100),

    -- WHICH COLUMN MEANS WHAT. A JSON object from the CSV's own header to the
    -- target field: {"HB.name": "name", "HB.location": "location"}. Data, not
    -- code (ADR-0020): a mapping is a set of pairs and nothing in it is allowed
    -- to be an expression, a formula or a rule.
    column_map      jsonb NOT NULL DEFAULT '{}'::jsonb
                    CHECK (jsonb_typeof(column_map) = 'object'),

    -- A CSV rarely says which currency a price is in, and guessing per row is
    -- how a household ends up with three. One currency per profile, stated.
    default_currency varchar(3) CHECK (default_currency IS NULL
                     OR default_currency ~ '^[A-Z]{3}$'),

    -- Which item type the rows are written against, by key. Null means the
    -- tenant's built-in `general`.
    item_type_key   text CHECK (item_type_key IS NULL OR length(item_type_key) <= 64),

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

ALTER TABLE portability.mapping_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE portability.mapping_profile FORCE ROW LEVEL SECURITY;

CREATE POLICY mapping_profile_tenant_isolation ON portability.mapping_profile
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON portability.mapping_profile TO homeinv_app;
GRANT SELECT ON portability.mapping_profile TO homeinv_readonly;

COMMENT ON TABLE portability.mapping_profile IS
    'A tenant''s own mapping from a CSV''s columns to item fields (REQ-PORT-001). The Homebox and '
    'InvenTree profiles ship as constants in code and are not rows here.';

-- -----------------------------------------------------------------------------
-- WHERE AN IMPORTED ITEM CAME FROM
--
-- REQ-PORT-008: an imported item keeps its provenance -- the source, the moment
-- and the key it had over there. Its own table rather than columns on
-- `inventory.item`, for two reasons: it is portability's vocabulary and would
-- otherwise sit on every item that was never imported, and an item can be
-- re-imported, which is a second row here rather than an overwrite of the first.
--
-- `source_key` is what the other system called the row -- Homebox's
-- `HB.import_ref`, InvenTree's `IPN`. It is what makes a second import of the
-- same file an update rather than a duplicate.
--
-- APPEND-ONLY, like every other record of something having happened. Importing
-- the same file twice writes a second row rather than editing the first, so the
-- table is the history REQ-PORT-008 asks the provenance to be visible in --
-- which is also why it carries no `version` and no `updated_*`: nothing here is
-- ever edited, and a column for who edited it would name nobody.
-- -----------------------------------------------------------------------------
CREATE TABLE portability.import_provenance (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    item_id         uuid NOT NULL,
    import_job_id   uuid NOT NULL,

    source          text NOT NULL CHECK (length(btrim(source)) BETWEEN 1 AND 100),
    source_key      text CHECK (source_key IS NULL OR length(source_key) <= 200),
    source_row      integer CHECK (source_row IS NULL OR source_row > 0),

    imported_at     timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,

    UNIQUE (tenant_id, id),
    CONSTRAINT import_provenance_item_same_tenant
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT import_provenance_job_same_tenant
        FOREIGN KEY (tenant_id, import_job_id)
        REFERENCES portability.import_job (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE portability.import_provenance ENABLE ROW LEVEL SECURITY;
ALTER TABLE portability.import_provenance FORCE ROW LEVEL SECURITY;

CREATE POLICY import_provenance_tenant_isolation ON portability.import_provenance
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- What a second import of the same file looks up, and what the history reads.
-- Not unique: a row per import is the point, and the most recent one wins.
CREATE INDEX import_provenance_by_item ON portability.import_provenance (tenant_id, item_id);
CREATE INDEX import_provenance_by_source_key
    ON portability.import_provenance (tenant_id, source, source_key, imported_at DESC)
    WHERE source_key IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON portability.import_provenance TO homeinv_app;
GRANT SELECT ON portability.import_provenance TO homeinv_readonly;

COMMENT ON TABLE portability.import_provenance IS
    'Where an imported item came from (REQ-PORT-008): the system, the key it had there and the '
    'job that brought it. Its own table because it is portability''s vocabulary, not inventory''s.';
