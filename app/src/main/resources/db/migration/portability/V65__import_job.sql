-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-PORT-003, REQ-PORT-004, REQ-PORT-007.
--
-- The other half of the export. An archive is uploaded, stored as a blob like
-- every other file this deployment holds (ADR-0032), and read back by the worker
-- into the tenant that asked -- in ONE transaction, because REQ-PORT-007 says an
-- import is complete or it never happened.
--
-- The job row is the exception to that sentence and has to be: it is written
-- before the import starts and updated after it ends, so a failure can be
-- reported at all. Its own transactions are short and separate from the one the
-- import runs in, the same arrangement `export_job` uses for the same reason.

CREATE TABLE portability.import_job (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- QUEUED -> RUNNING -> DONE, or -> FAILED. A `DRY_RUN` job walks the whole
    -- import and rolls it back deliberately (REQ-PORT-001): it is not a separate
    -- state because the states describe how far the job got, and a dry run gets
    -- exactly as far as a real one.
    state          text NOT NULL DEFAULT 'QUEUED'
                   CHECK (state IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),

    -- Whether the work is thrown away at the end. Asked for by the caller, and
    -- the reason the state machine above needs no extra state.
    dry_run        boolean NOT NULL DEFAULT false,

    progress       smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),

    -- WHAT WAS UPLOADED. Content-addressed, like every other file here. The blob
    -- is what the worker reads; nothing about the archive is parsed in the
    -- request that accepted it, because parsing a file somebody uploaded is work
    -- and work in a request is a way to hold one open.
    sha256         text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    byte_size      bigint NOT NULL CHECK (byte_size > 0),

    -- THE REPORT. What happened, per block, as JSON: how many rows were
    -- inserted, how many overwritten, how many deliberately skipped, and the
    -- sentences a person needs in order to know what did not travel -- the
    -- people and the roles above all, which an import never writes.
    report         jsonb CHECK (report IS NULL OR jsonb_typeof(report) = 'object'),

    -- What went wrong, for a job that failed. Never a stack trace and never a
    -- credential: it is shown to whoever asked for the import.
    failure        text CHECK (failure IS NULL OR length(failure) <= 2000),

    CONSTRAINT import_job_done_iff_reported
        CHECK ((state = 'DONE') = (report IS NOT NULL)),
    CONSTRAINT import_job_failed_iff_explained
        CHECK ((state = 'FAILED') = (failure IS NOT NULL)),

    requested_at   timestamptz NOT NULL DEFAULT now(),
    requested_by   uuid,
    finished_at    timestamptz,

    UNIQUE (tenant_id, id)
);

ALTER TABLE portability.import_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE portability.import_job FORCE ROW LEVEL SECURITY;

CREATE POLICY import_job_tenant_isolation ON portability.import_job
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX import_job_queued ON portability.import_job (tenant_id, requested_at)
    WHERE state = 'QUEUED';

-- The application role reads and writes it; the read-only role reads it. Neither
-- owns it and neither may change its shape: `homeinv_app` holds no DDL rights at
-- all (REQ-SEC-101), which is why a new table needs its grants written down here
-- rather than inherited from anywhere.
GRANT SELECT, INSERT, UPDATE, DELETE ON portability.import_job TO homeinv_app;
GRANT SELECT ON portability.import_job TO homeinv_readonly;

COMMENT ON TABLE portability.import_job IS
    'One uploaded archive, read back into this tenant. The import itself is one transaction; this '
    'row is written outside it so a failure can be reported.';

-- -----------------------------------------------------------------------------
-- WHICH TENANTS HAVE WORK WAITING
--
-- The same shape, and the same reason, as `tenants_with_queued_exports`: the
-- worker runs outside any tenant context and needs to know where to set one.
-- `SECURITY DEFINER` because `FORCE ROW LEVEL SECURITY` blocks even the table's
-- owner, and the function is on the closed documented list in 07 §7.5. It
-- returns tenant ids and nothing else -- not a row, not a count of rows, not a
-- name -- so it cannot become a way to read across the boundary.
-- -----------------------------------------------------------------------------
CREATE FUNCTION portability.tenants_with_queued_imports()
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = portability, pg_temp
AS $$
    SELECT DISTINCT j.tenant_id
    FROM portability.import_job j
    WHERE j.state = 'QUEUED';
$$;

GRANT USAGE, CREATE ON SCHEMA portability TO homeinv_bootstrap;
ALTER FUNCTION portability.tenants_with_queued_imports() OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA portability FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION portability.tenants_with_queued_imports() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portability.tenants_with_queued_imports() TO homeinv_app;

CREATE POLICY bootstrap_import_queue_lookup ON portability.import_job
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON portability.import_job TO homeinv_bootstrap;

COMMENT ON FUNCTION portability.tenants_with_queued_imports() IS
    'Tenant ids with an import waiting. SECURITY DEFINER, on the list in 07 §7.5: it returns ids '
    'and nothing else, so it cannot be used to read another tenant''s rows.';
