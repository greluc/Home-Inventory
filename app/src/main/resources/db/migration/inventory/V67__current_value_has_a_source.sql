-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-LIFE-009.
--
-- The current value was a figure with no account of where it came from, which
-- is the one thing a figure in an insurance conversation needs. Three answers
-- are possible and they are not interchangeable: somebody typed it, a plugin
-- estimated it, or this application depreciated it from the purchase price.
--
-- The distinction is load-bearing rather than informative. A refresh run
-- recomputes the depreciated ones and must never touch the other two: a number
-- a person typed is the one they will be asked to justify, and overwriting it
-- overnight would be the application disagreeing with its owner in silence.

ALTER TABLE inventory.item
    ADD COLUMN current_source text
        CHECK (current_source IS NULL
               OR current_source IN ('MANUAL', 'DEPRECIATION', 'PLUGIN'));

-- Anything already carrying a current value was typed by somebody: nothing else
-- could have written one before this column existed. Backfilled before the
-- constraint, because the constraint is what makes the pair inseparable from
-- here on.
UPDATE inventory.item SET current_source = 'MANUAL' WHERE current_amount IS NOT NULL;

ALTER TABLE inventory.item
    ADD CONSTRAINT item_current_value_has_a_source
        CHECK ((current_amount IS NULL) = (current_source IS NULL));

COMMENT ON COLUMN inventory.item.current_source IS
    'Who says so: MANUAL (typed), DEPRECIATION (straight-line, recomputed) or PLUGIN. A refresh '
    'run rewrites only DEPRECIATION (REQ-LIFE-009).';

-- What the refresh run looks for: the rows it may rewrite. Partial, because most
-- items carry no current value at all and an index over their NULLs would be an
-- index over nothing.
CREATE INDEX item_depreciated ON inventory.item (tenant_id, item_type_version_id)
    WHERE current_source = 'DEPRECIATION' AND deleted_at IS NULL;
