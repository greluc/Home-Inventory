-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The login lookup returns the tenant's name as well as its id and role.
--
-- REQ-TEN-003 lets a person belong to several tenants and switch between them
-- without signing in again. A switcher that offers ids is not a switcher, so the
-- name has to come out of the same lookup — and it has to come out of THIS one,
-- because the caller has no tenant context at the moment they are choosing which
-- tenant to have. That is the circularity V7 exists for, and it does not go away
-- for the second tenant.
--
-- The widening is deliberate and it is bounded. V7 said the function "returns no
-- membership row, no role, no name — a caller learns which tenants a user belongs
-- to and not one fact more"; V11 added the role because every request needs it,
-- and this adds the name because the person choosing already belongs to every
-- tenant it names. What must not widen is the SET OF ROWS: still one user's own
-- memberships, still nothing about anybody else's.
--
-- Deleted tenants drop out. A tenant in its 30-day grace period is a different
-- case and keeps its rows (REQ-TEN-011); `deleted_at` here means erased.

DROP FUNCTION IF EXISTS tenancy.tenants_of_user(uuid);

CREATE FUNCTION tenancy.tenants_of_user(p_user_id uuid)
RETURNS TABLE (tenant_id uuid, role text, tenant_name text)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- The search path is pinned. A SECURITY DEFINER function that resolves names
-- through the caller's search_path can be pointed at a different `membership`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT m.tenant_id, m.role, t.name
    FROM tenancy.membership m
    JOIN tenancy.tenant t ON t.id = m.tenant_id AND t.deleted_at IS NULL
    WHERE m.user_id = p_user_id
      AND m.deleted_at IS NULL
    ORDER BY m.created_at ASC
$$;

-- The join reads a second table, so the role that executes the function needs to
-- get past that table's policy as well. One permissive SELECT policy, for one
-- role that cannot log in and owns one function — the same shape V7 gave
-- `tenancy.membership`, for the same reason: FORCE ROW LEVEL SECURITY applies to
-- the owner too, so a definer function without this returns nothing and reads as
-- "this user has no tenant".
CREATE POLICY bootstrap_lookup ON tenancy.tenant
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON tenancy.tenant TO homeinv_bootstrap;

-- Ownership and grants belonged to the function that was just dropped, not to
-- its name, so both are restored. PostgreSQL requires the new owner to hold
-- CREATE on the schema before it will transfer ownership; it is granted for that
-- one statement and taken straight back, because this role is meant to own one
-- function rather than to be able to make more.
GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenants_of_user(uuid) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenants_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_of_user(uuid) TO homeinv_app;
