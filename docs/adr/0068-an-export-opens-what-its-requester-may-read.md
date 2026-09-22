<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0068 — An export opens a sealed value exactly as far as its requester may read it

**Status:** Accepted · **Date:** 2026-09-20

**Depends on:** [ADR-0019](0019-sensitive-field-encryption.md), [ADR-0032](0032-per-tenant-blob-addressing.md)

## Context

A field marked `sensitive` is stored **sealed**: envelope-encrypted with a per-tenant
data key, itself wrapped by a key that never leaves the deployment
([ADR-0019](0019-sensitive-field-encryption.md)). The seal is bound to the tenant, the record and
the field, so a ciphertext moved anywhere else does not open.

`REQ-PORT-003` asks for an archive that is *importable into another instance*, and
`REQ-PORT-006` for one that holds *all of the user's personal data*. The export writes
`inventory.item.attributes` and `locations.location.attributes` as they are stored — so
until this decision it wrote **ciphertext**, and a licence key or a purchase price
arrived on the receiving instance as a string nobody there could ever open. Nothing
failed: the archive was complete, the manifest counted every row, and the data was gone.
That is the precise failure the requirement exists to prevent.

The opposite is not free either. An export is a ZIP somebody downloads, and it then lives
on a laptop, in a cloud drive, in a support ticket. Opening every sealed value into it
puts the most closely held things a tenant owns into the least controlled place they
have.

And there is a third mistake available: opening values the person asking may not read.
`authz.field_visibility` decides who sees a sensitive field (`REQ-TEN-008`), and
`REQ-AUTH-011` additionally requires the second factor to have been proved recently. An
export that ignored either would be a documented way around both, with a download button
on it.

## Options

| Option | The archive holds | Moves to another instance | Can it be used to read past a permission |
|---|---|---|---|
| **Open as far as the requester may read** | plaintext for those fields, nothing for the rest | yes, for what they could read anyway | no — the same rule as the API, evaluated once |
| Open everything | plaintext for all of them | yes | **yes**, for anybody who may request an export |
| Carry the ciphertext | unreadable bytes | no | no |
| Leave sealed values out | the field absent | no | no |

Opening everything fails outright. When this was written the export endpoints asked for
`TENANT_READ`, so a `VIEWER` who may not read a purchase price could have exported one —
and although the permission changed the same day (O27), the objection does not depend on
which permission it is: whoever may ask for an archive is not automatically whoever may
read every field in it. Carrying the ciphertext and leaving the values out differ only in
how honest they look; both lose the data.

## Decision

**An export opens a sealed value if and only if the person who requested the archive
could have read that field in the browser at the moment they asked. Everything else is
withheld, and the manifest names it.**

Three things follow, and each is load-bearing:

1. **The same rule, not a second one.** The export calls
   `catalog.api.AttributeRedaction.forCaller(...)` — the port the REST layer already uses.
   A rule about who may read a sensitive field must not have two implementations, because
   the second is the one nobody updates.

2. **The caller is captured at request time, not reconstructed at build time.** A request
   answers `202` and the worker builds the archive minutes later, on another thread, with
   no caller of its own. `portability.export_job` therefore records `caller_role`,
   `caller_role_definition_id` and `caller_second_factor_at`, and `ExportRunner` runs the
   build as exactly that caller. Two consequences are deliberate: a role **granted after**
   the request does not widen the archive, and a job requested with **no caller at all** —
   a schedule, a test — opens nothing.

3. **The archive says what it does not hold.** `ExportSource.Sink.withheld(...)` writes one
   manifest entry per withheld field, with the reason. An archive that was silent about it
   would be one whose reader believes the field was empty, which is worse than an archive
   that is visibly incomplete.

## Consequences

- A tenant leaving with an owner's session and a fresh second factor takes everything,
  including what was sealed. That is what portability means, and it is the point.
- An archive can differ between two people of the same tenant. This is correct and it is
  visible: the manifest's `withheld` list is the difference, by name.
- Plaintext now exists in one more place than before — inside a ZIP the requester
  downloaded. The mitigations are the ones that already exist around the archive: it is
  stored as a blob under the tenant's own prefix, served only to a caller holding
  `portability:export:request` on that tenant — `ADMIN` or `OWNER`, and no scoped
  membership (O27) — and it is a file the person chose to create.
- `ExportJobIT.sealedValuesTravelAsFarAsTheRequesterMayReadThem` holds both directions —
  the value is in the owner's archive and absent from the one requested without a proved
  second factor, whose manifest names the field.

## What writing this turned up

Whether a membership **scoped to part of the location tree** (`REQ-TEN-007`) should
receive an archive of the whole tenant. It did, because the export ignored the scope
entirely and the endpoints asked only for `tenancy:tenant:read` — so the question this
record answers, which is about one field, sat beside a larger one about the whole archive.

Raised as **O27** and decided the same day: **an export is a tenant-level act and gets its
own permission**, `portability:export:request`, which no scoped membership holds whatever
its role. See [ADR-0000](0000-open-points.md).
