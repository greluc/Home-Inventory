# ADR-0035 — The delivered design system is binding, and `tokens.json` is its single source

**Status:** Accepted · **Date:** 2026-09-11

## Context

[`docs/design/design-system-brief.md`](../design/design-system-brief.md) was
written as a brief for Claude Design. The result was delivered on 2026-09-11 and
is now in [`design-system/`](../../design-system/): a W3C DTCG token set for both
themes, 48 components across nine groups, 22 specimen cards, self-hosted IBM Plex
and 121 Lucide SVGs, and roughly 30 KB of written rules.

It does not decide the things the brief already decided. Dark as the default
appearance is [ADR-0033](0033-dark-as-default-appearance.md); Lucide and the
no-third-party-host rule are [ADR-0034](0034-icon-set-and-no-third-party-hosts.md).
This ADR is about what the delivery *is* and what status it has, and it inherits
both.

Two questions had to be answered before it could be used for anything.

**Is it binding, or is it a reference?** A design system that is "available" gets
consulted for the first three screens and diverged from on the fourth, and by the
tenth nobody can say what the system is any more. This project already resolves
that class of problem the same way everywhere else — module boundaries, tenant
isolation, rootlessness are all *enforced*, not recommended
([ADR-0002](0002-modular-monolith.md), [ADR-0003](0003-multi-tenancy.md),
[ADR-0022](0022-rootless.md)). A design system that is merely encouraged would be
the one load-bearing rule in the repository held together by good intentions.

**Where does it live?** Not under `web/`. The brief required a platform-neutral
token source because the same tokens must generate the web client's CSS *and* the
Compose apps' `Theme.kt` (REQ-NFR-071). Putting it inside the web client would
make one consumer the owner of something both consumers depend on, and the apps
would end up with a second, drifting copy.

## Decision

**The design system in [`design-system/`](../../design-system/) is binding for
every user-visible surface of the web client and the apps.** It is a top-level
directory, a peer of `web/` and `app/`, because both consume it and neither owns
it.

**`design-system/tokens/tokens.json` is the single source.** The CSS under
`tokens/` is generated from it; the Compose `Theme.kt` will be generated from it.
A generated stylesheet is never hand-edited — the token changes and the artifacts
are regenerated.

Deviating from the system is handled like any other load-bearing rule: the
requirement is amended first and the repository owner approves, or the deviation
does not happen.

## What was deliberately not taken over

The delivery included preview harnesses — the `*.card.html` specimen pages,
`ui_kits/**/index.html` and `templates/inventory-screen/` — which load React 18
and Babel Standalone from `unpkg.com`.

They are not in the repository. This project contacts no third-party host, for
anything, without exception ([REQ-PRIV-015](../requirements/03-security-and-privacy.md)),
and a file that fetches a script from a CDN, sitting in the same repository as
the rule forbidding it, is how a rule stops being believed. The component sources
those harnesses rendered are all present; what is missing is a way to click
through them without a build, which is a cost worth paying and not a loss of
substance. Claude Design's own build artifacts (`_ds_bundle.js`,
`_ds_manifest.json`, thumbnails) were left out as tooling.

Two smaller consequences follow from the harnesses using **React 18** while the
web client targets **React 19**: the component sources are a specification of
behaviour and markup, not a drop-in library, and the first real build is where
they become React 19 components.

## What had to be corrected in the delivery

*2026-09-11. Being binding means the delivery is held to the requirements it is bound by,
and three things in it were not.*

1. **Hard-coded German display text.** Fourteen components carried literal German
   accessible names and labels — `aria-label="Schließen"`, `"Währung"`, `"Vorherige Seite"`,
   a `placeholder="0,00"` that hard-codes one locale's decimal mark. That violates
   `REQ-NFR-032` (no hard-coded display text, stage 0) and `REQ-CON-001` (code in English).
   Every one is now a **prop with an English default**, which is what a client passing text
   from its resource bundle needs, and which the `.d.ts` files document. The larger sets —
   `ScanOverlay`, `ConflictField`, `StatusChip` — export their default map so a client can
   translate from a known key set rather than guessing it.
2. **Five recorded contrast ratios did not reproduce.** `REQ-NFR-077` has CI recompute
   every ratio from the token values and fail on a mismatch; `color.code.ink` was recorded
   at 20.4 against an actual 19.8 (21 is the theoretical maximum, so 20.4 on `#0a0a0a` was
   never possible), and four others were off. The check would have failed on the first run.
   All 64 now reproduce.
3. **The adherence ruleset could not fail a build.** `REQ-NFR-076`'s acceptance says a raw
   hex value or a hard-coded `px` on a themed property **fails the build**, and every rule
   in `adherence.oxlintrc.json` was `warn`. They are `error` now, the px rule was extended
   to bare numeric literals on themed properties — the commonest way a px value enters
   React code, and the form the string rule could not see — and the three components that
   violated it were moved onto tokens.

