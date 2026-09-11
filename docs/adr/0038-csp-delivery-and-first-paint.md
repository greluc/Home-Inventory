# ADR-0038 — The CSP is delivered by `web` with hashes, and the theme is mirrored locally

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [12 §12.9](../architecture/12-security.md),
[04 §4.1](../architecture/04-building-blocks.md),
[ADR-0012](0012-web-frontend.md), [ADR-0033](0033-dark-as-default-appearance.md)

> **Amended by [ADR-0040](0040-no-cross-origin-isolation.md):**
> `Cross-Origin-Embedder-Policy` is **not** sent. §1 below lists it among the
> headers `web` emits; that half is withdrawn. Everything else in §1 — that `web`
> serves the shell **and** the header set, from configuration in this repository —
> stands, and is the whole point of the section.

## Context

Three decisions were each sound and could not all be true at once.

1. **The policy uses nonces.** [12 §12.9](../architecture/12-security.md) serves
   `script-src 'self' 'nonce-{r}'; style-src 'self' 'nonce-{r}'`. A nonce is
   per-response by definition — reusing one across responses is the same as not
   having one — so whatever emits the HTML document has to mint a fresh value and
   substitute it into the document in the same breath.
2. **Nothing that emits the document can do that.** `web` is a static
   `nginx-unprivileged` serving a built Vite bundle, and
   [04 §4.1](../architecture/04-building-blocks.md) additionally offered *"can
   also be served directly by the reverse proxy"* — an operator's configuration,
   outside this repository.
3. **The theme must be applied before first paint.** `REQ-NFR-040` and
   [ADR-0033](0033-dark-as-default-appearance.md) make dark the default and light
   an opt-in *stored server-side with the user profile*, with the binding
   condition that *"a light-preferring user never sees a dark frame"*. A
   preference that arrives with the profile fetch arrives after first paint. The
   only way to beat first paint is a small inline script or style in the document
   — which is precisely what the policy in (1) is set up to gate, and what (2)
   cannot give a nonce to.

So the corpus specified an inline bootstrap, a policy that requires a nonce for
it, and no component able to produce one. None of the three was wrong on its own.

## Options

| Option | For | Against |
|---|---|---|
| Server-render `index.html` from `api` | Nonces work; the theme can be stamped straight into the document from the session | Makes the application shell dynamic and per-user. `web` stops being static and cacheable, `api` gains an HTML-rendering surface, and the frontend stops being independently deployable — for one attribute on `<html>` |
| A third cookie carrying the theme | The server owns the value and can clear it at sign-out | A cookie on every request to pick a colour, plus a `SameSite` decision that has to be right on the cross-site scan path. It buys server-side clearing of a preference whose disclosure value is nil |
| Have nginx substitute a nonce (`ngx_http_sub_module` with `$request_id`) | Keeps the shell static-ish | A rewriting filter on every document response, a module that must be present in the image, and a value whose entropy and uniqueness now depend on nginx's request ID rather than on a CSPRNG. A lot of machinery to authorise a script we ship ourselves and can name exactly |
| **Drop the nonces, use hashes; serve the headers from `web`** | The bundle's inline content is fixed and known at build time, so a hash is the *more* precise statement: it authorises that exact script and no other, where a nonce authorises anything the server chooses to mark | The hash must be regenerated whenever the bootstrap changes, and the policy and the bundle must be checked against each other in CI — which is work that has to exist anyway (`REQ-SEC-060`) |
| Drop the "no flash" condition | No work | It is one of the three conditions [ADR-0033](0033-dark-as-default-appearance.md) named as making the dark-by-default override defensible at all. Removing it re-opens that decision |

## Decision

### 1. `web` serves the application shell and every security header

The option *"can also be served directly by the reverse proxy"* in
[04 §4.1](../architecture/04-building-blocks.md) is **withdrawn**. The CSP, HSTS,
COOP, CORP, `Referrer-Policy` and `Permissions-Policy` are emitted by the
`web` container, from configuration that lives in this repository and is compared
against an expected string in CI (`REQ-SEC-060`). *(This list read
"COOP/CORP/COEP" until 2026-09-11;
[ADR-0040](0040-no-cross-origin-isolation.md) drops COEP, and `REQ-SEC-061` now
makes the CI comparison **fail if it reappears**.)*

That is the whole reason. A header set an operator writes into their own proxy
config cannot be tested here, and a policy nobody tests is a policy that drifts
until the first `unsafe-inline` appears to make something work.

### 2. Hashes, not nonces

```
script-src 'self' 'sha256-<bootstrap>';
style-src  'self' 'sha256-<critical>';
```

