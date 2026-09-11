# Website — the request for a pre-rendered build

The first website delivery (2026-09-11) renders in the browser and fetches React
and Babel from `unpkg.com`. This document is the request for the variant its own
README offered instead: pre-rendered, with no external script.

It is kept here for the same reason the design system brief is kept — the ask
explains the result, and the next person to change the site needs to know which
constraints were deliberate.

**How to use it:** paste everything below the line into Claude Design. It is
written to stand on its own.

---

## The request

You built the static site for **Home Inventory** (five pages: index, features,
roadmap, install, docs). Your own `site/README.md` notes that the pages render in
the browser and fetch React and Babel from `unpkg.com`, and offers a pre-rendered
build with no external script. **Please produce that build.**

### Why this is not a preference

The site advertises a self-hosted, privacy-respecting inventory application whose
binding requirement REQ-PRIV-015 reads: *no CDN, and no third-party host at all —
for everything, without exception.* A project page that hands every visitor's IP
address, referrer and visit time to a third party, while advertising exactly the
opposite, is the kind of detail somebody finds and quotes. It also contradicts the
repository it links to.

So the one hard requirement is: **the published site loads nothing from any host
other than its own origin.** No `unpkg`, no CDN, no web font by link, no
analytics, no embedded third-party frame.

### What the build must look like

1. **Pre-render every page to plain HTML.** The markup that the component
   runtime produces today should be in the `.html` files as delivered content, so
   a page is complete and readable with JavaScript switched off entirely. No
   React, no ReactDOM, no Babel, no `support.js` transform step at runtime.
2. **Whatever interactivity remains, write as small vanilla JavaScript**, shipped
   from the site's own folder. Concretely that is: the theme toggle, the mobile
   navigation, and any disclosure or tab widget you used. If a piece of
   interactivity is not worth hand-written JavaScript, make it work without any.
3. **Keep the shared header and footer**, but resolve them at build time rather
   than fetching them per page. Five pages with duplicated header markup is the
   right trade here.
4. **Keep dark as the default**, applied before first paint from
   `localStorage["homeinv.theme"]`, exactly as the current build does. The
   inline boot script is correct and should stay. Light stays a visible toggle.
5. **Keep all paths relative**, so the project page at `/Home-Inventory/` works
   unchanged and a custom domain later needs no edits.
6. **Keep `.nojekyll`.**

### Do not bundle the design system

The current delivery ships a full copy of it under
`site/_ds/home-inventory-design-system-<uuid>/`. **Please drop that copy.**

The design system already lives in the repository at `design-system/`, it is
binding (ADR-0035), and a second copy will drift from it the first time a token
changes. The publishing workflow copies `design-system/` into the build output at
a fixed path instead.

So: **reference the design system at `./design-system/…`** relative to the site
root — for example
`<link rel="stylesheet" href="design-system/styles.css">` and icons at
`design-system/assets/icons/<name>.svg`. Assume that directory is present beside
`index.html` when the site is served, and ship nothing from it yourself. The UUID
in the path also just disappears, which is worth having on its own.

### Content rules, unchanged from the first delivery

- **Every claim traces back to the repository** — `docs/architecture`, `docs/adr`,
  `docs/requirements`, `docs/reference`, `deploy/`. Nothing invented, nothing
  aspirational stated as fact.
- **The install commands stay labelled as placeholders.** There is no release and
  no published image; the current wording ("No container image is published and
  no release exists") is right and should survive the rebuild.
- **The status is design phase**, and the site should say so without burying it.
  The repository contains the architecture, the requirements catalogue and the
  design system. There is no code.
- **British English throughout** — `-ise` rather than `-ize`, *colour*, *licence*
  as the noun, *catalogue*, *behaviour*. This is a project rule (REQ-CON-014).
  The exception is technical identifiers, which keep the spelling their
  technology uses.
- **English only.** The application will ship a German UI, but the repository and
  this site are English.

### Quality bar, unchanged

- WCAG 2.2 AA in **both** themes, and the page must survive at 360 px.
- The design system's own rules apply: one accent colour, no decorative
  gradients or glows, no illustrations, no animation that is not feedback,
  `prefers-reduced-motion` honoured.
- Machine-readable codes, if any appear, stay dark-on-light in both themes.

### How I will check it

- View source: is the content there, or is it an empty root element?
- JavaScript disabled: does every page still read completely?
- Network panel: is every request to the site's own origin?
- `grep -r "unpkg\|cdn\|googleapis" site/` returns nothing.
- Is there any copy of the design system inside the delivery? There should not be.
