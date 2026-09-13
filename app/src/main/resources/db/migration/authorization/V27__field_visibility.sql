-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Which roles may read which sensitive fields (REQ-TEN-008, REQ-SEC-027).
--
-- 12 §12.5 makes this layer four of five: "`sensitive` fields are REMOVED per
-- role, not masked — masked fields reveal existence and length". So what is
-- stored here is a permission to read a field, and what the application does with
-- it is delete a key from an answer rather than replace its value with asterisks.
--
-- Keyed by the field's KEY and not by a field definition. 04 §4.3's own example
-- is "purchase price only for ADMIN", and a purchase price is the same thing on a
-- book as on a drill: a rule per definition would have to be repeated for every
-- type that has the field, and the repetition is where the two would diverge.
-- It also keeps this schema free of a reference into `catalog`.

CREATE TABLE authz.field_visibility (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id          uuid NOT NULL,
    -- The field's key, as `catalog.field_definition.key` spells it.
    field_key          text NOT NULL
                       CHECK (field_key ~ '^[a-z][A-Za-z0-9]*$' AND length(field_key) <= 64),
    -- Exactly one of the two: a built-in role by name, or a tenant-owned one by
    -- id. A polymorphic `(kind, id)` pair would be a reference the database
    -- cannot check, which is the reason 07 §7.5 gives for every composite key
    -- here and the reason `tagging.tag_assignment` is shaped the same way.
    built_in_role      text
                       CHECK (built_in_role IS NULL OR built_in_role IN
                             ('OWNER','ADMIN','MEMBER','CONTRIBUTOR','VIEWER','GUEST')),
    role_definition_id uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    deleted_at         timestamptz,
    version            bigint NOT NULL DEFAULT 1,
    CONSTRAINT field_visibility_one_role
        CHECK (num_nonnulls(built_in_role, role_definition_id) = 1),
    UNIQUE (tenant_id, id),
    CONSTRAINT field_visibility_role_same_tenant
        FOREIGN KEY (tenant_id, role_definition_id)
        REFERENCES authz.role_definition (tenant_id, id) ON DELETE CASCADE
);

-- One rule per field per role. Partial, so a withdrawn rule leaves its tombstone
-- without blocking the same grant being made again (07 §7.1, rule 5).
CREATE UNIQUE INDEX field_visibility_unique
    ON authz.field_visibility (tenant_id, field_key, coalesce(built_in_role, ''),
                               coalesce(role_definition_id, '00000000-0000-0000-0000-000000000000'))
    WHERE deleted_at IS NULL;

CREATE INDEX field_visibility_by_tenant
    ON authz.field_visibility (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE authz.field_visibility ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.field_visibility FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON authz.field_visibility
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- DELETE, because a rule carries no history of its own: withdrawing one is the
-- act, and the audit log records that it happened. The tombstone above exists so
-- that re-granting is not blocked, not so that a trail accumulates.
GRANT SELECT, INSERT, UPDATE, DELETE ON authz.field_visibility TO homeinv_app;
GRANT SELECT ON authz.field_visibility TO homeinv_readonly;
