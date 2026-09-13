-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- `Idempotency-Key` and the answer it already got (REQ-API-005).
--
-- In PostgreSQL and not in Valkey, decided as open point O13 (ADR-0009): the
-- records were filed under a 24-hour cache TTL while 13 §13.6 rated losing that
-- cache as harmless. Both could not be true — losing them means a retry after a
-- network drop creates a duplicate, which is the exact failure this requirement
-- exists to prevent, for the exact clients it was written for.
--
-- The key and the entity it protects therefore share one COMMIT. There is no
-- window in which one exists without the other, which is the same argument the
-- outbox rests on.

CREATE TABLE idempotency.processed_request (
    tenant_id     uuid NOT NULL,
    key           text NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
    -- What the caller sent, hashed. A repeat with the same key and a DIFFERENT
    -- body is a client bug and is refused (409) rather than answered with
    -- somebody else's result -- which is the failure mode a key alone has.
    -- Lower-case hex, exactly 64 characters: a SHA-256 and nothing else. Stated as
    -- a shape rather than as a length, because `length(...) = 64` would also
    -- accept sixty-four characters of anything -- including whatever a client
    -- hoped would be taken as a hash.
    request_hash  text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- Which operation the key was spent on, so a key reused across endpoints is
    -- caught as well: the hash of an item body could collide with nothing, but a
    -- key sent to two different paths is still one key spent twice.
    operation     text NOT NULL CHECK (length(operation) BETWEEN 1 AND 100),
    -- The answer, as the caller received it. Stored so a repeat gets the ORIGINAL
    -- result rather than the current state: the point is that the second request
    -- changes nothing, not that it reports afresh.
    response      jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    PRIMARY KEY (tenant_id, key)
);

-- The expiry sweep reads this, and only this.
CREATE INDEX processed_request_by_age ON idempotency.processed_request (created_at);

ALTER TABLE idempotency.processed_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency.processed_request FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency.processed_request
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- There is no second policy for an instance-wide expiry sweep, and the first
-- attempt at one is why this comment exists. PostgreSQL applies SELECT policies
-- to a DELETE whose WHERE clause names a column, so a sweep running with no
-- tenant context matched nothing and reported success -- the exact shape of a
-- daily job that says it ran while the table grows forever.
--
-- Widening it would have meant a SELECT policy over other tenants' rows, and
-- `response` holds whatever the tenant created. So expiry happens where a tenant
-- context already exists: a record older than 24 hours is ignored on read and
-- removed when the same tenant next spends a key, and an erasure takes the rest
-- with the tenant (REQ-TEN-011).

GRANT SELECT, INSERT, DELETE ON idempotency.processed_request TO homeinv_app;
GRANT SELECT ON idempotency.processed_request TO homeinv_readonly;
