-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- AN UPLOAD THAT SURVIVES A BROKEN CONNECTION (REQ-MED-008, ADR-0084).
--
-- [05 §5.2](../../../../../../docs/architecture/05-runtime-view.md) has described
-- this flow since the chapter was written -- create the upload, send the bytes in
-- pieces, and continue where the last piece stopped -- and nothing implemented it:
-- an interrupted upload started again from zero, which on a mobile connection is
-- the difference between a photograph arriving and a photograph never arriving.
--
-- WHAT IS IN THIS TABLE AND WHAT IS NOT.
--
-- The bytes are NOT here, and neither is the offset. The bytes are staged in the
-- `blobstore` service, which holds the volume so that `api` stays stateless
-- (REQ-NFR-008) and so that a second replica can continue an upload the first one
-- began. The OFFSET is the length of that staged file, asked of the store on every
-- request: a number kept in two places disagrees after a crash, and the one on
-- disk is the one that is true.
--
-- What is here is everything the store does not know -- who the upload is for,
-- what it will be attached to, how long it may sit unfinished -- and the id, which
-- is the address of the staged bytes and the last path segment of the upload URL.

CREATE TABLE media.upload_session (
    -- Chosen by the server and never by a caller: it addresses the staged bytes
    -- in the blob store, and an id a caller picked would let one tenant name
    -- another's upload. `uuidv7` so the sweep below reads in creation order.
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL,

    -- What the finished file will hang on. Fixed when the upload is created
    -- rather than when it completes: a client that changed the target halfway
    -- through would have uploaded to one place and attached to another, and the
    -- quota was claimed against the first.
    target_kind       text NOT NULL CHECK (target_kind IN ('ITEM', 'LOCATION')),
    target_id         uuid NOT NULL,
    primary_image     boolean NOT NULL DEFAULT false,
    role              text NOT NULL DEFAULT 'PHOTO'
                      CHECK (role IN ('PHOTO', 'RECEIPT', 'WARRANTY_PROOF', 'OTHER')),

    -- What the client said it would send, checked against REQ-SEC-037's ceiling
    -- BEFORE a byte arrives -- which is what that requirement asks for and what a
    -- one-shot upload can only approximate by cutting the stream off mid-read.
    -- This is the one place where a resumable upload is the safer of the two.
    declared_length   bigint NOT NULL CHECK (declared_length > 0),

    -- When an unfinished upload stops being one worth keeping. A staged file with
    -- nobody coming back for it is a file that occupies the volume for ever, and
    -- tus calls this `Upload-Expires`.
    expires_at        timestamptz NOT NULL,

    -- Set when the last byte has arrived and the pipeline has run. Two things
    -- need it: a client whose final response was lost asks again and is told the
    -- same media id rather than uploading the file a second time, and the sweep
    -- can tell an abandoned upload from a completed one whose bytes are gone.
    media_object_id   uuid,

    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid, updated_by uuid,
    version           bigint NOT NULL DEFAULT 1,

    UNIQUE (tenant_id, id)
);

ALTER TABLE media.upload_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE media.upload_session FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON media.upload_session
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

COMMENT ON TABLE media.upload_session IS
    'An upload in flight (REQ-MED-008). The bytes are staged in the blobstore service and the '
    'offset is that file''s length; this row holds what the store does not know.';

-- What the sweep reads: unfinished uploads whose time is up, oldest first. Partial
-- on the unfinished ones, because a completed row is kept only so a lost response
-- can be answered and is never a candidate for deletion by age.
CREATE INDEX upload_session_expired
    ON media.upload_session (expires_at)
    WHERE media_object_id IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON media.upload_session TO homeinv_app;
GRANT SELECT ON media.upload_session TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- WHICH TENANTS HAVE AN UPLOAD THAT TIMED OUT
-- ---------------------------------------------------------------------------
--
-- The sweep has no tenant context: it is looking for the tenants that need one.
-- `homeinv_app` has neither `BYPASSRLS` nor any way to enumerate tenants, which
-- is the same circularity every function on 07 §7.5's list resolves the same
-- way. It returns tenant ids and nothing else; the sessions themselves are read
-- and deleted under each tenant's own context, under the ordinary policy.

CREATE FUNCTION media.tenants_with_expired_uploads(before timestamptz)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = media, pg_temp
AS $$
    SELECT DISTINCT u.tenant_id
    FROM media.upload_session u
    WHERE u.media_object_id IS NULL AND u.expires_at <= before;
$$;

GRANT USAGE, CREATE ON SCHEMA media TO homeinv_bootstrap;
ALTER FUNCTION media.tenants_with_expired_uploads(timestamptz) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA media FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION media.tenants_with_expired_uploads(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION media.tenants_with_expired_uploads(timestamptz) TO homeinv_app;

-- The one permissive policy for that role on this table, SELECT only -- the
-- same shape `media.media_object` already has for the orphan sweep.
CREATE POLICY bootstrap_expiry_lookup ON media.upload_session
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON media.upload_session TO homeinv_bootstrap;
