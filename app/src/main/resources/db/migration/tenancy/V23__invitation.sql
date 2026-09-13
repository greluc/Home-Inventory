-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Inviting somebody into a tenant (REQ-TEN-004).
--
-- Single-use, time-limited and bound to an e-mail address — the three properties
-- the requirement names, and each of them is a column here rather than a rule in
-- the application, because an invitation outlives the request that made it and
-- the request that uses it.
--
-- It is also the instance's only registration path (REQ-AUTH-004,
-- HOMEINV_REGISTRATION_MODE=invite_only): accepting an invitation for an address
-- that has no account creates that account. Without this table a fresh instance
-- has exactly one person on it forever, which is what it had until now.

CREATE TABLE tenancy.invitation (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid NOT NULL,
    -- Bound to an address (REQ-TEN-004). Stored as entered so it can be shown
    -- back that way, compared lowercased — the same treatment identity.app_user
    -- gives the address it is the credential for, and for the same reason:
    -- treating two spellings as two people is a way to lock somebody out.
    email        text NOT NULL CHECK (length(btrim(email)) > 0),
    role         text NOT NULL
                 CHECK (role IN ('OWNER','ADMIN','MEMBER','CONTRIBUTOR','VIEWER','GUEST')),
    -- The token is a credential, so only its hash is here (REQ-SEC-048). The
    -- value itself exists once, in the response to the request that created it,
    -- and in the invitation mail plugin-smtp sends when it is installed.
    -- Globally unique, because the acceptor presents a token and nothing else:
    -- there is no tenant yet to scope the lookup to.
    token_hash   text NOT NULL,
    expires_at   timestamptz NOT NULL,
    -- Single use. Set when it is redeemed; a second attempt finds it set and is
    -- refused. Kept rather than deleted, because "who let this person in, and
    -- when" is a question an audit asks about a membership that already exists.
    accepted_at  timestamptz,
    accepted_by  uuid,
    -- Withdrawn before it was used. Separate from `accepted_at` so the two cases
    -- stay distinguishable in the record, and answered identically to the caller
    -- so the token cannot be used to tell them apart.
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_by   uuid,
    deleted_at   timestamptz,
    version      bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id)
);

-- Instance-wide unique, not per tenant: the lookup is by token alone, before any
-- tenant is known. A collision would be two tenants' invitations answering to one
-- secret, which is a cross-tenant fault and not a usability one.
CREATE UNIQUE INDEX invitation_token_unique ON tenancy.invitation (token_hash);

-- One open invitation per address per tenant. Partial, so an invitation that was
-- used, withdrawn or has run out does not block a new one — which is the
-- ordinary case: somebody is invited, misses it, and is invited again.
CREATE UNIQUE INDEX invitation_open_per_address
    ON tenancy.invitation (tenant_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL AND deleted_at IS NULL;

CREATE INDEX invitation_by_tenant ON tenancy.invitation (tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE tenancy.invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.invitation FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.invitation
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON tenancy.invitation TO homeinv_app;
GRANT SELECT ON tenancy.invitation TO homeinv_readonly;

-- ---------------------------------------------------------------------------
-- The lookup that has to run before a tenant context exists
-- ---------------------------------------------------------------------------
--
-- The same circularity as the login (V7) and for the same reason: somebody
-- accepting an invitation presents a token, the token identifies the invitation,
-- and the invitation identifies the tenant. With no `app.tenant_id` set the
-- policy above yields zero rows, so the accept could never find the tenant it is
-- about to set.
--
-- 07 §7.5 names one way out of that and it is this one: an explicit, logged
-- SECURITY DEFINER function with its own permission check, never BYPASSRLS. It
-- is the smallest one that closes the circle — a token hash in, a tenant id and
-- an invitation id out, and not one fact more. Everything the accept then needs
-- it reads ordinarily, under the tenant context it has just established.
--
-- `homeinv_bootstrap` is the role it runs as, the same NOLOGIN role V7
-- introduced. It is not "the login role": it is the role for the reads that must
-- work BEFORE a tenant is known, and this is the second of them. What keeps it
-- narrow is that it owns nothing but these functions and can log in nowhere.

CREATE FUNCTION tenancy.invitation_by_token(p_token_hash text)
RETURNS TABLE (tenant_id uuid, invitation_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Pinned, because a SECURITY DEFINER function that resolves names through the
-- caller's search_path can be pointed at a different `invitation`.
SET search_path = tenancy, pg_temp
AS $$
    SELECT i.tenant_id, i.id
    FROM tenancy.invitation i
    WHERE i.token_hash = p_token_hash
      AND i.deleted_at IS NULL
$$;

-- Deliberately NOT filtered on expiry, acceptance or revocation. The application
-- reads those under the tenant context and answers all of them identically; a
-- function that returned nothing for a used token would make "no such token" and
-- "already used" distinguishable by how far the request got.

CREATE POLICY bootstrap_lookup ON tenancy.invitation
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON tenancy.invitation TO homeinv_bootstrap;

-- PostgreSQL requires the new owner to hold CREATE on the schema before it will
-- transfer ownership; granted for that one statement and taken straight back.
GRANT CREATE ON SCHEMA tenancy TO homeinv_bootstrap;
ALTER FUNCTION tenancy.invitation_by_token(text) OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA tenancy FROM homeinv_bootstrap;
REVOKE ALL ON FUNCTION tenancy.invitation_by_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.invitation_by_token(text) TO homeinv_app;
