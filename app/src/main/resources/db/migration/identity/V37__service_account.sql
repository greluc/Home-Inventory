-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Machine access: a token that belongs to a tenant, holds one role, and expires
-- (REQ-AUTH-010, 12 §12.4).
--
-- ---------------------------------------------------------------------------
-- WHY IT IS TENANT-SCOPED, IN THE `identity` SCHEMA
-- ---------------------------------------------------------------------------
--
-- A service account belongs to a tenant: a tenant administrator creates it, it
-- acts inside that tenant and nowhere else, and it goes when the tenant does. So
-- it carries `tenant_id` and a policy like every other domain table — unlike
-- `identity.app_user` and `identity.credential`, which are about a person and
-- are on the closed list of 07 §7.1.
--
-- It lives in `identity` because what it is, is a way of authenticating: 04 §4.3
-- puts `ServiceAccount` there beside `Session` and `Credential`. The block that
-- owns the schema is the block that owns the notion.
--
-- ---------------------------------------------------------------------------
-- THE TOKEN
-- ---------------------------------------------------------------------------
--
-- Stored as a SHA-256 and never as itself — the treatment `REQ-SEC-048` gives an
-- invitation token, for the same reason: the value is shown once, at creation,
-- and a database that could hand it back would make "shown exactly once" untrue.
--
-- The hash is globally unique because a caller presents a token and nothing
-- else: there is no tenant context yet when it is looked up.

CREATE TABLE identity.service_account (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    description     text,
    -- What it may do. The same pair a membership carries: one of the six
    -- built-in roles, and optionally a tenant-owned definition extending it
    -- (REQ-TEN-006), so `AccessControl` answers for a machine exactly as it does
    -- for a person.
    role            text NOT NULL
                    CHECK (role IN ('OWNER','ADMIN','MEMBER','CONTRIBUTOR','VIEWER','GUEST')),
    role_definition_id uuid,
    token_hash      text NOT NULL UNIQUE,
    -- REQ-AUTH-010 asks for an expiry date and means it: a token with none would
    -- be a credential nobody ever reviews.
    expires_at      timestamptz NOT NULL,
    last_used_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    CONSTRAINT service_account_role_definition_same_tenant
        FOREIGN KEY (tenant_id, role_definition_id)
        REFERENCES authz.role_definition (tenant_id, id)
);

ALTER TABLE identity.service_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.service_account FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON identity.service_account
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX service_account_of_tenant
    ON identity.service_account (tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Finding one by its token
-- ---------------------------------------------------------------------------
--
-- The same circularity an invitation has (V23) and the tenant revocation after
-- it (V31): whoever presents a token has no tenant context, and the token is
-- what decides which tenant they are acting for. It goes through a
-- SECURITY DEFINER function owned by the NOLOGIN bootstrap role, which is the
-- way out 07 §7.5 sanctions.
--
-- It returns ids and a role. Deliberately unfiltered on expiry and revocation,
-- so that an expired token and one that never existed take the same path and the
-- application decides — a lookup that filtered would answer them at different
-- speeds.

CREATE FUNCTION identity.service_account_by_token(p_token_hash text)
RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    role text,
    role_definition_id uuid,
    expires_at timestamptz,
    deleted_at timestamptz
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = identity, pg_temp
AS $$
    SELECT s.id, s.tenant_id, s.role, s.role_definition_id, s.expires_at, s.deleted_at
    FROM identity.service_account s
    WHERE s.token_hash = p_token_hash
$$;

-- USAGE as well as CREATE, and CREATE only for the one statement that transfers
-- ownership: PostgreSQL requires a new owner to hold CREATE on the schema, and
-- this role is meant to own one function rather than to be able to make more.
-- Without USAGE the function would be created, owned and then refused at its
-- first call with "permission denied for schema identity" — which is what
-- happened, and is why this comment is here.
GRANT USAGE, CREATE ON SCHEMA identity TO homeinv_bootstrap;
ALTER FUNCTION identity.service_account_by_token(text) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA identity FROM homeinv_bootstrap;

-- The table has FORCE row-level security, so its owner is subject to the policy
-- too and a grant alone would read nothing. One permissive policy, for the one
-- role, on the one table, SELECT only: the lookup reads and never writes. The
-- same arrangement V7 makes for the membership lookup.
CREATE POLICY bootstrap_lookup ON identity.service_account
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON identity.service_account TO homeinv_bootstrap;

REVOKE ALL ON FUNCTION identity.service_account_by_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.service_account_by_token(text) TO homeinv_app;

-- No DELETE: a revoked service account is tombstoned like every other removal
-- (07 §7.1, rule 5), because "which machine did this, and until when could it"
-- is a question an audit asks about one that no longer exists.
GRANT SELECT, INSERT, UPDATE ON identity.service_account TO homeinv_app;
GRANT SELECT ON identity.service_account TO homeinv_readonly;
