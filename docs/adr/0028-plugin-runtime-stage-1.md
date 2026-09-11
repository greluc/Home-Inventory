# ADR-0028 — The plugin runtime moves to stage 1

**Status:** Accepted · **Date:** 2026-09-11
**Depends on:** [ADR-0026](0026-core-outbound-via-plugins.md)
**Amends:** the stage plan in [04 Roadmap](../requirements/04-roadmap-and-stages.md)
and [03 §3.9](../architecture/03-solution-strategy.md).

## Context

[ADR-0026](0026-core-outbound-via-plugins.md) moves every outbound connection of
the core into a plugin. That collides head-on with the stage plan, which put the
plugin runtime in **stage 3**:

| Needs an outbound connection | Requirement | Stage |
|---|---|---|
| Invitations | `REQ-TEN-004` | 1 |
| Password reset | `REQ-SEC-018` | 1 |
| Security notifications ("cannot be switched off") | `REQ-NOTI-004` | 1 |
| Nextcloud and S3 storage | `REQ-MED-009` | 1 |

A stage 1 installation would be unable to invite anyone or reset a password. That
is not a shippable stage, and the roadmap's own rule is that every stage is
"usable and deployable on its own".

The stage plan also assumed something that is no longer true: *"Plugins — stage 3 —
only once the ports are functionally stable; a contract published early is
binding."* After ADR-0026 the contract is not an optional extension any more. It
is how the system sends mail.

## Options

| Option | For | Against |
|---|---|---|
| Minimal gRPC sidecars in stage 1, full runtime in stage 3 | Smallest stage-1 increment | Two mechanisms for the same thing, and the second one has to adopt the first. The transport, the mTLS identity and the contract would be built anyway — what is saved is the registry and the consent flow, which is the cheap half |
| **Full plugin runtime in stage 1** | One mechanism, built once. First-party plugins (SMTP, Nextcloud) are the proving ground for the contract before third parties touch it | Moves the single largest block forward and aggravates risk R1 |
| Keep the runtime in stage 3 and let stage 1 ship without mail | No schedule change | Stage 1 cannot invite a user. It fails its own definition of done |
| Exempt SMTP from ADR-0026 | Cheapest | Re-opens the decision ADR-0026 just closed, and does so for the one outbound connection that carries invitation tokens |

## Decision

**The plugin runtime — registry, manifest, signature verification, per-tenant
capability model, lifecycle, health, gRPC over mTLS, bulkhead and circuit breaker —
is part of stage 1.** Stage 3 keeps enrichment, the public SDK, third-party
plugins, the apps and offline sync.

### What moves from stage 3 to stage 1

`REQ-PLG-001` … `REQ-PLG-008`, `REQ-PLG-010`, `REQ-PLG-011`, `REQ-PLG-013` ·
`REQ-SEC-053` … `REQ-SEC-059`, `REQ-SEC-073` (plugin writes in the audit log — the
same fact as `REQ-PLG-011`, and leaving it at stage 3 would have been a
contradiction) · `REQ-API-007` · `REQ-API-010` · `REQ-AUTH-005` and its companions
`REQ-AUTH-006` / `REQ-SEC-020` (no automatic account linking — it only matters once
OIDC exists, which is now stage 1).

Two move further forward, to **stage 0**, because they describe the network layout
that is built there anyway: `REQ-PRIV-003` (the core has no outbound route) and
`REQ-SEC-034` (the core fetches no user-supplied URL).

### A correction made in the same pass

`REQ-SEC-001` … `REQ-SEC-008` and `REQ-TEN-001` — row-level security, the roles,
the tenant context, the isolation proof — carried **stage 1**, while the roadmap
said three times over that RLS belongs to stage 0 ("all security foundations",
"the RLS schema", and under *what was deliberately not deferred*: "RLS"). The
catalogue was wrong and is corrected to stage 0. Of that group only `REQ-SEC-009`
stays at stage 1, because it concerns the OpenSearch index, which does not exist
before then.

This is the discrepancy that costs most if it is discovered late: retrofitting RLS
means touching every table, every query and every test — which is the roadmap's own
argument for putting it first.

### What deliberately stays in stage 3

`REQ-PLG-009` (the public SDK for Java and Python), `REQ-PLG-012` (`ui:panel`),
`REQ-PLG-014` (`PLUGINS.md`), the whole `ENR` area, and the example plugins.

The distinction is **first-party versus third-party**. Stage 1 runs plugins we
wrote, against a contract we may still change. Stage 3 opens that contract to
others — and only from that point is it binding under
[ADR-0011](0011-api-versioning.md)'s six-month rule.

## Rationale

Given ADR-0026, the choice is between building the plugin runtime once in stage 1
or building a smaller thing in stage 1 and the real thing in stage 3. The smaller
thing is not much smaller: process boundary, mTLS identity, deadline, circuit
breaker and the protobuf contract are needed either way. What the "minimal
sidecar" option saves is the registry, the manifest signature and the consent
flow — perhaps a fifth of the work, at the price of two mechanisms and a migration
between them.

There is also a benefit that is easy to miss: **the first plugins are ours.**
Building SMTP and Nextcloud against the contract before publishing it is the best
possible test of whether the contract is right — far better than designing it in
the abstract in stage 3 and discovering its gaps with third parties watching.

## Consequences

- **Risk R1 gets worse and the roadmap must say so.** The mitigation "stage 0 is
  deliberately small, and after each stage the rest is re-assessed" still holds
  for stage 0, but stage 1 is now materially larger. R1 in
  [14 §14.3](../architecture/14-quality-risks-glossary.md) is updated rather than
  left claiming a protection it no longer offers.
- **The contract is exercised in stage 1 but published in stage 3.** Until it is
  published, `home_inv.plugin.v1` may change without the six-month guarantee — and
  that is stated in the contract documentation, not assumed.
- **Stage 3's definition of done changes.** "A third-party plugin runs without a
  core change" becomes a stage 1 criterion, proved with a first-party plugin.
  Stage 3 proves the same thing with an *external* author against the *published*
  SDK.
- **Stage 0 is untouched.** It uses filesystem storage and has no mail; its
  registration path is the operator creating the first account directly. That was
  already true — stage 0 never had invitations.
- **`REQ-NOTI-004` ("security notifications cannot be switched off") gains a
  precondition**: it holds from stage 1, when `plugin-smtp` exists. The
  requirement text says so instead of implying mail is always available.
- A stage 1 installation without `plugin-smtp` is a valid but reduced deployment:
  no invitations, no password reset by mail, no notifications. The administration
  UI states this rather than failing silently — the same treatment `PENDING_SCAN`
  gets when ClamAV is unreachable.
