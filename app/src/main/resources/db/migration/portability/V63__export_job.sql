-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- TAKING EVERYTHING WITH YOU (REQ-PORT-003, REQ-PORT-004, REQ-PORT-005).
--
-- An export is a job and not a response. 04 §4.5 lists `ExportJob` among this
-- block's notions and REQ-PORT-005 asks for `202` plus a job resource with
-- progress, for the ordinary reason: a tenant with ten thousand items and their
-- photographs is minutes of work and tens of megabytes, and an HTTP request
-- that tried to hold that open would be a request that times out on exactly the
-- installations where the feature matters most.
--
-- A JOB IS INFRASTRUCTURE, so 07 §7.1 rule 4's third kind: no `version`, no
-- `updated_*`. Its `state` and `progress` are the mechanism's own bookkeeping,
-- and an `updated_by` on it would name the scheduler rather than a person. What
-- it does carry is who ASKED, because that is a fact about a person.

CREATE SCHEMA IF NOT EXISTS portability;
GRANT USAGE ON SCHEMA portability TO homeinv_app, homeinv_readonly;

CREATE TABLE portability.export_job (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- QUEUED -> RUNNING -> READY, or -> FAILED. No cancellation state: a job
    -- that has started is minutes of work, and a tenant who no longer wants the
    -- archive simply does not download it.
    state          text NOT NULL DEFAULT 'QUEUED'
                   CHECK (state IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),

    -- How far along, as a percentage. Coarse on purpose: it is read by a person
    -- deciding whether to keep waiting, and a number that moves is worth more to
    -- them than a number that is accurate.
    progress       smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),

    -- WHERE THE ARCHIVE WENT. The blob store, addressed by content like every
    -- other blob (ADR-0032) -- an export is a file, and this deployment already
    -- has exactly one place files live. Null until the job succeeds.
    sha256         text CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    byte_size      bigint CHECK (byte_size IS NULL OR byte_size > 0),

    -- What went wrong, for a job that failed. Never a stack trace and never a
    -- credential: it is shown to whoever asked for the export.
    failure        text CHECK (failure IS NULL OR length(failure) <= 2000),

    -- The two must agree, so a READY job always has an archive and a job that
    -- has one is always READY. Without this a half-written job could be offered
    -- for download, which is a file that 404s after the browser has started it.
    CONSTRAINT export_job_ready_iff_stored
        CHECK ((state = 'READY') = (sha256 IS NOT NULL)),
    CONSTRAINT export_job_failed_iff_explained
        CHECK ((state = 'FAILED') = (failure IS NOT NULL)),

    requested_at   timestamptz NOT NULL DEFAULT now(),
    requested_by   uuid,
    finished_at    timestamptz,

    UNIQUE (tenant_id, id)
);

ALTER TABLE portability.export_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE portability.export_job FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portability.export_job
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "What have I asked for", newest first -- the only question asked of this table
-- by a person.
CREATE INDEX export_job_by_request ON portability.export_job (tenant_id, requested_at DESC);

-- The runner's own queue.
CREATE INDEX export_job_queued ON portability.export_job (tenant_id, requested_at)
    WHERE state = 'QUEUED';

GRANT SELECT, INSERT, UPDATE, DELETE ON portability.export_job TO homeinv_app;
GRANT SELECT ON portability.export_job TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- WHICH TENANTS HAVE AN EXPORT WAITING
-- ---------------------------------------------------------------------------
--
-- The runner has no tenant context: it is looking for the tenants that need one,
-- and `homeinv_app` has neither `BYPASSRLS` nor any way to enumerate tenants.
-- The same circularity every function on 07 §7.5's list resolves the same way.

CREATE FUNCTION portability.tenants_with_queued_exports()
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = portability, pg_temp
AS $$
    SELECT DISTINCT j.tenant_id
    FROM portability.export_job j
    WHERE j.state = 'QUEUED';
$$;

GRANT USAGE, CREATE ON SCHEMA portability TO homeinv_bootstrap;
ALTER FUNCTION portability.tenants_with_queued_exports() OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA portability FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION portability.tenants_with_queued_exports() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portability.tenants_with_queued_exports() TO homeinv_app;

CREATE POLICY bootstrap_queue_lookup ON portability.export_job
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON portability.export_job TO homeinv_bootstrap;

COMMENT ON TABLE portability.export_job IS
    'A tenant asking for everything it has (REQ-PORT-003/005). The archive itself lives in the '
    'blob store, addressed by content; this row says whether it is ready and how far along it is.';
