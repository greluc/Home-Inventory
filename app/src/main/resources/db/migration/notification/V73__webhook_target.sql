-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WEBHOOK TARGETS, AND THE HALF OF REQ-API-010 THAT IS THE CORE'S.
--
-- `plugins/webhook/` has existed since 2026-09-20 and can sign and deliver
-- anything it is handed. What did not exist is the thing that hands it
-- something: a tenant saying "when an item moves, tell this URL". That is here.
--
-- WHY THE DELIVERY IS A `notification.notification` ROW AND NOT ITS OWN TABLE
--
-- Because the retry schedule, the attempt log and the dead letter already exist
-- there, complete and tested (V51, REQ-NOTI-005), and a second copy of a backoff
-- is a second thing to get wrong. `user_id` therefore becomes nullable and a
-- `webhook_target_id` joins it, with a CHECK that exactly one of the two is set:
-- a row is something to tell a PERSON or something to deliver to a TARGET, never
-- both and never neither.
--
-- The consequence is stated rather than discovered: a webhook delivery appears
-- in the same delivery log a person's notifications do, which is what makes
-- `GET /api/v1/webhooks/{id}/deliveries` a filter rather than a second story.
--
-- WHAT A DELIVERY CARRIES (ADR-0078)
--
-- The event type, the moment and the subject's id -- never the name, never a
-- field. A receiver re-reads through the ordinary API, which applies the
-- ordinary permissions. The core writes that document into `body_text` and the
-- plugin wraps it in its own signed envelope.

-- ---------------------------------------------------------------------------
-- WHERE A TENANT WANTS TO BE TOLD
-- ---------------------------------------------------------------------------
CREATE TABLE notification.webhook_target (
    id             uuid PRIMARY KEY,       -- assigned by the application: the secret below is
                                           -- sealed against it, so it has to exist before the row
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- WHERE. `https` only and enforced here as well as in the plugin, because
    -- this column is what an operator reads when they are asked why something
    -- left the deployment in clear text. The plugin refuses anything else too
    -- (REQ-SEC-034) -- two checks, deliberately, on the one value in this system
    -- that a tenant chooses and something else then connects to.
    url            text NOT NULL CHECK (url ~ '^https://' AND length(url) BETWEEN 12 AND 500),

    -- What a person calls it in the list of targets.
    description    text CHECK (description IS NULL OR length(description) <= 300),

    -- WHICH EVENTS, by the names in `docs/reference/event-types.yaml`.
    --
    -- The set is NOT a CHECK constraint, and that is a departure from
    -- `notification_rule.trigger_kind` worth stating: a trigger kind is a closed
    -- set of six that changes when the product changes, and an event type is
    -- added by every feature that raises an event. A CHECK would mean a
    -- migration per event. What holds the set instead is `EventTypes`, which
    -- refuses an unknown name at the API, and `EventTypeRegistryTest`, which
    -- fails when the code and the registry disagree in either direction.
    event_types    text[] NOT NULL CHECK (cardinality(event_types) BETWEEN 1 AND 50),

    -- THE SIGNING SECRET, PER TARGET AND NOT PER TENANT (ADR-0077).
    --
    -- Sealed with the envelope encryption of ADR-0019, bound to this row's id
    -- and this key, so it cannot be moved to another target or another tenant
    -- and still open. It is never read back out to a person: a target shows
    -- whether it has one, and a new one replaces it.
    --
    -- Per target rather than as the plugin's tenant setting, which is where it
    -- lived until 2026-09-21: one secret for a tenant means every receiver that
    -- tenant configured can forge a delivery to every other one.
    signing_secret text NOT NULL CHECK (length(signing_secret) BETWEEN 1 AND 2000),

    -- Off rather than deleted, so that a receiver being repaired can be paused
    -- without losing what was configured -- and without filling a dead-letter
    -- log while somebody works on it.
    enabled        boolean NOT NULL DEFAULT true,

    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     uuid,

    -- One target per URL. A second row for the same address is a second copy of
    -- everything it already receives, which is a mistake rather than a wish: one
    -- row can name as many event types as it likes.
    UNIQUE (tenant_id, url)
);

-- The composite key the notification rows reference, tenant-qualified as
-- 07 §7.5 requires of a reference between two tenant-scoped tables.
ALTER TABLE notification.webhook_target ADD CONSTRAINT webhook_target_tenant_id_key
    UNIQUE (tenant_id, id);

ALTER TABLE notification.webhook_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.webhook_target FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.webhook_target
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON notification.webhook_target TO homeinv_app;
GRANT SELECT ON notification.webhook_target TO homeinv_readonly;

-- Which targets want this event, without reading every target of the tenant.
-- GIN, because the question is always containment: does this row's array hold
-- the one name an event just produced.
CREATE INDEX webhook_target_event_types ON notification.webhook_target
    USING gin (event_types);

COMMENT ON COLUMN notification.webhook_target.signing_secret IS
    'The sealed form of ADR-0019, bound to this row id and the key webhook.signingSecret. Per '
    'target and not per tenant (ADR-0077): one secret for a tenant lets every receiver it '
    'configured forge a delivery to every other one.';

COMMENT ON COLUMN notification.webhook_target.event_types IS
    'Names from docs/reference/event-types.yaml. Held to the code by EventTypes and '
    'EventTypeRegistryTest rather than by a CHECK, because the set grows with every feature that '
    'raises an event.';

-- ---------------------------------------------------------------------------
-- A NOTIFICATION IS FOR A PERSON **OR** FOR A TARGET
-- ---------------------------------------------------------------------------
ALTER TABLE notification.notification ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE notification.notification ADD COLUMN webhook_target_id uuid;

-- Composite and tenant-qualified (07 §7.5). CASCADE: deleting a target is an
-- administrator saying that receiver is gone, and a delivery log about a
-- receiver nobody can name any more answers no question. The attempts go with
-- it through the cascade V51 already put on `delivery_attempt`.
ALTER TABLE notification.notification ADD CONSTRAINT notification_webhook_target_same_tenant
    FOREIGN KEY (tenant_id, webhook_target_id)
    REFERENCES notification.webhook_target (tenant_id, id) ON DELETE CASCADE;

-- Exactly one recipient, and the constraint says so rather than a comment. A row
-- with both would be a message to a person that also went to a URL; a row with
-- neither is a delivery with nowhere to go, which is how a queue quietly grows.
--
-- `num_nonnulls` and not `(a IS NULL) <> (b IS NULL)`, which says the same thing:
-- it is the spelling `inventory.loan` already uses, and the one the isolation
-- proof's seeder reads to decide which of two mutually exclusive columns to fill.
-- A second spelling of one rule is a rule a tool understands by accident.
ALTER TABLE notification.notification ADD CONSTRAINT notification_one_recipient
    CHECK (num_nonnulls(user_id, webhook_target_id) = 1);

-- The deliveries of one target, newest first, which is what the endpoint shows.
CREATE INDEX notification_by_webhook_target
    ON notification.notification (tenant_id, webhook_target_id, created_at DESC)
    WHERE webhook_target_id IS NOT NULL;

COMMENT ON COLUMN notification.notification.webhook_target_id IS
    'Set exactly when user_id is not: this row is a webhook delivery rather than a message to a '
    'person (REQ-API-010). The retry schedule, the attempt log and the dead letter are the same '
    'ones V51 defined, which is why the column is here rather than in a table of its own.';
