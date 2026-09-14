-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-SRCH-011: full text covers notes as well as the name and the description.
--
-- The generated vectors were written at stage 0, when `notes` did not exist --
-- it arrived with V20 and nothing widened the vectors to take it in. So an item
-- whose notes said "wobbly leg, glued 2024" was findable by neither word, in the
-- one profile that has no OpenSearch at all.
--
-- A generated column and not a trigger, exactly as before: the column is a
-- function of the row, PostgreSQL keeps it in step, and there is no code path
-- that can forget to. The other three fields REQ-SRCH-011 names -- attribute
-- values, tags and the location path -- are NOT here and cannot be: a generated
-- column reads its own row and they live in `item_attr_index`, `tagging` and
-- `locations`. The PostgreSQL engine composes those through each block's
-- published port, the way it composes the facets (ADR-0002).
--
-- Rewriting a generated column means rewriting the table's tuples, which for a
-- household's inventory is seconds and for a warehouse's is minutes. It takes an
-- ACCESS EXCLUSIVE lock for that time, which is why this is its own migration
-- and not a line in a larger one: an operator reading the migration list can see
-- what will hold the table and for how long (07 §7.7).

ALTER TABLE inventory.item DROP COLUMN search_vector_de;
ALTER TABLE inventory.item DROP COLUMN search_vector_en;

ALTER TABLE inventory.item
    ADD COLUMN search_vector_de tsvector GENERATED ALWAYS AS (
        to_tsvector('german',
            coalesce(name, '') || ' '
                || coalesce(description, '') || ' '
                || coalesce(notes, ''))
    ) STORED;

ALTER TABLE inventory.item
    ADD COLUMN search_vector_en tsvector GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(name, '') || ' '
                || coalesce(description, '') || ' '
                || coalesce(notes, ''))
    ) STORED;

-- Dropping the columns took the indexes with them.
CREATE INDEX item_search_fts_de ON inventory.item USING gin (search_vector_de);
CREATE INDEX item_search_fts_en ON inventory.item USING gin (search_vector_en);

COMMENT ON COLUMN inventory.item.search_vector_de IS
    'German full text over name, description and notes (REQ-SRCH-001, REQ-SRCH-011, ADR-0047). '
    'Attribute values, tags and the location path are matched by composing the ports of the '
    'blocks that own them; a generated column can only read its own row.';
COMMENT ON COLUMN inventory.item.search_vector_en IS
    'English full text over name, description and notes (REQ-SRCH-001, REQ-SRCH-011, ADR-0047).';
