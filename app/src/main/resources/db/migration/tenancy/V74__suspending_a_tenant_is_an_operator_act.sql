-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Suspending a tenant, which is the immediate measure REQ-SEC-082 names
-- (REQ-TEN-011, 12 §12).
--
-- V31 gave the tenant its lifecycle states and the ways into PENDING_DELETION:
-- a tenant asks for its own erasure and withdraws the request with a token.
-- SUSPENDED had no way in at all. It was in the CHECK constraint, the
-- interceptor answered `tenant-inaccessible` for it, and nothing could ever put
-- a tenant there -- which made "suspend a tenant" a state the system understood
-- and could not reach.
--
-- It is an OPERATOR's act and not a tenant's, so it cannot go through the tenant
-- policy: the operator acts for no tenant and the policy would return no rows.
-- The way out is the one 07 §7.5 names and V7 established -- an explicit,
-- SECURITY DEFINER function owned by a NOLOGIN role that exists for this and
-- nothing else. `homeinv_tenant_state` owns the setter; the reader below is
-- `homeinv_bootstrap`'s, because that role reads and this one writes.
--
-- ---------------------------------------------------------------------------
-- Two states and not three
-- ---------------------------------------------------------------------------
--
-- ACTIVE and SUSPENDED, in either direction, and nothing else. PENDING_DELETION
-- and ERASED are deliberately unreachable from here in both directions:
--
--   * INTO deletion, because an erasure is a tenant's own decision with a grace
--     period and a revocation token, and an operator who could set the state
--     directly would start the thirty-day clock with none of that;
--   * OUT of deletion, because withdrawing a request is what the token is FOR
--     (REQ-TEN-011). An operator who could flip PENDING_DELETION back to ACTIVE
--     would be a second way to withdraw, and the one that does not need the
--     token.
--
-- A call that names either is refused rather than ignored: an immediate measure
-- that silently did nothing is worse than one that fails, because the operator
-- taking it believes it worked.

CREATE FUNCTION tenancy.set_tenant_lifecycle_state(
    p_tenant_id uuid,
    p_state     text,
    p_actor     uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `tenant`.
SET search_path = tenancy, pg_temp
AS $$
DECLARE
    v_current text;
BEGIN
    IF p_state NOT IN ('ACTIVE', 'SUSPENDED') THEN
        RAISE EXCEPTION 'A tenant is suspended or made active here; % is not one of those', p_state
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT t.lifecycle_state INTO v_current
    FROM tenancy.tenant t
    WHERE t.id = p_tenant_id
    FOR UPDATE;

    IF v_current IS NULL THEN
        RETURN NULL;
    END IF;

    IF v_current NOT IN ('ACTIVE', 'SUSPENDED') THEN
        RAISE EXCEPTION 'This tenant is %, and that is not a state suspension reaches', v_current
            USING ERRCODE = 'check_violation';
    END IF;

    UPDATE tenancy.tenant
    SET lifecycle_state = p_state,
        updated_at      = now(),
        updated_by      = p_actor,
        version         = version + 1
    WHERE id = p_tenant_id;

    RETURN p_state;
END;
$$;

-- What the operator view reads back. Separate from the setter so that looking is
-- not writing, and STABLE so the planner knows it.
CREATE FUNCTION tenancy.lifecycle_state_of_tenant(p_tenant_id uuid)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
    SELECT t.lifecycle_state FROM tenancy.tenant t WHERE t.id = p_tenant_id
$$;

-- The SETTER is owned by a role of its own, and not by `homeinv_bootstrap`.
-- That role reads and this function writes, and 00-roles.sql says in as many
-- words why the two are not the same role: one role, one sentence. What
-- `homeinv_tenant_state` may reach is one column of one table -- and the
-- function refuses every state but ACTIVE and SUSPENDED, so even that column
-- cannot be used to start an erasure or to end one.
--
-- And the POLICY, which is the half that is easy to forget and impossible to
-- notice: FORCE ROW LEVEL SECURITY applies to the table owner as well, so a
-- definer function without one silently reads and writes nothing -- a suspension
-- that reported success and changed no row. The same trap V7 documents and V24
-- fell into first.
CREATE POLICY tenant_state_administration ON tenancy.tenant
    TO homeinv_tenant_state
    USING (true)
    WITH CHECK (true);

GRANT USAGE ON SCHEMA tenancy TO homeinv_tenant_state;
GRANT SELECT, UPDATE ON tenancy.tenant TO homeinv_tenant_state;
GRANT CREATE ON SCHEMA tenancy TO homeinv_tenant_state;
ALTER FUNCTION tenancy.set_tenant_lifecycle_state(uuid, text, uuid) OWNER TO homeinv_tenant_state;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_tenant_state;

-- The READER is `homeinv_bootstrap`'s, which is what that role is for. Its
-- SELECT grant and its `bootstrap_lookup` policy on this table are already
-- there, from V22.
GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.lifecycle_state_of_tenant(uuid) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION tenancy.set_tenant_lifecycle_state(uuid, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenancy.lifecycle_state_of_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.set_tenant_lifecycle_state(uuid, text, uuid) TO homeinv_app;
GRANT EXECUTE ON FUNCTION tenancy.lifecycle_state_of_tenant(uuid) TO homeinv_app;
