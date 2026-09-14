-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Saved searches: a named query that appears as a smart list (REQ-SRCH-008,
-- 04 §4.3, 08 §8.1).
--
-- The tenant's, not the person's (decided with the owner 2026-09-14). 03 §3.4
-- lists a saved search in the same row as a type, a label template and a role --
-- "configuration, data in the running system" -- and REQ-SRCH-008 asks for it to
-- be usable as the source for label printing and stocktake runs, which somebody
-- other than its author has to be able to start. A household shares "everything
-- that needs mending" the way it shares the tag vocabulary.
--
-- What is stored is the query as the API spells it: the text, the `filter`
-- parameters verbatim, and the sort key. Not a parsed structure -- there is one
-- grammar (08 §8.2) and a second representation of it would be a second thing to
-- keep in step, and the parsing already refuses anything that is not of that
-- shape. Running a saved search is replaying its parameters.

-- The block's own schema. V1 creates only the schemas stage 0 fills and says the
-- rest arrive with the blocks that own them; this is one of those.
CREATE SCHEMA IF NOT EXISTS search;
GRANT USAGE ON SCHEMA search TO homeinv_app, homeinv_readonly;

CREATE TABLE search.saved_search (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,           -- a discriminator, not a foreign key (07 §7.5)
    -- The name IS how a person picks one out of a list, so it is unique per
    -- tenant and case-insensitively so: two smart lists called "Defekt" and
    -- "defekt" are a mistake somebody has to undo rather than a choice.
    name            text NOT NULL CHECK (length(btrim(name)) > 0 AND length(name) <= 200),
    -- The three halves of a query, as 08 §8.2 spells them.
    query_text      text CHECK (query_text IS NULL OR length(query_text) <= 500),
    -- One element per `filter=` parameter, verbatim. An array and not a single
    -- string, because the parameter is repeatable and joining them with a
    -- separator would invent a grammar the API does not have.
    filters         text[] NOT NULL DEFAULT '{}',
    sort            text CHECK (sort IS NULL OR length(sort) <= 100),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id)
);

ALTER TABLE search.saved_search ENABLE ROW LEVEL SECURITY;
ALTER TABLE search.saved_search FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search.saved_search
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE UNIQUE INDEX saved_search_name_unique
    ON search.saved_search (tenant_id, lower(name));

-- The listing is by creation, oldest first, like every other paged collection
-- here: the keyset cursor resumes in that order (08 §8.2).
CREATE INDEX saved_search_by_age ON search.saved_search (tenant_id, created_at, id);

GRANT SELECT, INSERT, UPDATE, DELETE ON search.saved_search TO homeinv_app;
GRANT SELECT ON search.saved_search TO homeinv_readonly;

COMMENT ON TABLE search.saved_search IS
    'A named query usable as a smart list (REQ-SRCH-008). The tenant''s, not the person''s. '
    'Deleted outright rather than archived: nothing points at one, and the audit log records who '
    'removed it and when.';
COMMENT ON COLUMN search.saved_search.filters IS
    'The `filter` parameters verbatim, one per element, in the grammar of 08 8.2. Replayed rather '
    'than re-parsed, so there is one grammar and not two.';