**The German that stays is a closed list of two**, and it is in the delivery on purpose:
[`guidelines/type-german.html`](../../design-system/guidelines/type-german.html) and the
mock data under [`ui_kits/`](../../design-system/ui_kits/). German compound width is a
design constraint this product actually has, and those two are where it is demonstrated.
`REQ-CON-012` and `REQ-CON-014` carry the list; a third entry needs a row there.

## Licence consequences, and why they were due now

The system vendors two third-party asset sets. This closes item A4 of
[ADR-0000](0000-open-points.md), which had been waiting for exactly this moment.

| Asset | Licence | Holder |
|---|---|---|
| `design-system/assets/fonts/**` — IBM Plex Sans and Mono | `OFL-1.1` | IBM Corp, Reserved Font Name "Plex" |
| `design-system/assets/icons/**` — 121 Lucide SVGs | `ISC AND MIT` | Lucide Contributors; Cole Bemis 2013–present |

`ISC AND MIT` is not a formality. Lucide is a fork of Feather, and its licence
file is genuinely two licences: ISC for the set, MIT for roughly 150 icons
inherited from Feather and named individually. Many of the 121 vendored icons are
on that list — `check`, `search`, `lock`, `x`, every `chevron-*` and `arrow-*`.
Shipping only the ISC half would under-attribute them. The delivered
`LUCIDE-LICENSE.txt` carries both parts verbatim, the icon list included, and it
stays that way.

Three things were done, and a fourth is still outstanding:

1. `LICENSES/ISC.txt`, `LICENSES/MIT.txt` and `LICENSES/OFL-1.1.txt` added, from
   the canonical SPDX texts.
2. [`REUSE.toml`](../../REUSE.toml) annotates both asset directories with
   `precedence = "override"`, placed **after** the `design-system/**` rule so the
   last matching annotation wins. Without that ordering the AGPL default swallows
   them and declares someone else's permissively licensed work as AGPL.
3. The upstream licence files are kept next to the assets they cover, so a reader
   who finds an SVG finds its terms without consulting a manifest.
4. **Still outstanding:** both licences require the notice *in all copies*, and a
   minified bundle is a copy. That is [REQ-CON-013](../requirements/02-non-functional.md)
   and it is not satisfied by anything in this ADR — it becomes real work when the
   first build exists.

**OFL-1.1 carries one obligation the others do not**, and the web build walks
straight into it. Clause 3 forbids a Modified Version from using the Reserved
Font Name — here, "Plex" — without written permission, and clause 19 defines a
Modified Version to include a derivative made "by changing formats". **Converting
the delivered TTFs to WOFF2 is therefore a modification**, and so is subsetting
them, which we will want anyway since the variable Plex Sans alone is 525 KB.

So the web font pipeline has exactly two lawful outcomes: ship the fonts
unmodified, or rename the modified ones so the primary family name presented to
the user is not "IBM Plex Sans". This is decided **before** the first `@font-face`
is written, not after, and it is written down here because it is the kind of
clause that is discovered during a licence audit rather than during a build.

## Consequences

**Good.** There is one visual vocabulary with measured contrast in both themes,
and it was derived from this repository's own documents rather than from a
generic template — the scanner, the generated form, the dense list and the
conflict resolver all trace back to chapters 10 and 11. The decisions the brief
forced, dark-by-default and codes-stay-dark-on-light among them, are carried
through into tokens rather than left as prose.

**Costly.** The repository gains 1.8 MB, most of it fonts, and a directory that no
build consumes yet. The system will age against a React 19 codebase that does not
exist, and some of it will need revision when it meets a real build — that is the
price of designing before implementing, which was a deliberate choice for this
project.

**Honest gaps, from §11 of the system's own README**: the QR renderer in
`CodePlate` draws correct geometry with a fake payload; the scanner's camera feed
is a gradient; audible and haptic scan feedback exist only as prose; six icons
this product wants have no Lucide counterpart and were deliberately not
substituted. None of these is hidden, and none should be discovered later as a
surprise.

## Alternatives considered

**Treat it as a reference, not a rule.** Rejected for the reason above: every
other constraint in this project is enforced, and the one that is not is the one
that erodes.

**Put it in `web/design-system/`.** Rejected: it would make the web client the
owner of something the apps also depend on, which contradicts REQ-NFR-071's
requirement that the breakpoints and tokens be shared rather than per-platform.

**Vendor only the tokens and rewrite the components by hand.** Rejected as a false
economy — the components are where the rules become concrete, and prose alone has
never yet stopped a divergent button.
