# ADR-0058 — The `authorization` block's schema is called `authz`

**Status:** Accepted · **Date:** 2026-09-13

## Context

[07 §7.1](../architecture/07-data-model.md), rule 6, gives every building block a
schema of its own, and [04 §4.5](../architecture/04-building-blocks.md) maps each
block to a schema **of the same name**. Nine of them follow that rule exactly.

The tenth does not. `authorization` is a **reserved word** in PostgreSQL, and the
server refuses it as a bare identifier — checked against PostgreSQL 18 rather
than inferred from the keyword table, because a keyword table lists several
classes of reservation and only one of them bites here:

```
postgres=# CREATE SCHEMA authorization;
ERROR:  syntax error at or near ";"

postgres=# CREATE SCHEMA "authorization";
CREATE SCHEMA

postgres=# CREATE TABLE authorization.t (id int);
ERROR:  syntax error at or near "authorization"
```

Quoting works and does not stop at the `CREATE`. Every later reference carries
the quotes too — every migration, every SQL constant, and in JPA a mapping
spelled `@Table(schema = "\"authorization\"")`. A missing pair is a syntax error
that appears at run time, in whichever query nobody ran during review.

The block itself keeps its name. `de.greluc.homeinv.authorization` is a Java
package and has no such problem, and the block is called `authorization`
throughout the documentation, in the permission ids and in the ArchUnit rules.

## Decision

**The block `authorization` gets the schema `authz`.**

It is the one block whose schema name differs from the block name, and
[04 §4.5](../architecture/04-building-blocks.md) says so in the mapping table
rather than leaving a reader to discover it. The rule it bends is the naming
convention, not the structural one: there is still exactly one schema for this
block, no other block writes into it, and the ArchUnit rule that forbids a block
naming a foreign schema is unchanged.

Rejected:

- **`"authorization"`, quoted everywhere.** It keeps the documented spelling and
  pays for it in every file that touches the schema, forever, with a failure mode
  that is a run-time syntax error. The corpus already carries two deliberate
  spelling carve-outs ([CLAUDE.md](../../CLAUDE.md), *Language*); this would be a
  third that has to be remembered at every use site rather than once.
- **Putting the role tables in `tenancy`.** Memberships live there and a role
  assignment hangs off one, so it is tempting. It breaks 07 §7.1 rule 6 in the
  way that actually matters: `tenancy` would hold another block's data, and the
  next question — "who may write `authz.role_definition`" — would have no
  structural answer.

## Consequences

- `authz` is created by the `authorization` block's own first migration, the way
  `tagging` created its schema in `V17`. Stage 0 created no schema for this block
  deliberately: "an empty schema passes every check and documents nothing".
- The **block-to-schema mapping is no longer derivable from the block name**, and
  a check that assumed it would now be wrong. `MigrationRulesTest` reads the
  schema out of the DDL rather than out of the directory name, so it is unaffected.
- The permission ids do **not** change. They are `<block>:<resource>:<action>`
  with the *block* in the first segment — `tenancy:member:invite`,
  `catalog:type:update` — and this block defines none of its own, because a
  permission to administer permissions is `tenancy:member:update`.
- A reader who looks for a schema called `authorization` finds nothing. That is
  the cost, and it is paid once in the mapping table and in this record.
