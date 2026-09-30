<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0083 — The licence notice travels inside the artifact, and the SBOM is one per artifact

**Status:** Accepted · **Date:** 2026-09-22

**Amends:** [ADR-0034](0034-icon-set-and-no-third-party-hosts.md) — it dated this obligation by
trigger (*"with the first distributed build"*) and named the three places the notice has to reach;
this is how it reaches them, and it corrects one of the three.

## Context

`REQ-CON-013` asks for the third-party licence notices to travel with **every distributed
artifact** — the web bundle, the container image, the app packages — and for a notices view to be
reachable from the running installation beside the version and source link of `REQ-CON-009`.
`REQ-CON-010` asks for a CycloneDX SBOM per release. [ADR-0034](0034-icon-set-and-no-third-party-hosts.md)
had already written down why: a permissive licence — MIT, BSD, ISC, Apache-2.0 — permits the copying
on one condition, that its notice appears **in all copies**, and *"a minified bundle is a copy,
while the `LICENSE` sitting in this repository is not part of it"*.

What existed on 2026-09-22 was one SBOM, for one of nine distributed artifacts, kept as a workflow
artifact for ninety days — and no licence notice anywhere at all.

Three facts about this repository decide the shape of the answer, and none of them was in
ADR-0034:

1. **Six of the nine images are `scratch`.** The two Rust services and the four Rust plugins ship
   one statically linked binary and nothing else, which CI asserts by trying to run a shell in each
   of them ([ADR-0050](0050-blobstore-service-in-rust.md), `ci.yml`). There is no filesystem in
   those images to put a notice file on.
2. **The dependency list and the artifact's contents are two different lists.** Reading the boot
   jar rather than the build found `guava-33.6.0-jre.jar` where the SBOM names
   `guava:33.6.0-android`, twenty-six components that contribute no file at all (BOMs, starters and
   relocation POMs), and `spring-boot-jarmode-tools`, which Spring Boot's plugin puts into the jar
   without it ever being on the runtime classpath — so no SBOM mentions it. On the web side the
   difference goes the other way: a production install resolves `typescript`, because `i18next`
   declares it as a peer dependency, and no line of it reaches a browser.
3. **A licence text is not always a licence.** `byte-buddy`'s `META-INF/LICENSE` opens with a
   sentence about the ASM it bundles and reproduces the Apache licence below it; `logback`'s names
   two licences in two sentences and reproduces neither.

## Decision

### The notice is generated from the artifact, committed, and checked

[`tools/notices.py`](../../tools/notices.py) produces one notice per distributed artifact from
**what that artifact carries** — the jars inside the boot jar, the packages Rollup put into the
chunks, the crates the binary is linked from — and takes the licence identifiers from that
artifact's own SBOM, which is the machine-readable form a jar does not carry.

The files are committed and CI regenerates them and fails on any difference, the same arrangement
[ADR-0080](0080-a-generated-client-is-committed-and-the-document-must-earn-it.md) made for the
generated clients and `deploy/generate.py` for the unit files. Two reasons, and the second is the
one that decided it: a dependency arriving with a new licence is then visible **in the pull request
that brings it**, and a `scratch` image's notice has to exist before the build that compiles it in.

**A component that cannot be attributed fails the tool**, and `ThirdPartyNoticesTest` and
`notices.test.ts` fail on a document that is merely current rather than complete. There is no
silent omission: five components in the application and one in every Rust crate publish no licence
text inside their artifact, and each is answered by a file under `tools/notices/overrides/` holding
the notice verbatim with the URL it was taken from and the date it was read.

### Where each artifact carries it

| Artifact | Where the notice is | Where the SBOM is |
|---|---|---|
| `api` (the application) | a resource in the jar, served at `GET /api/v1/version/notices` | `META-INF/sbom/application.cdx.json`, inside the image |
| `web` (the bundle) | `third-party-notices.json`, served from the document root | `dist/sbom.json`, inside the bundle |
| `plugin-oidc` | a resource in the jar, printed by `--licences` | `plugins/oidc/build/sbom/`, published with the release |
| the six `scratch` images | **compiled into the binary**, printed by `--licences` | published with the release |

