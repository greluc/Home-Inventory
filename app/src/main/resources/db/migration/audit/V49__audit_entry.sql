-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- THE AUDIT LOG (REQ-SEC-068 … REQ-SEC-071, ADR-0031).
--
-- Every mutating action writes one row here, in the same transaction as the
-- change it records (05 §5.1). Not beside it and not afterwards: a log that can
-- be written separately has a gap wherever the second write failed, and a gap
-- reads as "nothing happened".
--
-- HOW THIS DIFFERS FROM `audit.revision_record` BESIDE IT
--
-- The revision record answers "what did this item look like at version 4, and
-- can I have it back". This answers "who did what, when, from where". One is
-- domain history a person restores from; the other is evidence, and the
-- difference is why one is a snapshot per entity and the other a chain per
-- tenant.
--
-- THE CHAIN
--
-- `prev_hash` refers to the previous entry OF THE SAME TENANT (ADR-0031). A
-- single global chain was rejected on three counts: it serialises every write in
-- the instance against every other, it discloses through sequence gaps how much
-- other tenants are writing, and it cannot be verified by a tenant under its own
-- RLS context because the predecessor may belong to somebody else.
--
-- The first entry of a tenant chains from a genesis value derived from the
-- tenant id, so an empty chain and a truncated one are different things.

-- ---------------------------------------------------------------------------
-- PARTITIONED MONTHLY, as 07 §7.8 has it, and the cost is named rather than
-- discovered: PostgreSQL takes a unique index on a partitioned table only when
-- it contains the partition key, so `UNIQUE (tenant_id, seq)` cannot be declared
-- on the parent. It is created PER PARTITION instead (decided with the owner on
-- 2026-09-15).
--
-- That is not the weaker half it looks like. A duplicate `seq` would have to
-- occur within one month to escape, and reaching a second month with the same
-- number needs the clock to move backwards across a month boundary AND the
-- per-tenant advisory lock to have failed. The per-partition index catches
-- everything short of both at once.
-- ---------------------------------------------------------------------------
CREATE TABLE audit.audit_entry (
    id             uuid NOT NULL DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- This tenant's own counter, from one. A number per ENTRY: it is what a
    -- truncation marker points at (`oldest_seq`, ADR-0046) and what lets a
    -- verification run say "the entry after 4711 is missing" rather than only
    -- "a hash does not match".
    seq            bigint NOT NULL CHECK (seq > 0),

    occurred_at    timestamptz NOT NULL DEFAULT now(),

    -- WHO. Four kinds, because the answer to "was this a person" must not depend
    -- on reading an id and guessing. A plugin write carries the plugin's id in
    -- `actor_label` and no `actor_id` at all, which is what makes it
    -- distinguishable from a user action (REQ-SEC-073, REQ-PLG-011).
    actor_kind     text NOT NULL CHECK (actor_kind IN
                       ('USER', 'SERVICE_ACCOUNT', 'PLUGIN', 'SYSTEM')),
    actor_id       uuid,
    actor_label    text CHECK (actor_label IS NULL OR length(actor_label) <= 300),
    -- A person and a machine have an id; a plugin and the system have a name.
    -- Refused together rather than left to a service to remember.
    CONSTRAINT actor_is_identified CHECK (
        (actor_kind IN ('USER', 'SERVICE_ACCOUNT') AND actor_id IS NOT NULL)
     OR (actor_kind IN ('PLUGIN', 'SYSTEM') AND actor_id IS NULL AND actor_label IS NOT NULL)),

    -- WHAT. A verb the block chose, `item.created`, `member.removed`. Text and
    -- not an enum: the set grows with every feature and a check constraint would
    -- be a migration per verb.
    action         text NOT NULL CHECK (length(action) BETWEEN 1 AND 100),

    -- ON WHAT. No foreign key: an audit entry outlives the row it is about —
    -- which is the point of keeping one for a final removal.
    resource_type  text NOT NULL CHECK (length(resource_type) BETWEEN 1 AND 60),
    resource_id    uuid,

    -- WHAT CHANGED. Field to before/after, as the block chose to describe it.
    -- `sensitive` values never reach here: the diff is built after redaction,
    -- because an audit log that recorded what a role may not read would be a way
    -- to read it (REQ-SEC-027).
    diff           jsonb NOT NULL DEFAULT '{}'::jsonb
                       CHECK (jsonb_typeof(diff) = 'object'),

    -- FROM WHERE. The address is pruned after seven days for ordinary entries
    -- and kept for security-relevant ones (REQ-PRIV-006), which is a retention
    -- rule over this column rather than a reason not to record it.
    ip             inet,
    client         text CHECK (client IS NULL OR length(client) <= 300),
    correlation_id text CHECK (correlation_id IS NULL OR length(correlation_id) <= 200),

    -- THE CHAIN. Raw bytes rather than hex: 32 bytes against 64 characters, a
    -- million rows apart.
    prev_hash      bytea NOT NULL CHECK (length(prev_hash) = 32),
    entry_hash     bytea NOT NULL CHECK (length(entry_hash) = 32),

    -- The partition key has to be in it, so this is not the uniqueness the chain
    -- rests on; that one is per partition, below.
    PRIMARY KEY (tenant_id, occurred_at, seq)
) PARTITION BY RANGE (occurred_at);

