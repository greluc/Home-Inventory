<!-- Delivered by Claude Design on 2026-09-11 from docs/design/design-system-brief.md. -->

> ## This design system is binding
>
> Not a suggestion and not a starting point. Every surface of the web client and
> of the apps is built from the tokens, components and rules in this directory.
> Deviating from it requires an amended requirement and the repository owner's
> approval, exactly like any other load-bearing rule
> ([ADR-0035](../docs/adr/0035-design-system.md), REQ-NFR-074…077).
>
> **`tokens/tokens.json` is the single source.** The CSS under `tokens/` is
> generated from it, and the Compose `Theme.kt` will be. Never hand-edit a
> generated stylesheet; change the token and regenerate, or the two platforms
> drift apart silently.
>
> **What was left out of the delivery, and why.** The preview harnesses that came
> with it — `*.card.html`, `ui_kits/**/index.html`, `templates/inventory-screen/`
> — loaded React 18 and Babel from `unpkg.com`. This project contacts no
> third-party host, for anything, without exception (REQ-PRIV-015), and a file in
> this repository that fetches a script from a CDN would contradict that on the
> page where it is written down. The component sources those harnesses rendered
> are all here. Claude Design's own build artifacts (`_ds_bundle.js`,
> `_ds_manifest.json`, thumbnails) were left out as tooling.

# Home Inventory — Design System

A design system for **Home Inventory**, a self-hosted inventory application for
physical and digital possessions. It is a tool, not a product people enjoy
browsing. Success is: *the thing was found in four seconds.*

Dark is the shipped default on every platform. Light is one click away.

---

## 1 · Sources

Everything here is grounded in a real repository. Nothing was recreated from a
screenshot, because there is no UI to screenshot yet.

| Source | What was taken from it |
|---|---|
| **<https://github.com/greluc/Home-Inventory>** — branch `main`, commit tree `eb37ee5f278c` | the whole brief and every domain fact below |
| `docs/design/design-system-brief.md` | the design brief itself — the postures, the three device classes, the four hard surfaces, the tone rules, the hard constraints |
| `docs/architecture/10-identification-and-labels.md` | the public-code format (Crockford Base32, 10 payload characters plus a Damm check symbol, `7Q2M-4X9K-D2F`), the QR payload, the six scan modes, `LabelMedia` geometry, the `verified` flag, the print-job state machine |
| `docs/architecture/11-offline-synchronisation.md` | push/pull, `APPLIED · MERGED · CONFLICT · REJECTED · DUPLICATE`, the three-way compare, the per-field-kind merge rules, the conflict record, the device list, the media upload queue |
| `web/README.md` | the CSP and no-CDN constraints, the pre-paint theme script, Lucide from `lucide-static`, the codes-are-exempt rule |
| `app/README.md`, root `README.md` | product naming (`home-inv`), the module set, the technology stack |
| **<https://github.com/lucide-icons/lucide>** | the icon set, vendored as SVG into `assets/icons/` with its combined `ISC AND MIT` licence |
| **<https://github.com/google/fonts>** (`ofl/ibmplexsans`, `ofl/ibmplexmono`) | the self-hosted type, OFL 1.1 |

> **Read the repository.** The architecture documents were complete before the
> first line of code, and every screen here has a chapter behind it.
> If you are extending this system, read `docs/requirements/01-functional.md` and
> `docs/architecture/07-data-model.md` before inventing a surface.

**Status of the source:** this system was designed while `web/` and `app/` were
still empty. There was therefore **no existing UI, no existing stylesheet and no
existing component library** to copy — the visual decisions in it are new, but
every *domain* decision is quoted from the docs. `web/` now holds a client, and
it is built from these tokens rather than the other way round.

**There is no logo.** The repository contains no wordmark or brand mark. None was
invented. Wherever a mark would go, the product name is set in IBM Plex Sans
SemiBold beside a square carrying the initials `HI` in the one accent colour —
see `guidelines/brand-mark.html`. If a real mark exists, it replaces those
placeholders unchanged.

