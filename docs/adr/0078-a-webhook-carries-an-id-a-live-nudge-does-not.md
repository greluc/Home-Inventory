<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0078 — A webhook carries an id; a live nudge does not

**Status:** Accepted · **Date:** 2026-09-21

**Relates to:** `REQ-API-010` (webhooks), `REQ-API-011` (server-sent events),
[ADR-0026](0026-core-outbound-via-plugins.md) (the core makes no outbound call)

## Context

Two features carry the same domain events outward and were built a day apart, which is
exactly the situation in which two different answers get given to one question by accident.

`REQ-API-011` was built first. An open browser view is nudged with a **kind and a moment**
— `item`, `2026-09-21T09:14:02Z` — and no identifier at all, so the view re-reads the list
it is showing through the ordinary API. The reason is stated in `LiveChanges`: a member's
role may be **scoped to part of the location tree** (`REQ-TEN-007`), and sending the id
would tell them that a thing they may not see exists and has just changed.

`REQ-API-010` needs the same events delivered to a URL. The obvious move is to keep the
rule and send a kind — and it makes the feature close to useless. A receiver mirroring the
inventory into another system, which is what webhooks are for, would have to re-read
everything on every nudge to find out what changed.

So the question is whether the two surfaces get one rule or two, and if two, why.

## Options

| Option | A live view gets | A receiver gets | What it costs |
|---|---|---|---|
| **Kind for a view, type + moment + id for a receiver** | as today | enough to fetch the one thing | Two payload shapes, and a reason that has to be written down — this record. Chosen |
| Kind and moment for both | as today | a reason to re-read the whole inventory on every change | The feature exists and nobody would use it |
| Type, moment and id for both | an id a scoped member may not be entitled to | as chosen | Leaks across a location scope, in the surface with the most listeners |
| The whole entity for a receiver | as today | a copy of the row | A second copy of the inventory on a server this deployment knows nothing about, going stale, outside every permission this system has. Also unbounded: the payload would be whatever the type system happens to hold |

## Decision

**A webhook delivery carries the event type, the moment and the subject's id. A live nudge
carries the kind and the moment. Neither carries a name, a field, or the event's own
payload.**

What makes the two different is **who is at the other end**, and it is the only thing that
does:

- an SSE stream is held by a **member of the tenant**, whose role may be confined to a
  subtree they must not learn beyond;
- a webhook target is a URL a **tenant administrator typed in**, together with the secret
  it is signed with. `notification:webhook:write` is whole-tenant (`Permission.wholeTenant`),
  so a scoped membership cannot create one however senior its role — the same rule
  [ADR-0068](0068-an-export-opens-what-its-requester-may-read.md) applies to an export.

The id is therefore not a leak: it is delivered to somebody who could have asked for the
whole inventory.

**What stays the same on both sides is the refusal to carry data.** The receiver re-reads
through the ordinary API with an ordinary token, which applies the ordinary permissions and
the ordinary audit. A payload that carried the item would put the tenant's data somewhere
this deployment can no longer reach, and would make every future field a decision about
what a receiver may see. `WebhookTargetIT.aChangeQueuesADeliveryThatNamesNoNames` asserts
the absence directly: it creates an item with a unique name and requires that name not to
appear in the queued document, and `EventStreamIT` does the same for the live stream.

## Consequences

- The document is `{"type":…,"at":…,"id":…}` and is nested in `text` inside the plugin's
  own signed envelope, so a receiver parses **one shape** for a notification and an event
  alike.
- `TenantScopedEvent` gains `subjectId()` beside `eventType()`: what the event happened
  *to*, which for `tag.assigned` is the **thing that now wears the tag** rather than the
  tag — a receiver told a tag changed wants to know which thing.
- An event whose subject a receiver is then refused is possible in principle and is not a
  new hole: the id is a UUID, and what it opens is decided where reading is decided
  (`REQ-SEC-022`…`028`).
- A future event that is **only** meaningful with its data — none exists today — needs a
  new decision, not a wider payload here.
