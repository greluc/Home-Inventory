-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHAT A PER-TENANT CHAIN ALONE DOES NOT PROVE (REQ-SEC-096, REQ-SEC-107).
--
-- The chain in `audit.audit_entry` proves that nobody CARELESS touched the log:
-- altering one entry breaks every hash after it. It does not stop somebody who
-- can rewrite a tenant's rows from recomputing that tenant's chain, because the
-- chain is made of the tenant's own entries and nothing else.
--
-- The anchor closes that. Every hour a Merkle root is computed over the entries
-- of the window ACROSS ALL TENANTS and chained to its predecessor, so removing
-- an entry from one tenant would mean reproducing every anchor since — over data
-- belonging to every other tenant (ADR-0031).
--
-- Anchoring outside the instance would be stronger still, and is deliberately
-- not done: it would be an outbound connection, which ADR-0026 removed from the
-- core. If it is ever wanted it arrives as a plugin.

-- ---------------------------------------------------------------------------
-- WHAT THE CHAIN COVERS, AND THE ONE FIELD A PRIVACY RULE REMOVES
--
-- REQ-PRIV-006 removes the address from ordinary audit entries after seven days
-- and keeps it for security-relevant ones. Clearing a column that the entry hash
-- was computed over would break the chain at every cleared row — honouring one
-- requirement would forge evidence against another.
--
-- So the hash covers the address through `ip_hash`, which never changes, and
-- `ip` is free to be cleared. The tamper evidence stays complete: an altered
-- address no longer matches `ip_hash`, and a removed one leaves `ip_hash` to
-- check a candidate against.
--
-- Added the day after the table, before anything wrote to it in earnest.
-- ---------------------------------------------------------------------------
ALTER TABLE audit.audit_entry
    ADD COLUMN ip_hash bytea CHECK (ip_hash IS NULL OR length(ip_hash) = 32);

COMMENT ON COLUMN audit.audit_entry.ip IS
    'The address, clearable by the retention run (REQ-PRIV-006). Not covered by the entry hash.';
COMMENT ON COLUMN audit.audit_entry.ip_hash IS
    'The SHA-256 of the address, covered by the entry hash and never cleared (ADR-0031).';

-- The retention run clears the address; it may not touch anything else. An
-- UPDATE privilege on the whole table would let it rewrite an action.
GRANT UPDATE (ip) ON audit.audit_entry TO homeinv_housekeeping;

-- ---------------------------------------------------------------------------
-- THE ANCHOR
--
-- Instance-wide, with no `tenant_id`: it spans every tenant, which is the whole
-- point, and 07 §7.1's exception list covers it.
-- ---------------------------------------------------------------------------
CREATE TABLE audit.chain_anchor (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),

    -- The window, half-open. `UNIQUE` because an hour is anchored once: a second
    -- row for the same window would be a second answer about the same entries.
    window_start   timestamptz NOT NULL,
    window_end     timestamptz NOT NULL,
    CONSTRAINT window_is_forwards CHECK (window_end > window_start),
    UNIQUE (window_start),

    -- The Merkle root over every entry hash in the window, ordered. Empty
    -- windows are anchored too: an hour in which nothing happened is a fact
    -- about the log, and skipping it would leave a gap indistinguishable from a
    -- missed run.
    merkle_root    bytea NOT NULL CHECK (length(merkle_root) = 32),
    entry_count    bigint NOT NULL CHECK (entry_count >= 0),

    -- The anchors are a chain of their own, so an anchor cannot be recomputed in
    -- isolation either.
    prev_hash      bytea NOT NULL CHECK (length(prev_hash) = 32),
    anchor_hash    bytea NOT NULL CHECK (length(anchor_hash) = 32),

    -- A window whose entries a retention run has removed. The row and its place
    -- in the chain stay; verification stops expecting a recomputation over
    -- contents that are gone (ADR-0046).
    --
    -- Without this, honouring REQ-PRIV-010 and being attacked produce identical
    -- evidence, and the instance raises its own tampering alert daily.
    pruned         boolean NOT NULL DEFAULT false,

    computed_at    timestamptz NOT NULL DEFAULT now()
);

-- Never pruned: one row an hour, about nine thousand a year, and pruning them
-- would discard exactly the evidence they exist for. `homeinv_housekeeping` may
-- MARK one — that is an UPDATE of `pruned` and nothing else — and may not delete.
GRANT SELECT, INSERT ON audit.chain_anchor TO homeinv_app;
GRANT SELECT ON audit.chain_anchor TO homeinv_readonly;
GRANT SELECT, UPDATE ON audit.chain_anchor TO homeinv_housekeeping;