---

## 2 · Index

| Path | What is in it |
|---|---|
| `styles.css` | the single entry point — nothing but `@import` lines |
| `tokens/` | `fonts.css` · `palette.css` · `color.css` · `typography.css` · `spacing.css` · `layout.css` · `base.css` |
| `tokens/tokens.json` | **the platform-neutral source** (W3C DTCG). CSS and Compose `Theme.kt` are both generated from it |
| `components/` | 9 groups, 48 exported components — see §9 |
| `guidelines/` | 22 specimen cards: colour with measured ratios, type, spacing, density, breakpoints, motion, brand, voice |
| `ui_kits/home-inventory-web/` | the click-through web client — 13 surfaces × 4 widths × 2 themes × offline on/off, as React sources. Its `index.html` harness is not in the repository (see the note at the top); wire the screens into the real Vite build instead. |
| `adherence.oxlintrc.json` | the lint rules that enforce this system mechanically — no raw hex, no component-internal imports |
| `assets/fonts/` | IBM Plex Sans (variable) + IBM Plex Mono, with `OFL.txt` |
| `assets/icons/` | 121 Lucide SVGs + `LUCIDE-LICENSE.txt` |
| `SKILL.md` | the Agent-Skill entry point |

---

## 3 · Content fundamentals

**Person and address.** German uses **du**, not Sie — this is a tool people run
in their own cellar. English uses no pronoun where it can be avoided:
*"Etikett scannen"*, not *"Scanne dein Etikett"*. Buttons are imperative verbs.

**Sentence case everywhere.** Labels, buttons, headings, menu items. The only
uppercase is the 11 px column head and the `Badge`, both with `0.06em` tracking.
German nouns are capitalised because German capitalises nouns, not for emphasis.

**No emoji. Ever.** Not in UI copy, not in empty states, not in toasts. The icon
set is Lucide and nothing else.

**No exclamation marks, no apologies, no "Oops".** State the condition, then the
way out. Two words of diagnosis beat one word of sympathy.

| Say | Not |
|---|---|
| Offline — changes are stored locally and reconciled at the next connection. | Oops! No connection 😕 |
| This field is not visible to your role. | `••••••••••••` |
| 2 items are waiting for a decision. | A synchronisation error occurred! |
| Module size below 0.33 mm — unreliable with phone cameras. | Warning: possible printing problem |
| 3 fields left | Please fill in all fields |

**Numbers are facts, so show them.** `3 211 items`, not "many". `88` in a tree
row. `7 of 12` on an upload. Never `99+` — this is an inventory tool; the count
*is* the content. Thin space as the thousands separator in German, comma as the
decimal mark, `Intl.NumberFormat` in the client (never formatted on the server).

**Empty, no-results, error, restricted are four different sentences.** They are
the states people hit most and the ones most systems write once:

- *empty* — "No items in this location yet" + the action that fixes it
- *no results* — "No matches" + "Clear filters", and it names how many filters are active
- *error* — what failed and whether a retry is worth it
- *restricted* — "No permission for this field", with a hatch, never a masked value

**German is the sizing language, English the second.** Layout is checked against
`Wiederbeschaffungswert`, `Lagerortkategorie`, `Mindesthaltbarkeitsdatum`,
`Benachrichtigungseinstellungen` — never against lorem ipsum and never against
English. A label column that fits "Value" is a broken label column.

---

## 4 · Visual foundations

### Feel

A well-made technical instrument. Legible, dense, calm, slightly severe. It
should still look right in ten years, which mostly means: it does not look like
2026.

### Colour

One accent — a cobalt blue (`#1a66d6` dark, `#175fcc` light) — used for the
primary action, the focus ring, links and determinate progress. Nowhere else.
It is never a status colour, so it never collides with one.

Neutrals are a cool slate. Dark runs `#0d0f11 → #22272c` across five surfaces;
light runs `#edeff1 → #ffffff`, with the page a faint tint so white cards have
something to sit on. **Light is authored, not inverted:** it has its own hues for
every semantic state, and every pair was measured separately.

