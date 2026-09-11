-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Photos and documents (07 §7.8, ADR-0032).
--
-- The bytes live in the `BlobStore`, never in the database: a million items with
-- photos is about 700 GB, and a database is the wrong place for it. What is here
-- is the metadata, the scan verdict, and what an upload is attached to.

-- ---------------------------------------------------------------------------
-- The blob
-- ---------------------------------------------------------------------------
CREATE TABLE media.media_object (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,

    -- Content addressing, per tenant. The blob path is `sha256/<tenantId>/<hash>`
    -- and this uniqueness is the same statement in the database: uploading the
    -- same photo twice costs storage once, and two tenants holding the same file
    -- hold two copies with two reference counts (ADR-0032). Deduplicating across
    -- tenants would make "do you have this file" answerable by timing.
    sha256          char(64) NOT NULL,

    -- Detected from magic bytes, never from the extension or the declared type
    -- (REQ-MED-004). Stored so a variant can be served with the right header
    -- without sniffing the bytes again.
    media_type      text NOT NULL,
    byte_size       bigint NOT NULL CHECK (byte_size > 0),

    -- Set for images, null for documents.
    width_px        int CHECK (width_px IS NULL OR width_px > 0),
    height_px       int CHECK (height_px IS NULL OR height_px > 0),

    -- REQ-MED-013: until the scanner has spoken the blob is not retrievable.
    -- PENDING_SCAN is the state an upload is created in, and the only transition
    -- out of it is by the scanner.
    scan_state      text NOT NULL DEFAULT 'PENDING_SCAN'
                    CHECK (scan_state IN ('PENDING_SCAN', 'CLEAN', 'INFECTED', 'SCAN_FAILED')),
    scan_verdict    text,
    scanned_at      timestamptz,

    -- How many attachments point at this blob, within this tenant. Carried
    -- rather than counted, because deletion asks the question on every call and
    -- a count over a large table is the wrong answer to give often.
    ref_count       int NOT NULL DEFAULT 0 CHECK (ref_count >= 0),

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (tenant_id, sha256),
    UNIQUE (tenant_id, id)
);

ALTER TABLE media.media_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE media.media_object FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON media.media_object
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The scanner's work queue: what has not been judged yet. Partial, because
-- everything that has been judged is the overwhelming majority.
CREATE INDEX media_pending_scan ON media.media_object (tenant_id, created_at)
    WHERE scan_state = 'PENDING_SCAN';

-- ---------------------------------------------------------------------------
-- The derivatives
-- ---------------------------------------------------------------------------
-- Generated asynchronously after the scan says clean: a thumbnail of an infected
-- file is a second copy of an infected file.
CREATE TABLE media.media_variant (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    media_object_id uuid NOT NULL,

    -- `thumb` 200 px, `preview` 1024 px, `full` re-encoded at most 4096 px
    -- (REQ-MED-005). `full` exists even when it is the same size as the original,
    -- because re-encoding is what destroys an embedded payload (12 §12, row 5) —
    -- serving the uploaded bytes back would undo that.
    kind            text NOT NULL CHECK (kind IN ('thumb', 'preview', 'full')),
    sha256          char(64) NOT NULL,
    media_type      text NOT NULL,
    byte_size       bigint NOT NULL CHECK (byte_size > 0),
    width_px        int NOT NULL CHECK (width_px > 0),
    height_px       int NOT NULL CHECK (height_px > 0),

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (media_object_id, kind),
    UNIQUE (tenant_id, id),
    CONSTRAINT variant_object_same_tenant
        FOREIGN KEY (tenant_id, media_object_id)
        REFERENCES media.media_object (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE media.media_variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE media.media_variant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON media.media_variant
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- What a blob is attached to
-- ---------------------------------------------------------------------------
-- A polymorphic link rather than one table per target: an attachment is the same
-- thing whether it hangs on an item or a location (REQ-MED-001, REQ-CORE-050),
-- and two tables would mean two of every query.
--
-- No foreign key to the target, deliberately. One would have to name
-- `inventory.item` and `locations.location` from the `media` block's own
-- migration, which no block may do (04 §4.5) — and a polymorphic reference
-- cannot be a foreign key in PostgreSQL anyway. The application enforces
-- existence; row-level security makes a foreign target invisible regardless.
CREATE TABLE media.attachment (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    media_object_id uuid NOT NULL,

    target_kind     text NOT NULL CHECK (target_kind IN ('ITEM', 'LOCATION')),
    target_id       uuid NOT NULL,

    -- REQ-MED-002: the image a list shows. At most one per target, which the
    -- partial unique index below enforces rather than the application.
    primary_image   boolean NOT NULL DEFAULT false,
    display_order   int NOT NULL DEFAULT 0,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (tenant_id, id),
    CONSTRAINT attachment_object_same_tenant
        FOREIGN KEY (tenant_id, media_object_id)
        REFERENCES media.media_object (tenant_id, id)
);

ALTER TABLE media.attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE media.attachment FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON media.attachment
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX attachment_target ON media.attachment (tenant_id, target_kind, target_id)
    WHERE deleted_at IS NULL;

-- One primary image per target. In the database rather than in the application,
-- because two concurrent requests each setting a different primary would both
-- pass an application check and both commit.
CREATE UNIQUE INDEX attachment_one_primary
    ON media.attachment (tenant_id, target_kind, target_id)
    WHERE primary_image AND deleted_at IS NULL;

-- The same blob attached twice to the same thing is a mistake, not a feature.
CREATE UNIQUE INDEX attachment_unique_live
    ON media.attachment (tenant_id, target_kind, target_id, media_object_id)
    WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON
    media.media_object, media.media_variant, media.attachment TO homeinv_app;
GRANT SELECT ON
    media.media_object, media.media_variant, media.attachment TO homeinv_readonly;
