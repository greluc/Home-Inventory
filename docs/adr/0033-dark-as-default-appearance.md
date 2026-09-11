# ADR-0033 — Dark is the default appearance, on every platform

**Status:** Accepted · **Date:** 2026-09-11
**Records:** `REQ-NFR-040`, which was decided and implemented in the design brief
without an ADR. Written now so the reasoning survives the first time someone asks
why the system ignores the operating system setting.

## Context

Every other appearance decision in this project follows the platform. This one
does not: `REQ-NFR-040` ships **dark regardless of the OS preference**, and makes
light an opt-in. Overriding a user's system-level choice is the kind of decision
that looks like an oversight a year later, so it needs a record.

The driver is posture, not taste. The primary place this software is used is a
cellar, a garage, an attic or a storage unit — at arm's length, one-handed, in
poor light, often with the phone as the only light source besides the torch used
for scanning. A white screen at full brightness in the dark is genuinely worse
there: it destroys dark adaptation, it is the wrong thing to point at a shelf, and
it is what most inventory software does.

## Options

| Option | For | Against |
|---|---|---|
| Follow `prefers-color-scheme` | The conventional, least surprising choice; respects a preference the user already expressed | The preference was expressed for a desktop at a desk, not for the posture this tool is used in. Most users never change it from the platform default |
| **Dark by default, light opt-in** | Right for the primary posture; one obvious toggle for everyone else | Overrides an explicit platform preference, and is worse for some readers — see below |
| Light by default | Conventional and safest for legibility | Wrong for the posture the product is designed around |

## Decision

**Dark is the shipped default on web and apps, regardless of the operating system
setting.** Light is a per-user choice, stored server-side with the user profile
and applied **before first paint**.

Three conditions make the override defensible, and all three are binding:

| Condition | Requirement |
|---|---|
| The escape hatch is obvious | The toggle sits in the user menu, not inside a settings sub-page |
| The choice is remembered and never flashes | Applied before first paint; a light-preferring user never sees a dark frame. `color-scheme` is set so native controls, scrollbars and the caret follow; the PWA manifest carries a matching `theme_color`/`background_color` so the splash does not flash white |
| Both themes are designed, not derived | Light is a genuine second theme, not dark with inverted values. Every token pair carries a measured contrast ratio in **both** themes (`REQ-NFR-038`) |

## Rationale

The accessibility argument cuts **against** this decision and is the reason the
escape hatch is a condition rather than a nicety: light text on a dark ground is
harder to read for people with astigmatism, who see halation around the glyphs.
Dark-by-default is right for the cellar and wrong for some readers, and a default
that is wrong for some readers is only acceptable when reversing it takes one
obvious click. That is why "the toggle is easy to find" is written as a
requirement and not as advice.

## Consequences

- **Both themes are first-class work**, verified by `axe` in CI in both
  (`REQ-NFR-038`). A design that only works in one and is dimmed for the other
  fails review.
- **One inviolable exception:** machine-readable codes are always dark modules on a
  light ground, in both themes, and the label preview keeps its paper-white ground
  in dark mode (`REQ-LBL-014`). An inverted QR code is not reliably readable, and a
  printed label is dark-on-light whatever the screen does.
- The theme choice is part of the user profile and therefore travels with the
  account across devices — and is subject to `REQ-PRIV-001`, which allows exactly
  e-mail, display name and language as mandatory data. The theme is optional data
  with a default, not a new mandatory field.
- Should usage ever show that most users switch to light immediately, that is the
  signal to revisit — and it is measurable without telemetry, because the setting
  lives in our own database, not in an analytics service (`REQ-PRIV-002`).
