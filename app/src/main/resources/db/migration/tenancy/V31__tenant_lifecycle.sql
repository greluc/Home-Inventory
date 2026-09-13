-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A tenant can be suspended, and can be asked to be erased (REQ-TEN-011,
-- REQ-PRIV-005).
--
-- 05 §5.9 describes the flow: the owner asks, the tenant becomes
-- PENDING_DELETION, access stops at once while the data stays, and a thirty-day
-- grace period runs. Within it the request can be withdrawn; after it the blocks
-- erase their share and report, and an erasure certificate is issued.
--
-- Open point O26 decided what the API answers for a tenant in either of the two
-- inaccessible states: `403` with ONE token, `tenant-inaccessible`. Two tokens
-- would say which state a tenant is in, and that is a status oracle over a
-- boundary REQ-SEC-025 closes deliberately.

ALTER TABLE tenancy.tenant
    ADD COLUMN lifecycle_state text NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'PENDING_DELETION')),
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deletion_requested_by uuid,
    -- The token that withdraws the request, stored only as a SHA-256
    -- (REQ-SEC-048). It goes out in the answer to the request and in the mail
    -- `plugin-smtp` sends; a leaked database is not a set of working revocations.
    ADD COLUMN revocation_token_hash text,
    -- A request and its token travel together, and a state of PENDING_DELETION
    -- with no timestamp would be a grace period that never elapses.
    ADD CONSTRAINT tenant_deletion_request_complete
        CHECK (
            (lifecycle_state = 'PENDING_DELETION')
            = (deletion_requested_at IS NOT NULL AND revocation_token_hash IS NOT NULL)
        );

-- ---------------------------------------------------------------------------
-- Which tenants the grace period has run out for
-- ---------------------------------------------------------------------------
--
-- A query that spans tenants, and `homeinv_app` has neither BYPASSRLS nor a way
-- to enumerate them — 13 §13.8 makes the same point about the catch-up scan. The
-- way out is the one 07 §7.5 names and V7 established: an explicit, logged
-- SECURITY DEFINER function owned by `homeinv_bootstrap`, the NOLOGIN role for
-- the reads that cannot go through a tenant context.
--
-- It returns ids and instants and nothing else. A caller learns which tenants are
-- due and not one fact about any of them.

CREATE FUNCTION tenancy.tenants_due_for_erasure(p_grace interval)
RETURNS TABLE (tenant_id uuid, requested_at timestamptz)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `tenant`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT t.id, t.deletion_requested_at
    FROM tenancy.tenant t
    WHERE t.lifecycle_state = 'PENDING_DELETION'
      AND t.deleted_at IS NULL
      AND t.deletion_requested_at + p_grace <= now()
    ORDER BY t.deletion_requested_at ASC
$$;

GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenants_due_for_erasure(interval) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenants_due_for_erasure(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_due_for_erasure(interval) TO homeinv_app;

-- ---------------------------------------------------------------------------
-- Finding a tenant by its revocation token
-- ---------------------------------------------------------------------------
--
-- The same circularity an invitation has (V23): whoever withdraws a request
-- presents a token, the token identifies the tenant, and with no `app.tenant_id`
-- set the policy yields nothing. Deliberately unfiltered on state, so that a
-- token for a request already withdrawn is answered exactly like one that never
-- existed.

CREATE FUNCTION tenancy.tenant_by_revocation_token(p_token_hash text)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
    SELECT t.id
    FROM tenancy.tenant t
    WHERE t.revocation_token_hash = p_token_hash
      AND t.deleted_at IS NULL
$$;

GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenant_by_revocation_token(text) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenant_by_revocation_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenant_by_revocation_token(text) TO homeinv_app;
