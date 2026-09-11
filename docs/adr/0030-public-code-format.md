# ADR-0030 — The public code: 10 payload characters and a Damm check symbol

**Status:** Accepted · **Date:** 2026-09-11
**Partially supersedes:** [ADR-0016](0016-identifiers.md), *Decision, part 2*. Part 1
(UUIDv7 as the primary key) stands unchanged. Of the four reasons why the printed
code is not the UUID, **two stand** — human readability and decoupling through
`CodeBinding`. Two were withdrawn for the same reason, that the QR fragment prints
the UUID on the same label: "information leakage" as open point **O12**, and
"print size" on 2026-09-11 (see *Consequences*). Both had argued against printing
something the label prints anyway.

## Context

[ADR-0016](0016-identifiers.md) fixed the printed code at **8 payload characters
of Crockford Base32 plus a Crockford modulo-37 check character**, and stated:
*"40 bits of randomness give about 1.1 · 10¹² possibilities; enumeration is
hopeless with rate limiting."*

Two things were not checked when that was written.

**Density.** The stated scalability target is 1 M items per tenant
([01 §1.3](../architecture/01-introduction-and-goals.md), Q7), and the code space
is **global across all tenants** — so the relevant count is the sum over the
instance. At 10⁶ issued codes in a space of 2⁴⁰ ≈ 1.0995 · 10¹²:

| | 8 payload characters (40 bit) |
|---|---|
| Probability of at least one collision at 10⁶ codes | ≈ **36 %** |
| … at 3 · 10⁶ codes | ≈ 98 % |
| Chance that a random guess hits a valid code at 10⁶ issued | ≈ **1 : 1.1 million** |

Collisions themselves are harmless — `UNIQUE(code)` rejects, a new code is drawn —
but they are a routine event rather than a curiosity, and the guessing density is
six orders of magnitude away from what "1.1 · 10¹² possibilities" suggests to a
reader.

**The check symbol alphabet.** Crockford's check symbol is computed modulo **37**,
and the specification defines five symbols beyond the 32 of the payload alphabet
for the values 32–36: `*`, `~`, `$`, `=` and **`U`**. About 13.5 % of codes
therefore end in a character that our own alphabet excludes — `U` is excluded on
purpose ([10 §10.1](../architecture/10-identification-and-labels.md): no accidental
profanity), and `*~$=` are awkward in a URL path, in input normalisation, and in
QR alphanumeric encoding, which permits `$ * + - . / : %` and space but not `~` or
`=`.

Neither is a defect that breaks anything today. Both become permanent the moment
the first label is printed, and printed labels cannot be recalled
([10 §10.2.1](../architecture/10-identification-and-labels.md)).

## Options

### Length

| Option | Collision at 10⁶ | Guess density at 10⁶ | Cost |
|---|---|---|---|
| 8 payload (40 bit), as today | ≈ 36 % | 1 : 1.1 M | none |
| 9 payload (45 bit) | ≈ 1.4 % | 1 : 35 M | breaks the 3-3-3 grouping |
| **10 payload (50 bit)** | ≈ **0.044 %** | **1 : 1.1 G** | two characters on the label |
| 11+ | negligible | — | diminishing returns against readability |

### Check symbol

| Option | Detects | Symbol stays in the 32-symbol alphabet |
|---|---|---|
| Crockford modulo-37 | all single-character errors **and** all adjacent transpositions | no — 5 extra symbols, ≈ 13.5 % of codes |
| A weight-free checksum modulo 32 | single errors only; positional weights collapse, so it is close to useless | yes |
| **Damm algorithm over the 32 symbols** | all single-character errors **and** all adjacent transpositions | **yes** |

> Crockford's modulo-37 is *mathematically sound* — 37 is prime and the base-32
> positional weights are non-zero modulo 37, so a transposition always changes the
> residue. The claim in [10 §10.1](../architecture/10-identification-and-labels.md)
> was correct. The only problem is the alphabet, not the arithmetic.

## Decision

**10 payload characters plus one Damm check symbol, all eleven from the Crockford
Base32 alphabet.** Displayed in groups of four, four and three:

```
7Q2M-4X9K-D2F
└──┬──┘└─┬─┘└┬┘
   │     │   └── 2 payload + 1 check symbol
   │     └────── 4 payload
   └──────────── 4 payload
```