Semantic states: success green, warning amber, danger red, conflict violet,
degraded cyan, **offline deliberately hueless** (offline is normal, not an
alarm), restricted grey + hatch, pending muted. Every measured ratio is in
`tokens/tokens.json` under `$extensions.contrast`, and rendered on
`guidelines/colors-*.html`.

### Backgrounds and imagery

**There are none.** No illustrations, no mascot, no stock photography, no
patterns, no textures. The only pictures in this application are the user's own
photos of their own belongings. An item with no photo gets a Lucide glyph on
`--photo-placeholder`, never a stretched stand-in.

The **one permitted pattern fill** is `.hi-hatch`: a 135° 1px/6px repeating
stripe at 12–14 % alpha, used exclusively for *no permission*. It exists to give
that state a **texture**, which is what separates it from empty (nothing there)
and from error (red).

### Gradients

Two, both functional, neither decorative: the scanner's bottom scrim (so white
controls stay legible over an arbitrary camera image) and the `.ph` placeholder
hatch. No gradient is ever a surface, a button or a heading.

### Elevation and shadow

Two steps. `--elev-1` is a 1px hairline under sticky chrome; `--elev-2` is a soft
drop for overlays only — modal, drawer, popover, toast. **In dark mode elevation
is carried by surface lightness, not by shadow**, because a shadow on `#131619`
is invisible. A card does not float: one border, 3 px radius, no shadow.

### Borders

Four tiers, and the distinction is load-bearing:
`--border-subtle` (1.29:1, dividers) · `--border-default` (1.78:1, panel edges) ·
`--border-control` (**3.76:1**, anything whose shape identifies a control — WCAG
1.4.11) · `--border-strong` (5.81:1, hover and drag handles).

### Corner radii

`0` for table rows and the status strip. `2px` for buttons, inputs, chips.
`3px` for cards, modals, photo tiles. `999px` on exactly three things: the
scan-mode selector, the avatar, the count bubble. Nothing bubbly.

### Transparency and blur

Transparency appears in exactly three places: the modal scrim, the scanner's
control plates, and the hatch. **There is no backdrop blur anywhere.** It costs
GPU on the cheap phone that lives in the workshop, and it makes text behind it
worse, not better.

### Animation

Feedback only. `80 / 120 / 180 / 240 ms`, one entry curve
`cubic-bezier(.2,0,0,1)`. Four things animate: the press state, the drawer, the
toast entry, the scan sweep. Skeletons breathe on opacity, never shimmer.
`prefers-reduced-motion` collapses every duration to `1ms` and stops the sweep,
the spinner and the pulse — the loading *state* remains, the motion does not.

### Hover, press, focus

- **hover** — a surface step up (`--surface-hover`) plus, on controls, a border
  step up. Never a colour change on text alone, and never the only way to learn
  something exists. Under `hover: none` the rule simply does not apply.
- **press** — a surface step down (`--surface-active`); filled buttons darken.
  No scaling, no bouncing.
- **focus** — a **two-tone ring**: 2 px `--border-focus` with a 1 px offset plus
  a `--border-focus-offset` outer ring. The second ring is not decoration: a
  single light ring sitting on the accent fill measures 2.5:1 and fails. The
  ring is a shape change, so focus never relies on colour alone.

### Type

**IBM Plex Sans** for everything, **IBM Plex Mono** for codes. Self-hosted from
`assets/fonts/`, OFL 1.1.

Why Plex and not a grotesque: it was drawn for technical documentation, its
digits are unambiguous, it has real tabular figures and a slashed zero, and its
letterforms are narrow enough that `Benachrichtigungseinstellungen` fits a
280 px label column at 14 px. `0`/`O` and `1`/`l`/`I` are genuinely distinct in
the mono — which matters, because users read codes off labels. (The public-code
alphabet is Crockford Base32 and has already removed `I`, `L`, `O`, `U`; the
type is the second line of defence, for serial numbers and ISBNs, which have
not.)

