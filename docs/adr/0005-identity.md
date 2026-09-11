# ADR-0005 — An own identity core with optional OIDC federation

**Status:** Accepted · **Date:** 2026-09-11

## Context

The system is reachable from the internet and multi-tenant. Third parties should
be able to run it themselves. An existing Keycloak instance is **not** available
and should not be assumed.

## Options

| Option | For | Against |
|---|---|---|
| **An own core + OIDC as a module** | A fresh installation runs without a second service · full control over registration, invitations, second factor and session management · OIDC stays available for operators who have a provider | **Home-grown authentication is security-critical** and has to be maintained permanently |
| An external OIDC provider only | No passwords in the system, the smallest attack surface, familiar from other projects | Every installation mandatorily needs a second service — a high barrier for self-hosters |
| Built-in only, no federation | The simplest operation | No SSO, no integration into existing environments |

## Decision

**An own identity core** with Argon2id, TOTP and WebAuthn/passkeys, plus **OIDC
federation through the `IdentityProvider` port**.

## Rationale

The hurdle "install Keycloak first" is in practice what stops a self-hosted
project from being used. At the same time, an operator with existing identity
infrastructure must not be shut out. The port solves both. The extra effort is
known and is bounded by adhering strictly to OWASP ASVS and to established
libraries (Spring Security, WebAuthn4J) — nothing cryptographic is invented here.

## Consequences

- Authentication gets above-average test coverage and is the focus of every
  security review.
- The second factor is **mandatory** for `OWNER` and `ADMIN`, not optional.
- No automatic account linking by e-mail address alone — that is a known takeover
  route. Linking only after re-authentication.
- `identity` knows **no** tenants and **no** permissions. Who someone is and what
  someone may do stay separate questions and separate building blocks.
- An operator can disable local passwords instance-wide and require OIDC
  exclusively.
- Password hashing parameters are configurable and are upgraded transparently on
  login when the operator raises them.
