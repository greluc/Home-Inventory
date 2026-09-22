-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- PASSWORD RESET (REQ-SEC-018).
--
-- A single-use token, valid thirty minutes. Redeeming it sets a new password,
-- ends every session the account has open, and tells the OLD address that it
-- happened — which is the half that matters: if somebody else did this, the
-- person whose account it is finds out at the address the attacker no longer
-- controls.
--
-- INSTANCE-WIDE, no `tenant_id` — see the exception list in 07 §7.1.
--
-- A reset is asked for at a login page. There is no session, no tenant and, for
-- an address nobody has, no account either. A tenant-scoped table could not be
-- written at the moment the request arrives, and a policy keyed on a context
-- that does not exist yields zero rows rather than an error — the request would
-- appear to succeed and nothing would happen.
--
-- Reachable only by presenting the token: no endpoint takes a user id, and the
-- lookup below is by the token's hash.

CREATE TABLE identity.password_reset (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),

    -- Whose. Instance-wide `identity.app_user` is referenced by id and without a
    -- foreign key, exactly as `tenancy.membership` does it.
    user_id        uuid NOT NULL,

    -- ONLY THE HASH. The token is shown once, in the message that carries it,
    -- and never stored — the same treatment REQ-SEC-048 gives an invitation
    -- token and a service account's, and for the same reason: a database dump
    -- must not hand somebody every open reset.
    --
    -- Globally unique, because a redeemer presents a token and nothing else.
    token_hash     text NOT NULL UNIQUE CHECK (length(token_hash) = 64),

    requested_at   timestamptz NOT NULL DEFAULT now(),

    -- Thirty minutes, written as a column rather than computed on read: a reset
    -- outlives the request that made it, and "when does this stop working" is a
    -- property of the token rather than of whoever asks about it.
    expires_at     timestamptz NOT NULL,
    CONSTRAINT expiry_is_after_request CHECK (expires_at > requested_at),

    -- Single use. Set when redeemed, and what makes a second attempt fail.
    used_at        timestamptz,

    -- Where it was asked for, for the security notification and for an operator
    -- looking at a run of requests. Removed after seven days like every other
    -- address (REQ-PRIV-006).
    requested_ip   inet
);

-- One open reset per account. A second request replaces the first rather than
-- adding to it: two live tokens is two ways in, and somebody who clicks the
-- older mail should be told it no longer works rather than let in.
CREATE UNIQUE INDEX one_open_reset_per_account ON identity.password_reset (user_id)
    WHERE used_at IS NULL;

-- Looked up by the hash of what the redeemer presented.
CREATE INDEX password_reset_by_expiry ON identity.password_reset (expires_at)
    WHERE used_at IS NULL;

-- No row-level security: there is no tenant to key it on, and the table is
-- reachable only by presenting a token. The rights are what bound it.
-- DELETE is here and it is deliberate. Asking again REPLACES the open reset
-- rather than adding to it (the partial unique index below says so), and the
-- replaced token is better gone than kept: a spent-looking row that was never
-- spent would be a lie in the data, and there is nothing of record value in it.
-- What survives is the notification that a reset was asked for, which is durable
-- and is where an operator looks. Rule 5's "no deletion without a tombstone"
-- governs domain tables; this is infrastructure (07 §7.1, rule 4).
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.password_reset TO homeinv_app;
GRANT SELECT ON identity.password_reset TO homeinv_readonly;
GRANT SELECT, DELETE ON identity.password_reset TO homeinv_housekeeping;