Nine sizes, 11 → 32 px. Three weights: 400 / 500 / 600. Nothing heavier — weight
is a signal here, not a texture. Generous line heights, because a German
compound that wraps mid-word needs the descender room; `:lang(de)` turns on
`hyphens: auto`, and every label carries `overflow-wrap: anywhere`.

### Density and whitespace

Whitespace is not luxury here. A screen showing 8 rows where it could show 20 is
a worse screen. Two densities — comfortable (44 px rows) and compact (32 px
rows, 13 px type). Compact is a real working mode: it shrinks the rhythm and the
type, **never the hit target**.

---

## 5 · Iconography

**Lucide**, given by the brief, not chosen. Vendored as raw SVG into
`assets/icons/` — 121 files plus `LUCIDE-LICENSE.txt`, which is the combined
`ISC AND MIT` notice and must ship **whole and with the bundle**, because the
~150 Feather-derived icons are MIT © Cole Bemis and the ISC half alone
under-attributes them.

**Never the icon webfont.** Its private-use-area glyphs are skipped or read out
as a stray character by screen readers.

**Never `fill`.** Lucide is `fill="none"`, `stroke="currentColor"`, round caps
and joins, on a 24 × 24 grid. Setting `fill` paints the glyph into a blob.

### stroke-width is a token, not the default

2 px on a 24 grid is tuned for icons rendered near 24 px. Scaled to 16 px in a
dense table row it reads heavier than its label; at 32 px the rendered stroke
grows to 2.7 px. So the token holds the **rendered** stroke roughly constant and
matched to text weight:

| Icon size | Dark (default) | Light | Rendered, dark |
|---|---|---|---|
| 12 | `2.4` | `2.7` | 1.20 px |
| 14 | `2.2` | `2.5` | 1.28 px |
| 16 | `2` | `2.25` | 1.33 px |
| 20 | `1.75` | `2` | 1.46 px |
| 24 | `1.6` | `1.75` | 1.60 px |
| 32 | `1.4` | `1.5` | 1.87 px |
| 40 | `1.2` | `1.25` | 2.00 px |

**Dark was tuned first**, because it is the default and because a light stroke on
a dark ground blooms — every dark value is 0.1–0.3 lighter than its light twin.
Never scale an icon with CSS `transform`; the stroke scales with it.

12 and 14 exist only for glyphs set inside 12 px text — chips, badges, the table
sort arrow, the breadcrumb separator. `.hi-icon` carries 20 px as its base size,
so an unrecognised modifier degrades to 20 px rather than letting the SVG expand
to fill its container.

### Selected without a filled twin

Lucide is outline-only. Selected / active is therefore carried by **colour +
background + weight + a bar**, never by a fill swap:

| Component | Selected is |
|---|---|
| `Tabs` | accent stroke and label · semibold · 2 px underline |
| `NavRail`, `BottomNav` | accent · tinted background · 2 px edge bar · semibold |
| `IconButton` (toggle) | accent · tinted background · 1 px inset ring · `aria-pressed` |
| Table row | tinted row · 2 px inset left bar · `aria-selected` |
| `FilterBar` facet | accent · tint · border · **and an × appears** |
| Tree node | tint · 2 px inset left bar · medium weight |

### Icons this product needs and Lucide does not have

Named rather than approximated:

1. **A nested storage location** — `folder-tree` is the stand-in, but it reads as
   a filesystem. A crate-inside-a-crate glyph is missing.
2. **"Label not yet assigned"** — currently `tag` + a dashed border. A tag with
   an empty field would say it in one glyph.
3. **Stocktake / count-in-progress** — `clipboard-list` is borrowed from generic
   task UI; nothing depicts *counting against an expected list*.
4. **Three-way merge** — `git-merge` is developer vocabulary shown to homeowners.
   It is the best available and still wrong.
5. **A pre-printed blank code sheet** — no sheet-of-labels glyph exists.
6. **Degraded/fallback search** — `gauge` is a metaphor, not a depiction.

