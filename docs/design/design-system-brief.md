# Design system brief — the prompt for Claude Design

This is the brief handed to Claude Design to produce the design system for Home
Inventory. It is kept in the repository because the brief that produced a design
is worth as much as the design when someone later asks "why is it like this".

**How to use it:** paste everything below the line into Claude Design. It is
written to stand on its own — Claude Design cannot see this repository.

---

## The brief

I need a design system for **Home Inventory**, a self-hosted inventory
application for physical and digital possessions. Please design the system, not a
set of screens: tokens, components, states and the rules that hold them together.

### What the product is

People record what they own — tools, books, appliances, furniture, moving boxes,
software licences — where it is stored, what it is worth, and when the warranty
runs out. Storage locations nest arbitrarily: building → room → shelf → box →
compartment. Items carry photos, printed QR labels, and metadata fields that each
installation **configures itself at runtime**.

It is a tool, not a product people enjoy browsing. Success is: the thing was
found in four seconds.

### Who uses it, and where — this drives everything

Three postures, all of which the system must serve:

1. **In the cellar, garage or attic, standing, one hand.** A phone in one hand, a
   box in the other. Poor light. Possibly gloves or dusty hands. Scanning labels,
   photographing objects, moving things between locations. **This is the primary
   posture and the one most inventory software gets wrong.**
2. **At a desk, two hands, keyboard.** Bulk-editing 500 items, configuring field
   types, setting up roles, reviewing a stocktake discrepancy report. Dense
   tables, keyboard navigation, multi-select.
3. **Looking something up.** Someone with read-only access who wants one answer:
   where is the drill.

### Three device classes, not two

**Phone, tablet and desktop — all three are first-class, in both orientations.**
The tablet is the one that usually gets skipped, and it is neither a large phone
nor a small desktop: it is held in two hands, it rotates mid-task, it is touch,
and it is wide enough for master–detail — the location tree beside its contents,
the list beside the item.

- **The breakpoints are tokens, and they are shared with the apps.** The web
  client is React, the apps are Compose Multiplatform; if the web invents its own
  breakpoints, the two drift apart within a release. Align them with Material 3's
  window size classes — compact below 600 dp, medium 600–839 dp, expanded from
  840 dp — so the same token set drives both. Deliver them as part of the token
  set, not as numbers buried in stylesheets.
- **Width is not the whole question; the pointer is.** A tablet with a keyboard
  is expanded-width *and* touch. A touch laptop is both at once. So: **density
  follows the width, hit-target size follows the pointer** (`pointer: coarse`
  keeps 44 × 44 px however wide the screen), and nothing important may be
  hover-only — `hover: none` must not hide a function. Say what the coarse and
  fine variants of each component are.
- **Orientation is a state, not an edge case.** A tablet is rotated mid-task, in
  the middle of a form, with unsaved input. Scanning happens in both
  orientations.
- **One layout system, not three drawings.** For each surface, state what
  collapses, what moves and what is deliberately dropped as the width shrinks —
  and what the wide end does with the extra room. A table stretched across 2560 px
  is a worse table, not a better one; say where the line length stops growing.

### Dark is the default, everywhere

**Dark is the shipped default on every platform, regardless of the operating
system setting. Light is something the user switches on.** That is a deliberate
decision, not an oversight, and it has consequences you should design for:

- **Design dark first and let light follow**, not the usual other way round.
  Light must be a genuine second theme, not dark with the values flipped.
- **The light toggle has to be easy to find** — in the user menu, not three
  levels into a settings page. Overriding a platform preference is only
  defensible if reversing it takes one obvious click. There is also a real
  accessibility reason: light text on a dark ground is *harder* to read for
  people with astigmatism, who see halation around the glyphs. Dark-by-default
  is right for the cellar and wrong for some readers, and the escape hatch is
  what squares that.
- **No flash of the wrong theme.** The choice is remembered and applied before
  the first paint.
- Set `color-scheme: dark` so native form controls, scrollbars and the caret
  follow, and give the installable PWA a dark `theme_color` and
  `background_color` so the splash and the browser chrome match rather than
  flashing white on launch.

The reason behind the choice: this is a tool used in cellars, garages and attics,
at arm's length, often in poor light. Dark is not a preference here — it is what
the screen should be in the place the work happens.

### Tone: utilitarian, not playful

Operational criteria, so this is checkable rather than a matter of taste:

- **No illustrations, no mascot, no decorative imagery.** The only pictures in
  this application are the user's own photos of their own belongings.
- **No gradients, glows or shadows as decoration.** Elevation may be expressed,
  but through one or two restrained steps, not a lighting scheme.
- **No animation that is not feedback.** A scan confirmation, a state change, a
  loading indicator — yes. Anything that exists to delight — no. Honour
  `prefers-reduced-motion` throughout.
