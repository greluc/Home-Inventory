-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- WHAT WAS DONE TO AN ITEM, AND WHEN (REQ-LIFE-003).
--
-- A table and not columns on `inventory.item`, unlike the valuation figures of
-- V44: those are one fact each about one object, this is a list that grows. The
-- bicycle was serviced in March, had a tyre in June and a chain in October, and
-- the point of keeping it is the sequence.
--
-- APPEND-ONLY, WHICH IS THE REQUIREMENT AND NOT A SIMPLIFICATION.
--
-- REQ-LIFE-003 says entries are "not retroactively editable". That is what a
-- maintenance log IS: a record of what happened, which is worth something only
-- if it cannot be tidied up afterwards. So there is no `version` and there are
-- no `updated_*` columns -- 07 §7.1's rule 4 names this kind exactly -- and
-- `homeinv_app` is granted no UPDATE.
--
-- A MISTAKE IS CORRECTED BY A SECOND ENTRY, not by editing the first: that is
-- how a service history behaves on paper, and it keeps the two facts -- what was
-- recorded, and what it was corrected to -- both visible. Removing an entry
-- entirely is possible (somebody logged it against the wrong bicycle) and is a
-- deletion rather than an edit, which the audit log records.

CREATE TABLE inventory.maintenance_entry (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)

    -- Composite and tenant-qualified, as every reference between two
    -- tenant-scoped tables is (07 §7.5). ON DELETE CASCADE: the history of a
    -- purged item goes with it, and REQ-TEN-011's erasure depends on that.
    item_id       uuid NOT NULL,
    CONSTRAINT maintenance_entry_item_same_tenant
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,

    -- When it was done, not when it was typed: a date rather than a timestamp,
    -- because nobody records the minute a chain was changed, and `created_at`
    -- below already says when the entry was made.
    performed_on  date NOT NULL,

    -- What kind of work. Free text rather than an enum: "service", "Inspektion",
    -- "new tyres" -- a closed set here would be a migration every time somebody
    -- maintains a kind of thing nobody anticipated, and this column is read by
    -- people rather than branched on by code.
    kind          text NOT NULL CHECK (length(btrim(kind)) BETWEEN 1 AND 100),

    -- What it cost, per REQ-NFR-070: an amount and its currency, never a float,
    -- and never an amount without one. Both null together -- a service under
    -- warranty cost nothing and saying "0.00" would be a different claim.
    cost_amount   numeric(19,4),
    cost_currency varchar(3),
    CONSTRAINT maintenance_cost_is_whole
        CHECK ((cost_amount IS NULL) = (cost_currency IS NULL)),

    note          text CHECK (note IS NULL OR length(note) <= 2000),

    -- NO ATTACHMENT COLUMN, and that is the design rather than an omission.
    -- REQ-LIFE-003 asks for an attachment; the media block already owns what
    -- hangs on a thing, counts the references and cleans up after them. So an
    -- invoice is a media object whose target is this entry
    -- (`target_kind = 'MAINTENANCE_ENTRY'`), exactly as a photograph's target is
    -- an item -- and `inventory` holds no foreign key into `media`, which
    -- 04 §4.5 forbids and which the exception list in 07 §7.9 does not carry.

    -- The audit columns this kind of table can carry: when the entry was made
    -- and by whom. No `version`, no `updated_*` -- see the note above.
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    UNIQUE (tenant_id, id)
);

ALTER TABLE inventory.maintenance_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.maintenance_entry FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.maintenance_entry
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "What has been done to this thing", newest first, which is the only question
-- anybody asks of it.
CREATE INDEX maintenance_entry_by_item
    ON inventory.maintenance_entry (tenant_id, item_id, performed_on DESC);

-- No UPDATE. The append-only property is a grant rather than a promise: an
-- application that tried to edit an entry would be refused by the database.
GRANT SELECT, INSERT, DELETE ON inventory.maintenance_entry TO homeinv_app;
GRANT SELECT ON inventory.maintenance_entry TO homeinv_readonly;

COMMENT ON TABLE inventory.maintenance_entry IS
    'What was done to an item and when (REQ-LIFE-003). Append-only: no UPDATE is granted, and a '
    'correction is a second entry rather than an edit of the first.';
