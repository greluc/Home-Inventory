-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHERE AN ITEM IS IN ITS LIFE, AND HOW IT LEFT (04 SS4.4, REQ-LIFE-007).
--
-- `lifecycle_state` has been a column since V6 and free text ever since: the
-- application wrote 'ACTIVE' and 'TRASHED' and the database would have taken
-- 'banana'. 04 SS4.4 names the machine -- ACTIVE -> LENT -> ACTIVE, ACTIVE ->
-- ARCHIVED, ACTIVE -> TRASHED -> ACTIVE/PURGED, ACTIVE -> SOLD/DISPOSED -- and
-- 11 SS11.3 RANKS those values to resolve a sync conflict. A rank over a column
-- that could hold anything is a rank over nothing, so the set is closed here.
--
-- PURGED is deliberately absent: a purge is a DELETE and leaves no row to carry
-- it. A value no row could ever hold would be a lie in the constraint.

ALTER TABLE inventory.item
    ADD CONSTRAINT item_lifecycle_state_known
    CHECK (lifecycle_state IN ('ACTIVE', 'LENT', 'ARCHIVED', 'TRASHED', 'SOLD', 'DISPOSED'));

-- THE STATE AND THE TIMESTAMP SAY THE SAME THING. `deleted_at` stays beside the
-- state rather than being replaced by it -- the state says WHAT, the timestamp
-- says WHEN, and REQ-CORE-009's retention run needs the second. They are written
-- together in `Item.markDeleted`, and this is what stops them drifting apart:
-- every other state means the item is not in the trash.
ALTER TABLE inventory.item
    ADD CONSTRAINT item_trashed_iff_deleted
    CHECK ((lifecycle_state = 'TRASHED') = (deleted_at IS NOT NULL));

-- HOW IT LEFT (REQ-LIFE-007): price, date and recipient.
--
-- Columns on the item and not a table, for the reason V44's valuation figures
-- are columns and V55's maintenance entries are a table: a thing is sold ONCE.
-- The sequence that makes a maintenance log worth keeping does not exist here.
ALTER TABLE inventory.item
    -- What it fetched, per REQ-NFR-070: an amount and its currency, never one
    -- without the other. Both null for a disposal that earned nothing -- given
    -- away, thrown out -- because "nothing was paid" and "0.00 was paid" are
    -- different claims, which is the same distinction `maintenance_entry` draws.
    ADD COLUMN disposal_amount   numeric(19,4),
    ADD COLUMN disposal_currency varchar(3),
    -- When it went. A date: nobody records the minute.
    ADD COLUMN disposed_on       date,
    -- Who to, in the seller's own words. Free text and not a member reference:
    -- things are sold to strangers, and `identity.app_user` is instance-wide so
    -- a composite foreign key is impossible anyway (07 SS7.5) -- the argument
    -- `inventory.loan.borrower_user_id` makes one step further on.
    ADD COLUMN disposal_recipient text
        CHECK (disposal_recipient IS NULL OR length(btrim(disposal_recipient)) BETWEEN 1 AND 200),
    -- A note about the sale, not the item's own notes.
    ADD COLUMN disposal_note     text
        CHECK (disposal_note IS NULL OR length(disposal_note) <= 2000);

ALTER TABLE inventory.item
    ADD CONSTRAINT item_disposal_price_is_whole
        CHECK ((disposal_amount IS NULL) = (disposal_currency IS NULL));

-- THE DATE IS THE RECORD OF THE EVENT, so it is present exactly when the state
-- says the item is gone. Without this the two could disagree, and "when did we
-- sell it" would have two answers -- one of them silence.
ALTER TABLE inventory.item
    ADD CONSTRAINT item_disposed_iff_dated
        CHECK ((lifecycle_state IN ('SOLD', 'DISPOSED')) = (disposed_on IS NOT NULL));

-- A PRICE ONLY WHERE SOMETHING WAS SOLD. A disposal that fetched money is a
-- sale, and `SOLD` is what that is called here; an amount against `DISPOSED`
-- would be a row saying both.
ALTER TABLE inventory.item
    ADD CONSTRAINT item_only_a_sale_has_a_price
        CHECK (disposal_amount IS NULL OR lifecycle_state = 'SOLD');

-- "What did we part with, and what did it fetch" -- the report question, and the
-- only one asked of these columns.
CREATE INDEX item_disposed_by_date
    ON inventory.item (tenant_id, disposed_on DESC)
    WHERE disposed_on IS NOT NULL;

COMMENT ON COLUMN inventory.item.lifecycle_state IS
    'Where the item is in its life (04 SS4.4). Closed set; ranked by 11 SS11.3 to resolve a sync '
    'conflict. PURGED is absent because a purge deletes the row.';
COMMENT ON COLUMN inventory.item.disposed_on IS
    'When the item was sold or disposed of (REQ-LIFE-007). Present exactly when lifecycle_state '
    'says it is gone, which a check constraint enforces.';
