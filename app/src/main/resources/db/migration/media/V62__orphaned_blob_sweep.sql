-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A BLOB GOES ONLY WHEN NOTHING POINTS AT IT, AND NOT AT ONCE (REQ-MED-011).
--
-- 13 §13.8 has listed a weekly "orphaned blobs" run since the chapter was
-- written -- "check reference counts, remove unreferenced ones, WITH A GRACE
-- PERIOD, never immediately" -- and `MediaErasure` says in a comment that the
-- sweep "already removes what no reference points at". It did not: there was no
-- such run anywhere in the media block, so `ref_count` fell to zero and the
-- bytes stayed for ever.
--
-- WHY A GRACE PERIOD AT ALL. Detaching a photograph from an item and attaching
-- it to another is two operations, and between them the count is zero. Removing
-- the bytes the moment it hits zero would delete the picture somebody is in the
-- middle of moving -- and, worse, deduplication means that blob may be the one
-- copy several items would have shared.
--
-- WHICH NEEDS A DATE, and `updated_at` is not it: that moves when a scan
-- finishes, when a derivative is recorded, when anything at all changes. So the
-- moment the count reaches zero is its own column, cleared the moment a
-- reference comes back.

ALTER TABLE media.media_object
    ADD COLUMN unreferenced_since timestamptz;

-- The two must agree: a blob with no references has a date, and one with
-- references has none. Written together by the aggregate, and this is what stops
-- them drifting -- a stale date would delete a blob that is in use, which is the
-- one mistake this table must not make.
ALTER TABLE media.media_object
    ADD CONSTRAINT media_object_unreferenced_iff_dated
        CHECK ((ref_count = 0) = (unreferenced_since IS NOT NULL));

-- Existing rows: everything already at zero starts its grace period now rather
-- than in the past, so deploying this removes nothing on its first run. A
-- migration that made a week of blobs instantly eligible would be a migration
-- that deletes data as a side effect of being applied.
UPDATE media.media_object SET unreferenced_since = now() WHERE ref_count = 0;

-- The sweep's own query: what has been unreferenced long enough.
CREATE INDEX media_object_unreferenced
    ON media.media_object (tenant_id, unreferenced_since)
    WHERE ref_count = 0;

-- ---------------------------------------------------------------------------
-- WHICH TENANTS HAVE SOMETHING TO SWEEP
-- ---------------------------------------------------------------------------
--
-- The run has no tenant context: it is looking for the tenants that need one.
-- `homeinv_app` has neither `BYPASSRLS` nor any way to enumerate tenants, which
-- is the same circularity every function on 07 §7.5's list resolves the same
-- way. It returns tenant ids and nothing else; the objects themselves are read
-- under each tenant's own context, under the ordinary policy.

CREATE FUNCTION media.tenants_with_orphaned_blobs(before timestamptz)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = media, pg_temp
AS $$
    SELECT DISTINCT o.tenant_id
    FROM media.media_object o
    WHERE o.ref_count = 0 AND o.unreferenced_since <= before;
$$;

GRANT USAGE, CREATE ON SCHEMA media TO homeinv_bootstrap;
ALTER FUNCTION media.tenants_with_orphaned_blobs(timestamptz) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA media FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION media.tenants_with_orphaned_blobs(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION media.tenants_with_orphaned_blobs(timestamptz) TO homeinv_app;

-- The one permissive policy for that role on this table, SELECT only -- the
-- shape `notification.notification` already has for the delivery run.
CREATE POLICY bootstrap_orphan_lookup ON media.media_object
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON media.media_object TO homeinv_bootstrap;

COMMENT ON COLUMN media.media_object.unreferenced_since IS
    'When the last attachment pointing at this blob went (REQ-MED-011). Null exactly when '
    'ref_count > 0, which a check constraint enforces. The weekly sweep of 13 SS13.8 removes what '
    'has been unreferenced longer than the grace period.';
