-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Roles a tenant defines for itself (REQ-TEN-006).
--
-- The schema is `authz` and not `authorization`, because the latter is a
-- reserved word PostgreSQL will not accept as a bare identifier — see ADR-0058
-- for what was measured and what was rejected. The BLOCK is still called
-- `authorization`; this is the one place where the two names differ, and
-- 04 §4.5 says so in its mapping table.
--
-- Stage 0 created no schema here on purpose: "an empty schema passes every check
-- and documents nothing" (V1). This is the migration that fills it.

CREATE SCHEMA IF NOT EXISTS authz;

GRANT USAGE ON SCHEMA authz TO homeinv_app, homeinv_readonly;

-- ---------------------------------------------------------------------------
-- A role a tenant defined
-- ---------------------------------------------------------------------------
--
-- 04 §4.3: "tenant-owned roles EXTEND the built-in ones; they do not replace".
-- So a definition starts from one of the six and adds to it, rather than being
-- assembled from thirty permissions by somebody who has to know all thirty.
--
-- A tenant never defines a new PERMISSION. That would be a tenant deciding what
-- the application does, which is the line ADR-0020 draws for the type system and
-- which holds here for the same reason: the set of permissions is code, and the
-- combinations are data.

CREATE TABLE authz.role_definition (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    name        text NOT NULL
                CHECK (length(btrim(name)) > 0 AND length(name) <= 64),
    description text CHECK (description IS NULL OR length(description) <= 500),
    -- Where it starts. Every permission the base holds is held, and the grants
    -- below add to that set; nothing subtracts, because a role that took a
    -- permission away from its base would be a role whose name lies about what
    -- it is.
    base_role   text NOT NULL
                CHECK (base_role IN ('OWNER','ADMIN','MEMBER','CONTRIBUTOR','VIEWER','GUEST')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    deleted_at  timestamptz,
    version     bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id)
);

-- One live role of a name per tenant, case-insensitively. Partial, so a role that
-- was removed leaves its tombstone (07 §7.1, rule 5) without blocking the name.
CREATE UNIQUE INDEX role_definition_name
    ON authz.role_definition (tenant_id, lower(name))
    WHERE deleted_at IS NULL;

ALTER TABLE authz.role_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.role_definition FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON authz.role_definition
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- What it adds to its base
-- ---------------------------------------------------------------------------
--
-- A row per permission, rather than an array on the definition. Two reasons and
-- neither is style: a row can be audited on its own, and the `UNIQUE` below is
-- what stops the same permission being granted twice and then revoked once.
--
-- `permission` is text and carries no foreign key, because the set of
-- permissions lives in code (`authorization.api.Permission`) and a table of them
-- would be a second copy of a list `AuthorizationRegistryTest` already compares
-- against `docs/reference/permissions.yaml`. An id this build does not know
-- grants nothing — the same treatment an unknown role gets.

CREATE TABLE authz.role_permission (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id          uuid NOT NULL,
    role_definition_id uuid NOT NULL,
    permission         text NOT NULL CHECK (length(permission) <= 100),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    deleted_at         timestamptz,
    version            bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, role_definition_id, permission),
    -- Composite, so a grant cannot cross a tenant boundary even though a foreign
    -- key check bypasses row-level security (07 §7.5).
    CONSTRAINT role_permission_same_tenant
        FOREIGN KEY (tenant_id, role_definition_id)
        REFERENCES authz.role_definition (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE authz.role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.role_permission FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON authz.role_permission
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX role_permission_by_role
    ON authz.role_permission (tenant_id, role_definition_id)
    WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON authz.role_definition TO homeinv_app;

-- DELETE on the grants and not on the definitions, and the difference is the
-- point. A definition is tombstoned, because "who could do what, when" is a
-- question an audit asks about a role that no longer exists. A grant carries no
-- such history: the definition it belongs to does, and editing a role replaces
-- its grants wholesale rather than accumulating a trail of what it once added.
GRANT SELECT, INSERT, UPDATE, DELETE ON authz.role_permission TO homeinv_app;

GRANT SELECT ON authz.role_definition, authz.role_permission TO homeinv_readonly;
