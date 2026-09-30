-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REMINDER RULES AS DATA (REQ-NOTI-001, REQ-NOTI-003).
--
-- 04 §4.4 says it plainly: "Reminder rules are data (level 1), not code: a
-- condition as a saved search + a time offset + a channel." This is those three,
-- plus the one thing an offset cannot do without.
--
-- WHY A TRIGGER KIND IS THE FOURTH COLUMN.
--
-- An offset has to be an offset FROM something, and 08 §8.2's filter grammar has
-- no relative dates: `warrantyUntil:lte:2026-10-01` is an absolute date that
-- stops being true tomorrow, so a saved search alone cannot express "expires
-- within a fortnight" in a way that rolls forward. The trigger names WHICH date
-- (or which condition), and REQ-NOTI-003 already names the set -- warranty
-- expiry, maintenance interval, return date, minimum stock, licence expiry,
-- stocktake discrepancy. Decided with the owner on 2026-09-20; the alternatives
-- were a free-text date-field name, which two of the six triggers do not have,
-- and relative dates in the filter grammar, which changes the public API
-- contract for every surface.
--
-- The saved search KEEPS its job: it narrows WHICH things the rule watches --
-- the tools in the garage, anything tagged valuable -- and a rule without one
-- watches everything the trigger finds.

CREATE TABLE notification.notification_rule (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- What a person calls it in the list of rules.
    name           text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),

    -- WHICH date or condition. A closed set, mirrored by `ReminderTrigger` in
    -- code and compared against it on every build -- the treatment
    -- `problem-types.yaml` gets, because an id that reaches a stored row outlives
    -- the constant that produced it.
    trigger_kind   text NOT NULL CHECK (trigger_kind IN (
                       'WARRANTY_EXPIRY', 'MAINTENANCE_DUE', 'LOAN_DUE',
                       'MINIMUM_STOCK', 'LICENCE_EXPIRY', 'STOCKTAKE_DISCREPANCY')),

    -- WHICH things, or all of them. Composite and tenant-qualified like every
    -- reference between two tenant-scoped tables (07 §7.5). ON DELETE SET NULL
    -- rather than CASCADE: deleting the smart list should widen the rule, not
    -- silently stop the reminders. REQ-SRCH-008 removes a saved search outright
    -- because "nothing points at a saved search", and this is now the one thing
    -- that does.
    saved_search_id uuid,
    CONSTRAINT notification_rule_search_same_tenant
        FOREIGN KEY (tenant_id, saved_search_id)
        REFERENCES search.saved_search (tenant_id, id) ON DELETE SET NULL,

    -- THE TIME OFFSET, in days, and one rule covers both directions: the reminder
    -- is due when today >= the trigger date minus offset_days. POSITIVE warns
    -- early, so 14 means a fortnight before the warranty ends. NEGATIVE waits
    -- until after, which is what REQ-LIFE-006 needs for an OVERDUE return: -1
    -- fires the day after it was due. Zero fires on the day itself.
    offset_days    integer NOT NULL DEFAULT 0
                   CHECK (offset_days BETWEEN -365 AND 365),

    -- Which channel, as a plugin manifest names it. The same key
    -- `notification.subscription` carries, resolved the same way.
    channel_key    text NOT NULL CHECK (length(channel_key) BETWEEN 1 AND 60),

    -- Off without being deleted: a rule somebody is tuning should stop firing
    -- while they think, without losing what they had written.
    enabled        boolean NOT NULL DEFAULT true,

    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     uuid,

    UNIQUE (tenant_id, id)
);

ALTER TABLE notification.notification_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.notification_rule FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.notification_rule
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX notification_rule_enabled
    ON notification.notification_rule (tenant_id, trigger_kind)
    WHERE enabled;

