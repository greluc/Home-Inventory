---
name: home-inventory-design
description: Use this skill to generate well-branded interfaces and assets for Home Inventory, a self-hosted inventory application, either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for protoyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files.
If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code, you can copy assets and read the rules here to become an expert in designing with this brand.
If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts _or_ production code, depending on the need.

## Non-negotiables in this system

1. **Dark is the default**, applied from a stored preference before first paint — never from `prefers-color-scheme`. Light is a genuine second theme, one click away in the app bar.
2. **One accent colour**, for the primary action, focus, links and determinate progress. Exactly one filled primary button per context.
3. **Machine-readable codes and label print previews stay dark-on-light in both themes.** Use `--code-*`; never re-theme them.
4. **No illustrations, no mascot, no decorative gradients, no glows.** The only pictures are the user's own photos of their belongings.
5. **No animation that is not feedback**, and every duration collapses under `prefers-reduced-motion`.
6. **Static CSS only** — the product runs under a strict CSP with no `unsafe-inline`. No CSS-in-JS.
7. **Nothing from a CDN.** Fonts and Lucide SVGs are in `assets/`.
8. **Size layouts with German**: `Wiederbeschaffungswert`, `Lagerortkategorie`, `Mindesthaltbarkeitsdatum`, `Benachrichtigungseinstellungen`.
9. **Density follows the width; hit-target size follows the pointer** (44 px under `pointer: coarse`, whatever the width).
10. **Never a masked value for a secret** — say "Keine Berechtigung" with a hatch, because a row of dots leaks the length.

## Where things are

| Need | File |
|---|---|
| Tokens (platform-neutral) | `tokens/tokens.json` |
| Tokens (CSS) | `styles.css` → `tokens/*.css` |
| Written rules | `readme.md` §8 |
| Layout per device class | `readme.md` §6 |
| Components | `components/<group>/<Name>.jsx` + `.d.ts` + `.prompt.md` |
| Full product recreation | `ui_kits/home-inventory-web/*.jsx` — the sources; the `index.html` harness is not in this repository because it loaded React from a CDN |
| Foundations at a glance | `guidelines/*.html` |
