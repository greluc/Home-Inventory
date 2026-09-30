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

The API client will come from `api/openapi.yaml` — `REQ-API-002`, stage 1. At
stage 0 it is hand-written in `src/api.ts`, which is the drift this sentence used
to say was prevented, so `npm run contract:check` compares every path the client
calls against the document. The document is itself generated from the running
application ([ADR-0049](../docs/adr/0049-openapi-generated-from-the-implementation.md)),
so that comparison is against the implementation. It says the path exists, not
that the shape matches; the shape is what generation adds.

`nginx/default.conf` is generated from the built bundle by `scripts/csp.mjs`,
because the CSP names the hash of every inline script.

## Text, and the two languages

Every string a user reads comes from `src/i18n/en.json` or `src/i18n/de.json`.
Nothing is written into a component (`REQ-NFR-032`), English is the fallback
(`REQ-NFR-033`), and `npm run i18n:check` fails the build when a key a component
asks for is missing, when the two bundles disagree, or when a key nothing asks
for is still being translated.

The language is decided in this order: a choice made with the switch in the bar
and remembered in this browser, then the `locale` on the signed-in user's
profile, then the browser's own preference, then English. Changing the profile is
stage 1; until then the switch is what a user has, which is why it sits in the bar
and not behind a settings screen.

Category names are a case worth knowing about. The server sends the shipped key —
`room`, `shelf`, `box` — and never a label, because a label would be interface
text the server would have to translate into a language it does not know the
reader wants. The bundle translates the key, and the picker sorts by the result:
thirteen translated words sort differently in every language.

## Formatting money and dates

In the client, through `Intl.NumberFormat` and `Intl.DateTimeFormat` — not on the
server. The browser has better locale data than a JVM ever will, and it is one
of the reasons the server needs no money-formatting library
([ADR-0025](../docs/adr/0025-money-representation.md)).

## The design system

It lives in [`design-system/`](../design-system/README.md), it is **binding**, and
it is a peer of this directory rather than a part of it — the same tokens have to
drive the Compose apps ([ADR-0035](../docs/adr/0035-design-system.md)). The brief
that produced it is
[`docs/design/design-system-brief.md`](../docs/design/design-system-brief.md).

Consume `design-system/styles.css`; it is the single entry point and contains
nothing but `@import` lines. Never hand-edit the generated CSS under
`design-system/tokens/` — change `tokens.json` and regenerate, or this client and
the apps drift apart (REQ-NFR-073).

The component sources there are React **18** and a specification of markup and
behaviour, not a drop-in library; making them React 19 components is part of the
first build.


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
  ([REQ-CON-013](../docs/requirements/02-non-functional.md)). **That is now
  automatic**: `public/third-party-notices.json` is generated from what Rollup
  put into the chunks, so the day an icon or a font is imported it appears in the
  notice, and CI fails until the committed file says so
  ([ADR-0083](../docs/adr/0083-the-notice-travels-inside-the-artifact.md)).
- **Vendoring the SVGs into this directory changes their licence on paper.**
  `REUSE.toml` declares `web/**` as AGPL-3.0-or-later by aggregate precedence, so
  a permissively licensed SVG dropped in here without its own SPDX header gets
  labelled AGPL. Consume them from the package, or vendor them together with
  `LICENSES/ISC.txt`, `LICENSES/MIT.txt` and explicit annotations
  (ADR-0000, A4).

## Uploads go in pieces

`src/upload.ts` speaks **tus 1.0.0** against `/api/v1/media/uploads` (`REQ-MED-008`,
[ADR-0084](../docs/adr/0084-an-upload-arrives-in-pieces-and-is-staged-where-the-volume-is.md)):
create the upload, send the file in 4 MB chunks, and — when a chunk fails — ask the
server where it actually got to and continue from there. On the connection a phone has
while standing in front of a shelf, that is the difference between a photograph
arriving and a photograph never arriving.

**Hand-written, and `tus-js-client` is not a dependency.** What it would buy is parallel
uploads, storage back-ends and a retry policy this client does not want; what it would
cost is a line in nine licence notices (`REQ-CON-013`), an entry in this bundle's SBOM
and a package to keep current. The protocol used here is four requests.

It deliberately does **not** remember an upload across page loads. Somebody who
navigates away has made a decision, and an upload that resumed itself the next morning
would be a surprise rather than a convenience.

## What the bundle says about itself

Two files the build writes into `dist/`, and both ship with the image:

- **`third-party-notices.json`** — every package in the bundle with the licence it
  is under and the text of it (`REQ-CON-013`). Generated by
  [`tools/notices.py`](../tools/notices.py), committed under `public/`, checked in
  CI. The list comes from Rollup rather than from `npm ls`: a production install
  resolves `typescript`, because `i18next` declares it as a peer dependency, and no
  line of it reaches a browser — twelve resolved packages against seven bundled
  ones. The plugin that answers that question is at the top of `vite.config.ts`.
- **`sbom.json`** — the CycloneDX bill of materials of the production tree
  (`REQ-CON-010`), from `@cyclonedx/cyclonedx-npm`, written by `npm run build` so
  it cannot be stale relative to the bundle beside it.

`ThirdPartyNotices.tsx` shows the notice, opened from the footer that carries the
version and the source link. It shows **two**: this bundle's and the server's,
because they are two artifacts with two dependency sets and neither covers the
other.

## The build runtime

**Node 26.** Newest major and the LTS line from 2026-10-28, maintained to
2029-04-30 — Node 24 would have been the safer-sounding choice and would have
forced a migration a year earlier. It stays on an LTS line for the same reason
Java does; leaving that line is a decision, not a version bump, and Renovate is
configured to hold it
([02 Constraints](../docs/architecture/02-constraints-and-context.md)).

## Status

Stage 0's screens are here: sign in, search, create an item and put it somewhere,
build the tree of places, photograph a thing where it stands. That is the whole
of "an item can be created, photographed, stored and found again"
([the roadmap](../docs/requirements/04-roadmap-and-stages.md)) — offline
operation, labels and scanning are later stages.

There is no router. Two panels behind the login and inline forms; a router would
be a dependency carrying its own history handling for a navigation that does not
exist yet.

## Linting

`npm run lint` runs [`oxlint`](https://oxc.rs) with `--deny-warnings`.

`REQ-SEC-076` named ESLint with `eslint-plugin-security`, and that combination
cannot run here: `typescript-eslint` refuses to load under TypeScript 7
([upstream issue 10940](https://github.com/typescript-eslint/typescript-eslint/issues/10940)),
so ESLint cannot parse a single `.tsx` file in this directory. `oxlint` parses
TypeScript 7 and carries the same classes of rule, and CodeQL's
`javascript-typescript` analysis covers the queries it does not. The requirement
records the substitution, the reason and when to revisit it.

Two rules are switched off in `.oxlintrc.json`, both with a narrow scope:

| Rule | Where | Why |
|---|---|---|
| `react/react-in-jsx-scope` | everywhere | It is for the legacy JSX transform. React 19 uses the automatic runtime, where `React` is not in scope and does not need to be |
| `import/no-unassigned-import` | `src/main.tsx` only | `import "./styles.css"` and `import "./i18n"` are how Vite gets the stylesheet into the bundle and how i18next is initialised before the first component asks for a string. Neither has a binding by design |