Compiling it in is the same move the three TLS-speaking plugins already make with the public
roots: *"the public roots are compiled in as data, which is what lets a `scratch` image verify a
certificate at all"* (`plugins/smtp/Dockerfile`). A notice is data the artifact must carry, an
image with no filesystem has nowhere to put a file, so it carries it in the binary — and the
`the_notice_travels_with_the_binary` test in each crate is what says it is still linked in, because
`include_str!` of a missing file is a compile error and of an empty one is not.

### The SBOM is one per artifact, and it is not committed

Three ecosystems, three generators, each run by the build that produces the artifact:
`cyclonedxDirectBom` for the two Java images, `@cyclonedx/cyclonedx-npm` for the bundle,
`cargo cyclonedx` for the six crates. Each is published as a workflow artifact for ninety days so a
release attaches the SBOM of its own commit.

It is **not** committed, unlike the notice, and that is not an inconsistency: a CycloneDX document
carries a timestamp and a fresh serial number on every run, so a committed one would differ from
itself and a drift check on it would fail on every build.

### The view is in the client, and it shows two notices

The application's notice is a typed endpoint and the web client renders it (`ThirdPartyNotices.tsx`),
opened from the same footer that shows the version and the source link — which is what ADR-0034
asked for. It shows **two** notices side by side, the server's and the bundle's, because they are
two artifacts with two dependency sets and neither covers the other.

## Options

| Option | Why not |
|---|---|
| **Generated from the artifact, committed, checked in CI** | Chosen |
| Generated by each build, never committed | It cannot work for the six `scratch` images, which `REQ-CON-013` covers like every other artifact: `include_str!` reads the file at compile time, so it has to exist before `cargo build` runs, which is what the `the_notice_travels_with_the_binary` test in each crate depends on. It would also hide a licence change from the review of the commit that causes it |
| One notice for the whole repository | It would over-attribute every artifact and under-attribute none — which sounds safe until a reader asks which of these licences applies to the thing they are running. `REQ-CON-013` says *each* artifact carries its own |
| The notice generated from the dependency tree rather than the artifact | The three findings under Context are what that would have got wrong, and each of them in a different direction |
| `cargo-about`, `license-maven-plugin` and their peers | One per ecosystem, three output formats, three sets of templates, and none of them reads the built artifact. The rules that mattered here — which text travels and which is reproduced once — are thirty lines |

## Consequences

- **A dependency change shows up as a licence diff.** Adding a library adds its notice to a
  committed file, and a reviewer sees the licence in the pull request rather than in an audit.
- **Nine artifacts, nine notices, about 1.1 MB in the repository.** The Rust ones are the bulk of
  it: 72 kB to 155 kB each, mostly MIT and BSD copyright lines that cannot be deduplicated, because
  the copyright line *is* the notice. A licence whose text is a standalone document — Apache-2.0,
  EPL-2.0, the GPL family — is reproduced once per notice instead.
- **A Rust notice is generated for the target that ships, not for the host.**
  `cargo cyclonedx` resolves the dependency graph for the machine it runs on unless told
  otherwise, so the first six notices — written on Windows — named `windows-sys` and
  `windows-link` and left out `libc`, `errno` and `signal-hook-registry`. Five components wrong
  in a document whose whole job is to say what the artifact contains, and green locally, because
  the generator and the check agreed with each other. **CI is what caught it**, by running the
  same generator on Linux; the triple is now pinned to the one every Dockerfile builds, so the
  answer no longer depends on who asked.
- **A licence this repository has never seen stops the tool** until its canonical text is stored
  under `tools/notices/licences/` or a component in the artifact carries a clean copy of it. That is
  deliberate: the alternative is a notice that lists a licence and does not reproduce it.
- **The `--licences` flag is part of each binary's contract.** It is what the notice is reachable
  through in an image that has no shell to read a file with.
- **ADR-0034's third place is not yet covered.** *"the bundle, the container image and the app
  packages"* — the app packages are the Kotlin Multiplatform clients, which are stage 2 and stage 3
  and do not exist. When they do, they are two more rows in `tools/notices.py` and nothing else.
