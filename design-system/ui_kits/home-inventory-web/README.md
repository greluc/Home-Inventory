# UI kit — Home Inventory web client

A click-through recreation of the React 19 PWA described in
[`web/README.md`](https://github.com/greluc/Home-Inventory/blob/main/web/README.md)
and the design brief. **The repository contains no implementation yet** (it is in
its design phase), so these screens are built from the written architecture —
the data model, the sync protocol, the label model and the scan modes are taken
from the docs, not invented.

## What the chrome does

The dark bar at the top is the kit's own harness, not part of the product, and is therefore in
English. **Everything below it is in German on purpose** — the screens render the shipped German UI,
and its compounds are the width case the layout has to survive. The mock data is one of the two
entries on the closed carve-out list of `REQ-CON-012` / `REQ-CON-014`; the other is
[`guidelines/type-german.html`](../../guidelines/type-german.html).

| Control | Why it is here |
|---|---|
| **Screen** | 13 surfaces, including all four that the brief calls the hard ones |
| **Phone 390 · Tablet portrait 834 · Tablet landscape 1194 · Desktop** | the four window size classes, so the *tablet's own layout* can be compared against the phone and the desktop rather than described |
| **Comfortable / Compact** | the two densities, live |
| **Offline** | toggles the persistent status strip and the unsent-changes counter |
| **Dark / Light** | dark is the default; light is one click away, exactly as the product requires |

## Screens

| Screen | What it proves |
|---|---|
| Item list | table ↔ photo grid, facets, multi-select, bulk bar, pagination, and the long German name that does not fit; below 840 px the table becomes a two-line list rather than scrolling sideways |
| Item detail | read-only rendering of every field type, with the conflict and pending-upload banners |
| Generated form | switch between a 6-field book and a 20-field power tool across 4 groups — nobody designed either |
| Scanner | four results (success · duplicate · unknown · unassigned), five modes, torch, continuous, thumb-zone controls |
| Conflict resolution | field by field, phone-first; auto-merged fields say why they are not being asked |
| Locations | tree beside contents at expanded width; tree above contents below it |
| Search | facets, saved searches, the degraded-search strip |
| Field and type editor | the administrative meta-UI, plus the online-only warning |
| Labels | to-scale print preview on paper-white ground, plus print-job states |
| Stocktake | run progress and the discrepancy report |
| Devices | sync state, unsent changes, local storage budget, tenant switching |
| Overview | value and expiry |
| Sign-in | password → second factor → the "confirm again" prompt for a secret |

## Caveats

- The camera feed is a flat gradient; the QR is a deterministic block pattern, not a real encoder.
- Photographs are hatched placeholders — the only real pictures in this product are the user's own.
- **The preview harness is not in this repository.** It loaded React and Babel from `unpkg`, and
  this project contacts no third-party host for anything ([ADR-0035](../../../docs/adr/0035-design-system.md),
  `REQ-PRIV-015`). The component sources it rendered are all here; what is missing is a way to
  click through them without a build. The design system itself ships no external dependency:
  fonts and icons are in `assets/`, and every component is static CSS plus a plain React function.