Do not substitute something approximate for these; use the stand-in listed and
leave the gap visible.

---

## 6 · Layout: one system, three device classes

Breakpoints are **tokens**, and they are Material 3 window size classes so the
Compose apps and the web client cannot drift. Never type a pixel into a media
query — read `--bp-*`.

| Class | Width | Navigation | Primary layout |
|---|---|---|---|
| compact | < 600 | bottom bar, scan in the middle slot | one column; detail is a route |
| medium | 600–839 | bottom bar | one column, wider gutters; tree *above* contents |
| expanded | ≥ 840 | left nav rail | master–detail; form goes two-column |
| large | ≥ 1200 | left nav rail | tree + list + detail, three panes |
| x-large | ≥ 1600 | left nav rail | growth stops at `--layout-max` 1680 px |

**Density follows the width. Hit-target size follows the pointer.**
`@media (pointer: coarse)` restores 44 × 44 px whatever the width and whatever
the density — a tablet with a keyboard is expanded *and* touch; a touch laptop is
both at once. And `hover: none` must never hide a function: the tooltip vanishes
entirely under a coarse pointer, so nothing load-bearing may live in one.

### What each surface does as width shrinks

| Surface | Expanded → | Medium → | Compact |
|---|---|---|---|
| **Item list** | table, user-chosen columns, sticky head, pagination | table with fewer columns (name, location, value) | **stops being a table**: two-line rows, name + path + status, infinite scroll. A sideways-scrolled table on a phone is a table nobody reads. |
| **Photo grid** | tiles cap at 220 px; more room = more tiles | 3–4 columns | 2 columns |
| **Generated form** | two columns, label 11–16 rem | one column, sections open | one column, sections collapsed except the first, action bar pinned in the thumb zone |
| **Location tree** | left pane beside contents | tree collapsed above contents | tree is its own route; contents reached by tapping |
| **Item detail** | right pane beside the list | route | route |
| **Conflict** | three option cards in a row per field | row per field | **stack of three full-width cards per field**, one field at a time, with an "x of y decided" counter |
| **Scanner** | same overlay, centred at 420 px | full width | full bleed; every interactive control in the bottom band — 44 px mode pills, 48 px toggles, result banner above them |
| **Filters** | persistent left facet pane | drawer | bottom sheet |

**Where the wide end stops.** `--layout-max: 1680px`. Beyond it the table does
not widen: the extra room becomes a persistent detail pane, then a persistent
tree pane. Line length stops at `--measure-prose: 68ch`; a form field stops at
`--measure-form: 60ch` plus its label column. A table stretched across 2560 px
is a worse table.

**Orientation is a state, not an edge case.** A tablet rotated mid-form crosses
840 px and the form re-flows from one column to two. Nothing is remounted, no
input is lost, scroll position is preserved by anchoring to the focused field.
Scanning works in both orientations; the thumb band is bottom-anchored in
portrait and becomes a right-hand band in landscape on phones.

---

## 7 · Dark by default

Dark is applied because it is the **stored preference, defaulting to dark** — the
`prefers-color-scheme` media query is deliberately not consulted.

```html
<!-- index.html, before anything else. The one inline script in the app;
     it gets its own CSP hash, never unsafe-inline. -->
<script>
  try {
    var t = localStorage.getItem("homeinv.theme") || "dark";
    if (t === "light") document.documentElement.setAttribute("data-theme", "light");
  } catch (e) { /* storage blocked → dark, which is the default anyway */ }
</script>
```

`:root { color-scheme: dark }` makes native controls, scrollbars and the caret
follow. The PWA manifest carries `"theme_color": "#131619"` and
`"background_color": "#131619"` so the splash and the browser chrome do not
flash white.

**The light toggle lives in the app bar and in the user menu — one click, always
visible.** Overriding a platform preference is only defensible if reversing it is
trivial, and there is a real accessibility reason: light text on a dark ground is
*harder* to read for people with astigmatism, who see halation around the glyphs.
Dark-by-default is right for the cellar and wrong for some readers. The escape
hatch is what squares that.

