-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The second factor: what an account authenticates with besides its password
-- (REQ-AUTH-002, 12 §12.4).
--
-- ---------------------------------------------------------------------------
-- WHY IT CARRIES NO TENANT
-- ---------------------------------------------------------------------------
--
-- The seventh entry on the closed list in 07 §7.1, and it is `identity.app_user`'s
-- reason exactly: authentication happens BEFORE any tenant is known. The second
-- factor is asked for between the password and the session, and at that moment
-- there is no `app.tenant_id` to scope a policy with — the value is derived from
-- the membership the login goes on to choose. A tenant column here would have to
-- be read before the row carrying it had been found.
--
-- What protects it instead: it is reachable only through the caller's own
-- session. `/api/v1/auth/mfa` reads and writes the credentials of whoever is
-- signed in, or of whoever has just proved a password; no endpoint takes a user
-- id, and there is no instance-operator path to it either — an operator
-- administers entitlements, and somebody else's authenticator is not one.
--
-- ---------------------------------------------------------------------------
-- WHAT IS STORED, AND HOW
-- ---------------------------------------------------------------------------
--
--   * TOTP — the shared secret, AES-GCM-sealed under the instance's credential
--     key, which is mounted as a file and never in the database. A database
--     dump is therefore not a set of working second factors, which is the whole
--     point of having a second one.
--   * RECOVERY_CODE — one row per code, holding an Argon2id hash and never the
--     code. A recovery code is a password by another name (`REQ-AUTH-002`: single
--     use), so it is stored the way a password is.
--   * PASSKEY — arrives with WebAuthn and brings the columns it needs. It is in
--     the check constraint already, because a constraint that has to be widened
--     later is a migration nobody plans for (the same argument V31 makes about
--     SUSPENDED).

CREATE TABLE identity.credential (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id        uuid NOT NULL REFERENCES identity.app_user (id),
    kind           text NOT NULL CHECK (kind IN ('TOTP', 'RECOVERY_CODE', 'PASSKEY')),
    -- What the person calls this authenticator, for a list they can act on.
    label          text,
    -- The sealed secret or the hash, depending on the kind. Never the value.
    material       text NOT NULL,
    -- A TOTP secret counts only once a code generated from it has been shown:
    -- an enrolment that was never confirmed is somebody who scanned a QR code
    -- and closed the app, and locking them out of their own account for it would
    -- be the wrong end of the trade.
    confirmed_at   timestamptz,
    -- For TOTP, when a code from it was last accepted — which is also the replay
    -- guard: a code from a time step already spent is refused (RFC 6238 §5.2).
    -- For a recovery code, it is what "single use" means: a code with this set
    -- is spent.
    last_used_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    deleted_at     timestamptz,
    version        bigint NOT NULL DEFAULT 1
);

-- One TOTP secret per account, live. A second enrolment replaces the first
-- rather than running beside it: two secrets would be two answers to "is this
-- code right", and removing one of them would leave the other working.
CREATE UNIQUE INDEX credential_one_live_totp
    ON identity.credential (user_id)
    WHERE kind = 'TOTP' AND deleted_at IS NULL;

CREATE INDEX credential_of_user
    ON identity.credential (user_id, kind)
    WHERE deleted_at IS NULL;

-- No DELETE. A removed authenticator is tombstoned like every other removal
-- (07 §7.1, rule 5), and the audit question "who could sign in, how, and when"
-- is asked about credentials that no longer exist.
GRANT SELECT, INSERT, UPDATE ON identity.credential TO homeinv_app;
GRANT SELECT ON identity.credential TO homeinv_readonly;
