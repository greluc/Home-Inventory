<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0076 — A federated sign-in creates an account exactly where a sign-up form could

**Status:** Accepted · **Date:** 2026-09-21

**Amends:** `REQ-AUTH-004` (what an `open` instance needs before it may start)

**Depends on:** [ADR-0029](0029-session-cookie-and-oidc-state.md),
[ADR-0066](0066-instance-level-capability-grants.md),
[ADR-0072](0072-first-party-plugins-live-here.md)

## Context

`REQ-AUTH-005` puts federated sign-in behind the `IdentityProvider` port and `REQ-AUTH-006` says
what it must never do: **no automatic account linking by e-mail address alone.** Between them they
settle the dangerous half. They leave one question open, and it is the question that decides how
much of the feature exists at all: *when a verified identity arrives that nobody has linked, may
this instance create an account for it?*

Three answers were defensible. Only signing in identities somebody already linked is the smallest
and safest. Creating one wherever a sign-up form could is the most useful. Letting an invitation be
redeemed by a federated sign-in is the most convenient and the most machinery.

The second answer runs into `REQ-AUTH-004`, which says an `open` instance creates an account
**after the address is confirmed**, and that confirming one needs `plugin-smtp` — so `open` refused
to start at all while there was no plugin runtime. There is one now
([ADR-0028](0028-plugin-runtime-stage-1.md), [ADR-0072](0072-first-party-plugins-live-here.md)),
and the refusal had to become a real check before any of this could be reached.

## Options

| Option | A stranger with a verified identity | What an operator has to run |
|---|---|---|
| **Create where `open` would** | an account, on an instance that says so | a provider, or a mail sender |
| Only sign in what is linked | refused; the account has to exist first | nothing extra |
| Redeem an invitation too | an account, if somebody invited that address | a provider, plus invitation handling in the flow |

## Decision

**A federated sign-in creates an account exactly where a sign-up form could, and never anywhere
else.**

| The instance is | An identity nobody has linked |
|---|---|
| `invite_only` or `closed` | refused — `federated-identity-unlinked` |
| `open`, address **verified** and free | an account is created, with no password |
| `open`, address verified and **taken** | refused — `federated-address-taken` |
| `open`, address **unverified** | refused — `federated-address-unverified` |

Three things follow, and each is the part somebody would otherwise get wrong:

1. **An address that already has an account is a refusal, not a link.** That is `REQ-AUTH-006`
   stated in the one place it would be tempting to break: the address matches, the provider
   verified it, and linking it here would still be the account takeover the requirement exists to
   forbid. The person signs in with the account they have and links the provider from their own
   settings, re-confirming the second factor first.
2. **An unverified address creates nothing.** An address a provider did not confirm is a claim
   about somebody else, and a provider that lets a person type anything would otherwise create
   accounts here in other people's names.
3. **An `open` instance may be served by either mechanism.** `REQ-AUTH-004` asks for an account
   created *"after the address is confirmed"* rather than for a particular way of confirming it. A
   **notification channel** confirms one by sending a link and seeing it followed; an **identity
   provider** confirms one by verifying a token that asserts it, which is stronger and needs this
   deployment to send nothing. An instance that has neither cannot confirm anything and **refuses
   to start**, which is what the requirement always said — checked now where the registry is
   readable rather than in a constructor that could only refuse.

## Rationale

The third answer — redeeming an invitation — was rejected for what it would have to bind: an
invitation is issued to an address, and accepting one by federated sign-in means deciding whether
the provider's assertion of that address is good enough to consume somebody else's invitation. That
is the same question as (1), asked in a place with more moving parts, and the gain over "sign in,
then accept" is one click.

Creating an account is deliberately tied to the **same setting** that governs a sign-up form rather
than to a setting of its own. An operator who has decided who may come to exist on their instance
has decided it once; a second switch would eventually disagree with the first, and the disagreement
would be discovered by whoever walked through the door nobody meant to leave open.

## Consequences

- **`identity.federated_identity`** keys a link on `(issuer, subject)` and nothing else, unique
  instance-wide. The address is stored as evidence of what it was at linking; nothing looks an
  account up by it.
- **`identity.federated_login`** holds the flow: the PKCE verifier, the nonce and the purpose,
  keyed by the SHA-256 of a single-use handle that travels in `state`
  ([ADR-0029](0029-session-cookie-and-oidc-state.md)). A replayed callback finds it spent.
- **The purpose is fixed before the redirect.** A callback cannot turn a sign-in into a link, which
  is the difference between attaching an identity and taking an account over.
- **Five problem types** are registered for the endings a caller can act on, and the callback
  answers a browser with a redirect carrying the same token — a person following a provider's
  redirect must not land on a JSON document.
- **Linking requires a fresh second factor**, so an account with none cannot link a provider until
  it has one. That is the intended order: protect the account, then add a second door to it.
- **A federated sign-in still answers the second factor.** The provider proved who somebody is, not
  that they hold the authenticator this instance knows about, so the flow stops exactly where a
  password login stops (`REQ-AUTH-002`).
- **`OpenRegistrationReadiness`** replaces `RegistrationPolicy`'s flat refusal of `open`. A
  constructor cannot ask the registry what is installed, and the answer decides whether the
  instance may run at all.
