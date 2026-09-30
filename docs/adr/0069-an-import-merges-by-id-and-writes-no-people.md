<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0069 — An import merges by id, matches the catalogue by key, and writes no people

**Status:** Accepted · **Date:** 2026-09-20

**Depends on:** [ADR-0002](0002-modular-monolith.md), [ADR-0004](0004-attribute-storage-model.md),
[ADR-0019](0019-sensitive-field-encryption.md),
[ADR-0068](0068-an-export-opens-what-its-requester-may-read.md)

## Context

`REQ-PORT-003` says an export is *importable into another instance* and `REQ-PORT-004` asks
for **the move** as its acceptance test. Until now only half of that existed: ten blocks
wrote themselves into an archive and nothing read one back, so the sentence was a claim
about a file rather than about a system.

Five questions had to be answered before any of it could be written, and none of them
follows from the code.

## Decision

### 1. Rows merge by id, and the archive wins

A row whose id is not here is inserted; one that is here is **overwritten** from the
archive. Importing into a tenant that holds rows of its own is a restore as much as it is a
move, and a merge that kept the local row would produce a third state out of two that were
each complete — with nothing saying which parts came from where.

Rejected: *refuse unless the tenant is empty*, which serves the move and not the restore;
*keep the local row*, which yields a half-import nobody can explain to the person who asked
for it; and *assign new ids*, which breaks every printed QR label, because the label
carries the item's id in its URL ([10 §10.2.1](../architecture/10-identification-and-labels.md))
and a label already on a box is never recalled.

### 2. The catalogue is matched by its natural key, and the ids it moves are remembered

Merging by id fails for the rows **provisioning** creates. Every tenant is given the same
built-in item types, location categories and value lists, and each copy has its own id. An
archive therefore carries a type `general` with one id while the receiving tenant already
holds a type `general` with another, and `UNIQUE (tenant_id, key)` allows only one of them:
inserting the archive's copy fails, ignoring it leaves every imported item pointing at
nothing.

So `catalog` matches on the natural key at each of its three levels — the key of a type, the
version number under it, the key of a field under that — and records in `Remapping` what
each archive id became. Every later block resolves its references through it. That is what
makes a move into a freshly provisioned tenant work at all, and
`ArchiveMoveIT.everythingArrives` is the proof: without the remapping the import fails on a
foreign key rather than quietly producing something wrong.

### 3. An import writes no people

Accounts, memberships, the roles a tenant defined and the notification channels carrying
somebody's address are all **read for the report and never written**. A file somebody
uploads must not be able to create accounts on an instance, and a membership needs an
account to point at.

Two consequences are stated in the report rather than discovered later: a tenant moving with
five members invites those five again, and the field-visibility rules a tenant wrote do not
arrive either — they hang off role definitions — so the receiving instance decides who may
read a `sensitive` field by its own defaults until somebody sets them again.

The archive still carries all of it, because Art. 15 asks who had access (`REQ-PORT-006`).
What an archive **holds** and what an upload may **write** are different questions.

### 4. The whole import is one transaction, and a dry run is the same work thrown away

`REQ-PORT-007` says an import is complete or it never happened. A half-imported inventory is
worse than none: it looks like a success to anybody who was not watching and nothing says
which half arrived. The only row written outside that transaction is the job itself, through
`REQUIRES_NEW` — otherwise a failure would roll back the record of having failed, and the
person who asked would watch a job sit in `RUNNING` for ever.

A dry run (`REQ-PORT-001`) writes every row, meets or fails every constraint, and then rolls
back on purpose. It is deliberately **not** a separate, simpler code path: a preview produced
by different code is a preview of something else, which is the one failure a dry run exists
to prevent.

### 5. A CSV is the same job, read through a profile of pairs

`REQ-PORT-001` asks for a CSV import with a mapping profile, a preview and a dry run, and
`REQ-PORT-002` for profiles for Homebox and InvenTree. It is the **same** `import_job` — the
same upload, queue, single transaction and report — with one column saying which profile
reads it. A second table would have duplicated the state machine, the claiming and the
report for one column's worth of difference.

A profile is **pairs and nothing else**: this column is that field. No expressions, no
conditions, no defaults that depend on another column. ADR-0020 says the configuration
surface must not become a programming language, and a mapping with an `if` in it is the
first step of exactly that. A file needing more than pairs is edited before it is uploaded,
or handled by a plugin.

The two that ship are **constants**, not rows. They are identical on every instance and in
every tenant, so a row per tenant per profile would be provisioning writing the same thing a
thousand times — and a built-in nobody may edit is what a constant already is. A tenant
needing one changed copies it, keeps the key and edits the columns; its own then wins.

Their column names come from each system's own source rather than from its documentation:
Homebox tags every field of `io_row.go` with the header it writes, which is why
`HB.purchase_time` sits beside `HB.purchase_date` — an older installation writes the older
spelling, and that is exactly the file somebody is migrating away from.

Three reactions to what a file carries, and the difference between them is the decision:

| What | What happens | Why |
|---|---|---|
| A column the profile does not map | Reported, skipped | Another system's export always carries columns this one has no home for |
| An attribute the item type does not declare | Reported, skipped | Which fields an item has is the tenant's decision (ADR-0020); refusing the file until they create them would make a migration a project |
| A value that is not what its column says it is | **Fails the import**, naming the line | A date that is not a date is data loss, not a mismatch of vocabulary |

Places and tags named in a file are **created** when they are missing. The alternative
refuses every row whose place does not exist yet, which on a first import is all of them.
Both go through their own service rather than being written behind its back, so the depth
ceiling, the category rules and the audit entries all apply: an import is not a way around
the rules the tree has.

## Consequences

- **An archive lives in one tenant per instance.** An id is unique in the database and not
  merely within a tenant — on purpose, because it is the id printed on a label. Importing an
  archive into a second tenant of the same instance while the first still holds those rows
  asks for the same id twice, and the import fails with that sentence and writes nothing. It
  is why `ArchiveMoveIT` empties the source tenant before importing: a move is not a copy.
- **Sensitive values are sealed again on the way in.** An export opens them as far as its
  requester may read ([ADR-0068](0068-an-export-opens-what-its-requester-may-read.md)), so the
  archive holds plaintext where the column holds ciphertext. `inventory` and `locations` put
  them back through `AttributeSealing`, under the receiving tenant's own key. Writing them
  back unchanged would leave a value somebody marked sensitive sitting in the column in
  clear, on an instance whose operator never agreed to that.
- **Derived state is rebuilt, never imported.** `item_attr_index` and the OpenSearch index
  are recomputed from the rows that arrived. An archive that carried them would hold one fact
  twice, and the copy that was wrong would be the one somebody trusted.
- **Two tables with the same natural key and different ids fail the import.** Merging foreign
  rows into a populated tenant — a tag named *valuable* here and another named *valuable*
  there — is refused rather than guessed at, with nothing written. The catalogue is the one
  place where that case is resolved rather than refused, because provisioning guarantees it
  and a move would otherwise be impossible.
- **`ImportSql` builds its statements.** The table and every column are Java constants
  declared by the block that owns them, beside the `ExportSource` naming the same list — an
  allowlist in the sense the SQL rule means, and the alternative was six hundred lines in
  which the two sides drift apart on the first change.
