# website/

The project website, published to GitHub Pages at
<https://greluc.github.io/Home-Inventory/> by
[`.github/workflows/pages.yml`](../.github/workflows/pages.yml).

## What is here

Five pre-rendered pages — `index`, `features`, `roadmap`, `install`, `docs` —
plus `site.css` and `site.js`. Each page is complete in its HTML: no framework,
no build step at view time, nothing fetched from another host.

The first delivery rendered in the browser and fetched React and Babel from
`unpkg.com`; it was not taken over. This is the pre-rendered replacement that was
asked for in
[`docs/design/website-prerender-request.md`](../docs/design/website-prerender-request.md),
kept for the same reason the design system brief is kept.

### JavaScript is an enhancement, never a requirement

`site.js` is about 90 lines of plain JavaScript with no dependencies. Every page
reads completely without it:

| Enhancement | Without JavaScript |
|---|---|
| Appearance toggle | dark, the shipped default |
| Runtime tabs on the install page | all three paths shown, one after another |
| Conflict picker on the features page | all three versions shown, none chosen |

Dark is applied before first paint by one inline script in `<head>`, reading
`localStorage["homeinv.theme"]` — the same approach the application uses.

## What belongs here, and what does not

The pages and whatever CSS and hand-written JavaScript they need. Two things are
deliberately absent:

- **No copy of the design system.** It lives in [`design-system/`](../design-system/)
  and the workflow copies it to `design-system/` inside the build output, which
  is why the pages reference `design-system/styles.css` and find nothing beside
  them in this directory. The first delivery bundled its own copy under a UUID
  path; that copy would have drifted from the first token change onwards
  ([ADR-0035](../docs/adr/0035-design-system.md)).
- **No third-party resources.** REQ-PRIV-015 has no exception, and it binds this
  site as much as the application: a page advertising a self-hosted,
  privacy-respecting tool must not hand every visitor's address to a CDN. The
  workflow greps the assembled output for resources loaded from another host and
  fails rather than trusting review. Hyperlinks to other sites are fine — a
  visitor clicking one is a choice, a fetch is not.

## Rules the pages inherit

| | |
|---|---|
| **Dark is the default** | Applied before first paint from `localStorage["homeinv.theme"]`, never from `prefers-color-scheme`. Light stays a visible toggle (REQ-NFR-040). |
| **Relative paths only** | So the project page at `/Home-Inventory/` works unchanged and a custom domain later needs no edits. |
| **British English** | REQ-CON-014. English only — the application will ship a German UI, this site does not. |
| **Claims trace to the repository** | Every statement comes from `docs/`. The install commands stay labelled as placeholders until a release exists. |
| **AA in both themes, 360 px upward** | The same bar as the application. |

## Turning Pages on

One setting, once: **Settings → Pages → Source: GitHub Actions**. Without it
`deploy-pages` has nowhere to publish to and the workflow fails at the last step.

## Viewing it locally

Opening `index.html` from the filesystem shows the pages **without the design
system**, because `design-system/` only sits beside them in the build output.
Assemble it the way the workflow does:

```bash
rm -rf .site && mkdir .site && cp -r website/. .site/ && rm .site/README.md
mkdir .site/design-system && cp design-system/styles.css .site/design-system/
cp -r design-system/tokens design-system/assets .site/design-system/
(cd design-system && find components -name '*.css' -print0 | tar --null -cf - -T -)   | (cd .site/design-system && tar -xf -)
python -m http.server -d .site 8080
```
