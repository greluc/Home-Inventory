-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The plugin registry and the per-tenant capability model (REQ-PLG-004…006,
-- REQ-PLG-013, 09 §9.4).
--
-- Two tables and they are scoped differently on purpose, which is the whole
-- shape of the capability model:
--
--   * a REGISTRATION is instance-wide. An operator installs a plugin, the way
--     they install any other container; there is no installation from inside the
--     running system (REQ-PLG-013). Like `identity.app_user`, this table carries
--     no `tenant_id` and no row-level security, because it describes the
--     deployment rather than anybody's data;
--   * a GRANT is per tenant, with RLS like everything else. An installed plugin
--     is active for tenant A and invisible to tenant B until B's administrator
--     consents (09 §9.4). A plugin has NO permissions until they are granted --
--     no base entitlement, no "read access, which is harmless anyway".

CREATE SCHEMA IF NOT EXISTS plugins;
GRANT USAGE ON SCHEMA plugins TO homeinv_app, homeinv_readonly;

CREATE TABLE plugins.plugin_registration (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    -- The reverse-domain id from the manifest. What a grant is recorded against,
    -- so it is the natural key and not the surrogate one.
    plugin_id       text NOT NULL UNIQUE
                        CHECK (plugin_id ~ '^[a-z0-9]+(\.[a-z0-9-]+)+$'),
    -- `plugin_version` and not `version`: the latter is the optimistic-lock
    -- column every table here carries (07 §7.1, rule 4), and a plugin's own
    -- SemVer is a different thing that would have taken its name.
    plugin_version  text NOT NULL,
    -- The manifest as it was read, byte for byte. Kept because the signature is
    -- over these bytes and because a grant refers to what an administrator was
    -- shown -- a re-serialised copy would be neither.
    manifest        text NOT NULL,
    -- SHA-256 of the above, hex. What tells one manifest from another without
    -- comparing documents.
    manifest_digest text NOT NULL CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    -- The capability ids the manifest declares, sorted. Denormalised from the
    -- manifest so that "did this update ask for more than before" is a set
    -- comparison rather than a parse (REQ-PLG-006).
    capabilities    text[] NOT NULL DEFAULT '{}',
    contract        text NOT NULL,
    runtime         text NOT NULL CHECK (runtime IN ('out-of-process', 'in-process')),
    -- Where it listens and which certificate may answer there. Pinned like every
    -- other in-deployment peer: the CA signs every service, so trusting it alone
    -- would let any of them answer as this plugin (REQ-SEC-056, ADR-0044).
    endpoint        text,
    fingerprint     text,
    -- Whether the manifest's signature was verified. An unsigned plugin is
    -- permitted only where the operator explicitly allowed it, and this column is
    -- what the administration surface shows a permanent warning from (REQ-PLG-004).
    signed          boolean NOT NULL DEFAULT false,
    -- REGISTERED once the manifest is read and the contract matches; DISABLED
    -- when the operator switched it off, the circuit stayed open, or the
    -- signature stopped verifying (09 §9.4). A plugin whose contract does not
    -- match is never registered at all, and the core starts regardless
    -- (REQ-PLG-008).
    state           text NOT NULL DEFAULT 'REGISTERED'
                        CHECK (state IN ('REGISTERED', 'DISABLED')),
    -- The four audit columns and the lock, like every other table. `created_by`
    -- and `updated_by` stay null here and that is correct rather than missing: a
    -- registration is the OPERATOR's act, and an operator is not a user of any
    -- tenant (REQ-PLG-013, ADR-0057). The columns exist so that rule 4 is a rule
    -- and not a rule with exceptions.
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1
);

CREATE TABLE plugins.capability_grant (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)
    plugin_id       text NOT NULL,
    capability      text NOT NULL,
    -- Which manifest the administrator was looking at when they said yes. A
    -- later manifest that asks for MORE does not touch this row: the plugin
    -- continues with what it was granted and the new capability is simply not
    -- granted, which is what "it never escalates silently" means and what was
    -- decided with the owner on 2026-09-14. The digest is here so that the
    -- administration surface can say "granted against version 1.4.2".
    manifest_digest text NOT NULL,
    -- The grant's time and its author ARE the row's creation and its creator, so
    -- they are the audit columns rather than two more beside them. A grant is
    -- never edited: it is made or withdrawn, which is why `updated_*` stay as
    -- they were written.
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid NOT NULL, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, plugin_id, capability),
    UNIQUE (tenant_id, id)
);

ALTER TABLE plugins.capability_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugins.capability_grant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plugins.capability_grant
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "May this plugin do this, here" is the question asked before every plugin call,
-- so it is the index that exists.
CREATE INDEX capability_grant_by_plugin
    ON plugins.capability_grant (tenant_id, plugin_id, capability);

GRANT SELECT, INSERT, UPDATE ON plugins.plugin_registration TO homeinv_app;
GRANT SELECT, INSERT, DELETE ON plugins.capability_grant TO homeinv_app;
GRANT SELECT ON plugins.plugin_registration, plugins.capability_grant TO homeinv_readonly;

COMMENT ON TABLE plugins.plugin_registration IS
    'What the operator installed (REQ-PLG-013). Instance-wide and without RLS, like app_user: it '
    'describes the deployment rather than anybody''s data.';
COMMENT ON TABLE plugins.capability_grant IS
    'What one tenant permitted one plugin (REQ-PLG-005). A plugin has no permissions at all until '
    'they are granted here -- no base entitlement (09 §9.4).';
COMMENT ON COLUMN plugins.plugin_registration.capabilities IS
    'The capability ids the manifest declares. Denormalised so that "did this update ask for more" '
    'is a set comparison (REQ-PLG-006).';
