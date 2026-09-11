# web/

The web frontend — React 19 + TypeScript + Vite, shipped as an installable PWA.

## Why a PWA and not server-rendered pages

Because it has to work in a cellar with no signal. The offline requirement rules
out a server-rendered UI, and among the SPA options React's ecosystem for
camera-based scanning and offline storage decided it
([ADR-0012](../docs/adr/0012-web-frontend.md)).

## Things that will bite, written down in advance

| | |
|---|---|
| **Camera access needs HTTPS** | Without TLS there is no `getUserMedia`, so scanning silently does not work. This is the most common stumbling block for self-hosters and belongs in the installation guide, not in a footnote. |
| **A strict CSP, no `unsafe-inline`, no `unsafe-eval`** | The Vite build is configured for it, and a CI test compares the served policy against an expected string. |
| **IndexedDB is not encrypted** | Which is why the PWA stores no `sensitive` field locally, and says so in the UI. |
| **The browser may evict storage** | `navigator.storage.persist()` is requested; if it is refused, the user is told rather than silently losing offline data. |
| **`dangerouslySetInnerHTML` is forbidden** | Enforced by a lint rule, not by good intentions. |

## What is generated, not written

The API client comes from `api/openapi.yaml`. Hand-writing one would mean the
contract and the client can disagree, which is exactly what the generation
prevents.

## Formatting money and dates

In the client, through `Intl.NumberFormat` and `Intl.DateTimeFormat` — not on the
server. The browser has better locale data than a JVM ever will, and it is one
of the reasons the server needs no money-formatting library
([ADR-0025](../docs/adr/0025-money-representation.md)).

## The design system

The brief that defines it is
[`docs/design/design-system-brief.md`](../docs/design/design-system-brief.md).
Two things in it are constraints rather than taste, and are easy to violate
without noticing:

- **No runtime style injection.** The CSP forbids `unsafe-inline`, which rules
  out emotion and styled-components. Tokens are CSS custom properties in a
  static stylesheet.
- **The tokens are not ours alone.** The same set has to generate a Compose
  `Theme.kt` for the apps, so they live in a platform-neutral source and are
  generated into CSS — not hand-written here.
- **Dark is the default, not `prefers-color-scheme`.** The theme is decided by
  the stored user preference, falling back to dark — the media query is
  deliberately not consulted ([REQ-NFR-040](../docs/requirements/02-non-functional.md)).
  The class on `<html>` must therefore be set by a small blocking inline script
  in `index.html`, before React mounts, or a light-preferring user gets a dark
  flash on every load. That script is the one piece of inline JavaScript in the
  application and needs its own CSP hash — not `unsafe-inline`, which would
  undo the policy for everything else. `index.html`, the manifest `theme_color`
  and `background_color`, and `:root { color-scheme: dark }` all default dark
  too, otherwise the splash screen and the browser chrome flash white while the
  app is still starting.
- **Three device classes, one layout system.** Phone, tablet and desktop, both
  orientations ([REQ-NFR-071](../docs/requirements/02-non-functional.md)). The
  breakpoints come from the token set and match the window size classes the
  Compose apps use — a media query with a number typed into a stylesheet is how
  the two platforms drift apart. Hit-target size is driven by
  `@media (pointer: coarse)`, not by width: a tablet with a keyboard is wide and
  still touch.
- **Codes are exempt from the theme.** QR and barcode renderings, and the label
  print preview, stay dark-on-light in both themes
  ([REQ-LBL-014](../docs/requirements/01-functional.md)). An inverted code does
  not scan reliably, and the preview is a picture of paper.

## Icons

**Lucide** — self-hosted SVG, no CDN, and never the icon webfont, whose private
use area glyphs are read out as garbage by screen readers
([REQ-PRIV-015](../docs/requirements/03-security-and-privacy.md)).

Source is **`lucide-static`** (the raw SVGs and the sprite), not `lucide-react`:
the same files also have to feed the Compose apps, and a React package cannot.
One source, one `<Icon>` component of our own.

- **They are strokes, not fills**: a 24 × 24 grid, `fill: none`,
  `stroke: currentColor`, `stroke-width: 2`, round caps and joins. Colour them
  with `stroke`, never `fill` — setting `fill` on a Lucide icon paints it into a
  blob. `stroke-width` is a token per icon size, not the inherited default.
- **The licence is `ISC AND MIT`**, in one file: ISC for the set, MIT (© Cole
  Bemis) for about 150 icons inherited from Feather. Ship that file **whole** —
  the ISC half alone under-attributes the Feather icons — and ship it **with the
  bundle**, not only in this repository: a minified bundle is a copy, and both
  licences require the notice in all copies
  ([REQ-CON-013](../docs/requirements/02-non-functional.md)).
- **Vendoring the SVGs into this directory changes their licence on paper.**
  `REUSE.toml` declares `web/**` as AGPL-3.0-or-later by aggregate precedence, so
  a permissively licensed SVG dropped in here without its own SPDX header gets
  labelled AGPL. Consume them from the package, or vendor them together with
  `LICENSES/ISC.txt`, `LICENSES/MIT.txt` and explicit annotations
  (ADR-0000, A4).

## Status

Empty. Stage 0 delivers the first screens:
[the roadmap](../docs/requirements/04-roadmap-and-stages.md).
