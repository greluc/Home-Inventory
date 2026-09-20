<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0070 — A document is described, not programmed, and rendering it is a plugin's job

**Status:** Accepted · **Date:** 2026-09-20

**Amends:** [ADR-0064](0064-the-ports-a-plugin-implements-are-apache.md) (a sixteenth port)

**Depends on:** [ADR-0020](0020-configuration-as-data.md),
[ADR-0026](0026-core-outbound-via-plugins.md)

## Context

`REQ-LIFE-016` asks for an insurance report "as a PDF and as a table". The table is
ordinary; the PDF is not, because the core has no PDF library and adding one is a decision
rather than a dependency bump — fonts, layout engines and their CVEs come with it, into the
image that holds the data.

Two other things were already waiting on the same answer. `LabelRenderer`'s contract says
"the PDF sheet renderer is in the core image", written before there was one. And a plugin
that wants to produce a document today has no way to: it would need its own PDF library, its
own fonts, and its own opinion about what a document looks like.

The owner's decision on 2026-09-20 was that **a plugin renders it and the core serves the
table**. This record is what that decision turned into.

## Decision

**A sixteenth port, `DocumentRenderer`, and a document model that is data.**

Anything in this system that has to become a document describes it as a `Document` and asks
whichever renderer the operator installed. The insurance report is the first caller and the
port is deliberately not about insurance reports: a second report, a printed inventory, a
handover note for a lent item, a label sheet — none of them needs a new port, a new plugin
or a PDF library in the core.

### The document model is a closed set

`Block` is sealed: a heading, a paragraph, a table, a list of labelled facts, a picture, a
page break, a spacer. There is no template language here and there will not be one.
[ADR-0020](0020-configuration-as-data.md) says the configuration surface must not become a
programming language, and a document format with conditionals in it is that language
arriving through the back door — a renderer that evaluates its input is a scripting engine
with a font.

The division that follows is the one that makes this port worth having:

> **The core decides what a document says. The plugin decides what it looks like.**

A renderer chooses fonts, spacing, how a table breaks across pages, what a heading weighs.
It does not decide that a figure is worth showing, because it is not the one that knows. A
renderer written for one report therefore renders every later one without being touched.

### Values arrive formatted

Money is a string in the document model. The core knows its currency, its scale and the
rule that a total is never mixed across currencies (`REQ-LIFE-017`); a renderer knows none
of that, and a renderer formatting money would be a second place for that rule to live and
a second place for it to be wrong.

### Pictures travel with the document

An `Image` carries its bytes. The core hands out no URL for a plugin to fetch
(`REQ-SEC-034`) and a renderer has no route to the blob store, so a reference would be a
reference to nothing. It makes a document with photographs large, which is why both
directions of `Render` stream — an insurance report of a household carries a picture per
item, and that is the case this exists for.

## Options

| Option | PDF library in core | A second report needs | A plugin can produce documents |
|---|---|---|---|
| **A `DocumentRenderer` port with a described document** | no | nothing | yes, through the host channel ([ADR-0071](0071-the-core-answers-plugins-on-one-channel.md)) |
| A PDF library in the core image | yes, with its fonts and its CVEs | code in the core | no |
| A report-shaped port (`ReportRenderer`) | no | a new message, and often a new port | only for reports |
| An HTML template rendered to PDF | no | nothing | yes — and the template is a program, which ADR-0020 forbids |

The report-shaped port was the obvious first design and is the one this record rejects. A
port that takes "an insurance report" has to grow a message for every later document, and
every renderer has to be updated before that document can be printed. A port that takes "a
document" does not.

## Consequences

- **An instance with no renderer installed produces no PDF**, and says so: the table is
  core, and asking for a PDF without a renderer is `409` with a problem document naming the
  missing capability rather than an empty file. The one document somebody needs after a fire
  requires an installation step, which is the cost the owner accepted when choosing this over
  a library in the core image.
- **`REQ-PLG-001` names sixteen ports**, and `PortCatalogueTest` counts them. Six of the
  sixteen still belong to stage 2 or 3 features; this one is used at stage 1.
- **A new kind of block is a breaking change.** `buf breaking` enforces it, which is the
  point of the set being closed rather than open: a renderer written today handles every
  document this major version will ever describe.
- **The same model serves both directions.** A plugin describing a document describes it the
  same way whether the core asked for it or the plugin did
  ([ADR-0071](0071-the-core-answers-plugins-on-one-channel.md)), so there is one document
  format in the system rather than two.
