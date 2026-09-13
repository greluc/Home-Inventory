-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A scoped session reads only the items in its own part of the tree
-- (REQ-TEN-007, ADR-0059).
--
-- An item has no path of its own — it has a place, and the place has the path —
-- so this policy asks `locations.location` for one. That is a statement in one
-- block's migration naming another block's schema, which 07 §7.9 forbids with a
-- documented exception list; this is on it, beside the composite foreign keys
-- that are there because a tenant boundary has to survive a foreign key check.
--
-- WHAT IT COSTS. A scoped session pays one index lookup per item row, on the
-- primary key of `locations.location`. An unscoped one pays nothing: the setting
-- is empty, `nullif(...) IS NULL` is true, and the subquery is never considered.
--
-- AN ITEM WITH NO PLACE IS INVISIBLE to a scoped session, because `EXISTS` over
-- no place is false. A digital item is in nobody's garage. The application layer
-- agrees, and ADR-0059 states it rather than leaving somebody to find it.

DROP POLICY tenant_isolation ON inventory.item;

CREATE POLICY tenant_isolation ON inventory.item
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            nullif(current_setting('app.location_scope', true), '') IS NULL
            OR EXISTS (
                SELECT 1
                FROM locations.location place
                WHERE place.tenant_id = item.tenant_id
                  AND place.id = item.location_id
                  AND place.path <@ current_setting('app.location_scope', true)::ltree
            )
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            nullif(current_setting('app.location_scope', true), '') IS NULL
            -- A scoped session puts items inside its own subtree and nowhere
            -- else. Without this half it could create one in the house and then
            -- not be able to read it back.
            OR EXISTS (
                SELECT 1
                FROM locations.location place
                WHERE place.tenant_id = item.tenant_id
                  AND place.id = item.location_id
                  AND place.path <@ current_setting('app.location_scope', true)::ltree
            )
        )
    );
