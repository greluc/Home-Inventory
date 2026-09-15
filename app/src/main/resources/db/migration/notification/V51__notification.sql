-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- NOTIFICATIONS (REQ-NOTI-002, REQ-NOTI-005, REQ-NOTI-006, 04 `notification`).
--
-- Every channel is a plugin — e-mail and webhook included — because every one of
-- them talks to a host outside the deployment (ADR-0026). Nothing here knows
-- what a channel is; it knows a channel KEY, which the plugin runtime resolves.
--
-- WHY DELIVERY IS A ROW AND NOT A QUEUE MESSAGE
--
-- REQ-NOTI-005 asks for attempts to be logged, with retries and dead-lettering.
-- A queue gives retries and hides them: a message redelivered five times leaves
-- five invisible attempts and, at the end, a dead-letter queue somebody has to
-- know to look in. A row with a state and a next attempt time gives the same
-- retries and answers "what happened to the invitation I sent" from the same
-- place a person is already looking.
--
-- It also works in `minimal`, which has no RabbitMQ at all (06 §6.7). A
-- notification path that needed a broker would be a path that does not exist in
-- the profile most installations start with.

CREATE SCHEMA IF NOT EXISTS notification;
GRANT USAGE ON SCHEMA notification TO homeinv_app, homeinv_readonly;

-- ---------------------------------------------------------------------------
-- WHAT A PERSON WANTS TO BE TOLD ABOUT (REQ-NOTI-006)
-- ---------------------------------------------------------------------------
CREATE TABLE notification.subscription (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- Whose preference. Instance-wide `identity.app_user` is referenced by id
    -- and without a foreign key, exactly as `tenancy.membership` does it.
    user_id        uuid NOT NULL,

    -- What about, as the block that raises it names the kind. Text and not an
    -- enum for the reason the audit log's `action` is text: the set grows with
    -- every feature, and a check constraint would be a migration per kind.
    kind           text NOT NULL CHECK (length(kind) BETWEEN 1 AND 100),

    -- Which channel, as a plugin's manifest names it: `email`, `webhook`.
    channel_key    text NOT NULL CHECK (length(channel_key) BETWEEN 1 AND 60),

    -- Where, in that channel's own address scheme. The plugin validates it; this
    -- only stores it.
    address        text NOT NULL CHECK (length(address) BETWEEN 1 AND 500),

    -- Off rather than deleted, so that turning something back on restores the
    -- address a person typed instead of asking for it again.
    enabled        boolean NOT NULL DEFAULT true,

    version        bigint NOT NULL DEFAULT 1,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     uuid,

    -- One preference per person, kind and channel. A second row would be a
    -- second answer to "do they want this here".
    UNIQUE (tenant_id, user_id, kind, channel_key)
);

ALTER TABLE notification.subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.subscription FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.subscription
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON notification.subscription TO homeinv_app;
GRANT SELECT ON notification.subscription TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- ONE THING TO TELL SOMEBODY
-- ---------------------------------------------------------------------------
CREATE TABLE notification.notification (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,
    user_id        uuid NOT NULL,
    kind           text NOT NULL CHECK (length(kind) BETWEEN 1 AND 100),

    channel_key    text NOT NULL CHECK (length(channel_key) BETWEEN 1 AND 60),
    address        text NOT NULL CHECK (length(address) BETWEEN 1 AND 500),

    subject        text NOT NULL CHECK (length(subject) <= 300),
    body_text      text NOT NULL,
    body_html      text,
    language       text NOT NULL DEFAULT 'en' CHECK (length(language) BETWEEN 2 AND 10),

    -- QUEUED until something delivers it, DELIVERED when a channel accepted it,
    -- DEAD_LETTERED when the attempts ran out. No CANCELLED: a notification that
    -- should not have been raised is a bug in what raised it.
    state          text NOT NULL DEFAULT 'QUEUED'
                       CHECK (state IN ('QUEUED', 'DELIVERED', 'DEAD_LETTERED')),

    attempts       integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),

    -- When the next attempt is due. NULL once the notification is finished, so
    -- the index below holds only work that is actually outstanding.
    next_attempt_at timestamptz,

    -- What the channel called it, once one accepted it. For tracing a delivery
    -- with whoever runs the mail server.
    provider_message_id text,

    -- The key a channel deduplicates on. Stable across every retry of this
    -- notification, because at-least-once means a channel will see it twice
    -- (REQ-NFR-016).
    idempotency_key text NOT NULL,

    created_at     timestamptz NOT NULL DEFAULT now(),
    delivered_at   timestamptz,

    UNIQUE (tenant_id, idempotency_key)
);