---

## 8 · The written rules

### Modal or drawer

- **Modal** — a decision that must be made before anything else continues:
  destructive confirmation, the second-factor "confirm again", confirming the
  base URL before the first label print. Focus traps on open and returns to the
  trigger on close. Esc closes, unless there is unsaved input, in which case Esc
  asks.
- **Drawer** — a side task where seeing the context still helps: filters, the
  location picker, bulk edit, an item's detail beside the list. No focus trap
  when it has no scrim.
- **Below 600 px both become bottom sheets**, so the buttons land in the thumb
  zone. A modal's footer stacks, primary on top.
- **Neither** for something with no decision in it — that is an `InlineMessage`.

### Where the primary action sits

- **Compact**: bottom-pinned `ActionBar`, full width, inside the 140 px thumb
  band, safe-area padded. The primary is `order: 2` in CSS so source order stays
  cancel-then-confirm while the visual order follows the platform.
- **Medium**: the same bar, inline at the end of the content.
- **Expanded and up**: right-aligned, inline, below the form; in a list view, in
  the toolbar at the top right.
- **Exactly one filled button per context.** If a screen seems to need two, one
  of them is secondary.

### Destructive confirmation

Three tiers, by what is recoverable:

1. **Undoable** (move, tag, archive) — do it, then a toast with *Undo*.
   No dialog.
2. **Recoverable** (delete to trash, deregister a device) — a modal naming the
   object and the count: *"Delete 3 items?"*, danger button, cancel is ghost.
3. **Irreversible** (purge, remote wipe, change the base URL after printing) — a
   modal that requires **typing the object's name**, plus a re-authentication if
   the action touches a `secret`. The danger button stays disabled until the
   typed string matches.

Destructive buttons are never the default focus and never sit where the
confirming button usually is.

### How focus moves

Tab order is DOM order; nothing has a positive `tabindex`. Opening a modal moves
focus to its heading and traps; closing returns to the trigger. A drawer with no
scrim does not trap. Route changes move focus to the `<h1>` and announce the
title through a polite live region. Inside a table, `Tab` reaches the row, arrow
keys move between rows, `Space` toggles selection, `Shift+Click` ranges. The
focus ring is never removed and never replaced by colour alone.

### What happens to a long string

| Where | Behaviour |
|---|---|
| Table name cell | single line, ellipsis, full value in `title` and in the detail pane |
| Photo tile | clamps to 2 lines |
| Tree node | single line, ellipsis — a wrapping tree loses its indent reading |
| Breadcrumb | collapses **from the middle**: first › … › last two. The last two segments are what identify a shelf. |
| Field label | wraps, `overflow-wrap: anywhere`, column 11–16 rem |
| Field value | wraps freely; never truncated — a value you cannot read is a value you do not have |
| Tag / chip | capped at 14 rem, ellipsis |
| Toast | two lines, then it is the wrong component |

### Offline, and the other first-class states

Offline is **not** an error screen. It is a persistent status strip in
`--state-offline-*`, hueless, with the count of unsent changes. Everything stays
editable. The strip stays until the condition ends — it is not a toast, because a
toast that disappears is a message the user missed.

| State | Rendering | Rule |
|---|---|---|
| offline | hueless strip + `cloud-off` + unsent count | never red, never blocking |
| pending upload | muted chip + `cloud-upload`; thumbnails get a dashed edge | normal, not a warning |
| conflict | violet strip + `git-merge` + count, linking to the resolver | the server version is the valid one until decided |
| degraded | cyan strip + `gauge`, saying *what* got worse | name the limitation, not just the fact |
| no permission | hatched plate + `lock` + the words | different from empty, different from error, **never dots** — a row of dots tells the viewer the length of the secret |
| not yet assigned | dashed border + `tag`, with a bind action | shape carries it, not colour |
| loading | shaped skeleton, or determinate progress where a number exists | a spinner that could have been a percentage is a lie |
| empty / no results / error / first run | `EmptyState`, four different sentences | never the same copy twice |

