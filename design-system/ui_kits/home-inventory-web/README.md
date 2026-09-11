# UI kit — Home Inventory web client

A click-through recreation of the React 19 PWA described in
[`web/README.md`](https://github.com/greluc/Home-Inventory/blob/main/web/README.md)
and the design brief. **The repository contains no implementation yet** (it is in
its design phase), so these screens are built from the written architecture —
the data model, the sync protocol, the label model and the scan modes are taken
from the docs, not invented.

## What the chrome does

The dark bar at the top is the kit's own harness, not part of the product:

| Control | Why it is here |
|---|---|
| **Screen** | 13 surfaces, including all four that the brief calls the hard ones |
| **Telefon 390 · Tablet hoch 834 · Tablet quer 1194 · Desktop** | the four window size classes, so the *tablet's own layout* can be compared against the phone and the desktop rather than described |
| **Komfortabel / Kompakt** | the two densities, live |
| **Offline** | toggles the persistent status strip and the unsent-changes counter |
| **Dunkel / Hell** | dark is the default; light is one click away, exactly as the product requires |

## Screens

| Screen | What it proves |
|---|---|
| Artikelliste | table ↔ photo grid, facets, multi-select, bulk bar, pagination, and the long German name that does not fit; below 840 px the table becomes a two-line list rather than scrolling sideways |
| Artikeldetail | read-only rendering of every field type, with the conflict and pending-upload banners |
| Erzeugtes Formular | switch between a 6-field book and a 20-field power tool across 4 groups — nobody designed either |
| Scanner | four results (success · duplicate · unknown · unassigned), five modes, torch, continuous, thumb-zone controls |
| Konfliktlösung | field by field, phone-first; auto-merged fields say why they are not being asked |
| Lagerorte | tree beside contents at expanded width; tree above contents below it |
| Suche | facets, saved searches, the degraded-search strip |
| Feld-/Typeditor | the administrative meta-UI, plus the online-only warning |
| Etiketten | to-scale print preview on paper-white ground, plus print-job states |
| Inventur | run progress and the discrepancy report |
| Geräte | sync state, unsent changes, local storage budget, tenant switching |
| Übersicht | value and expiry |
| Anmeldung | password → second factor → the "confirm again" prompt for a secret |

## Caveats

- The camera feed is a flat gradient; the QR is a deterministic block pattern, not a real encoder.
- Photographs are hatched placeholders — the only real pictures in this product are the user's own.
- React and Babel load from unpkg **in the preview harness only**. The design system itself ships
  no external dependency: fonts and icons are in `assets/`, and every component is static CSS
  plus a plain React function.