| Property | Value |
|---|---|
| Alphabet | Crockford Base32, `0123456789ABCDEFGHJKMNPQRSTVWXYZ` — unchanged |
| Payload | 10 characters = **50 bits** ≈ 1.126 · 10¹⁵ |
| Check symbol | **Damm**, over a totally anti-symmetric quasigroup of order 32, generated once and held as a constant table (~1 KB) in `identification` |
| Generation | Cryptographically random, never sequential — unchanged |
| Uniqueness | Global, `UNIQUE(code)` — unchanged |
| Input tolerance | Hyphens, case and the confusables normalised (`o`→`0`, `l`/`i`→`1`) — unchanged, and now applicable to the **whole** string including the check symbol |
| Collision handling | Redraw on a unique violation. The redraw rate is a metric, not an assumption |

## Rationale

Both changes are free now and impossible later. The label is the one artifact in
this system that cannot be revised after the fact, so the bar for "leave it as it
is" should be higher here than anywhere else — and neither the 36 % collision rate
nor the five stray symbols would be defended by anyone seeing them for the first
time.

Damm is chosen over Crockford's modulo-37 purely to keep the check symbol inside
the alphabet the rest of the design assumes. It is a published algorithm (H. Michael
Damm, 2004) with the same detection guarantees, not an invention of this project.
The deviation from Crockford's specification is deliberate and is written here so
nobody later "fixes" it back.

Eleven characters instead of nine costs roughly 1.5 mm of label width at the sizes
in [`label-media.yaml`](../reference/label-media.yaml) and does not change the QR
version. The version is dominated by the UUID fragment, which **is** kept
(open point **O12**, decided 2026-09-11): its lowercase hex forces byte mode for
the whole symbol, so the two extra code characters disappear into a symbol that is
already larger for another reason.

## Consequences

- **`REQ-IDENT-001` changes**: "Crockford Base32, 10 characters plus a Damm check
  symbol". The acceptance criterion gains the transposition case explicitly.
- **All examples in the corpus change.** They were inconsistent in three different
  ways before this (`7Q2-M4X-9KD`, `7Q2-M4X-9`, `7Q2M4X9`); they are now one
  string, `7Q2M-4X9K-D2F`, everywhere.
- **[12 §12.3](../architecture/12-security.md) gains real numbers.** "≥ 40 bits of
  randomness" becomes "50 bits; at 10⁶ issued codes a guess hits with probability
  1 : 1.1 · 10⁹, which is what makes rate limiting sufficient rather than merely
  helpful."
- **The quasigroup table is a constant that must never change.** A different table
  invalidates the check symbol of every printed label. It is fixed in the source,
  covered by a test with known vectors, and called out in the same place as the
  base URL warning — both are values that write themselves into the physical world.
- **Pre-issued labels printed before this change do not exist**, because no code
  has been issued yet. This is the last moment at which this decision is free.
- **The "print size" reason in [ADR-0016](0016-identifiers.md) is withdrawn, and
  the minimum label size is now a computed figure rather than an assertion.**
  Measured out, the specified payload
  (`https://<base>/c/<11 chars>#i=<36-char uuid>`, ≈ 76 characters) is a QR
  **version 5** symbol: 37 × 37 modules, 45 including the four-module quiet zone.
  At the 0.33 mm minimum module size that
  [10 §10.5](../architecture/10-identification-and-labels.md) and `REQ-LBL-007`
  already require, that is **≈ 15 mm of label edge**, not the 10 mm
  [10 §10.1](../architecture/10-identification-and-labels.md) claimed. The same
  measurement inverts the old argument: a URL carrying the UUID in its *path*
  instead (≈ 62 characters) would be a **version 4** symbol — smaller than what
  this design prints. Reasons 1 and 2 — human readability and decoupling — carry the
  decision on their own, exactly as they did after O12. *(This said "Reasons 2 and 3"
  until 2026-09-11, which was the pre-withdrawal numbering;
  [10 §10.1](../architecture/10-identification-and-labels.md) renumbered them when the
  other two were struck.)*
- **The starter catalogue is unaffected.** Its smallest format, Avery Zweckform
  3667 at 48.5 × 16.9 mm, yields 0.376 mm per module — above the floor. The
  correction lands in the prose and in the preview warning, not in
  [`label-media.yaml`](../reference/label-media.yaml).
