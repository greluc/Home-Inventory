# ADR-0034 — Lucide as the icon set, and no third-party host for anything

**Status:** Accepted · **Date:** 2026-09-11
**Records:** `REQ-PRIV-015` and the icon decision in the design brief.

> **Amended by [ADR-0083](0083-the-notice-travels-inside-the-artifact.md)** — the obligation
> table below dated the notice by trigger, *"with the first distributed build"*, and named
> the three places it has to reach. That build arrived on 2026-09-22 and the answer is one
> generated notice per distributed artifact, taken from what the artifact carries: a
> resource in the two jars, a file in the bundle, and **compiled into the binary** for the
> six `scratch` images, which have no filesystem to put a file on. The third place — the app
> packages — is stage 2 and 3 and does not exist yet.
**Moves here:** the Lucide licence analysis that was filed as note **A4** in
[ADR-0000](0000-open-points.md) — it is a settled decision with obligations, not an
open point, and ADR-0000 is for things that are still open.

## Context

Two decisions were made together in the design brief and recorded only there and
as `REQ-PRIV-015`: the icon set is **Lucide**, and **no client asset comes from a
third-party host — for anything, without exception**.

They belong together because an icon set is the single most common reason a
project quietly acquires a CDN dependency.

## Decision

### No third-party host

Every client — web and apps — ships every asset it needs: fonts, icons,
stylesheets, scripts, images, sounds. At runtime a client contacts **only its own
instance**. The CSP enforces it — with `default-src 'none'` and each fetch
directive named explicitly ([12 §12.9](../architecture/12-security.md)), not with
`default-src 'self'`; and the acceptance test is mechanical: load every view with
an empty cache and observe that every request goes to a hostname **this
deployment serves**.

> **Two corrections to the paragraph above, made 2026-09-11.** It said
> `default-src 'self'` was "the floor" and that the test observes "requests to one
> origin". Both were wrong against the policy actually in force, and
> `REQ-PRIV-015` already says so: `default-src 'none'` is stricter than `'self'`
> and is what [12 §12.9](../architecture/12-security.md) serves, and a literal
> `default-src 'self'` is explicitly **not** the criterion — because the media
> host is deliberately a **separate origin** ([12 §12.7](../architecture/12-security.md)),
> as is `HOMEINV_PLUGIN_UI_BASE_URL` where a plugin panel is installed. The rule
> is *no host the operator does not run*, which is three origins here, not one.
> A test written to the old wording would have failed on the design's own media
> host.

| Reason | |
|---|---|
| Privacy | A third-party host sees the user's IP address, the referring page and the timing of every visit. For a self-hosted inventory that is the whole point defeated |
| Offline | `REQ-SYNC-001` requires the client to work with no network. An asset fetched on demand is missing exactly where the product is meant to work |
| Supply chain | A CDN is remote code execution with a good reputation |
| Consistency | It is the same rule as [ADR-0026](0026-core-outbound-via-plugins.md) applied to the client instead of the server. The system contacts nothing it does not ship or explicitly enable |

### Lucide as the icon set

1600+ icons, ISC/MIT, outline-only, on a 24 × 24 grid with
`stroke="currentColor"`. Shipped **self-hosted as SVG** — never as an icon
webfont, whose private-use-area glyphs a screen reader either skips or reads out
as a stray character.

Two properties follow from the drawing model and are design tokens, not defaults
to inherit: `stroke-width` is stated **per icon size** (2 px on a 24 px grid reads
heavy at 16 px in a dense table row and thin at 32 px), and the optical weight is
tuned in **dark mode first**, because a light stroke on a dark ground blooms — and
dark is our default ([ADR-0033](0033-dark-as-default-appearance.md)).

Lucide is outline-only: there is no filled twin. Selected-versus-unselected must
therefore come from colour, background or weight, never from swapping to a fill
variant that does not exist.

## The licence obligation, and where it is easy to fail

Lucide's `LICENSE` is **one file containing two licences**:

- **ISC**, © Lucide Contributors — the set as a whole
- **MIT**, © Cole Bemis 2013–present — roughly 150 icons inherited from Feather,
  listed **by name** in that same file

Both combine one-way into AGPL-3.0-or-later without friction. Neither demands
attribution in the user interface or a "powered by". What they demand is that the
copyright and permission notice **appears in all copies** — and a minified bundle
is a copy, while the `LICENSE` sitting in this repository is not part of it.

| When | What is owed |
|---|---|
| Now, while Lucide is only a package dependency | Nothing |
| With the first distributed build | The full `LICENSE`, verbatim, in the bundle, the container image and the app packages, reachable from the running installation beside the version and source link (`REQ-CON-009`, `REQ-CON-013`). **The list of icon names is part of the licence text, not decoration** — shipping only the ISC half under-attributes the ≈150 MIT icons |
| In the SBOM (`REQ-CON-010`) | Recorded as `ISC AND MIT`, not as ISC |
| With the first **vendored** SVG under `web/` | `LICENSES/ISC.txt` and `LICENSES/MIT.txt` plus annotations in [`REUSE.toml`](../../REUSE.toml). Without them the files are swept up by the `web/**` default and silently declared AGPL-3.0-or-later — a false statement about someone else's work. **Do not add those two licence files early:** `reuse lint` reports a licence file that nothing references as an error, so they belong in the same commit as the icons |

## Consequences

- **No web font by link, no icon pack on demand, no analytics beacon, no embedded
  third-party frame** — and the CSP is what enforces it, so a regression fails CI
  rather than shipping quietly.
- **Self-hosting fonts and icons costs bundle size**, which is accepted. The
  alternative costs privacy and offline capability, both of which are requirements.
- **If an icon the application needs has no counterpart in Lucide**, it is recorded
  in a list rather than substituted with something approximate — a deliberately
  visible gap beats a silently wrong metaphor.
- The obligations above are dated by trigger, not by calendar, so they cannot be
  missed by being "not yet due": the first distributed build is the deadline for
  the notice, and the first vendored SVG for the REUSE annotations.
