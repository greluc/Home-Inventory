# ADR-0023 — Push through Firebase and APNs as a plugin, with a content-free payload

**Status:** Accepted · **Date:** 2026-09-11
**Resolves:** O9 in [ADR-0000](0000-open-points.md)

> **Amended by [ADR-0026](0026-core-outbound-via-plugins.md).** The row
> *"Web Push as an equal channel"* below was wrong in one respect: VAPID Web Push
> needs no **account** with Google or Apple, but it does open a **connection** to
> their push endpoints (`fcm.googleapis.com` for Chrome, `web.push.apple.com` for
> Safari). It is therefore not a core channel either — `plugin-webpush` carries it,
> under the same three opt-ins as FCM and APNs. Everything else here stands, and the
> content-free payload now applies to Web Push as well.

## Context

The apps are to receive push notifications (warranty expiry, a due return, a
maintenance date, minimum stock, security-relevant account events). On Android
and iOS the path with the best delivery rate goes through **Firebase Cloud
Messaging** and the **Apple Push Notification service**.

That is in tension with two documented principles:

- [12 §12.11](../architecture/12-security.md): no telemetry, no analytics
  services, all data stays on the operator's instance.
- [12 §12.2](../architecture/12-security.md) / REQ-PRIV-003: **the core has no
  outbound route to the internet**; external calls go exclusively through
  plugins.

## Decision

**Firebase and APNs are supported** — but in a way that preserves both
principles:

| Decision | |
|---|---|
| **As a plugin, not in the core** | `plugin-push-fcm` and `plugin-push-apns` implement the existing `NotificationChannel` port. They run in the `plugins` network segment with a fixed target list (`fcm.googleapis.com`, `api.push.apple.com`). The core keeps its "no outbound route" rule **unchanged**. |
| **A content-free payload** | The push message contains **no** inventory data — no item name, no location, no amount, no tenant name. What is transmitted is an identifier and a category (`reminder`, `security`, `conflict`). The app then fetches the text through the authenticated API. |
| **Explicit consent** | Off until the operator installs the plugins **and** the tenant administrator grants the `network:outbound` capability **and** the individual user switches push on in their settings. Three levels, each a deliberate step. |
| **Web Push as an equal channel** | For the PWA, VAPID Web Push is available, which needs no account with Google or Apple. Anyone who does not need native apps does not need the third-party plugins. |
| **Fallback without push** | Everything keeps working without push: e-mail notification and fetching when the app is opened. Push is convenience, never a precondition. |

## Rationale

The conflict cannot be argued away: a push notification to an iPhone goes through
Apple's infrastructure, full stop. What can very much be shaped is **how much**
leaks and **who decides**.

The content-free payload is the real lever here: Google and Apple see *that* a
device belonging to a particular operator receives a notification, and roughly of
what kind — not *what it is about*. Without that rule the message would read
"Warranty for Bosch GSR 18V expires", and part of the inventory would be sitting
with a third party.

Implementing it as a plugin is not a sleight of hand but exactly the case the
plugin architecture exists for: an extension point with network access, a target
list, a capability check and revocability.

## Consequences

- **REQ-PRIV-003 remains valid unchanged** — the core still gets no outbound
  route. The exception lives in the plugin segment, where it belongs.
- **The data protection section is extended, not softened**: Google and Apple are
  named as processors, the scope of the transmission (device token, timestamp,
  category) is disclosed, and the use has to be recorded in the processing
  register. A template ships with the documentation.
- **Device tokens** are personal data: stored encrypted
  ([ADR-0019](0019-sensitive-field-encryption.md)), deleted when a device is
  deregistered, expired after 180 days without contact.
- Operators need their own accounts with Google and Apple for the native apps.
  That is named as a precondition in the operations documentation, **not**
  presumed.
- On receipt the app first shows a neutral message and replaces it with the real
  content once it has fetched it. Without a valid session the neutral message
  stays — a stolen device reveals nothing on the lock screen.
- Security-relevant account events additionally always go by e-mail
  (REQ-NOTI-004), because they must not depend on a third-party service.
