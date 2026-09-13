-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A tenant that has been erased says so (REQ-TEN-011, REQ-PRIV-005).
--
-- V31 gave the tenant three states and tied the last of them to its request:
-- PENDING_DELETION exactly when there is a timestamp and a revocation token. The
-- erasure then ends by clearing the token — a request that has been carried out
-- cannot be withdrawn — which left the row in a state that constraint forbids,
-- and no third value to move it to.
--
-- The answer is the state the row is actually in. ERASED is what a tombstone is,
-- `deleted_at` says when, and the two now have to agree: a tombstone that still
-- read PENDING_DELETION would be the one fact in that row nobody could check
-- against the rest of it.
--
-- Nobody in this state can ask anything. An erased tenant has no memberships
-- left, so no session acts for it, and the interceptor that answers
-- `tenant-inaccessible` for everything but ACTIVE (O26) covers it without a
-- change.

ALTER TABLE tenancy.tenant
    DROP CONSTRAINT tenant_lifecycle_state_check;

ALTER TABLE tenancy.tenant
    ADD CONSTRAINT tenant_lifecycle_state_check
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'PENDING_DELETION', 'ERASED'));

-- The tombstone and the state are one fact, written once, and this is what keeps
-- them from becoming two. Every other `deleted_at` in this schema is an ordinary
-- tombstone (07 §7.1, rule 5); a tenant's is only ever set by an erasure.
ALTER TABLE tenancy.tenant
    ADD CONSTRAINT tenant_tombstone_is_erased
        CHECK ((deleted_at IS NOT NULL) = (lifecycle_state = 'ERASED'));