- **Whitespace is not luxury here.** Inventory means long lists; a screen that
  shows 8 rows where it could show 20 is a worse screen. Provide a **comfortable
  and a compact density**, and make compact genuinely usable rather than a
  punishment.
- **One accent colour**, used for the primary action and nothing else. Exactly one
  filled primary button per context; everything else is quiet.
- Square or barely-rounded corners. Nothing bubbly.

Think closer to a well-made technical instrument than to a consumer app. Legible,
dense, calm, slightly severe. It should look like it will still be here in ten
years.

### Hard technical constraints

These are not preferences. A design that violates them cannot be built.

| Constraint | What it rules out |
|---|---|
| **A strict Content Security Policy with no `unsafe-inline` and no `unsafe-eval`.** | Any approach that injects `<style>` at runtime. In practice: not emotion, not styled-components. Design for **static CSS with custom properties** — plain CSS, CSS Modules or a zero-runtime approach. |
| **No external CDN of any kind.** No font, stylesheet, icon set or script may be loaded from a third-party host. | Google Fonts by link. Icon packs by CDN. Everything is self-hosted and shipped with the application. |
| **The same tokens must drive a second platform.** The web client is React 19 + TypeScript; native Android and iOS apps in Kotlin Multiplatform with Compose Multiplatform follow later. | A token set expressed only as CSS. Deliver a platform-neutral source that can generate both. |
| **Two languages, and one of them is German.** | Any layout sized to English. Real strings in this product include `Wiederbeschaffungswert`, `Lagerortkategorie`, `Mindesthaltbarkeitsdatum`, `Benachrichtigungseinstellungen`. A label column that fits "Value" will break. Show your components with the long German word, not with lorem ipsum. |
| **WCAG 2.2 AA is the target**, verified automatically in CI. | Colour pairs that do not meet contrast, focus states that rely on colour alone, touch targets under 44 × 44 px, meaning carried by colour only. State the measured contrast ratio for every foreground/background token pair, in both themes. |
| **Offline is a normal state, not an error.** | Treating "no connection" as a failure screen. It is the expected condition in a cellar. |

### The four surfaces that will break a naive design system

Please design these first; they are the proof that the system works. The rest of
the application is ordinary by comparison.

**1. The scanner.** Full-screen camera, used one-handed in the dark. Needs: a
framing indicator, a torch toggle, a continuous mode that scans code after code
without leaving the screen, and per-scan feedback that works when the phone is at
arm's length and the user is not looking at it closely — visual, audible and
haptic. Show what a successful scan, a duplicate scan (debounced), an unrecognised
code and a "this label is not assigned yet" look like. Every control must sit
within thumb reach of a hand holding the phone at the bottom.

**2. A form generated from a type definition.** This is the unusual one. Each
installation defines its own item types and their fields, so **there is no fixed
"item form"** — there is a renderer that must handle any combination of these
field types, grouped into sections, with conditional visibility:

`text` · `multiline` · `integer` · `decimal` · `money` (amount + currency) ·
`boolean` · `date` · `datetime` · `enum` · `multi-enum` · `url` · `email` ·
`quantity` (value + unit) · `reference` (to another item or location) ·
`secret` (hidden, revealed only with a second factor) · `file`

Design each field type in: empty, filled, focused, invalid with a message,
disabled, and read-only. Then show them assembled into a realistic form — both a
short type (a book: 6 fields) and a long one (a power tool: 20 fields across 4
groups) — on a phone, on a tablet and on a desktop. The 20-field form is the one
that exposes whether the tablet has its own layout or just inherits one.

**3. A dense item list.** Thousands of rows. Needs a table view and a photo-grid
view, faceted filters, multi-select for bulk actions, sorting, and a column set
that the user chooses. Show it with a photo, without a photo, and with a
long German item name that does not fit.

**4. Conflict resolution.** Two devices edited the same item offline. The user
sees the common ancestor, their own version and the server's version, and decides
**field by field**. This screen is the one that has to make a confusing situation
feel manageable; it is also the one users will meet at the worst moment. It must
work on a phone — three versions side by side is a desktop idea, and the phone
layout is the real design problem here.

### The remaining surfaces

Once the four above hold, cover: location tree navigation and a location's
contents · item detail view · search with facets and saved searches · the field
and type editor (the administrative meta-UI) · label template editor with a
to-scale print preview · print job status · stocktake mode with its discrepancy
report · the device list with sync status · tenant switching · login with a
second factor and the "confirm again" prompt for sensitive actions · a value and
expiry overview.

### States that are first-class, not edge cases

Most systems treat these as afterthoughts. Here they are daily:

