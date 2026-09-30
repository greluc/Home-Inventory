-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- FEDERATED SIGN-IN (REQ-AUTH-005, REQ-AUTH-006).
--
-- Two tables, and the difference between them is what they are worth to
-- somebody who steals the database: one is a LINK that lasts, the other is a
-- sign-in in progress that is spent within ten minutes.
--
-- INSTANCE-WIDE, no `tenant_id` — see the exception list in 07 §7.1. A sign-in
-- happens before any tenant is known: the person arrives at a login page, and
-- which tenants they are a member of is what the session goes on to decide. A
-- policy keyed on a context that does not exist yields zero rows rather than an
-- error, so a tenant-scoped table would make an unlinked account look like a
-- wrong password.
--
-- WHAT THIS IS NOT: a way into an account by e-mail address. An identity is
-- keyed on (issuer, subject) and on nothing else, because a person changes
-- their address and a provider reuses one. REQ-AUTH-006 forbids automatic
-- linking by address, and the shape of these tables is what makes writing it
-- that way awkward rather than merely discouraged.

-- ---------------------------------------------------------------------------
-- The link
-- ---------------------------------------------------------------------------
CREATE TABLE identity.federated_identity (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),

    -- Whose. Instance-wide `identity.app_user` is referenced by id and without a
    -- foreign key, exactly as `identity.password_reset` and `tenancy.membership`
    -- do it.
    user_id        uuid NOT NULL,

    -- Which provider the operator installed this as. It names the configuration
    -- rather than the issuer, so that an operator who moves to a new issuer with
    -- the same button is a migration somebody performs rather than a silent
    -- re-pointing of every link.
    provider_key   text NOT NULL CHECK (length(provider_key) BETWEEN 1 AND 64),

    -- WHAT AN ACCOUNT IS LINKED BY, and the only thing.
    --
    -- The issuer is the `iss` of the verified token and the subject is its
    -- `sub`: stable, opaque, and the pair the provider promises not to reuse.
    -- The address is deliberately NOT part of it.
    issuer         text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
    subject        text NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),

    -- What the address WAS when the link was made. Evidence for the person
    -- looking at their own account page — "you linked this as ada@example.org" —
    -- and never an identifier: nothing looks an account up by it.
    linked_email   text,

    linked_at      timestamptz NOT NULL DEFAULT now(),

    -- The last time this link actually signed somebody in, so that a person can
    -- see which of their links is still in use and revoke the rest.
    last_used_at   timestamptz
);

-- ONE ACCOUNT PER FOREIGN IDENTITY, instance-wide.
--
-- Two accounts linked to the same (issuer, subject) would make the sign-in
-- ambiguous, and an ambiguity resolved by `LIMIT 1` is an account takeover with
-- an ordering bug in front of it.
CREATE UNIQUE INDEX one_account_per_foreign_identity
    ON identity.federated_identity (issuer, subject);

-- A person's own list, for the account page and for revoking one.
CREATE INDEX federated_identity_by_account ON identity.federated_identity (user_id);

-- No row-level security: there is no tenant to key it on. What bounds it is the
-- rights below and the shape of every query — a link is read by (issuer,
-- subject) during a sign-in, or by `user_id` for the caller's own account, and
-- no endpoint takes somebody else's user id.
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.federated_identity TO homeinv_app;
GRANT SELECT ON identity.federated_identity TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- The sign-in in progress
-- ---------------------------------------------------------------------------
--
-- ADR-0029: `state` and the PKCE verifier are held SERVER-SIDE, never in a
-- cookie. The session cookie is `SameSite=Strict` and a provider's callback is a
-- cross-site top-level navigation, so a cookie written before the redirect does
-- not come back with it. What travels is a single-use handle inside the `state`
-- parameter; the verifier never reaches the browser at all.
CREATE TABLE identity.federated_login (
    -- ONLY THE HASH of the handle, the same treatment `identity.password_reset`
    -- gives a reset token and for the same reason: a database dump must not hand
    -- somebody every sign-in in flight. The handle itself exists in the `state`
    -- parameter and nowhere else.
    handle_hash    text PRIMARY KEY CHECK (length(handle_hash) = 64),

    provider_key   text NOT NULL CHECK (length(provider_key) BETWEEN 1 AND 64),

    -- What this flow is FOR, decided before the redirect and never after.
    --
    -- SIGN_IN may create a session; LINK may only attach a foreign identity to
    -- the account named in `user_id`, which was signed in and re-confirmed when
    -- the flow began (REQ-AUTH-006). A callback cannot change its own purpose:
    -- that is the difference between linking an account and taking one over.
    purpose        text NOT NULL CHECK (purpose IN ('SIGN_IN', 'LINK')),

    -- Set for LINK and null for SIGN_IN, which the check below enforces rather
    -- than trusting the code that writes it.
    user_id        uuid,
    CONSTRAINT link_names_the_account CHECK (
        (purpose = 'LINK' AND user_id IS NOT NULL)
        OR (purpose = 'SIGN_IN' AND user_id IS NULL)
    ),

    -- The PKCE verifier, in the clear and deliberately so. It is not a
    -- credential of this deployment and it is worth nothing on its own: using it
    -- needs the provider's authorization code as well, which is single-use and
    -- goes to the redirect URI the provider itself checks. It lives ten minutes.
    -- Sealing it would add a key to rotate for a value that expires before the
    -- next backup.
    code_verifier  text NOT NULL CHECK (length(code_verifier) BETWEEN 43 AND 128),

    -- The core's one-time value for the token. The PLUGIN checks it inside the
    -- ID token; the core stores it so that the check is against what the core
    -- sent rather than against what came back.
    nonce          text NOT NULL CHECK (length(nonce) BETWEEN 16 AND 128),

    -- The redirect the provider was given, stored so the completion sends the
    -- identical one: the provider compares them, and a mismatch is how a code
    -- stolen from one client is refused at another.
    redirect_uri   text NOT NULL,

    -- Where to send the browser afterwards. A PATH on this instance and never a
    -- URL: anything absolute here would be an open redirect with a sign-in in
    -- front of it, which is the classic way a phishing page borrows somebody
    -- else's domain. The application refuses anything that does not start with a
    -- single `/`, and the check makes the same statement in the schema.
    return_to      text CHECK (return_to IS NULL OR return_to ~ '^/[^/]'),

    started_at     timestamptz NOT NULL DEFAULT now(),

    -- Ten minutes, written as a column rather than computed on read: a flow
    -- outlives the request that started it, and "when does this stop working" is
    -- a property of the handle.
    expires_at     timestamptz NOT NULL,
    CONSTRAINT expiry_is_after_start CHECK (expires_at > started_at),

    -- SINGLE USE. Set when the callback consumes it, and what makes a replayed
    -- callback fail — which is the property REQ-AUTH-005 is verified by.
    consumed_at    timestamptz
);

-- What the housekeeping run removes.
CREATE INDEX federated_login_by_expiry ON identity.federated_login (expires_at)
    WHERE consumed_at IS NULL;

-- No row-level security, for the reason the table above gives: a flow begins
-- before there is a tenant, and for a person who has no account here there is
-- none even in principle. Reachable only by presenting the handle.
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.federated_login TO homeinv_app;
GRANT SELECT ON identity.federated_login TO homeinv_readonly;
GRANT SELECT, DELETE ON identity.federated_login TO homeinv_housekeeping;
