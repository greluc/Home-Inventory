-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHAT ONE TENANT CONFIGURED FOR ONE PLUGIN (ADR-0073, REQ-PLG-017).
--
-- 09 §9.3 has declared `settings` in the manifest since the chapter was
-- written, `core:setting:read` has been in the capability set for as long, and
-- there was NO WAY FOR A PLUGIN TO OBTAIN ONE: no table, no endpoint, no field
-- on the wire. The whole of it was a surface nobody had built, which is not
-- visible until the first plugin needs to be told something.
--
-- WHAT BELONGS HERE, AND WHAT DELIBERATELY DOES NOT (ADR-0073)
--
-- Here: what a TENANT decides. The webhook signing secret this tenant agreed
-- with its own receiver, the metadata source it prefers, its own API token for a
-- service it has an account with.
--
-- Not here: what the OPERATOR decides for the whole installation — the SMTP
-- host, the S3 endpoint and its keys, the OIDC client secret. Those are the
-- plugin container's own configuration, mounted into it from
-- `deploy/services.yaml` like every other service's credentials, and they never
-- pass through the core at all. A plugin is a container an operator declares
-- (REQ-PLG-013), so it is configured like one.
--
-- A SECRET IS SEALED, with the same envelope encryption as a `sensitive` field
-- (ADR-0019): a data key per tenant, wrapped by the deployment's master key,
-- with the tenant, the plugin and the setting key bound into the ciphertext as
-- additional authenticated data. A dump of this table is therefore a dump of
-- ciphertext, and a value cannot be moved to another plugin, another key or
-- another tenant and still open.

CREATE TABLE plugins.plugin_setting (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)

    -- Which plugin, as its manifest names it. No foreign key to
    -- `plugin_registration`, exactly as the two grant tables have none: what a
    -- tenant configured outlives a re-registration of the same plugin, and the
    -- registry is the deployment's description rather than this table's parent.
    plugin_id       text NOT NULL,

    -- The key the manifest declares. A key the current manifest does not declare
    -- is refused when it is written -- consent to configure something a plugin
    -- never asked for is the same mistake as granting a capability it never
    -- asked for (REQ-PLG-006), and it would sit here waiting to become live the
    -- day an update declared it.
    setting_key     text NOT NULL,

    -- The value as text, whatever the declared type is: an integer setting is
    -- "60" here and a boolean is "true". The type lives in the manifest, which
    -- is the document that can change with the plugin, and a column type here
    -- would have to be migrated when it did.
    --
    -- SEALED when `sealed` is true, and then this is the base64url ciphertext of
    -- ADR-0019 rather than anything readable.
    value           text NOT NULL,

    -- Whether `value` is sealed. Written from the manifest's declared type at
    -- the moment the value is stored, and read rather than re-derived when it is
    -- opened: a plugin update that stopped calling a setting a secret must not
    -- turn the stored ciphertext into a value somebody reads out as plaintext.
    sealed          boolean NOT NULL DEFAULT false,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (tenant_id, plugin_id, setting_key),
    UNIQUE (tenant_id, id)
);

ALTER TABLE plugins.plugin_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugins.plugin_setting FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plugins.plugin_setting
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "What did this tenant configure for this plugin" is asked before every call
-- that carries settings, so it is the index that exists. The unique constraint
-- above would serve it; this one is named for what it is for.
CREATE INDEX plugin_setting_by_plugin
    ON plugins.plugin_setting (tenant_id, plugin_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON plugins.plugin_setting TO homeinv_app;
GRANT SELECT ON plugins.plugin_setting TO homeinv_readonly;

COMMENT ON TABLE plugins.plugin_setting IS
    'What one tenant configured for one plugin (ADR-0073, REQ-PLG-017). Per tenant by definition: '
    'what the operator configures for the whole installation is the plugin container''s own '
    'environment and never passes through the core.';
COMMENT ON COLUMN plugins.plugin_setting.sealed IS
    'Whether `value` is the sealed form of ADR-0019. Read rather than re-derived from the current '
    'manifest, so that a plugin update which stopped calling a setting a secret cannot turn stored '
    'ciphertext into something a surface prints as plaintext.';
