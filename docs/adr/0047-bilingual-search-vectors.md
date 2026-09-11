# ADR-0047 — Two generated search vectors, German and English, instead of `simple`

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0008](0008-search.md), [07 §7.3](../architecture/07-data-model.md),
[07 §7.10](../architecture/07-data-model.md), `REQ-SRCH-001`, `REQ-SRCH-011`

## Context

[07 §7.3](../architecture/07-data-model.md) defines the fallback full-text column as

```sql
search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(description,''))
) STORED
```

`simple` is the configuration that does **nothing**: no stemming, no stop words, no
language knowledge at all. Every token is lowercased and indexed verbatim.

That matters more here than it would elsewhere, for three reasons that compound:

1. **It is the only stage-0 mechanism.** `REQ-SRCH-001` is priority **M** at stage 0 and
   there is no OpenSearch before stage 1 ([04 Roadmap](../requirements/04-roadmap-and-stages.md)).
2. **It is permanent in `minimal`.** That profile never gets OpenSearch
   ([06 §6.10](../architecture/06-deployment-view.md)), and it is the profile a small
   self-hoster runs.
3. **The product ships German** (`REQ-NFR-033`), and German is where `simple` hurts
   most. *Bohrmaschinen* does not match *Bohrmaschine*; *Schrauben* does not match
   *Schraube*. The corpus already treats German compounds as a first-class design
   constraint — `guidelines/type-german.html`, the label column sizing, the ui-kit
   fixture — and then indexed them with the configuration that understands none of it.

Nothing in the corpus said why. It read as a default nobody revisited rather than as a
decision.

## Options

| Option | German stemming | English stemming | Cost |
|---|---|---|---|
| Keep `simple` | no | no | none — and `REQ-SRCH-001` promises "full-text search" and delivers substring matching |
| One column, `'german'` | yes | partial — the German stemmer mangles some English, but rarely into a wrong match | One column, one index. Assumes the data's language |
| One column, `'english'` | no | yes | The wrong way round for this product |
| **Two generated columns, `'german'` and `'english'`** | yes | yes | Two columns, two GIN indexes, ~300 MB at 1 M items |
| A per-tenant configuration chosen at runtime | yes | yes | **Not possible in a generated column**: `to_tsvector` is immutable only with a literal `regconfig`, so a per-tenant one means a trigger and a second truth, which is what the generated column was chosen to avoid |

## Decision

**Two generated columns.**

```sql
search_vector_de tsvector GENERATED ALWAYS AS (
    to_tsvector('german',  coalesce(name,'') || ' ' || coalesce(description,''))
) STORED,
search_vector_en tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(name,'') || ' ' || coalesce(description,''))
) STORED
```

with a GIN index on each.

| Point | Rule |
|---|---|
| Which one is queried | The requesting principal's display language, from the same setting that picks the resource bundle |
| The other one | Queried as well, `OR`-combined, ranked lower. A German household with an English manual title finds it, and vice versa — two index lookups on a GIN index is not where this query spends its time |
| Why `GENERATED`, still | Both stay derived from the row they describe and cannot drift from it. That was the reason for the generated column in the first place and it is unchanged |
| Why not a trigger | A trigger is a second place the value can come from. [07 §7.3](../architecture/07-data-model.md) chose `GENERATED` precisely so there is one |
| `pg_trgm` | Unchanged. It carries typo tolerance and substring matching; stemming and trigram similarity solve different halves, and [05 §5.6](../architecture/05-runtime-view.md) already uses both |

## Rationale

The two-column form is chosen over a single `'german'` column because the single column
encodes an assumption about the data that the product explicitly refuses to make
elsewhere: field labels are multilingual data, the UI ships two languages, and a tenant
may well be an English-speaking household. Guessing the corpus language in the schema
would be the same class of mistake as hard-coding a display string.

The cost is real and is stated rather than buried: two `tsvector` columns plus their GIN
indexes add roughly 300 MB at the 1 M-item scale of
[07 §7.10](../architecture/07-data-model.md), against a row estimate of ~2.5 GB. That is
about 12 %, for the difference between a search that works and a search that matches
prefixes.

A per-tenant configuration was the option that looked best and is not available:
PostgreSQL requires the `regconfig` argument of `to_tsvector` to be a literal for the
expression to be immutable, and a non-immutable expression cannot be a generated column at
all. That is a hard constraint, not a preference, and it is written down here so nobody
rediscovers it by trying.

## Consequences

- **[07 §7.3](../architecture/07-data-model.md) changes**: one column becomes two, and
  `item_search_fts` becomes `item_search_fts_de` and `item_search_fts_en`.
- **[07 §7.10](../architecture/07-data-model.md) changes**: the `item` row estimate grows
  by ~300 MB at 1 M items, and the figure names the two indexes.
- **`REQ-SRCH-001` gains an acceptance criterion that names stemming**: searching
  *Bohrmaschinen* finds an item named *Bohrmaschine*, with PostgreSQL alone and no
  OpenSearch. Without that, the requirement was met by a `LIKE`.
- **`REQ-SRCH-011`'s PostgreSQL adapter** covers notes and attribute values the same way;
  the stage-1 columns are generated against the same two configurations.
- **The OpenSearch adapter is unaffected.** It has its own analysers and its own language
  handling ([ADR-0008](0008-search.md)); this is the fallback's business.
- **A migration writes both columns for existing rows.** At stage 0 there are none, which
  is why this is decided now: after the first million items it is a rewrite of the table.
