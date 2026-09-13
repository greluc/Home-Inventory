-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Who runs the instance, and who may create a tenant on it (ADR-0057).
--
-- Three columns, and none of them is a permission. The six roles in
-- `authorization.Role` are evaluated against a tenant context; these are read
-- where there is none — when a person creates their FIRST tenant, or when the
-- operator administers the instance itself. A role could never carry them: a
-- tenant that could grant "may create a tenant" would be granting the right to
-- create a second one.
--
-- `identity.app_user` is the one instance-wide table there is (07 §7.1), which is
-- why all three sit here: each is a property of a person on this instance rather
-- than of a person within a tenant.

ALTER TABLE identity.app_user
    -- The instance operator (REQ-SEC-072, 13 §13.9). Grants nothing inside any
    -- tenant: row-level security answers to `app.tenant_id` and not to who asked,
    -- so an operator who is not a member of a tenant still reads none of its
    -- rows. Reaching a tenant's data is what impersonation is for, and that is a
    -- separate, audited, time-limited act.
    ADD COLUMN instance_operator  boolean NOT NULL DEFAULT false,
    -- REQ-TEN-002's "entitled user". Off by default, granted by an operator —
    -- and deliberately NOT implied by being one, so that administering an
    -- instance does not silently accumulate tenants of one's own.
    ADD COLUMN may_create_tenants boolean NOT NULL DEFAULT false,
    -- The bound on that entitlement. NULL means "the instance-wide default",
    -- which is `HOMEINV_TENANTS_PER_USER`; a number here overrides it for this
    -- one person, which is the shape 13 §13.9 uses for quotas throughout.
    --
    -- The ceiling of 200 is REQ-NFR-010's page size, and it is here rather than
    -- only in the application because of what it buys: a person can be in at most
    -- one page of tenants, so `GET /api/v1/me/tenants` answers completely in one
    -- page and needs no cursor. A limit above the page size would mean a switcher
    -- that silently omits tenants somebody belongs to.
    ADD COLUMN tenant_limit       integer
        CHECK (tenant_limit IS NULL OR (tenant_limit >= 0 AND tenant_limit <= 200));

-- "Who can administer this instance" is a question an operator asks and an
-- auditor asks; over a table of people it must not be a sequential scan that
-- gets slower as the instance grows.
CREATE INDEX app_user_instance_operator ON identity.app_user (id)
    WHERE instance_operator AND deleted_at IS NULL;
