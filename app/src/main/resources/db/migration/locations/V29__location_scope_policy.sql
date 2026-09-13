-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A scoped session reads only its own part of the tree (REQ-TEN-007, ADR-0059).
--
-- The application checks this too, on the loaded object, which is where 12 §12.5
-- puts it and where a comprehensible 404 comes from. This is the second line: a
-- query that forgets the filter returns nothing rather than somebody else's room.
-- It is the same argument ADR-0003 made for the tenant boundary, applied to the
-- one other boundary that is invisible in a query's text.
--
-- `app.location_scope` holds the scope's PATH and not the id the membership
-- stores. A policy that resolved an id would do it per row; the path is resolved
-- once per transaction, beside `app.tenant_id` and by the same mechanism, so a
-- subtree that has been re-parented since the session began still reads right.
--
-- The empty string means "no scope", which is what a membership without one has.
-- An id that resolves to nothing sets a label no tree contains instead — see
-- `TenantAwareTransactionManager`: failing OPEN here is the one outcome this must
-- never have.

DROP POLICY tenant_isolation ON locations.location;

CREATE POLICY tenant_isolation ON locations.location
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            nullif(current_setting('app.location_scope', true), '') IS NULL
            OR path <@ current_setting('app.location_scope', true)::ltree
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            nullif(current_setting('app.location_scope', true), '') IS NULL
            -- A scoped session creates places inside its own subtree and nowhere
            -- else. Without this half, somebody confined to the garage could make
            -- a room in the house and then not be able to see it.
            OR path <@ current_setting('app.location_scope', true)::ltree
        )
    );
