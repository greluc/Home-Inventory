-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A membership can be confined to one part of the tree (REQ-TEN-007).
--
-- "A user scoped to 'garage' sees nothing beyond it" is the acceptance, and
-- 12 §12.5 says where the check goes: layer 3, "object scope — checked on the
-- LOADED object: does it belong to the tenant, does it lie in the permitted
-- location subtree", using the `ltree` path. Layer 5, row-level security, stays
-- what it is: the tenant boundary and nothing else.
--
-- The column holds a location ID and not a path. A path in a membership row would
-- be a copy of something that moves — a subtree can be re-parented, and 07 §7.4
-- rewrites every path under it when that happens — so the row would be right
-- until somebody moved the garage. An id is a reference the database checks, and
-- the check that uses it reads today's path.

ALTER TABLE tenancy.membership
    ADD COLUMN scope_location_id uuid,
    -- Composite, so a scope cannot point at another tenant's place even though a
    -- foreign key check bypasses row-level security (07 §7.5). It is the third
    -- cross-schema reference in the system and is on the documented exception
    -- list in 07 §7.9.
    ADD CONSTRAINT membership_scope_same_tenant
        FOREIGN KEY (tenant_id, scope_location_id)
        REFERENCES locations.location (tenant_id, id);

-- ---------------------------------------------------------------------------
-- The login lookup carries it too
-- ---------------------------------------------------------------------------
--
-- For the reason the role and the role definition travel with it (V11, V26):
-- all of them are read at login, in the one moment there is no tenant context to
-- read them with, and a second path to the same fact is one more place for the
-- two to disagree.

DROP FUNCTION IF EXISTS tenancy.tenants_of_user(uuid);

CREATE FUNCTION tenancy.tenants_of_user(p_user_id uuid)
RETURNS TABLE (
    tenant_id          uuid,
    role               text,
    tenant_name        text,
    role_definition_id uuid,
    scope_location_id  uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `membership`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT m.tenant_id, m.role, t.name, m.role_definition_id, m.scope_location_id
    FROM tenancy.membership m
    JOIN tenancy.tenant t ON t.id = m.tenant_id AND t.deleted_at IS NULL
    WHERE m.user_id = p_user_id
      AND m.deleted_at IS NULL
    ORDER BY m.created_at ASC
$$;

GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenants_of_user(uuid) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenants_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_of_user(uuid) TO homeinv_app;
