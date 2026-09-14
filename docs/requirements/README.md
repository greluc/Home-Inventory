# Requirements Catalogue

This catalogue is the **binding, implementable** form of the architecture. What
is written here gets built; what is not written here does not get built until it
has been added here first.

## Structure

| File | Content |
|---|---|
| [01 Functional requirements](01-functional.md) | What the system must be able to do |
| [02 Non-functional requirements](02-non-functional.md) | How well, how fast, how maintainable |
| [03 Security and privacy](03-security-and-privacy.md) | Protection requirements and legal obligations |
| [04 Roadmap and stages](04-roadmap-and-stages.md) | Assignment to stages 0–3, dependencies |

## ID scheme

```
REQ-<AREA>-<nnn>
```

| Area | Subject |
|---|---|
| `CORE` | Items, type system, locations, tags |
| `MED` | Media and attachments |
| `IDENT` | UUID, codes, scanning |
| `LBL` | Labels and printing |
| `ENR` | Metadata enrichment |
| `SRCH` | Search and saved searches |
| `LIFE` | Lifecycle, maintenance, lending, valuation |
| `TEN` | Tenants, memberships, roles |
| `AUTH` | Authentication and sessions |
| `API` | Interfaces and versioning |
| `PLG` | Plugins and extensibility |
| `SYNC` | Offline reconciliation |
| `NOTI` | Notifications and reminders |
| `PORT` | Import, export, data subject access |
| `NFR` | Non-functional |
| `SEC` | Security |
| `PRIV` | Privacy |
| `OPS` | Operations and observability |
| `CON` | Constraints and project obligations |

**Numbers are stable and never reused.** If a requirement is dropped it is marked
`Withdrawn` and stays in place.

Numbers are handed out **in order of assignment**, not in order of appearance. A
later requirement therefore sometimes sits next to its topical partner rather
than at the end of its section — REQ-NFR-070 next to REQ-NFR-036, for instance.
Renumbering to tidy that up would break every reference, which costs more than
it is worth.

## Columns

| Column | Meaning |
|---|---|
| **ID** | The stable identifier |
| **Requirement** | One verifiable statement. No "should ideally" — it is either met or it is not. |
| **Prio** | `M` must · `S` should · `K` could |
| **Stage** | 0 (MVP) · 1 (core) · 2 (identification) · 3 (ecosystem) |
| **Acceptance** | How fulfilment is verified |

## Priorities

| Prio | Meaning |
|---|---|
| **M — must** | Without it the corresponding stage is not finished. Not negotiable. |
| **S — should** | Will be implemented, but may slip one stage under time pressure. The deferral is documented here. |
| **K — could** | Desirable. May be dropped without further justification. |

## How to add one

1. A new row with the next free number in the matching area.
2. Phrase it verifiably and state an acceptance condition.
3. Assign a stage in the **Stage** column. That column is the single source —
   [04 Roadmap](04-roadmap-and-stages.md) describes each stage in prose and
   derives its requirement list from here rather than repeating it, because the
   two had already drifted apart in three places.
4. If the requirement forces an architectural decision: write an ADR and link it.

## Count

| File | Areas | Count |
|---|---|---|
| [01 Functional](01-functional.md) | CORE 43 · MED 13 · IDENT 18 · LBL 14 · ENR 9 · SRCH 11 · LIFE 17 · TEN 12 · AUTH 11 · API 12 · PLG 15 · SYNC 16 · NOTI 9 · PORT 8 | **208** |
| [02 Non-functional](02-non-functional.md) | NFR 79 · CON 15 | **94** |
| [03 Security and privacy](03-security-and-privacy.md) | SEC 110 · PRIV 15 | **125** |
| **Total** | | **427** |

As of 2026-09-13. The count is kept current with every addition.
