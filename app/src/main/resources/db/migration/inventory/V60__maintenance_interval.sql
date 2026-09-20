-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- HOW OFTEN A THING NEEDS SERVICING (REQ-LIFE-004).
--
-- "Maintenance intervals raise reminders. The interval is settable and the
-- reminder fires." The reminder is REQ-NOTI-003's `MAINTENANCE_DUE` trigger,
-- which had nothing to read until this column existed -- so it was declared and
-- refused on save, the treatment `LICENCE_EXPIRY` still gets.
--
-- A COLUMN AND NOT A TABLE, for V44's reason rather than V55's: "serviced every
-- twelve months" is ONE fact about one object. The list that grows is the
-- maintenance log, and it is already a table.
--
-- DAYS AND NOT MONTHS. An interval in months has to answer what "every 3 months
-- from 31 January" means, and every answer to that is a surprise to somebody. A
-- surface offers months and multiplies; the stored fact stays a count of days,
-- which is what the comparison needs anyway.

ALTER TABLE inventory.item
    ADD COLUMN maintenance_interval_days integer
        CHECK (maintenance_interval_days IS NULL
               OR maintenance_interval_days BETWEEN 1 AND 3650);

COMMENT ON COLUMN inventory.item.maintenance_interval_days IS
    'How often this needs servicing, in days (REQ-LIFE-004). Null: no interval, so the '
    'MAINTENANCE_DUE reminder never considers it. Measured from the last maintenance entry, or '
    'from the purchase date when there is none.';

-- "What is due for servicing" -- the only question asked of this column, and it
-- is asked by a scheduled run over every item of a tenant.
CREATE INDEX item_with_maintenance_interval
    ON inventory.item (tenant_id)
    WHERE maintenance_interval_days IS NOT NULL;