- **Offline** — working, with a clear indicator, not an error
- **Pending upload** — a photo that exists only on this device yet
- **Conflict** — this record has two versions awaiting a decision
- **Degraded** — search is running on the fallback and is less precise
- **No permission** — this field exists but is not for you. Must look
  *different* from empty and different from an error. Never a masked value:
  a row of dots tells the viewer the length of the secret.
- **Not yet assigned** — a printed label with no item behind it
- Plus the ordinary set: loading, empty, error, no results, first run

### What to deliver

1. **A token set**, platform-neutral (W3C Design Tokens format or equivalent
   JSON), from which both CSS custom properties and a Compose `Theme.kt` can be
   generated. Semantic names — `surface.raised`, `text.muted`, `border.subtle`,
   `state.conflict` — never raw colour names at the point of use. Complete for
   **both** themes.
2. **A colour system** with the measured contrast ratio for every meaningful
   pair, in light and dark. Include the semantic states: success, warning,
   danger, conflict, offline, degraded, disabled.
3. **One inviolable exception to the theme**: machine-readable codes are always
   **dark modules on a light ground**, in both themes. An inverted QR code is not
   reliably readable by every scanner, and a printed label is dark-on-light
   whatever the screen does. The label preview therefore keeps its paper-white
   ground in dark mode — it is a preview of something printed, not a UI surface
   — while the chrome around it follows the theme. Design that boundary
   deliberately; it will look odd if it is an accident.
4. **A type scale** that survives German compounds, with the self-hosted family
   named and a reason for the choice. Prefer a face with good digit legibility —
   this application is full of serial numbers, quantities and codes — and
   unambiguous `0`/`O` and `1`/`l`/`I`, because users read codes off labels.
5. **Spacing, sizing and density**: one scale, two densities, stated touch target
   minimums — plus the **breakpoints and layout rules** as tokens, with the
   coarse- and fine-pointer variants of each component named.
6. **A component canon** with every state drawn: buttons (one primary per
   context), inputs per field type, select, combo box, checkbox, radio, switch,
   date picker, tag, chip, badge, table, tree, card, modal, drawer, toast,
   inline message, tooltip, tabs, breadcrumb, pagination, skeleton, avatar,
   progress, empty state, the scan overlay.
7. **Icons: Lucide** (<https://github.com/lucide-icons/lucide>, 1600+ icons).
   The set is **given** — do not choose one, and do not draw icons by hand. It
   ships self-hosted as SVG; no CDN, and not as an icon webfont, whose private
   use area glyphs a screen reader either skips or reads out as a stray
   character.

   Its drawing model is the one to design against: a **24 × 24 grid**,
   `fill="none"`, `stroke="currentColor"`, `stroke-width="2"`, round caps and
   joins. Two things follow.

   **`stroke-width` is a real design token here, so decide it rather than
   inheriting the default.** 2 px on a 24 px grid is tuned for icons rendered
   around 24 px; at 16 px in a dense table row the same stroke reads heavy, and
   at 32 px it reads thin. State the stroke width per icon size, and match it to
   the weight of the text it sits beside — an icon should not look bolder than
   its label.

   **Check the optical weight in dark mode specifically.** A light stroke on a
   dark ground blooms and reads heavier than the same stroke dark-on-light. Since
   dark is our default, that is the case to tune first, not the afterthought.

   Lucide is outline-only — there is no filled twin of each icon. Where a
   component needs to distinguish selected from unselected or active from
   inactive, that distinction must come from **colour, background or weight**,
   not from swapping to a fill variant that does not exist. Say how.

   If an icon this application needs has no counterpart in the set, **name it in
   a list** rather than substituting something approximate.

8. **The rules in writing.** When does something become a modal versus a drawer.
   Where does the primary action sit on a phone versus a desktop. How is
   destructive confirmation handled. How does focus move. What happens to a long
   string. A design system without written rules is a picture collection.

### How I will judge it

- Does it work one-handed on a phone at 375 px, in the dark, with the primary
  action reachable by a thumb?
- Does the tablet have a layout of its own — in portrait *and* landscape — rather
  than a stretched phone or a cramped desktop?
- Does the generated form look deliberate for both a 6-field and a 20-field type,
  without anyone having designed either?
- Does every colour pair pass AA, in both themes, with the number stated?
- Does nothing require runtime style injection or an external host?
- Do the German strings fit?
- Does it look like a tool rather than an app?

### What not to do

- Do not design a marketing page, a landing page or an onboarding carousel.
- Do not invent a brand identity beyond a single restrained mark and one accent
  colour. This is a self-hosted tool, not a product with a market.
- Do not use a UI kit's default look unexamined — if you build on one, say which
  and what you changed.
- Do not show only the happy path. A design system is judged on its error,
  empty and degraded states.
- Do not pick colours that only work in one theme and then dim them for the
  other. Design both; verify both.
