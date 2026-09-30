-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHAT THE DEPLOYMENT OWES AN ACCOUNT (REQ-NOTI-004, REQ-SEC-018, ADR-0066).
--
-- `notification.notification` beside this one is a tenant's queue: a reminder,
-- an invitation, something that happened inside a tenant, delivered on the
-- channels the person subscribed to. None of that fits an account event.
--
-- REQ-NOTI-004 says security-relevant ACCOUNT events are always reported by
-- e-mail and cannot be switched off by a user or a tenant administrator. Two
-- things follow that the queue beside this one cannot do:
--
--   1. There may be no tenant. A password reset is asked for at the login page,
--      and the account may be a member of several tenants or of none.
--   2. There is no subscription to consult. "Always" means the address on the
--      account, whether or not anybody asked for it.
--
-- A tenant's delivery history should not contain another person's account mail
-- either, which is the third reason this is a table rather than a nullable
-- column on the one next door.
--
-- INSTANCE-WIDE, no `tenant_id` -- see the exception list in 07 §7.1.

CREATE TABLE notification.security_notification (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),

    -- Whose account. Instance-wide `identity.app_user` is referenced by id and
    -- without a foreign key, exactly as `tenancy.membership` does it.
    user_id        uuid NOT NULL,

    -- What happened, as the raising block names it: `security.password-reset`,
    -- `security.password-changed`, `security.second-factor-enrolled`,
    -- `security.sessions-ended`. Text and not an enum, for the reason the audit
    -- log's `action` is text: the set grows with every feature and a check
    -- constraint would be a migration per kind.
    kind           text NOT NULL CHECK (length(kind) BETWEEN 1 AND 100),

    -- Where it goes. Copied from the account at the moment the notification is
    -- raised rather than read at delivery time: a reset that told the OLD
    -- address must still tell the old address if the address changes while the
    -- message waits in the queue. That is the half of REQ-SEC-018 that matters.
    address        text NOT NULL CHECK (length(address) BETWEEN 1 AND 500),

    -- Which channel key the plugin answers to. `email` for everything today;
    -- the column exists because the port takes one and the core does not decide
    -- what a channel is (REQ-NOTI-002).
    channel_key    text NOT NULL DEFAULT 'email' CHECK (length(channel_key) BETWEEN 1 AND 60),

    subject        text NOT NULL CHECK (length(subject) <= 300),
    body_text      text NOT NULL,
    body_html      text,
    language       text NOT NULL DEFAULT 'en' CHECK (length(language) BETWEEN 2 AND 10),

    -- The same three states the tenant queue has, and no CANCELLED here either:
    -- a security notification that should not have been raised is a bug in what
    -- raised it.
    state          text NOT NULL DEFAULT 'QUEUED'
                       CHECK (state IN ('QUEUED', 'DELIVERED', 'DEAD_LETTERED')),

    attempts       integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    provider_message_id text,

    -- Stable across every retry, because at-least-once means a channel will see
    -- it twice (REQ-NFR-016). Globally unique: there is no tenant to scope it
    -- with, which is the whole point of this table.
    idempotency_key text NOT NULL UNIQUE,

    created_at     timestamptz NOT NULL DEFAULT now(),
    delivered_at   timestamptz
);

-- What a delivery run asks for: the outstanding work, oldest first. Partial,
-- because a finished notification is never due again.
CREATE INDEX security_notification_due
    ON notification.security_notification (next_attempt_at)
    WHERE state = 'QUEUED';

-- "What have we told this account" -- the operator's question when somebody
-- says they never got the mail.
CREATE INDEX security_notification_by_account
    ON notification.security_notification (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- WHAT HAPPENED ON EACH TRY (REQ-NOTI-005)
-- ---------------------------------------------------------------------------
--
-- Append-only, like `notification.delivery_attempt`. The requirement asks for
-- attempts to be logged with retries and dead-lettering, and it does not stop
-- applying because the recipient has no tenant.

CREATE TABLE notification.security_delivery_attempt (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),

    notification_id uuid NOT NULL
        REFERENCES notification.security_notification (id) ON DELETE CASCADE,

    attempt_no     integer NOT NULL CHECK (attempt_no > 0),
    attempted_at   timestamptz NOT NULL DEFAULT now(),

    outcome        text NOT NULL CHECK (outcome IN ('ACCEPTED', 'DEDUPLICATED', 'REFUSED', 'FAILED')),

    -- What the far side said, or what went wrong. Never a credential, never the
    -- message body, and never the token the message carried.
    detail         text CHECK (detail IS NULL OR length(detail) <= 1000),

    UNIQUE (notification_id, attempt_no)
);

-- No row-level security on either table: there is no tenant to key a policy on,
-- and a policy keyed on a context that does not exist would yield zero rows --
-- which would stop the delivery run from seeing its own work. What bounds them
-- is the rights below and the fact that no tenant-facing endpoint reads them.
GRANT SELECT, INSERT, UPDATE ON notification.security_notification TO homeinv_app;
GRANT SELECT, INSERT ON notification.security_delivery_attempt TO homeinv_app;
GRANT SELECT ON notification.security_notification, notification.security_delivery_attempt
    TO homeinv_readonly;
-- Retention: an account's security mail is removed with the account, and old
-- delivered rows are housekeeping's to prune.
GRANT DELETE ON notification.security_notification TO homeinv_housekeeping;

COMMENT ON TABLE notification.security_notification IS
    'Account-level security mail (REQ-NOTI-004). Instance-wide and without RLS: the recipient may '
    'belong to no tenant, and "always" cannot be conditional on a subscription (ADR-0066).';
COMMENT ON COLUMN notification.security_notification.address IS
    'Copied when the notification is raised, not read at delivery time -- so a reset still reaches '
    'the address the account had when it was asked for (REQ-SEC-018).';
