-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A FIELD CAN SAY THAT ITS DATE IS AN EXPIRY (REQ-LIFE-013, REQ-CORE-023).
--
-- REQ-LIFE-013 collects warranty, licence and best-before dates into one
-- overview. `warranty_until` is a column on the item and the other two are type
-- ATTRIBUTES -- `expiresOn` in the software-licence template, `bestBefore` in
-- the food one -- and 07 §7.13 already wrote down why that is a problem:
--
--   "a report across every type cannot read a field that exists only where a
--    type happened to declare it, under whatever name it chose"
--
-- which is exactly why the valuation figures became columns. The same objection
-- applies here, and the same answer does not: a licence expiry belongs to the
-- types that have licences, and giving every item an `expires_on` column would
-- be a column that is null for almost every row.
--
-- SO THE FIELD SAYS WHAT IT IS. A fifth flag beside `searchable`, `sortable`,
-- `facetable` and `sensitive`, which is already how this system marks what a
-- field MEANS rather than what it holds. Decided with the owner on 2026-09-20;
-- the alternatives were collecting every date-typed attribute -- which puts
-- "last calibrated" in an expiry list -- and hard-coding the two shipped keys,
-- which is the silent failure 07 §7.13 warns about the moment a tenant names
-- its own expiry `validUntil`.
--
-- It stays declarative, so it stays on this side of ADR-0020's line: a marking
-- is not a formula, and nothing here computes or branches.

ALTER TABLE catalog.field_definition
    ADD COLUMN expiry boolean NOT NULL DEFAULT false;

-- ONLY A DATE CAN EXPIRE. Marking a text field as an expiry would produce a row
-- in the overview with nothing to sort by, and the overview sorts by due date --
-- so the refusal belongs here, where it cannot be forgotten, rather than in a
-- service that happens to check.
ALTER TABLE catalog.field_definition
    ADD CONSTRAINT field_definition_expiry_is_a_date
        CHECK (NOT expiry OR data_type IN ('date', 'datetime'));

-- "Which fields are expiries" is asked once per overview, over the published
-- versions of every type a tenant has.
CREATE INDEX field_definition_expiry
    ON catalog.field_definition (item_type_version_id)
    WHERE expiry;

COMMENT ON COLUMN catalog.field_definition.expiry IS
    'Whether this date is an expiry, and so belongs in the overview of REQ-LIFE-013. Only a date '
    'or datetime may carry it; a check constraint enforces that.';
