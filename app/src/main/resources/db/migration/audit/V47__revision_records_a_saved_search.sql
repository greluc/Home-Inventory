-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-SRCH-008: a saved search is a third kind of thing the revision log records.
--
-- It is the first that is configuration rather than content, and the first that
-- is removed outright rather than trashed -- nothing points at a saved search, so
-- a tombstone would be retention without a reason (decided with the owner,
-- 2026-09-14). Its revision is therefore the whole answer to "who took away the
-- list called Repairs, and when", which is what CLAUDE.md rule 12 asks for: the
-- record outlives the row, exactly as it already does for a purged item.
--
-- A CHECK and not a foreign key, as before: `revision_record` deliberately points
-- at no table, so that a removal cannot erase its own history (07 §7.4).

ALTER TABLE audit.revision_record
    DROP CONSTRAINT revision_record_entity_type_check;

ALTER TABLE audit.revision_record
    ADD CONSTRAINT revision_record_entity_type_check
        CHECK (entity_type IN ('ITEM', 'LOCATION', 'SAVED_SEARCH'));

COMMENT ON COLUMN audit.revision_record.entity_type IS
    'ITEM, LOCATION or SAVED_SEARCH. A CHECK rather than a foreign key: this history outlives what '
    'it describes, so it points at no table (07 §7.4, REQ-SRCH-008).';