Both hashes are emitted by the build and written into the served policy by the
same build. A CI check recomputes them from the built bundle and fails if the
policy and the bundle disagree — the same drift mechanism as
`deploy/services.yaml` and `design-system/tokens/tokens.json`.

`'strict-dynamic'` is **not** used. It exists to let an authorised script load
further scripts, which is the opposite of what a fixed, self-hosted bundle with
no third-party host ([ADR-0034](0034-icon-set-and-no-third-party-hosts.md)) wants.

### 3. The theme is mirrored into `localStorage`

The **authoritative** value stays where
[ADR-0033](0033-dark-as-default-appearance.md) put it: in the user profile,
server-side, travelling with the account. What the client keeps is a **local
mirror of it**, written after the profile loads and read by the inline bootstrap
before the first paint.

| Property | Value |
|---|---|
| Key | `homeinv.theme` |
| Contents | `dark` or `light`. Nothing else, ever |
| Authority | **none.** It selects a stylesheet. No authorisation path reads it, and a forged value changes a colour |
| Written by | the client, once the profile is loaded and whenever the preference changes |
| Cleared by | the client on sign-out, together with the rest of the local store |
| Absent | yields `dark`, which is the documented default — so there is no flash, there is simply the default |

This does not weaken the no-flash condition, and the timing is worth spelling out
because it is the reason a cookie looked necessary: a cookie would arrive with the
document on a cold start, and a `localStorage` read happens in the first inline
script of that same document. **Both beat the first paint; neither beats the
first-ever profile load on a new device**, where a light-preferring user sees the
default once — with a cookie too, since the server can only set one after it
knows who is asking.

Two things `localStorage` does better here, and one it does worse:

- **No `SameSite` question at all.** A cookie would have needed `Lax` so that a QR
  scan arriving cross-site at `/c/{code}` carried the theme — one more attribute
  to get right on the very path [ADR-0029](0029-session-cookie-and-oidc-state.md)
  exists to protect. `localStorage` is per-origin and is read the same way however
  the navigation arrived.
- **Two cookies stay two.** [ADR-0029](0029-session-cookie-and-oidc-state.md)
  reasoned each of its cookies out in a sentence; a third that exists to pick a
  colour would have diluted that.
- **The server cannot clear it.** Sign-out is a client action here, so a shared
  browser keeps the last user's theme until the next profile loads. That is a
  colour, on a device the previous user was signed into — it discloses nothing
  and corrects itself on the next login.

`localStorage` is not a contradiction of
[11 §11.8](../architecture/11-offline-synchronisation.md)'s *"No `localStorage`
for domain data"*: a theme name is not domain data, it is not synchronised, and
losing it costs one default-coloured paint.

## Rationale

The nonce was never earning anything here. Its advantage over a hash is that it
authorises scripts the server decides on at request time — which this application
does not have and, under
[ADR-0034](0034-icon-set-and-no-third-party-hosts.md)'s no-third-party-host rule,
is never going to have. Every byte of script and style in the shell is fixed at
build time. A hash says exactly that, and says it without requiring a dynamic
component in front of a static bundle.

Withdrawing the "reverse proxy may serve it" option is the part that costs an
operator something — one more container in the smallest deployment. It is worth
it because `REQ-SEC-060` is written as a CI check, and a CI check over a file the
operator supplies is not a check. The project already made this trade once, for
the same reason, when it chose the egress proxy over `nftables` on the host
([ADR-0027](0027-egress-enforcement.md)): a mechanism that can be verified beats
a mechanism that is merely configured.

## Consequences

- **[12 §12.9](../architecture/12-security.md) changes**: `'nonce-{r}'` becomes
  `'sha256-…'` in both directives, with the build as the source of the values.
- **[04 §4.1](../architecture/04-building-blocks.md) loses a row**: `web` is no
  longer optional. It was listed as "can also be served directly by the reverse
  proxy", and that is now stated as unsupported, with the reason.
- **`REQ-SEC-060` gains the drift check** — the served policy's hashes must match
  the built bundle — and keeps its functional tests.
- **A new requirement, `REQ-SEC-100`**, covers the local theme mirror: what it may
  contain, that it carries no authority, and that the profile remains the
  authoritative copy.
- **The cookie count stays at two** — the `__Host-` session cookie and the
  `__Secure-` cookie scoped to `/c`
  ([ADR-0029](0029-session-cookie-and-oidc-state.md)). An earlier draft of this
  ADR added a third for the theme; `localStorage` does the same job without a
  `SameSite` decision on the scan path and without a cookie sent on every request.
- **The apps are unaffected.** Compose Multiplatform reads the preference from the
  profile it already fetches, and has no first-paint race of this kind.
- **A `<noscript>` user gets dark.** That is the default anyway, and the
  application is a PWA that does not function without scripting, so nothing is
  lost that was not already gone.
