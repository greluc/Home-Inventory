-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHO HAS IT (REQ-LIFE-005).
--
-- 07 §7.8 asks for exactly four things: a borrower, internal or free text; the
-- date it was handed out; the date it is due back; and the return. This is that
-- row, with the two properties the requirement's acceptance criterion adds --
-- "a lent item is recognisable as such and not deletable".
--
-- A TABLE AND NOT COLUMNS ON `inventory.item`, for the reason V55 gave: a thing
-- is lent out repeatedly and who had it last year is part of what the record is
-- for. Unlike a maintenance entry a loan IS edited -- the return is written into
-- the row that recorded the handover, because a return is the end of THAT loan
-- and not a separate event -- so this carries `version` and all four audit
-- columns, which 07 §7.1 rule 4 demands of every table that does.

CREATE TABLE inventory.loan (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- Composite and tenant-qualified, as every reference between two
    -- tenant-scoped tables is (07 §7.5). ON DELETE CASCADE covers the purge at
    -- the end of the two-stage deletion and the tenant erasure of REQ-TEN-011;
    -- it is not a way to delete a lent item, which the application refuses while
    -- the loan is open.
    item_id          uuid NOT NULL,
    CONSTRAINT loan_item_same_tenant
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,

    -- THE BORROWER IS ONE OF TWO THINGS AND NEVER BOTH. A member of this tenant,
    -- or a name somebody typed -- most things are lent to people who have no
    -- account here, and a system that could only record the former would be
    -- answered by writing the neighbour's name into the note field, which is a
    -- column nothing can query.
    --
    -- `num_nonnulls(...) = 1` is the shape 07 §7.8 gives `tagging.tag_assignment`
    -- for its two targets, and it is here for the same reason: two nullable
    -- columns the database checks, rather than a `(kind, id)` pair it cannot.
    borrower_user_id uuid,
    borrower_name    text CHECK (borrower_name IS NULL
                                 OR length(btrim(borrower_name)) BETWEEN 1 AND 200),
    CONSTRAINT loan_has_exactly_one_borrower
        CHECK (num_nonnulls(borrower_user_id, borrower_name) = 1),

    -- NO FOREIGN KEY ON `borrower_user_id`, deliberately, and it is the same
    -- decision `created_by` makes everywhere in this schema: `identity.app_user`
    -- is instance-wide (07 §7.1 rule 2) so the reference cannot be composite,
    -- and a plain one would tie a tenant's loan history to a row in a table the
    -- tenant does not own. Somebody who leaves the household still borrowed the
    -- drill in March.

    -- Dates and not timestamps: nobody records the minute a drill changed hands,
    -- and `created_at` already says when the row was written.
    handed_out_on    date NOT NULL,
    due_on           date,                   -- null: lent with no date agreed
    returned_on      date,                   -- null: still out. THIS is the flag
    CONSTRAINT loan_due_not_before_handover
        CHECK (due_on IS NULL OR due_on >= handed_out_on),
    CONSTRAINT loan_returned_not_before_handover
        CHECK (returned_on IS NULL OR returned_on >= handed_out_on),

    note             text CHECK (note IS NULL OR length(note) <= 2000),

    version          bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,

    UNIQUE (tenant_id, id)
);

ALTER TABLE inventory.loan ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.loan FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.loan
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ONE THING IS LENT TO ONE PERSON AT A TIME, and the database says so rather
-- than a service checking first and hoping. Two requests arriving together would
-- both find no open loan and both write one; a partial unique index is the same
-- instrument `identity.password_reset` uses to keep one reset open per account,
-- and it is the reason "is it lent" has a single answer.
CREATE UNIQUE INDEX loan_one_open_per_item
    ON inventory.loan (tenant_id, item_id)
    WHERE returned_on IS NULL;

-- "What has been lent, and what is overdue" -- the two questions asked of this
-- table. The second is REQ-LIFE-006's, which reads the open loans by due date.
CREATE INDEX loan_by_item ON inventory.loan (tenant_id, item_id, handed_out_on DESC);
CREATE INDEX loan_open_by_due
    ON inventory.loan (tenant_id, due_on)
    WHERE returned_on IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.loan TO homeinv_app;
GRANT SELECT ON inventory.loan TO homeinv_readonly;

COMMENT ON TABLE inventory.loan IS
    'Who has an item and since when (REQ-LIFE-005). At most one open loan per item, enforced by a '
    'partial unique index; the return is written into the row that recorded the handover.';