### Codes and paper — the one exception to the theme

`--code-paper`, `--code-ink`, `--code-quiet`, `--code-frame` and `--code-caption`
are declared once and **never re-declared in a theme scope**. QR and barcode
renderings and the label print preview stay dark-on-light in both themes,
because an inverted code does not scan reliably and the preview is a picture of
paper.

The boundary is drawn on purpose: a 1 px `--code-frame` edge, a small gap, and —
on the print preview — a "Print preview · to scale" strap above it. Without
that, paper-white in dark mode reads as a rendering bug.

The human-readable caption beneath every code is not decoration either: a label
must still work when the scan fails. You can read and type `7Q2M-4X9K-D2F`; you
cannot read and type a UUID.

### Contrast rules

Every foreground/background pair carries a measured ratio in
`tokens/tokens.json`. Body text clears 4.5:1 in both themes on every surface it
is allowed to sit on. `--text-disabled` is 3.98:1 (dark) and 2.59:1 (light) under
the WCAG 1.4.3 inactive-control exception, and disabled state is additionally
carried by `aria-disabled` and the cursor. `--border-control` clears 3:1 against
every adjacent surface (1.4.11). **Meaning is never carried by colour alone** —
every status has an icon and a word; every selected state has a shape change.

---

## 9 · Component canon

48 exported components in 9 groups. Each directory has one `@dsCard` preview.

**`components/foundation/`** — `Icon`

**`components/actions/`** — `Button` · `IconButton` · `ActionBar` ·
`ActionBarSpacer`

**`components/forms/`** — `Field` · `FormSection` · `FieldRenderer` (+
`renderControl`) · `TextInput` · `MoneyInput` · `QuantityInput` · `DateInput` ·
`Select` · `ComboBox` · `Checkbox` · `Radio` · `RadioGroup` · `Switch` ·
`TagInput` · `ReferenceInput` · `SecretInput` · `FileInput`

**`components/data/`** — `Table` · `RowList` · `PhotoTile` · `PhotoGrid` ·
`Tree` · `Card` · `Pagination` · `Skeleton` · `SkeletonRows` · `Avatar` ·
`Progress` · `FilterBar` · `BulkBar`

**`components/feedback/`** — `Badge` · `Tag` · `StatusChip` · `InlineMessage` ·
`Toast` · `ToastStack` · `Modal` · `Drawer` · `Tooltip` · `EmptyState`

**`components/navigation/`** — `Tabs` · `Breadcrumb` · `BottomNav` · `NavRail`

**`components/scan/`** — `ScanOverlay`

**`components/code/`** — `CodePlate` · `LabelPreview`

**`components/sync/`** — `ConflictField` · `ConflictProgress` · `DeviceRow`

### The 16 generated field types

`FieldRenderer` maps a runtime type definition onto the canon. There is no fixed
item form; there is this table.

| Type | Component | Note |
|---|---|---|
| `text`, `multiline`, `integer`, `decimal`, `url`, `email` | `TextInput` | numeric variants right-align with tabular figures; url/email get a leading glyph |
| `money` | `MoneyInput` | amount + attached currency cell, `Intl.NumberFormat` |
| `quantity` | `QuantityInput` | same anatomy as money, on purpose |
| `boolean` | `Checkbox` | **not** `Switch` — a switch implies it is already saved |
| `date`, `datetime` | `DateInput` | native picker: correct on every platform, follows `color-scheme`, works offline |
| `enum` | `Select` ≤ 12 options, `ComboBox` above | |
| `multi-enum` | `TagInput` | chips wrap downward; never a sideways scroll |
| `reference` | `ReferenceInput` | leaf name + path, picker as drawer/modal by width |
| `secret` | `SecretInput` | dashed well, key icon, the words "Wert verborgen". Never dots. Says when it is not stored on this device. |
| `file` | `FileInput` | drop zone is also a 44 px tap target |

