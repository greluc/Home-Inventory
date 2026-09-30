-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHAT THE INSTANCE ITSELF PERMITTED A PLUGIN (ADR-0066, REQ-NOTI-004).
--
-- The capability model had one level: a tenant administrator grants a plugin a
-- capability for their tenant, and a plugin with no grant can do nothing
-- (09 §9.4). That level cannot answer one question, and the question is not
-- exotic — it is a stage-1 requirement.
--
-- REQ-NOTI-004 says security-relevant account events are ALWAYS reported by
-- e-mail and cannot be switched off by a user or a tenant administrator. A
-- password reset (REQ-SEC-018) is asked for at the login page, and the account
-- asking may be a member of several tenants or of none. There is no tenant to
-- resolve a channel plugin for, and `CallContext` refused a call without one:
-- "a call for no tenant is a call the capability model cannot answer".
--
-- So the model gains a second level rather than the requirement gaining an
-- exception. The instance operator (ADR-0057) grants a plugin a capability for
-- the DEPLOYMENT, and a call made under such a grant carries CALL_SCOPE_INSTANCE
-- and no tenant at all.
--
-- WHAT THIS DELIBERATELY IS NOT
--
-- It is not a way around a tenant's consent. A grant here authorises calls the
-- INSTANCE makes on its own behalf — an account-level obligation the deployment
-- owes a person. It grants no access to any tenant's data: an instance call runs
-- with no tenant context, so every row-level policy in the database yields zero
-- rows, and a plugin calling back into the core on such a call reaches nothing
-- that belongs to a tenant (REQ-SEC-057).
--
-- INSTANCE-WIDE, no `tenant_id` — see the exception list in 07 §7.1. The whole
-- point of the row is that it belongs to no tenant; a tenant column here would
-- be the thing it exists to avoid.

CREATE TABLE plugins.instance_capability_grant (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),

    -- Which plugin, as its manifest names it. No foreign key to
    -- `plugin_registration`, exactly as `capability_grant` has none: a grant
    -- outlives a re-registration of the same plugin, and the registry is the
    -- deployment's description rather than this table's parent.
    plugin_id       text NOT NULL,

    -- One capability id per row, so a grant can be withdrawn and audited on its
    -- own. Same shape as the per-tenant table.
    capability      text NOT NULL,

    -- Which manifest the operator was looking at when they said yes. A later
    -- manifest that asks for more does not touch this row (REQ-PLG-006).
    manifest_digest text NOT NULL,

    -- The grant's time and its author ARE the row's creation and its creator,
    -- as in `capability_grant`. A grant is never edited: it is made or
    -- withdrawn.
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 1,

    UNIQUE (plugin_id, capability)
);

-- "May this plugin do this for the instance" is asked before every instance
-- call, so it is the index that exists.
CREATE INDEX instance_capability_grant_by_plugin
    ON plugins.instance_capability_grant (plugin_id, capability);

-- No row-level security: there is no tenant to key a policy on, and a policy
-- keyed on a context that does not exist would yield zero rows — which would
-- disable the one path this table was added to make possible. What bounds it is
-- the rights below and the fact that only the instance operator writes it
-- (ADR-0057).
GRANT SELECT, INSERT, DELETE ON plugins.instance_capability_grant TO homeinv_app;
GRANT SELECT ON plugins.instance_capability_grant TO homeinv_readonly;

COMMENT ON TABLE plugins.instance_capability_grant IS
    'What the instance operator permitted a plugin to do for the deployment itself (ADR-0066). '
    'Instance-wide and without RLS: an instance call carries no tenant, which is the property '
    'that makes REQ-NOTI-004 reachable for an account that belongs to no tenant.';