GRANT SELECT, INSERT, UPDATE, DELETE ON notification.notification_rule TO homeinv_app;
GRANT SELECT ON notification.notification_rule TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- WHAT HAS ALREADY BEEN RAISED
-- ---------------------------------------------------------------------------
--
-- The schedule runs every few minutes and a warranty expires for months. Without
-- this table a rule would raise the same reminder on every pass, and the person
-- who set it would turn it off -- which is the failure mode that makes a
-- reminder feature worthless.
--
-- APPEND-ONLY, so 07 §7.1 rule 4's second kind: no `version`, no `updated_*`.
-- Nothing edits a record that something happened, and a reminder that should not
-- have gone out is not un-sent by editing a row.

CREATE TABLE notification.reminder (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    rule_id        uuid NOT NULL,
    CONSTRAINT reminder_rule_same_tenant
        FOREIGN KEY (tenant_id, rule_id)
        REFERENCES notification.notification_rule (tenant_id, id) ON DELETE CASCADE,

    -- WHAT it was about. An id and a kind rather than a foreign key: the subject
    -- is an item today and a stocktake run tomorrow, and a column per kind would
    -- be a migration per trigger. Nothing joins on it -- it is read back to build
    -- the message and compared for the uniqueness below -- so the reference the
    -- database cannot check costs nothing here, unlike the polymorphic pair
    -- 07 §7.8 refuses for `tag_assignment`.
    subject_kind   text NOT NULL CHECK (length(subject_kind) BETWEEN 1 AND 40),
    subject_id     uuid NOT NULL,

    -- The date the reminder was ABOUT, not the day it went out. This is what
    -- makes it fire again when a due date is moved: a new date is a new thing to
    -- be reminded of, and the same date is not.
    due_on         date NOT NULL,

    raised_at      timestamptz NOT NULL DEFAULT now(),

    -- ONCE PER RULE, THING AND DATE. The whole point of the table.
    UNIQUE (tenant_id, rule_id, subject_kind, subject_id, due_on)
);

ALTER TABLE notification.reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.reminder FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.reminder
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "What have we already told them about", by rule.
CREATE INDEX reminder_by_rule ON notification.reminder (tenant_id, rule_id, raised_at DESC);

-- No UPDATE: the append-only property is a grant rather than a promise. DELETE
-- is granted so a retention run can drop old records and so the cascade works.
GRANT SELECT, INSERT, DELETE ON notification.reminder TO homeinv_app;
GRANT SELECT ON notification.reminder TO homeinv_readonly;

COMMENT ON TABLE notification.notification_rule IS
    'A reminder rule as data (REQ-NOTI-001): a trigger kind, an optional saved search that narrows '
    'it, a day offset and a channel.';
COMMENT ON TABLE notification.reminder IS
    'What a rule has already raised, so it raises it once. Append-only; keyed by rule, subject and '
    'the date the reminder was about.';

-- ---------------------------------------------------------------------------
-- WHICH TENANTS HAVE A RULE AT ALL
-- ---------------------------------------------------------------------------
--
-- The reminder run has no tenant context: it is looking for the tenants that
-- need one, which is the circularity every function on 07 §7.5's list resolves
-- the same way. It returns tenant ids and nothing else; the rules themselves are
-- then read under each tenant's own context, under the ordinary policy.

CREATE FUNCTION notification.tenants_with_enabled_rules()
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = notification, pg_temp
AS $$
    SELECT DISTINCT r.tenant_id
    FROM notification.notification_rule r
    WHERE r.enabled;
$$;

GRANT USAGE, CREATE ON SCHEMA notification TO homeinv_bootstrap;
ALTER FUNCTION notification.tenants_with_enabled_rules() OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA notification FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION notification.tenants_with_enabled_rules() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification.tenants_with_enabled_rules() TO homeinv_app;

-- The one permissive policy for that role on this table, SELECT only -- the
-- shape `notification.notification` already has for the delivery run.
CREATE POLICY bootstrap_rule_lookup ON notification.notification_rule
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON notification.notification_rule TO homeinv_bootstrap;
