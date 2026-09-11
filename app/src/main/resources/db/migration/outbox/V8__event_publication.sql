-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The transactional outbox (07 §7.7).
--
-- Spring Modulith provides the mechanism and its own default DDL; this is the
-- extended form the architecture specifies, with `tenant_id` and `trace_id`
-- added so an entry can be attributed after the fact.
--
-- Stage 0 publishes no cross-block events and runs no relay — RabbitMQ arrives
-- with stage 1. The table exists now because Spring Modulith's JPA event
-- registry validates against it at startup, and because an outbox added later
-- would be an outbox that did not exist for every event written before it.
--
-- Infrastructure, not a domain table: owned by a mechanism rather than by a
-- block, which is why it has its own schema, carries no `version`, and has no
-- audit columns (07 §7.1, rule 4).

CREATE TABLE outbox.event_publication (
    id                     uuid PRIMARY KEY,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    -- Nullable: an event raised outside any tenant context — an instance-wide
    -- housekeeping event — still belongs in the outbox.
    tenant_id              uuid,
    trace_id               text,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    -- The three columns below are Spring Modulith 2.x and are absent from the
    -- DDL in 07 §7.7, which describes an older version. Taken from what the
    -- library's own entity mapping generates, not from guesswork; the chapter
    -- has been corrected to match.
    completion_attempts    integer NOT NULL DEFAULT 0,
    last_resubmission_date timestamptz,
    status                 text
                           CHECK (status IN ('PUBLISHED','PROCESSING','COMPLETED','FAILED','RESUBMITTED'))
);

-- The relay's only query: what has not been acknowledged yet. Partial, because
-- the completed rows are the overwhelming majority and indexing them would make
-- the index the size of the table.
CREATE INDEX outbox_incomplete ON outbox.event_publication (publication_date)
    WHERE completion_date IS NULL;

-- No row-level security. The outbox is instance-wide infrastructure: the relay
-- reads every tenant's entries by design, and a policy here would hide the rows
-- from the one process whose job is to publish them. The `tenant_id` column is
-- for attribution, not for isolation — the payload it carries was already
-- produced under a policy that held.
--
-- This is deliberately NOT a fifth entry on the instance-wide list in 07 §7.1:
-- that list is about *domain* tables, and rule 4 already classes the outbox as
-- infrastructure.

GRANT SELECT, INSERT, UPDATE, DELETE ON outbox.event_publication TO homeinv_app;