-- The composite key every reference to this table uses, tenant-qualified as
-- 07 §7.5 requires of a reference between two tenant-scoped tables.
ALTER TABLE notification.notification ADD CONSTRAINT notification_tenant_id_key
    UNIQUE (tenant_id, id);

ALTER TABLE notification.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.notification FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.notification
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON notification.notification TO homeinv_app;
GRANT SELECT ON notification.notification TO homeinv_readonly;
GRANT SELECT, DELETE ON notification.notification TO homeinv_housekeeping;

-- What is due, cheaply. Partial, so the index holds outstanding work and not the
-- history beside it.
CREATE INDEX notification_due ON notification.notification (next_attempt_at)
    WHERE state = 'QUEUED';

-- ---------------------------------------------------------------------------
-- WHAT HAPPENED ON EACH TRY (REQ-NOTI-005)
--
-- Append-only. An attempt that could be rewritten would make the delivery log
-- useless for the one question it is asked: "did this person get it, and if
-- not, why not".
-- ---------------------------------------------------------------------------
CREATE TABLE notification.delivery_attempt (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,

    -- Composite, tenant-qualified, as every reference between two tenant-scoped
    -- tables is (07 §7.5).
    notification_id uuid NOT NULL,
    FOREIGN KEY (tenant_id, notification_id)
        REFERENCES notification.notification (tenant_id, id) ON DELETE CASCADE,

    attempt_no     integer NOT NULL CHECK (attempt_no > 0),
    attempted_at   timestamptz NOT NULL DEFAULT now(),

    outcome        text NOT NULL CHECK (outcome IN ('ACCEPTED', 'DEDUPLICATED', 'REFUSED', 'FAILED')),

    -- What the far side said, or what went wrong: an SMTP response line, an HTTP
    -- status, the kind of a plugin failure. Never a credential and never the
    -- message body.
    detail         text CHECK (detail IS NULL OR length(detail) <= 1000),

    UNIQUE (tenant_id, notification_id, attempt_no)
);

ALTER TABLE notification.delivery_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.delivery_attempt FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.delivery_attempt
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON notification.delivery_attempt TO homeinv_app;
GRANT SELECT ON notification.delivery_attempt TO homeinv_readonly;
GRANT SELECT, DELETE ON notification.delivery_attempt TO homeinv_housekeeping;


-- ---------------------------------------------------------------------------
-- WHICH TENANTS HAVE SOMETHING WAITING
--
-- The delivery run has no tenant context — it is looking for the ones that need
-- one. That is a read across tenants, and the sanctioned shape for it is an
-- explicit SECURITY DEFINER function owned by `homeinv_bootstrap` with one
-- permissive policy (07 §7.5, V7). `FORCE ROW LEVEL SECURITY` means a function
-- owned by the migrator would be blocked by the very policy it needs to step
-- around.
--
-- It returns tenant ids and nothing else: a caller learns which tenants have
-- something waiting and not one fact about any notification.
-- ---------------------------------------------------------------------------
CREATE FUNCTION notification.tenants_with_due_notifications(at timestamptz)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = notification, pg_temp
AS $$
    SELECT DISTINCT n.tenant_id
    FROM notification.notification n
    WHERE n.state = 'QUEUED' AND n.next_attempt_at <= at;
$$;

GRANT USAGE, CREATE ON SCHEMA notification TO homeinv_bootstrap;
ALTER FUNCTION notification.tenants_with_due_notifications(timestamptz)
    OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA notification FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION notification.tenants_with_due_notifications(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification.tenants_with_due_notifications(timestamptz) TO homeinv_app;

-- The one permissive policy, for the one role, on the one table. SELECT only.
CREATE POLICY bootstrap_due_lookup ON notification.notification
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON notification.notification TO homeinv_bootstrap;
