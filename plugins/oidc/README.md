# plugin-oidc

Signing somebody in against an OIDC provider — Keycloak, Authentik, Authelia,
Zitadel, or a hosted one.

It is the fifth of the five first-party plugins and **the only one in Java**.
That is a deliberate exception to the reasoning that made the other four Rust,
and [ADR-0072](../../docs/adr/0072-first-party-plugins-live-here.md) states it:
ID-token validation is where an audited library outweighs an image size, because
a subtle verification bug written twice is how a federated login becomes an
authentication bypass.

## What it verifies before it says anything

Every one of these, through [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt):

| | |
|---|---|
| the signature | against the provider's published key set, fetched through the proxy and refreshed when a key id is unknown |
| the algorithm | `RS256` or `ES256`. `none` and the HMAC family are refused: a token signed with a shared secret is one anybody holding that secret can mint |
| the issuer | equal to the configured one, and equal to what the discovery document says it is (OIDC Discovery §4.3) |
| the audience | this deployment's client id |
| the expiry | present and in the future |
| the **nonce** | equal to the one the **core** minted for this sign-in |

The nonce is the one check a library cannot make on its own, because the
expected value belongs to the core. Without it, an ID token obtained through one
sign-in completes another.

**No redirect is ever followed.** A redirect is a target the far side chose, and
following one would send a client secret or an authorization code somewhere the
operator's allowlist never approved.

## What it decides about accounts: nothing

It reports who the provider says somebody is. Whether that becomes a session,
and against which account, is the core's decision and a person's confirmation
([ADR-0076](../../docs/adr/0076-a-federated-sign-in-creates-an-account-only-where-a-form-could.md),
`REQ-AUTH-006`). An address this plugin reports as **unverified** stays
unverified: the core treats one as absent, because an unconfirmed address is a
claim about somebody else.

## Configured once, by the operator

This is the plugin that is **not** configured twice, and the reason is what a
sign-in is: it happens before any tenant is known — it is the step that decides
which memberships a session may act on — so there is no tenant whose settings
could be read. The core resolves it through the **instance operator's** grant
([ADR-0066](../../docs/adr/0066-instance-level-capability-grants.md)).

| Setting | Where | Default |
|---|---|---|
| `HOMEINV_OIDC_ISSUER` | container environment | none — an `https://` URL; discovery, the key set and the token endpoint all follow it |
| `HOMEINV_OIDC_CLIENT_ID` | container environment | none |
| `HOMEINV_OIDC_CLIENT_SECRET_FILE` | **a mounted secret** | `/run/secrets/plugin-oidc-client-secret`; empty means a public client, authenticating with PKCE alone |
| `HOMEINV_OIDC_PROVIDER_KEY` | container environment | `oidc` — the stable key the core names this configuration by |
| `HOMEINV_OIDC_DISPLAY_NAME` | container environment | `Single sign-on` — what the button says |
| `HOMEINV_OIDC_SCOPES` | container environment | `openid email profile`; `openid` is added whether or not it is listed |
| `HOMEINV_OIDC_TIMEOUT_SECONDS` | container environment | `20` |
| `HOMEINV_EGRESS_PROXY` | container environment | none — and without it there is no route out |

An `https://` issuer is required rather than preferred: everything else is
reached from that value, and an `http://` one would send a client secret and an
authorization code over a connection nothing protects.

**The client secret never passes through the core.** It is a file mounted into
this container; `deploy/setup.sh` creates
`deploy/secrets/plugin-oidc-client-secret` **empty** and says so, because a
secret issued by somebody else's system is not one this deployment may invent.

## What the core keeps

The `state`, the `nonce` and the PKCE verifier are the **core's**. They are
minted there, stored server-side ([ADR-0029](../../docs/adr/0029-session-cookie-and-oidc-state.md))
and checked there; this plugin receives the challenge and the nonce because the
protocol puts them on the wire. A value only a plugin knew would be a value the
core could not check — and a sign-in the core cannot check is a redirect with a
session attached to it.

## Redirect URI

`<HOMEINV_PUBLIC_BASE_URL>/api/v1/auth/federated/callback`, which is the core's
and is registered with the provider. A plugin does not get to choose it.

## Memory

192 MB reserved, 512 MB limit — `REQ-NFR-009`'s adder for **this** container,
which is its own since [ADR-0075](../../docs/adr/0075-the-one-jvm-plugin-has-its-own-line-in-the-budget.md).
The requirement was amended before this plugin was written rather than after; a
number changed to fit what was built is not a budget.

## Building and testing

```bash
./gradlew :plugins:oidc:check
```

The tests run a **real provider**: a small HTTP server that publishes a
discovery document and a key set and signs tokens with a key generated for the
run. A good token verifies; one with somebody else's nonce, somebody else's
signature, somebody else's audience or an expiry in the past does not. The first
run of that suite found the audience and the issuer passed to the verifier the
wrong way round, which would have refused every valid token — which is the
argument for an audited library, made by the test rather than in a comment.
