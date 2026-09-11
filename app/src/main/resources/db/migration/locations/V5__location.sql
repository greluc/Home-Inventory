-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The storage location tree (07 §7.4).
--
-- `parent_id` and `path` are both maintained: `parent_id` is the truth for
-- foreign keys and cascades, `path` is the accelerator that turns "everything
-- below the cellar" into one index lookup instead of a recursive query
-- (REQ-CORE-044, REQ-CORE-049).

CREATE TABLE locations.location (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    category_id     uuid NOT NULL,
    parent_id       uuid,
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    path            ltree NOT NULL,
    depth           int   NOT NULL CHECK (depth >= 0),
    is_mobile       boolean NOT NULL DEFAULT false,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    sealed_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    CONSTRAINT depth_limit CHECK (depth <= 12),
    -- The accelerator has to agree with the truth, or the two drift and nothing
    -- notices. A root has depth 0 and a one-label path.
    CONSTRAINT depth_matches_path CHECK (nlevel(path) = depth + 1),
    -- A root has no parent and a non-root has one; the two columns cannot
    -- disagree about which this row is.
    CONSTRAINT root_has_no_parent CHECK ((depth = 0) = (parent_id IS NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id)
        REFERENCES catalog.location_category (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id)
        REFERENCES locations.location (tenant_id, id)
);

ALTER TABLE locations.location ENABLE ROW LEVEL SECURITY;
ALTER TABLE locations.location FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON locations.location
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX location_path_gist ON locations.location USING gist (path);
CREATE INDEX location_tenant_parent ON locations.location (tenant_id, parent_id);

-- Siblings keep distinct names so a path segment identifies exactly one child,
-- and so the tree a person reads matches the tree the database holds. Partial,
-- because a deleted location leaves a tombstone (07 §7.1, rule 5) and a
-- tombstone must not block re-using the name.
CREATE UNIQUE INDEX location_sibling_name ON locations.location (
    tenant_id,
    coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
    lower(name)
) WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON locations.location TO homeinv_app;
GRANT SELECT ON locations.location TO homeinv_readonly;
