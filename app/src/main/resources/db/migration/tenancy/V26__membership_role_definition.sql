-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Which tenant-owned role a member holds, when it is not one of the six
-- (REQ-TEN-006).
--
-- `role` keeps its check constraint and keeps naming a built-in role. That is
-- not a leftover: a tenant-owned role EXTENDS one of the six (04 §4.3), so there
-- is always a built-in answer to "what is this person at least", and a build that
-- did not know the definition would still refuse and permit the right things.
-- The column below says which definition, and where it is set its permissions are
-- the ones that apply.
--
-- The reference is composite, for the reason every reference between two
-- tenant-scoped tables is: a foreign key check bypasses row-level security, so a
-- single-column one could point across a tenant boundary and succeed (07 §7.5).
-- It is the second cross-schema reference in the system and is on the documented
-- exception list in 07 §7.9.

ALTER TABLE tenancy.membership
    ADD COLUMN role_definition_id uuid,
    ADD CONSTRAINT membership_role_definition_same_tenant
        FOREIGN KEY (tenant_id, role_definition_id)
        REFERENCES authz.role_definition (tenant_id, id);

-- ---------------------------------------------------------------------------
-- The login lookup returns it too
-- ---------------------------------------------------------------------------
--
-- Same reason the role itself travels with the tenant (V11): both are read at
-- login, in the one moment there is no tenant context to read them with, and
-- re-reading per request would be a second path to the same fact.
--
-- The function is dropped and recreated because the return type changes;
-- PostgreSQL refuses CREATE OR REPLACE across a changed RETURNS TABLE.

DROP FUNCTION IF EXISTS tenancy.tenants_of_user(uuid);

CREATE FUNCTION tenancy.tenants_of_user(p_user_id uuid)
RETURNS TABLE (tenant_id uuid, role text, tenant_name text, role_definition_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `membership`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT m.tenant_id, m.role, t.name, m.role_definition_id
    FROM tenancy.membership m
    JOIN tenancy.tenant t ON t.id = m.tenant_id AND t.deleted_at IS NULL
    WHERE m.user_id = p_user_id
      AND m.deleted_at IS NULL
    ORDER BY m.created_at ASC
$$;

-- Ownership and grants belonged to the function that was just dropped, not to
-- its name. PostgreSQL requires the new owner to hold CREATE on the schema before
-- it will transfer ownership; granted for that one statement and taken back.
GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.tenants_of_user(uuid) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.tenants_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_of_user(uuid) TO homeinv_app;
