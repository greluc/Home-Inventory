-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The login lookup returns the role as well as the tenant.
--
-- `V7` returned the tenant alone, which was everything the session needed while
-- nothing asked "may they". The authorization block asks, on every request, and
-- the answer is the membership's role — so it has to travel with the tenant into
-- the session rather than being re-read per request from a table the caller's
-- own policies govern.
--
-- Reading it here, once, through the same SECURITY DEFINER function is the point:
-- at login there is no tenant context yet, so an ordinary read of
-- `tenancy.membership` returns nothing (07 §7.5, and the reason V7 exists at
-- all).
--
-- The return type changes, so the function is dropped and recreated. PostgreSQL
-- refuses CREATE OR REPLACE across a changed RETURNS TABLE.

DROP FUNCTION IF EXISTS tenancy.tenants_of_user(uuid);

CREATE FUNCTION tenancy.tenants_of_user(p_user_id uuid)
RETURNS TABLE (tenant_id uuid, role text)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- The search path is pinned. A SECURITY DEFINER function that resolves names
-- through the caller's search_path can be pointed at a different `membership`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT m.tenant_id, m.role
    FROM tenancy.membership m
    WHERE m.user_id = p_user_id
      AND m.deleted_at IS NULL
    ORDER BY m.created_at ASC
$$;

-- Ownership and grants have to be restored: they belonged to the function that
-- was just dropped, not to its name.
--
-- PostgreSQL requires the NEW owner to hold CREATE on the function's schema
-- before it will transfer ownership, and V7 revoked it again immediately after
-- doing exactly this. So it is granted for one statement and taken straight
-- back: `homeinv_bootstrap` is meant to own one function, not to be able to make
-- more.
GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenants_of_user(uuid) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenants_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_of_user(uuid) TO homeinv_app;
