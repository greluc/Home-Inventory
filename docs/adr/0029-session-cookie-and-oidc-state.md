# ADR-0029 — `SameSite=Strict` stays, and the two flows it breaks are decoupled

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** `REQ-SEC-017`, [08 §8.2](../architecture/08-api-contract.md),
[12 §12.4](../architecture/12-security.md)

## Context

The web session is a `__Host-` cookie with `Secure`, `HttpOnly` and
**`SameSite=Strict`**. `Strict` means the browser does not send the cookie on a
cross-site request — **including a top-level navigation the user performed
themselves**. Two flows in this system are exactly that:

1. **The QR scan from a foreign camera app.** The user points a phone camera at a
   label, taps the notification, and the browser performs a cross-site top-level
   `GET` to `/c/{code}`. Under `Strict` the session cookie is withheld, so an
   already-signed-in user is treated as anonymous and redirected to the login page.
   This is the system's primary capture and lookup path — quality goal Q6,
   `REQ-NFR-031` ("≤ 3 interactions"), the whole of
   [10 §10.7](../architecture/10-identification-and-labels.md).
2. **The OIDC callback.** The identity provider redirects back to us, again a
   cross-site top-level `GET`. A `state`/PKCE value stored in a `Strict` cookie
   before the redirect does not come back with it, and the flow cannot complete.

`Strict` was never argued for against these two flows; it was chosen as the
stricter of two values.

## Options

| Option | For | Against |
|---|---|---|
| Switch the session cookie to `SameSite=Lax` | The standard answer; `Lax` exists for precisely this case and still blocks cross-site `POST`. One change, no new mechanism | Widens the CSRF surface to every state-changing `GET`. We have none by design, but "we have none" is a property that has to stay true forever |
| **Keep `Strict`; give the two flows their own carriers** | The session cookie keeps the strongest setting. CSRF exposure is not widened at all. The code-resolution cookie carries no authority beyond "which account is this", and the OIDC state leaves the browser entirely | Two cookies instead of one, plus server-side state for OIDC. More moving parts, and the second cookie needs its own reasoning |
| Keep `Strict` and accept the detour | No work | Every scan from a foreign camera app goes through the login page. It breaks `REQ-NFR-031` for the flow chapter 10 calls the main one |

## Decision

**`SameSite=Strict` stays on the session cookie.** The two flows get their own
mechanisms.

### 1. Code resolution: a second, narrow cookie

| Property | Value |
|---|---|
| Name prefix | **`__Secure-`**, *not* `__Host-` |
| Path | `/c` |
| `SameSite` | `Lax` |
| Attributes | `Secure`, `HttpOnly` |
| Lifetime | Tied to the session; destroyed with it, and by remote sign-out |
| Contents | An opaque reference to the same server-side session. It grants **no** authority of its own |
| What it permits | Exactly one thing: `GET /c/{code}` may resolve the account and redirect to the item view instead of to the login page |

> **Why `__Secure-` and not `__Host-`.** The `__Host-` prefix requires `Path=/`.
> A cookie scoped to `/c` cannot carry it. Using `__Host-` here would be rejected
> by the browser and the cookie silently dropped — so `__Secure-` is not a
> weakening, it is the only prefix compatible with a path-scoped cookie. The
> protection `__Host-` adds over `__Secure-` (no `Domain`, so no subdomain may set
> it) is replaced by the cookie's own narrowness: it is read on one path, by one
> handler, and authorises one redirect.

Every other endpoint, including the item view the redirect lands on, requires the
`Strict` session cookie. A cross-site navigation therefore gets the user *to* the
right place with their identity known, and the page itself loads same-site.

### 2. OIDC: the state leaves the browser

`state` and the PKCE verifier are **not** stored in a cookie. They are stored
server-side, keyed by a single-use, high-entropy handle that travels in the
`state` parameter itself. The callback carries the handle, the server looks up the
verifier, and consumes it. Nothing needs to survive a cross-site navigation in the
browser.

This is stricter than the cookie approach in two ways: the verifier is never
exposed to the browser at all, and the handle is single-use, so a replayed
callback fails.

## Rationale

`Lax` would be a defensible choice and is what most projects pick. It was not
chosen because of one asymmetry: `Lax` weakens the **session** cookie — the one
that authorises everything — to fix a problem that exists in **one** path and one
callback. The decoupled version keeps the strongest setting where the authority
is, and gives the two exceptions carriers that are narrow enough to reason about
in a sentence each.

The OIDC half is not a trade-off at all. Server-side state is better than a state
cookie regardless of `SameSite`, and it removes the interaction entirely.

## Consequences

- **A second cookie exists and must be handled everywhere the session is**:
  created together, destroyed together, revoked by remote sign-out
  (`REQ-AUTH-009`), and covered by the same test that checks cookie attributes.
  A test asserts it is rejected on any path other than `/c`.
- **The OIDC flow needs server-side storage** for state and verifier, in Valkey
  with a short TTL. A Valkey outage therefore breaks *new* OIDC logins — added to
  the degradation table in
  [13 §13.6](../architecture/13-operations-and-observability.md), where Valkey's
  consequences were previously listed as "sessions invalid" only.
- **`/c/{code}` keeps its neutral response** (`REQ-IDENT-014`): unknown code and
  missing permission remain indistinguishable. The new cookie changes who is
  recognised, never what is disclosed.
- **The offline path is unaffected.** The installed PWA and the apps resolve
  `/c/{code}` and the `#i=` fragment locally without any cookie
  ([10 §10.2](../architecture/10-identification-and-labels.md)).
- Two new requirements are added to `SEC-B` for the code-resolution cookie and the
  server-side OIDC state, so both are verified rather than implied.
