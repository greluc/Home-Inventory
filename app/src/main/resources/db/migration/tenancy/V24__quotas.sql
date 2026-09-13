-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- What a tenant may use, and what it has used (REQ-TEN-009).
--
-- Two tables and not one, because the two answer to different people. The limit
-- is set by the instance operator and changes rarely; the usage is written by the
-- application on every create and every removal. Putting them in one row would
-- mean every item created contends with an operator reading the limits.
--
-- ADR-0003: "Quotas (item count, bytes, tenants per user, API calls) exist from
-- the start, because with open tenant creation they are abuse protection and do
-- not work as a retrofit." This is that mechanism for the three quotas that are
-- STOCKS. The fourth, API calls, is a monthly counter and lives in Valkey — a
-- write to PostgreSQL on every request would be a write to PostgreSQL on every
-- request, and losing that counter to a restart costs an over-generous month
-- rather than anything durable.

CREATE TABLE tenancy.tenant_quota (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    quota       text NOT NULL
                CHECK (quota IN ('ITEM_COUNT', 'STORAGE_BYTES', 'PLUGIN_COUNT', 'API_CALLS')),
    -- Zero is legal and means "none for now": a way to stop a tenant growing
    -- without removing the tenant. NULL is not used — a row's absence is how
    -- "the instance-wide default applies" is said, so a row always has a number.
    permitted   bigint NOT NULL CHECK (permitted >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    deleted_at  timestamptz,
    version     bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, quota)
);

ALTER TABLE tenancy.tenant_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant_quota FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.tenant_quota
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 07 §7.8 describes this as "carried forward, not counted on every query", and
-- that is the whole design: counting a million items to answer "may I create one"
-- would make the guard cost more than the operation it guards.
CREATE TABLE tenancy.quota_usage (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    quota       text NOT NULL
                CHECK (quota IN ('ITEM_COUNT', 'STORAGE_BYTES', 'PLUGIN_COUNT', 'API_CALLS')),
    -- Never negative. A release that would take it below zero clamps instead,
    -- because a counter that can go negative hides the drift that produced it —
    -- and drift is what the reconciliation run of REQ-NFR-073 exists to correct.
    used        bigint NOT NULL DEFAULT 0 CHECK (used >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    deleted_at  timestamptz,
    version     bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    -- The target of the upsert every increment runs through.
    UNIQUE (tenant_id, quota)
);

ALTER TABLE tenancy.quota_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.quota_usage FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.quota_usage
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON tenancy.tenant_quota, tenancy.quota_usage TO homeinv_app;
GRANT SELECT ON tenancy.tenant_quota, tenancy.quota_usage TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- Setting a tenant's quota, from outside that tenant
-- ---------------------------------------------------------------------------
--
-- The instance operator decides what a tenant may use (13 §13.9), and the
-- operator is not a member of that tenant — ADR-0057 is explicit that the flag
-- grants nothing inside one. So the write cannot go through a tenant context,
-- and it must not go through BYPASSRLS.
--
-- 07 §7.5 names the one way that is left: "Cross-tenant administration goes
-- through explicit, logged SECURITY DEFINER functions — never BYPASSRLS." This
-- is that function. It is owned by `homeinv_quota`, a NOLOGIN role that owns
-- nothing else and whose policies reach one table holding no domain data. The
-- permission check is the application's: `/api/v1/instance/**` requires the
-- INSTANCE_OPERATOR entitlement before this is called, and every call is logged
-- with the operator, the tenant and the new value.

CREATE FUNCTION tenancy.set_tenant_quota(
    p_tenant_id uuid,
    p_quota     text,
    p_permitted bigint,
    p_actor     uuid)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `tenant_quota`.
SET search_path = tenancy, pg_temp
AS $$
    INSERT INTO tenancy.tenant_quota (tenant_id, quota, permitted, created_by, updated_by)
    VALUES (p_tenant_id, p_quota, p_permitted, p_actor, p_actor)
    ON CONFLICT (tenant_id, quota) DO UPDATE
    SET permitted = excluded.permitted,
        updated_by = excluded.updated_by,
        updated_at = now(),
        version = tenancy.tenant_quota.version + 1
$$;

-- Reading them back, for the operator's view. Limits only: what a tenant has
-- USED is its own data, and an operator who wants that impersonates (REQ-SEC-072)
-- rather than being handed a window into it here.
CREATE FUNCTION tenancy.quotas_of_tenant(p_tenant_id uuid)
RETURNS TABLE (quota text, permitted bigint)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
    SELECT q.quota, q.permitted
    FROM tenancy.tenant_quota q
    WHERE q.tenant_id = p_tenant_id
      AND q.deleted_at IS NULL
    ORDER BY q.quota
$$;

-- The policies the owning role needs. FORCE ROW LEVEL SECURITY applies to the
-- table owner as well, so without these the functions above would silently write
-- and read nothing — the same trap V7 documents.
CREATE POLICY quota_administration ON tenancy.tenant_quota
    TO homeinv_quota
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON tenancy.tenant_quota TO homeinv_quota;

-- USAGE as well as CREATE, and USAGE is the one that stays. A SECURITY DEFINER
-- function executes AS this role, so without USAGE on the schema it cannot reach
-- its own table and fails with "permission denied for schema tenancy" at call
-- time rather than at creation time. CREATE is needed only to receive ownership
-- and is taken straight back.
GRANT USAGE, CREATE ON SCHEMA tenancy TO homeinv_quota;
ALTER FUNCTION tenancy.set_tenant_quota(uuid, text, bigint, uuid) OWNER TO homeinv_quota;
ALTER FUNCTION tenancy.quotas_of_tenant(uuid) OWNER TO homeinv_quota;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_quota;

REVOKE ALL ON FUNCTION tenancy.set_tenant_quota(uuid, text, bigint, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenancy.quotas_of_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.set_tenant_quota(uuid, text, bigint, uuid) TO homeinv_app;
GRANT EXECUTE ON FUNCTION tenancy.quotas_of_tenant(uuid) TO homeinv_app;
