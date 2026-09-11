-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The tenant, and who belongs to it.
--
-- Stage 0 runs with one tenant and the UI shows one, but the model is the
-- multi-tenant one from the start: `membership` exists with a single row per
-- user, so stage 1's "users are members of several tenants" (REQ-TEN-003) adds
-- rows rather than migrating a schema while credentials hang off it.

CREATE TABLE tenancy.tenant (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    -- A tenant row is scoped to itself. The discriminator is spelled out rather
    -- than using `id` in the policy, so the RLS check finds the column it looks
    -- for on every domain table and this table needs no exception (07 §7.1).
    tenant_id   uuid NOT NULL GENERATED ALWAYS AS (id) STORED,
    name        text NOT NULL CHECK (length(btrim(name)) > 0),
    locale      text NOT NULL DEFAULT 'de',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    deleted_at  timestamptz,
    version     bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id)
);

ALTER TABLE tenancy.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.tenant
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Membership is tenant data: which people a tenant has, and what they may do.
-- Stage 0 writes `OWNER` only; the remaining built-in roles arrive with
-- REQ-TEN-005 in stage 1 and need no schema change.
CREATE TABLE tenancy.membership (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    -- Single-column on purpose: the composite rule of 07 §7.5 governs references
    -- between two *tenant-scoped* tables, and `app_user` is instance-wide, so
    -- there is no tenant to carry. This is the fourth entry on the cross-schema
    -- exception list in 07 §7.9.
    user_id     uuid NOT NULL REFERENCES identity.app_user (id),
    role        text NOT NULL
                CHECK (role IN ('OWNER','ADMIN','MEMBER','CONTRIBUTOR','VIEWER','GUEST')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    deleted_at  timestamptz,
    version     bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id)
);

-- One live membership per person per tenant. A partial unique index rather than
-- an EXCLUDE constraint: EXCLUDE with `=` on uuid would pull in btree_gist for
-- no gain. Partial, so a removed membership leaves its tombstone (07 §7.1,
-- rule 5) without blocking a later re-invitation.
CREATE UNIQUE INDEX membership_unique_live
    ON tenancy.membership (tenant_id, user_id) WHERE deleted_at IS NULL;

ALTER TABLE tenancy.membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.membership FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.membership
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX membership_user ON tenancy.membership (user_id) WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON tenancy.tenant, tenancy.membership TO homeinv_app;
GRANT SELECT ON tenancy.tenant, tenancy.membership TO homeinv_readonly;
