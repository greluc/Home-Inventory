-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The one lookup that has to run before a tenant context exists.
--
-- Authentication presents an e-mail address. The address identifies a user; the
-- user's memberships identify the tenant. But `tenancy.membership` is
-- tenant-scoped and RLS-protected, so with no `app.tenant_id` set the policy
-- yields zero rows — and the login could never find the tenant it is about to
-- set. The bootstrap is genuinely circular.
--
-- 07 §7.5 names exactly one way out of it: "Cross-tenant administration: an
-- explicit, logged SECURITY DEFINER function with its own permission check —
-- never BYPASSRLS". This is that function, and it is deliberately the smallest
-- one that closes the circle:
--
--   * it takes a user id and returns tenant ids, nothing else;
--   * it returns no membership row, no role, no name — a caller learns which
--     tenants a user belongs to and not one fact more;
--   * it is owned by the migrator, so the application cannot redefine it;
--   * `search_path` is pinned, because a SECURITY DEFINER function that resolves
--     names through the caller's search_path is the classic way to turn one into
--     privilege escalation.
--
-- What protects it is that knowing a user's id already requires having found the
-- user, which at stage 0 means having presented their password.

CREATE FUNCTION tenancy.tenants_of_user(p_user_id uuid)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
    SELECT m.tenant_id
    FROM tenancy.membership m
    WHERE m.user_id = p_user_id
      AND m.deleted_at IS NULL
    ORDER BY m.created_at ASC
$$;

-- EXECUTE is not granted to PUBLIC by default for functions created this way in
-- PostgreSQL 15 and later, but saying so explicitly costs nothing and survives a
-- future default change.
REVOKE ALL ON FUNCTION tenancy.tenants_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.tenants_of_user(uuid) TO homeinv_app;
