-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The revision history of REQ-CORE-010: every change keeps the state it produced,
-- earlier states are viewable, and any of them can be made current again.
--
-- A snapshot per revision rather than a diff. A diff is smaller and is the wrong
-- shape for this: restoring one would mean replaying every diff since, viewing
-- one would mean the same, and a gap anywhere makes every later state
-- unreadable. A snapshot answers both questions with one row, and an item is a
-- few hundred bytes.
--
-- In `audit` rather than in each block's schema, because it is one mechanism
-- serving several: `inventory` and `locations` both write here through a port,
-- and 07 §7.8 already lists `audit.revision_record` as the place domain history
-- lives.

CREATE TABLE audit.revision_record (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid NOT NULL,         -- a discriminator, not a foreign key (07 §7.5)
    -- What the revision is of. No foreign key: a revision outlives the row it
    -- describes — that is the point of keeping one for a final removal — and a
    -- reference would delete the history with the thing.
    entity_type   text NOT NULL CHECK (entity_type IN ('ITEM', 'LOCATION')),
    entity_id     uuid NOT NULL,
    -- A counter of this history's own, starting at one, assigned by the insert
    -- from what is already there.
    --
    -- NOT the entity's optimistic-lock version, which was the first attempt and
    -- fails on the operation that matters most: a final removal changes nothing
    -- about the row, so it carries the same version as the deletion before it and
    -- the two collide on the key below. A history needs a number per ENTRY, and
    -- the entity's version is a number per STATE.
    revision      bigint NOT NULL CHECK (revision > 0),
    change_kind   text NOT NULL CHECK (change_kind IN
                      ('CREATED', 'UPDATED', 'TRASHED', 'RESTORED', 'PURGED')),
    -- The state AFTER the change, as the block that owns it chose to describe it.
    -- `catalog` is not consulted: a snapshot has to stay readable after the type
    -- it was written against has changed, which is exactly when history matters.
    snapshot      jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at    timestamptz NOT NULL DEFAULT now(),
    changed_by    uuid,
    UNIQUE (tenant_id, entity_type, entity_id, revision)
);

ALTER TABLE audit.revision_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.revision_record FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.revision_record
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "The history of this thing, newest first", which is the only way it is read.
CREATE INDEX revision_by_entity ON audit.revision_record
    (tenant_id, entity_type, entity_id, revision DESC);

-- INSERT and SELECT, and deliberately nothing else. History that the application
-- could rewrite would not be history, and 07 §7.8 states the same rule for the
-- audit log beside it. A retention run removes old revisions under the
-- housekeeping role, which is a different role for exactly this reason.
GRANT SELECT, INSERT ON audit.revision_record TO homeinv_app;
GRANT SELECT ON audit.revision_record TO homeinv_readonly;
GRANT SELECT, DELETE ON audit.revision_record TO homeinv_housekeeping;