Every type renders in six states through `Field`: empty · filled · focused ·
invalid with a message · disabled · read-only — plus **restricted**, which is
not a state of the value but of the viewer.

### Coarse and fine variants

| Component | Fine pointer | Coarse pointer |
|---|---|---|
| `Button`, `IconButton` | 36 px (28 compact) | **44 px**, always |
| `Table` row | 44 / 32 px by density | 44 px floor; cell padding widens to 10 px |
| `Tree` node | 32 px rows; twisty on hover-highlight | 44 px rows; twisty is its own separate 24 px target |
| `Tag` | 24 px, 20 px remove button | 32 px, 28 px remove button |
| `Tooltip` | shown on hover and focus | **not rendered at all** — never load-bearing |
| `Tabs` | 36 px | 44 px, horizontally scrollable |
| `Drawer` | right-hand panel, 28 rem | bottom sheet with a grip |
| `Pagination` | page numbers | replaced by load-on-scroll below 600 px |
| `ScanOverlay` | same overlay, 420 px wide | full bleed, controls in the bottom 140 px |
| Row hover | surface step | no hover rule at all |

### Intentional additions

Beyond the canon the brief lists, five components exist because this product has
states a generic system does not:

- **`Icon`** — a wrapper is required to apply the per-size stroke tokens.
- **`StatusChip`** — so offline / pending / conflict / degraded / restricted /
  unassigned are impossible to invent twice.
- **`RowList`** — the compact-width form of `Table`; without it, teams reach for
  a horizontally scrolled table.
- **`ConflictField` + `ConflictProgress`** — the phone-first three-way chooser.
- **`CodePlate` + `LabelPreview`** — the theme exception needs a component, or
  it becomes a one-off.
- **`DeviceRow`** — sync state has a fixed anatomy; repeating it invites drift.

---

## 10 · Technical constraints, and how they are met

| Constraint | How |
|---|---|
| Strict CSP, no `unsafe-inline`, no `unsafe-eval` | every component styles through **static CSS classes** in `components/**/*.css`, all reachable from `styles.css`. No CSS-in-JS, no runtime `<style>` injection, no `dangerouslySetInnerHTML` (`Icon` parses SVG with `DOMParser` and builds React elements). The one inline script is the pre-paint theme setter, which takes its own hash. |
| No external CDN | fonts in `assets/fonts/`, icons in `assets/icons/`. The design system loads nothing from a third-party host. *(The preview harness in `ui_kits/` and the `@dsCard` files loads React and Babel from unpkg — that is tooling for this repository, not part of the shipped system.)* |
| Same tokens drive Compose | `tokens/tokens.json` is the source; the CSS is generated from it. Themes are sibling groups so one traversal emits two `ColorScheme`s and two CSS scopes. Breakpoints are M3 window size classes. |
| German | every specimen, card and kit screen uses real German strings. Label columns are sized to `Mindesthaltbarkeitsdatum`. |
| WCAG 2.2 AA, verified in CI | measured ratios for every pair in both themes (`$extensions.contrast`); 44 px coarse targets; two-tone focus ring; icon + word on every state. |
| Offline is normal | a persistent hueless strip, editable content, pending/conflict as ordinary states. |

---

## 11 · Known gaps

- **No logo.** None existed; none was invented. See §1.
- **The QR in `CodePlate` is a deterministic block pattern**, not a real encoder.
  It is correct about geometry — quiet zone, module count, paper ground — and
  wrong about payload. Swap in the real renderer.
- **The camera feed in `ScanOverlay`** is a flat gradient.
- **Audible and haptic scan feedback** are specified in prose (four distinct
  tones; one pulse success, two short duplicate, long pulse error) but obviously
  not audible in HTML.
- **Brother DK printable-width insets** are still unknown upstream; the label
  preview warns rather than guessing.
- Six icons this product wants have **no Lucide counterpart** — listed in §5,
  deliberately not substituted.