ALTER TABLE audit.audit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_entry FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_entry
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- REQ-SEC-069. INSERT and SELECT, and deliberately nothing else: append-only is
-- a privilege here, not a rule a service has to keep. Retention deletions run
-- under `homeinv_housekeeping`, which is a different role for exactly this
-- reason (ADR-0046).
GRANT SELECT, INSERT ON audit.audit_entry TO homeinv_app;
GRANT SELECT ON audit.audit_entry TO homeinv_readonly;
GRANT SELECT, DELETE ON audit.audit_entry TO homeinv_housekeeping;

-- ---------------------------------------------------------------------------
-- PARTITION MANAGEMENT
--
-- A missing partition is an insert that fails, and this insert is inside the
-- transaction of the change it records — so a missing partition does not lose an
-- audit entry, it refuses the operation. That is the right way round and it is
-- still an outage, which is why the window below is generous and the function
-- beside it exists.
-- ---------------------------------------------------------------------------

-- Creates the month's partition and its per-partition unique index, if missing.
--
-- `SECURITY DEFINER` because `homeinv_app` has no DDL rights and must never get
-- any (07 §7.9). It creates one shape of one object in one schema and takes a
-- timestamp, so there is nothing for a caller to steer: the name is derived,
-- never passed.
--
-- Idempotent and safe under concurrency: two transactions reaching the same
-- missing month both succeed, one of them by catching `duplicate_table`.
CREATE OR REPLACE FUNCTION audit.ensure_audit_partition(at timestamptz)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = audit, pg_temp
AS $$
DECLARE
    starts  date := date_trunc('month', at AT TIME ZONE 'UTC')::date;
    ends    date := (date_trunc('month', at AT TIME ZONE 'UTC') + interval '1 month')::date;
    name    text := format('audit_entry_%s', to_char(starts, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE audit.%I PARTITION OF audit.audit_entry FOR VALUES FROM (%L) TO (%L)',
        name, starts, ends);

    -- The uniqueness the chain rests on. Per partition, because the parent
    -- cannot carry it.
    EXECUTE format(
        'CREATE UNIQUE INDEX %I ON audit.%I (tenant_id, seq)', name || '_tenant_seq', name);

    -- "What did this account do in this period" (REQ-SEC-071), which is the one
    -- question the log exists to answer quickly.
    EXECUTE format(
        'CREATE INDEX %I ON audit.%I (tenant_id, actor_id, occurred_at DESC)',
        name || '_actor', name);

    EXECUTE format('GRANT SELECT, INSERT ON audit.%I TO homeinv_app', name);
    EXECUTE format('GRANT SELECT ON audit.%I TO homeinv_readonly', name);
    EXECUTE format('GRANT SELECT, DELETE ON audit.%I TO homeinv_housekeeping', name);
EXCEPTION
    WHEN duplicate_table THEN
        NULL;
END;
$$;

REVOKE ALL ON FUNCTION audit.ensure_audit_partition(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.ensure_audit_partition(timestamptz) TO homeinv_app;

-- Thirteen months from the start of this one, so the function above is a safety
-- net rather than the ordinary path. Keeping the window ahead is a housekeeping
-- task; falling off the end of it is caught by the writer, which creates the
-- month and retries once.
DO $$
DECLARE
    month timestamptz := date_trunc('month', now());
    step  int;
BEGIN
    FOR step IN 0..12 LOOP
        PERFORM audit.ensure_audit_partition(month + (step || ' months')::interval);
    END LOOP;
END;
$$;