-- ---------------------------------------------------------------------------
-- WHERE A TENANT'S VERIFIABLE CHAIN BEGINS
--
-- A retention run removes a tenant's oldest entries, which breaks the chain at
-- exactly the place it removed them. Recording where it did is what separates
-- "this was pruned on purpose, here, by this rule" from "somebody removed
-- entries" (ADR-0046).
-- ---------------------------------------------------------------------------
CREATE TABLE audit.chain_truncation (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)
    truncated_at   timestamptz NOT NULL DEFAULT now(),

    -- What survives, and what a verification run starts from instead of genesis.
    oldest_seq     bigint NOT NULL CHECK (oldest_seq > 0),
    oldest_hash    bytea NOT NULL CHECK (length(oldest_hash) = 32),

    removed_count  bigint NOT NULL CHECK (removed_count >= 0),
    reason         text NOT NULL CHECK (reason IN ('retention', 'tenant-erasure')),

    UNIQUE (tenant_id, truncated_at)
);

ALTER TABLE audit.chain_truncation ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.chain_truncation FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.chain_truncation
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Append-only for everybody. The housekeeping role WRITES these — it is the one
-- that truncates — and may not edit or remove one afterwards, because a marker
-- that could be rewritten would let a deletion be backdated.
GRANT SELECT ON audit.chain_truncation TO homeinv_app;
GRANT SELECT ON audit.chain_truncation TO homeinv_readonly;
GRANT SELECT, INSERT ON audit.chain_truncation TO homeinv_housekeeping;

CREATE INDEX truncation_by_tenant ON audit.chain_truncation (tenant_id, truncated_at DESC);

-- ---------------------------------------------------------------------------
-- READING ACROSS TENANTS, ONCE, FOR ONE PURPOSE
--
-- The anchor spans every tenant and row-level security does not — correctly.
-- `SECURITY DEFINER` is how 07 §7.5 crosses that boundary where a mechanism
-- genuinely must, and this function is the narrowest shape of it: it returns
-- HASHES, never content, for a bounded window, in a fixed order.
--
-- What leaks through it is how many entries were written in an hour across the
-- instance, to whoever may already read every anchor. That is the same figure
-- the anchor itself carries, which is why it is acceptable here and why the
-- function returns nothing else.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit.entry_hashes_in(starts timestamptz, ends timestamptz)
RETURNS TABLE (entry_hash bytea)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = audit, pg_temp
AS $$
    -- Ordered by the tenant and its own sequence, never by time: two entries can
    -- share a microsecond, and a root computed over a different order is a
    -- different root.
    SELECT e.entry_hash
    FROM audit.audit_entry e
    WHERE e.occurred_at >= starts AND e.occurred_at < ends
    ORDER BY e.tenant_id, e.seq;
$$;

-- Owned by `homeinv_bootstrap` and not by the migrator, which is what makes it
-- work at all: `FORCE ROW LEVEL SECURITY` applies to the table's owner too, so a
-- definer function owned by the migrator is blocked by the very policy it needs
-- to step around. `homeinv_bootstrap` is the NOLOGIN role that exists for these
-- reads, holding one permissive policy per table and nothing else — the shape
-- 07 §7.5 sanctions and V7 established.
--
-- CREATE on the schema is granted for the one statement that transfers ownership
-- and taken straight back: this role owns functions, it does not make them.
GRANT USAGE, CREATE ON SCHEMA audit TO homeinv_bootstrap;
ALTER FUNCTION audit.entry_hashes_in(timestamptz, timestamptz) OWNER TO homeinv_bootstrap;

REVOKE ALL ON FUNCTION audit.entry_hashes_in(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.entry_hashes_in(timestamptz, timestamptz) TO homeinv_app;

-- Where the anchor run starts on an instance that has never anchored.
--
-- Across every tenant for the same reason and with the same narrowness: it
-- returns one timestamp and nothing else. Without it the run reads `min` under
-- row-level security with no tenant context, gets nothing, and concludes there is
-- nothing to anchor — which is how a log with entries in it ends up with no
-- anchors at all.
CREATE OR REPLACE FUNCTION audit.oldest_entry_at()
RETURNS timestamptz
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = audit, pg_temp
AS $$
    SELECT min(occurred_at) FROM audit.audit_entry;
$$;

ALTER FUNCTION audit.oldest_entry_at() OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA audit FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION audit.oldest_entry_at() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.oldest_entry_at() TO homeinv_app;

-- The one permissive policy, for the one role, on the one table. SELECT only:
-- the anchor run reads hashes and a timestamp, and writes nothing here.
--
-- What this opens is exactly what the anchor needs and no more. A row read
-- through it never leaves the two functions above: one returns hashes, the other
-- returns a single instant.
CREATE POLICY bootstrap_anchor ON audit.audit_entry
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON audit.audit_entry TO homeinv_bootstrap;
