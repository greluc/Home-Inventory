-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The person who logs in.
--
-- `identity.app_user` is **instance-wide**: authentication happens before any
-- tenant is known, because the credential presented is an e-mail address and an
-- e-mail address does not name a tenant. A `tenant_id` on this table would have
-- to be read before the row that carries it has been found.
--
-- It is therefore the fifth entry on the closed list in 07 §7.1, and what
-- protects it instead is stated there and here: no endpoint returns a user by
-- anything but their own session or a membership row of the caller's tenant, so
-- the table is reachable only through `tenancy.membership`, which is
-- RLS-protected. Enumeration is answered identically for a known and an unknown
-- address (REQ-SEC-110).

CREATE TABLE identity.app_user (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Case-insensitive and unique instance-wide. `citext` is avoided: it is an
    -- extension with its own collation surprises, and a lowercased functional
    -- index says the same thing with no dependency.
    email           text NOT NULL,
    display_name    text NOT NULL CHECK (length(btrim(display_name)) > 0),
    locale          text NOT NULL DEFAULT 'de',
    -- Argon2id, in PHC string format, so the parameters travel with the hash and
    -- a rehash on login can detect an outdated cost (REQ-AUTH-001).
    password_hash   text NOT NULL,
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    -- Set when the account may not authenticate. Distinct from `deleted_at`:
    -- a locked account still exists and its memberships still resolve.
    locked_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX app_user_email_unique
    ON identity.app_user (lower(email)) WHERE deleted_at IS NULL;

-- No row-level security by tenant: there is no tenant at this point. The table
-- is on the instance-wide list in 07 §7.1 for exactly this reason.
-- The application may read and write it; it may never delete a row, because a
-- deleted user would orphan audit entries that name them (07 §7.1, rule 5).
GRANT SELECT, INSERT, UPDATE ON identity.app_user TO homeinv_app;
GRANT SELECT ON identity.app_user TO homeinv_readonly;
