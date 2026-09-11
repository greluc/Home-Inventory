# ADR-0025 — An in-house `Money` value type instead of JavaMoney or Joda-Money

**Status:** Accepted · **Date:** 2026-09-11

## Context

Money appears in three places: as purchase price, current value and replacement
value on items ([REQ-LIFE-001/009/014](../requirements/01-functional.md)), as a
`money` field type in the tenant-configurable type system
([REQ-CORE-022](../requirements/01-functional.md)), and in reporting totals per
location, type and tag ([REQ-LIFE-008/015](../requirements/01-functional.md)).

The question raised: should [JavaMoney / JSR 354](https://javamoney.github.io/)
with its reference implementation
[Moneta](https://github.com/JavaMoney/jsr354-ri) be used for this?

### What this project actually needs

| Need | Covered by |
|---|---|
| An exact decimal amount plus an ISO 4217 code | `BigDecimal` + `java.util.Currency` |
| Addition, comparison, multiplication by a factor | `BigDecimal` |
| Currency minor units for validation and rounding | `Currency.getDefaultFractionDigits()` |
| Totals per currency | SQL `SUM(…) GROUP BY currency` |
| Locale-aware display | The **clients** — `Intl.NumberFormat` in the web PWA, the platform formatter in the KMP apps |
| Currency conversion with historical rates | **Explicitly out of scope** — a deliberate piece of technical debt ([14 §14.4](../architecture/14-quality-risks-glossary.md)) |

The decisive observation about the shape of the problem: a money value in this
system spends almost its entire life as **JSON and as a SQL numeric**. It arrives
from a client as JSON, is validated against a generated JSON Schema, stored in a
JSONB column, projected into an index side table and summed in SQL. There is
barely a point at which a rich money object flows through domain computation.
The domain arithmetic is thin: add, compare, multiply by a depreciation factor.

## Options

| Option | For | Against |
|---|---|---|
| **JavaMoney / JSR 354 + Moneta** | A standardised API (`javax.money`) · `Money`, `FastMoney` and `RoundedMoney` implementations · custom currencies (crypto, loyalty points) · pluggable rounding and formatting · FX providers with ECB and IMF data · Jackson support is now maintained by FasterXML | The spec has been frozen at *Maintenance Release 1* since May 2020 · the RI ships roughly one or two releases a year (1.4.5, March 2025) · **the conversion modules schedule background fetches to remote endpoints** (see below) · `ServiceLoader`-based discovery means the classpath decides what registers itself · three amount implementations with **different** semantics (`FastMoney` can overflow, `RoundedMoney` rounds on every operation) is a subtle footgun for a small team · it sits outside the Spring/Jakarta release train |
| **Joda-Money** | Mature, actively maintained (2.0.3, two maintained branches) · no dependencies, no SPI, no network · `Money` (fixed to the currency's scale) and `BigMoney` (arbitrary scale) · a maintainer with an exceptional track record (Joda-Time, JSR 310) | Brings **its own** ISO 4217 data file, which can drift from the JDK's · we would still write the JSON, JPA and index mapping ourselves · gives us roughly 150 lines of value type we would otherwise write |
| **`BigDecimal` + `Currency` without a wrapper** | Nothing to maintain | Nothing stops mixing currencies; no single place for the invariants; the rule "never a float" is not enforceable anywhere |
| **An in-house `Money` record** in `platform` | Zero new runtime dependency in the most security-sensitive layer · the JSON shape is a project contract anyway · `java.util.Currency` is the authoritative ISO 4217 source and ships with the JDK · the invariants live in exactly one place | We take on money semantics ourselves — a domain with real pitfalls |

### The disqualifying detail about Moneta

Moneta's conversion modules (`moneta-convert-ecb`, `moneta-convert-imf`) load
exchange rates from remote endpoints **on a scheduled background thread**. The
shipped configuration reads
`load.ECBCurrentRateProvider.type=SCHEDULED` with `period=03:00` against
`https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml`.

Providers are discovered through `ServiceLoader`. In other words: whether the
application makes outbound network calls is decided by **what happens to be on
the classpath**, not by our code.

That collides head-on with a load-bearing rule of this system: **the core has no
outbound route to the internet** ([REQ-PRIV-003](../requirements/03-security-and-privacy.md),
[12 §12.2](../architecture/12-security.md)). External calls happen exclusively
through plugins in their own network segment with an explicit host allowlist.

The collision is avoidable — take `moneta-core` only and never the conversion
modules. But it means every dependency bump has to be checked for a transitive
module that quietly registers a scheduled network fetch. For a feature we do not
want (FX conversion is out of scope), that is a permanent watch item bought for
nothing.

## Decision

**An in-house `Money` value type in the `platform` shared kernel**, backed by
`BigDecimal` and `java.util.Currency`. **No** JavaMoney, **no** Joda-Money.

```java
public record Money(BigDecimal amount, Currency currency) {
    // Invariants enforced in the compact constructor:
    //  - amount and currency non-null
    //  - amount is NORMALISED to exactly currency.getDefaultFractionDigits()
    //    via setScale(digits, RoundingMode.HALF_UP)  ← see the two notes below
    //  - arithmetic across different currencies throws CurrencyMismatchException
    //  - no constructor, factory or operation accepts double or float
    //
    // Deliberately NOT Comparable — see below.
    public boolean isGreaterThan(Money other) { … }   // throws on a currency mismatch
    public static Comparator<Money> inCurrency(Currency c) { … }
}
```

> **Two corrections to the first draft of this record, both of which would have
> shipped as bugs.**
>
> **Scale must be exact, not "≤".** A `record` derives `equals` from
> `Objects.equals` on its components, and `BigDecimal.equals` compares **scale as
> well as value**: `49.90` and `49.9` are unequal and hash differently. Under a
> `scale ≤ digits` invariant both are valid, so two `Money` values representing the
> same amount could fail `equals`, break a `HashMap` lookup, and make a set of
> prices contain apparent duplicates. Normalising to exactly the currency's
> fraction digits in the compact constructor removes the class of bug entirely —
> and it is exactly the kind of detail a money library would have handled, which is
> the honest counterweight to the "write it yourself" decision above.
>
> **`Comparable` is wrong here.** Its contract expects a total order over the type,
> and throwing on a currency mismatch does not provide one: `Collections.sort` on a
> mixed list would fail partway through, leaving the list in an undefined state,
> and `TreeMap` would behave unpredictably. Comparison is only ever meaningful
> within one currency, so it is exposed as `Comparator` factories and explicit
> `isGreaterThan`/`isLessThan` methods that check and throw — the check stays, the
> broken contract goes.

Rules that go with it:

| Rule | Reason |
|---|---|
| **Arithmetic across currencies throws.** There is no implicit conversion, ever. | Silently adding EUR to USD is the failure mode that must never be possible |
| **No `double` or `float` anywhere on the money path** — not in a constructor, a factory, a DTO or a test helper | The classic money defect; an ArchUnit rule enforces it |
| JSON shape `{"amount": "49.90", "currency": "EUR"}` — the amount is a **string** | A JSON number would lose precision in JavaScript clients before our code ever sees it |
| Rounding mode is always explicit (`RoundingMode.HALF_UP` as the default), never implicit | `BigDecimal` throws on an implicit rounding requirement; an explicit choice is auditable |
| **`Money` is immutable and does not implement `Comparable`.** Comparison within one currency goes through `isGreaterThan`/`isLessThan` or a `Comparator` from `Money.inCurrency(c)` | Comparing across currencies is meaningless, and a `compareTo` that throws does not satisfy `Comparable`'s contract — see the second correction note above. *This row read "`Comparable` only within the same currency" until 2026-09-11 and contradicted the record it sits under; an implementer following it would have shipped the bug the note describes.* |

## Rationale

Where money lives as JSON and SQL numerics, a money framework buys nothing.
JavaMoney's differentiating features — FX providers, pluggable rounding SPIs, a
formatting SPI, custom currency units — are either not needed, or actively
unwanted (the network fetching), or done elsewhere (formatting happens in the
clients, which have far better locale data than a JVM ever will).

Joda-Money is the far better of the two libraries and would be a defensible
choice: well maintained, dependency-free, no SPI, no network. It lost on a
narrow point — it gives us a value type we can write in an afternoon, while
bringing its own ISO 4217 data set that can drift from the JDK's, and we would
still hand-write every mapping that actually costs effort here.

**This is deliberately a case of "write it yourself" in a domain where that
advice is usually wrong.** What makes it defensible is that the type is tiny, the
pitfalls are well known and enumerable, and the semantics can be taken straight
from Joda-Money's design without the dependency.

## Consequences

- `Money` lives in `platform` and satisfies the shared-kernel rule: more than
  three blocks need it, and it contains no decision
  ([04](../architecture/04-building-blocks.md)).
- **Totals are computed per currency.** A mixed-currency total is never produced
  silently; reports show one line per currency
  ([REQ-LIFE-017](../requirements/01-functional.md)).
- This forces a change to the index side table: `item_attr_index` needs a
  `unit_value` column, otherwise a `money` attribute loses its currency in the
  projection and `SUM()` would add EUR to USD. The same applies to the `quantity`
  field type ([REQ-CORE-032](../requirements/01-functional.md),
  [07 §7.3](../architecture/07-data-model.md)).
- Test coverage of `Money` is held at 100 % branch coverage. It is 150 lines with
  a high blast radius — the one place where that bar is worth it.
- **Should FX conversion ever come into scope**, it arrives as a
  `CurrencyConversion` **plugin** in the plugin network segment with a host
  allowlist — the same way every other external data source does. Not as a
  library inside the core.
- If the in-house type starts growing beyond the rules above, that is the signal
  to reconsider and switch to **Joda-Money** — not to JavaMoney.

## Sources

Checked on 2026-09-11: Moneta 1.4.5 (March 2025), repository activity to April
2026; JSR 354 at Maintenance Release 1 (May 2020). Joda-Money 2.0.3, actively
maintained across two branches. Zalando's `jackson-datatype-money` was retired in
March 2026 and folded into FasterXML's `jackson-datatypes-misc` — a good sign for
the JSR 354 ecosystem, but it does not change the analysis above.
