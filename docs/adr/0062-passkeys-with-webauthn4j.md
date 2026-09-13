# ADR-0062 — Passkeys are verified by webauthn4j, and no attestation is trusted

**Status:** Accepted · **Date:** 2026-09-13

## Context

`REQ-AUTH-002` asks for a second factor "through TOTP **and** WebAuthn/passkeys",
with both usable. TOTP is forty lines of HMAC and needed no decision. WebAuthn
does: the verification is CBOR parsing, COSE keys, attestation statements in
half a dozen formats, an origin check, a signature check and a counter check, and
a mistake in any of them is a second factor that verifies things that are not
true.

Two libraries are plausible on the JVM: Yubico's `java-webauthn-server` and
`webauthn4j`. Writing it by hand was considered and rejected in one line — this
is the class of code where a subtle error is invisible in review and fatal in
use.

## Decision

**`webauthn4j`**, Apache-2.0, pinned in the version catalogue like everything
else. It is the more complete of the two — registration, assertion, every
attestation format, and an authenticator emulator that the tests here drive — and
from 0.31 it is on **Jackson 3**, which is the Jackson this application already
uses. An older line would have brought a second Jackson major into the image for
one library.

**No attestation is trusted, deliberately.** The manager is webauthn4j's
non-strict one: it accepts `none` and self attestation and validates no
certificate path. Two reasons, and the second decides it:

- A self-hosted instance has no business deciding which manufacturer's
  authenticator makes a person trustworthy. The person is already known — this is
  a *second* factor, after the password.
- Validating a certificate path means FIDO's metadata service, which is an
  outbound connection, and the core makes none
  ([ADR-0026](0026-core-outbound-via-plugins.md)). A library configured to fetch
  it would fail closed on an instance with no egress, which is every instance
  here.

What **is** verified is everything that decides whether a response belongs to
this challenge: the origin, the relying party id, the challenge itself, the
signature, and the authenticator's sign counter.

**A passkey is a second factor, not a replacement for the password.** No resident
key is asked for (`residentKey: discouraged`) and no user handle is looked up: the
account is known by the time the passkey is used, because the password came
first. Passwordless sign-in is a different requirement and would need its own
decision about what a lost device costs.

**The relying party is `HOMEINV_PUBLIC_BASE_URL`.** The `rpId` is its host and the
accepted origin is the URL itself. That makes it the second thing in this system
that a change to the base URL invalidates — printed labels are the first
([10 §10.2.1](../architecture/10-identification-and-labels.md)) — and the warning
there now names both.

## Consequences

- **A tenant of an instance whose base URL changes must register their passkeys
  again.** Nothing is lost silently: the passkeys stop verifying, the login asks
  for a code instead, and somebody who has only passkeys uses a recovery code.
- **The image carries one more dependency and its transitive CBOR parser.** Both
  are Apache-2.0, and `cargo deny`'s JVM counterpart — the licence gate on the
  dependency report — covers them.
- **The tests drive real ceremonies.** `webauthn4j-test`'s emulator produces
  attestation and assertion objects signed by a key pair it holds, against the
  challenges this application issued, through the REST API. A stub would have
  proved that a stub agrees with the code under test.
- **The browser half is twenty lines and a fallback.** The client uses
  `parseCreationOptionsFromJSON` and `toJSON` where the browser has them and
  converts the four base64url fields itself where it does not, so a phone one
  release behind still works.
- **An authenticator that only does attestation this refuses does not exist.**
  Accepting `none` is the permissive end; nothing is rejected for the kind of
  attestation it carries.
