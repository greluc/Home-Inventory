#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The third-party licence notices every distributed artifact carries (`REQ-CON-013`).

A permissive licence — MIT, BSD, ISC, Apache-2.0 — permits the copying on one
condition: that its notice appears **in all copies**. A container image is a
copy. A minified bundle is a copy. A statically linked binary is a copy. The
`LICENSE` file sitting in this repository is not part of any of them, so the
obligation is not discharged by the repository being public
([ADR-0034](../docs/adr/0034-icon-set-and-no-third-party-hosts.md)).

This generates, per distributed artifact, the notice that travels **inside** it:
the components it carries, the licence each is under, and the text of every one
of those licences.

## Where the component list comes from

From the artifact itself — the jars inside the boot jar, the packages the bundle
was built from, the crates the binary was linked from — and not from the build
files, because those are two different lists and only one of them is shipped.
The `REQ-CON-010` SBOM supplies the licence identifiers, which a jar does not
carry in machine-readable form.

The two lists are compared rather than assumed equal, and the differences found
on the first run are the reason this reads the artifact: the SBOM named
`guava:33.6.0-android` where the boot jar carries `guava-33.6.0-jre.jar`, listed
twenty-six components that contribute no file at all (BOMs, starters and
relocation POMs), and omitted `spring-boot-jarmode-tools`, which Spring Boot's
plugin puts into the jar without it ever being on the runtime classpath.

## Where the licence text comes from

A licence whose text is a **template around a copyright line** — MIT, BSD, ISC —
cannot be deduplicated, because the copyright line *is* the notice. Each
component's own copy travels with it, read out of the artifact: the
`META-INF/LICENSE` of a jar, the `LICENSE` beside a package's `package.json`,
the one in a crate's source.

A licence whose text is a **standalone document** — Apache-2.0, EPL-2.0,
MPL-2.0, the GPL family — is the same 11 to 26 kB for everyone under it, so it
appears once, in an appendix. That copy comes from `tools/notices/licences/`,
and where this repository stores none, from a component in the artifact that
carries the licence cleanly — with the provenance named in the document either
way. The order was the other way round until the first generated notice was
read: `byte-buddy`'s `META-INF/LICENSE` opens with a sentence about the ASM it
bundles, and that sentence would have headed the Apache licence for all 130
components under it.

A component whose text cannot be found anywhere **fails this tool**. There is no
silent omission: an unattributed component is the one defect this file exists to
prevent, so it has to be answered by adding the component's notice under
`tools/notices/overrides/` with the source it was taken from.

Run with ``--check`` to compare instead of write, which is what CI does.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import pathlib
import re
import sys
import tempfile
import zipfile
from typing import Iterable

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent
STORE = REPOSITORY / "tools" / "notices"

# Licences whose text is a document rather than a template around a copyright
# line. One copy of each, in an appendix, is what they ask for — the Apache
# licence is the same 11 kB in all 130 jars that carry it.
#
# Everything NOT on this list is treated as a template, including any licence
# named rather than identified, because that is the safe direction: a template
# carried once too often is noise, a copyright line omitted is a licence breach.
STANDALONE = frozenset(
    {
        "Apache-2.0",
        "EPL-1.0",
        "EPL-2.0",
        "MPL-2.0",
        "CDDL-1.0",
        "CDDL-1.1",
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-2.0-with-classpath-exception",
        "GPL-3.0-only",
        "GPL-3.0-or-later",
        "LGPL-2.1-only",
        "LGPL-2.1-or-later",
        "LGPL-3.0-only",
        "AGPL-3.0-or-later",
        "CC0-1.0",
        "MIT-0",
        "Unlicense",
        "Unicode-3.0",
        "MPL-1.1",
    }
)

# What a licence or notice file is called, in the three ecosystems together.
# Matched case-insensitively against the file name, and deliberately loosely:
# the first run found `asm.license`, `FastDoubleParser-LICENSE` and
# `license/minimal-json-LICENSE.txt` in jars that a stricter pattern skipped,
# and a skipped notice is the one defect this file exists to prevent.
#
# The extension is what keeps it from matching source code — a file is a notice
# when it carries one of these words and is either extensionless or text. The
# rest is caught by `readable`, which refuses anything that is not UTF-8: Bouncy
# Castle ships a `LICENSE.class`, and a class file is not a notice.
NOTICE_NAME = re.compile(r"notice", re.IGNORECASE)
LICENCE_NAME = re.compile(r"(licen[sc]e|copying|copyright)", re.IGNORECASE)
TEXT_SUFFIX = frozenset({"", ".txt", ".md", ".markdown", ".html", ".license", ".licence"})
# How far into a file the licence may start and still count as its opening.
# Long enough for a title line and a blank line, short enough that a paragraph
# about something else does not fit in front of it: `byte-buddy` puts 162
# characters about bundled ASM ahead of the Apache licence, and that file is
# its own notice rather than a copy of the licence.
OPENING = 120
# Anything a jar carries under this name is a bundled third-party notice rather
# than the component's own licence, and it travels whatever the component is
# licensed under.
BUNDLED = re.compile(r"(third[-_]?party|thirdparty|licen[sc]es/)", re.IGNORECASE)

# Nothing in the artifact that is ours needs a third-party notice: the
# repository's own LICENSE covers it, and listing ourselves as a third party
# would be a false statement about who owes what.
FIRST_PARTY = ("de.greluc.homeinv", "homeinv-", "home-inv-")

# How a standalone licence's text is recognised in a file that claims to be it.
#
# The appendix takes its texts FROM THE ARTIFACT — one component that carries
# Apache-2.0 supplies the copy for all 130 that are under it — and a phrase is
# what makes that safe: `logback` ships a LICENSE.txt naming EPL-2.0 and
# LGPL-2.1 in two sentences rather than reproducing either, and a copy of that
# under the heading "EPL-2.0" would be a notice that is not the licence.
#
# Missing a marker costs nothing but a stored file; a wrong one would put the
# wrong licence in front of a reader, so each is a phrase from the licence's
# own opening rather than a word that appears near it.
MARKERS: dict[str, tuple[str, ...]] = {
    "Apache-2.0": ("Apache License", "Version 2.0", "TERMS AND CONDITIONS"),
    "EPL-1.0": ("Eclipse Public License", "v 1.0"),
    "EPL-2.0": ("Eclipse Public License", "v. 2.0"),
    "MPL-1.1": ("MOZILLA PUBLIC LICENSE", "Version 1.1"),
    "MPL-2.0": ("Mozilla Public License", "2.0"),
    "CDDL-1.0": ("COMMON DEVELOPMENT AND DISTRIBUTION LICENSE", "1.0"),
    "CDDL-1.1": ("COMMON DEVELOPMENT AND DISTRIBUTION LICENSE", "1.1"),
    "GPL-2.0-only": ("GNU GENERAL PUBLIC LICENSE", "Version 2"),
    "GPL-2.0-or-later": ("GNU GENERAL PUBLIC LICENSE", "Version 2"),
    "GPL-2.0-with-classpath-exception": ("GNU GENERAL PUBLIC LICENSE", "Classpath"),
    "GPL-3.0-only": ("GNU GENERAL PUBLIC LICENSE", "Version 3"),
    "GPL-3.0-or-later": ("GNU GENERAL PUBLIC LICENSE", "Version 3"),
    "LGPL-2.1-only": ("LESSER GENERAL PUBLIC LICENSE", "2.1"),
    "LGPL-2.1-or-later": ("LESSER GENERAL PUBLIC LICENSE", "2.1"),
    "LGPL-3.0-only": ("LESSER GENERAL PUBLIC LICENSE", "Version 3"),
    "AGPL-3.0-or-later": ("GNU AFFERO GENERAL PUBLIC LICENSE", "Version 3"),
    "CC0-1.0": ("CC0 1.0 Universal",),
    "MIT-0": ("MIT No Attribution",),
    "Unlicense": ("This is free and unencumbered software released into the public domain",),
    "Unicode-3.0": ("UNICODE LICENSE", "3.0"),
}


@dataclasses.dataclass(frozen=True)
class Component:
    """One thing a distributed artifact carries that somebody else wrote.

    ``name`` and ``version`` are read from the artifact, so they name the file
    that is actually shipped rather than the coordinate that was asked for.
    ``licences`` is a list of SPDX identifiers, or of names where the ecosystem
    declared one that is not an identifier. ``texts`` holds the verbatim
    licence and notice files that travel with this component, each keyed by the
    path it had inside it; ``carried`` holds every licence-ish file found,
    including the ones the appendix covers, because that is where the appendix
    takes its texts from.
    """

    name: str
    version: str
    licences: tuple[str, ...]
    texts: tuple[tuple[str, str], ...]
    carried: tuple[tuple[str, str], ...] = ()


@dataclasses.dataclass(frozen=True)
class Artifact:
    """One distributed artifact and the notice it has to carry.

    ``carrier`` is what the notice is derived from — the boot jar, the
    dependency directory, the package tree, the crate — and it is a build
    output, so the tool says what to build rather than guessing when it is
    absent. ``form`` is ``json`` where something renders the notice (the
    application serves it, the client shows it) and ``text`` where the artifact
    can only print it.
    """

    key: str
    ecosystem: str
    title: str
    carrier: pathlib.Path
    sbom: pathlib.Path | None
    output: pathlib.Path
    form: str
    build: str


ARTIFACTS: tuple[Artifact, ...] = (
    Artifact(
        key="app",
        ecosystem="maven",
        title="Home Inventory — application (api)",
        carrier=REPOSITORY / "app" / "build" / "libs",
        sbom=REPOSITORY / "app" / "build" / "sbom" / "home-inv-sbom.json",
        output=REPOSITORY / "app" / "src" / "main" / "resources" / "third-party-notices.json",
        form="json",
        build="./gradlew :app:bootJar :app:cyclonedxDirectBom",
    ),
    Artifact(
        key="plugin-oidc",
        ecosystem="maven",
        title="Home Inventory — OIDC plugin",
        carrier=REPOSITORY / "plugins" / "oidc" / "build" / "install" / "oidc" / "lib",
        sbom=REPOSITORY / "plugins" / "oidc" / "build" / "sbom" / "home-inv-plugin-oidc-sbom.json",
        output=REPOSITORY / "plugins" / "oidc" / "src" / "main" / "resources" / "THIRD-PARTY-NOTICES.txt",
        form="text",
        build="./gradlew :plugins:oidc:installDist :plugins:oidc:cyclonedxDirectBom",
    ),
    Artifact(
        key="web",
        ecosystem="npm",
        title="Home Inventory — web client",
        carrier=REPOSITORY / "web" / "node_modules",
        sbom=REPOSITORY / "web" / "dist" / "sbom.json",
        output=REPOSITORY / "web" / "public" / "third-party-notices.json",
        form="json",
        build="cd web && npm ci && npm run build",
    ),
)

# How a Rust artifact's bill of materials is produced.
#
# `--target` is not optional and is not a detail. `cargo cyclonedx` resolves the
# dependency graph FOR THE HOST unless told otherwise, so a notice generated on
# Windows named `windows-sys` and `windows-link` and left out `libc`, `errno` and
# `signal-hook-registry` -- five components wrong in a document whose whole job is
# to say what the artifact contains. The triple is the one every Dockerfile here
# builds, so the notice describes the binary that ships whatever machine wrote it.
CARGO_SBOM = (
    "cargo cyclonedx --format json --spec-version 1.5 --no-build-deps"
    " --target x86_64-unknown-linux-musl"
)

# The six Rust services, which are `scratch` images: one binary and nothing
# else, by design and by a CI assertion (`blobstore/Dockerfile`). There is no
# filesystem in them to put a notice file on, so the notice is compiled INTO
# the binary and printed by `--licences` — the same shape the public roots take
# in the three plugins that speak TLS, and the reason these are generated ahead
# of the build rather than by it.
for _crate, _package, _title in (
    ("blobstore", "homeinv-blobstore", "Home Inventory — blob store"),
    ("egress-proxy", "homeinv-egress-proxy", "Home Inventory — egress proxy"),
    ("plugins/smtp", "homeinv-plugin-smtp", "Home Inventory — SMTP plugin"),
    ("plugins/webhook", "homeinv-plugin-webhook", "Home Inventory — webhook plugin"),
    ("plugins/blobstore-s3", "homeinv-plugin-blobstore-s3", "Home Inventory — S3 blob store plugin"),
    (
        "plugins/blobstore-nextcloud",
        "homeinv-plugin-blobstore-nextcloud",
        "Home Inventory — Nextcloud blob store plugin",
    ),
):
    ARTIFACTS += (
        Artifact(
            key=_crate.replace("/", "-"),
            ecosystem="cargo",
            title=_title,
            carrier=REPOSITORY / _crate,
            sbom=REPOSITORY / _crate / f"{_package}.cdx.json",
            output=REPOSITORY / _crate / "THIRD-PARTY-NOTICES.txt",
            form="text",
            build=f"cd {_crate} && {CARGO_SBOM}",
        ),
    )

BY_KEY = {artifact.key: artifact for artifact in ARTIFACTS}


class Missing(Exception):
    """What could not be attributed, and what to do about it.

    Raised rather than printed so that the message reaches the caller whole:
    a partial notice written to disk would look finished.
    """


# --------------------------------------------------------------------------
# The SBOM: licence identifiers, which no artifact carries in machine-readable
# form.
# --------------------------------------------------------------------------


def declared_licences(document: dict) -> dict[str, tuple[str, ...]]:
    """Reads component name to licence identifiers out of a CycloneDX document.

    Keyed by bare name and not by purl: the three ecosystems spell a purl
    differently, and one of them — Gradle's variant selection — names a
    component by a coordinate whose version string is not the one on the file
    it ships (`guava:33.6.0-android` against `guava-33.6.0-jre.jar`).

    :param document: a parsed CycloneDX 1.6 document
    :return: licence identifiers or names per component name, deduplicated and
        ordered as the document lists them
    """
    found: dict[str, tuple[str, ...]] = {}
    for component in document.get("components", []):
        names: list[str] = []
        for entry in component.get("licenses", []) or []:
            if "expression" in entry:
                names.extend(part for part in re.split(r"\s+(?:AND|OR)\s+", entry["expression"]))
                continue
            licence = entry.get("license") or {}
            named = licence.get("id") or licence.get("name")
            if named:
                names.append(named)
        if not names:
            continue
        key = component["name"]
        merged = list(found.get(key, ()))
        for name in names:
            if name not in merged:
                merged.append(name)
        found[key] = tuple(merged)
        versioned = f"{component['name']}@{component.get('version', '')}"
        found[versioned] = found[key]
    return found


# --------------------------------------------------------------------------
# The overrides: what an artifact does not carry and could not be found.
# --------------------------------------------------------------------------


def override_for(ecosystem: str, name: str) -> tuple[str, ...] | None:
    """Reads the hand-recorded notice for a component that ships none.

    A few projects publish no licence file inside the artifact at all. Their
    notice is recorded once under ``tools/notices/overrides/<ecosystem>/``,
    named after the component, with the source it was taken from in a header
    comment — so the provenance of every line of this file is answerable.

    :param ecosystem: maven, npm or cargo
    :param name: the component's name as the artifact spells it
    :return: the text, or None when there is no override
    """
    path = STORE / "overrides" / ecosystem / f"{name.replace('/', '_')}.txt"
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8")
    # The header names where the text came from and is not part of the notice.
    body = text.split("---8<---\n", 1)
    return (body[1] if len(body) == 2 else text,)


def override_licences(ecosystem: str, name: str) -> tuple[str, ...] | None:
    """Reads the licence identifiers an override declares, where it declares any.

    :param ecosystem: maven, npm or cargo
    :param name: the component's name
    :return: the identifiers from the override's ``SPDX:`` header line, or None
    """
    path = STORE / "overrides" / ecosystem / f"{name.replace('/', '_')}.txt"
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("SPDX:"):
            return tuple(part.strip() for part in line[len("SPDX:") :].split(",") if part.strip())
        if line.startswith("---8<---"):
            break
    return None


def first_party(document: dict) -> frozenset[str]:
    """The names in the SBOM that are this repository's own.

    Read from the document rather than guessed from the name, because a jar is
    called `plugin-api-0.1.0-SNAPSHOT.jar` and carries its group nowhere: the
    file name alone cannot say whether it is ours.

    :param document: a parsed CycloneDX document
    :return: the component names published from this repository
    """
    ours = set()
    root = (document.get("metadata") or {}).get("component") or {}
    # The artifact's own component sits in `metadata`, not in `components` — so
    # a build whose own jar lands beside its dependencies (`installDist`) would
    # otherwise ask for a third-party notice for itself.
    if root.get("name"):
        ours.add(root["name"])
    for component in document.get("components", []):
        group = component.get("group") or ""
        purl = component.get("purl") or ""
        if group.startswith("de.greluc.homeinv") or "de.greluc.homeinv" in purl:
            ours.add(component["name"])
    return frozenset(ours)


def is_first_party(name: str, ours: frozenset[str] = frozenset()) -> bool:
    """Whether the component is ours, and therefore not a third party.

    :param name: the component's name
    :param ours: the names the SBOM marks as this repository's own
    :return: true for anything published from this repository
    """
    return name in ours or any(name.startswith(prefix) for prefix in FIRST_PARTY)


def travels(path: str, text: str, licences: Iterable[str]) -> bool:
    """Whether a licence-ish file inside a component travels with the notice.

    A file travels unless it is a clean copy of a standalone licence this
    component declares — because that is the one case the appendix already
    answers, and it answers it once for all 130 components rather than 130
    times. Three consequences, each of which a run of this tool found:

    * every ``NOTICE`` travels, because Apache-2.0 §4(d) says it must, and no
      appendix can carry an attribution that differs per component;
    * a bundled third-party licence travels whatever the component is under —
      `postgresql` carries the SCRAM licences, `archunit` carries ASM's;
    * a file that is **not only** the licence travels. `byte-buddy`'s
      `META-INF/LICENSE` opens with a sentence about the ASM it bundles and
      reproduces the Apache licence below it; dropping the file because the
      appendix carries Apache-2.0 would drop that sentence with it.

    It is written per file rather than per component for the Rust crates: most
    are `MIT OR Apache-2.0` and ship both texts, and a rule that asked only
    whether the component has a template licence carried the 11 kB Apache copy
    sixty times over — three quarters of a megabyte per binary.

    :param path: the file's path inside the component
    :param text: its content
    :param licences: the component's declared licence identifiers
    :return: whether to carry this file
    """
    names = list(licences)
    name = path.rsplit("/", 1)[-1]
    if not licence_ish(path):
        return False
    if NOTICE_NAME.search(name):
        return True
    if BUNDLED.search(path):
        return True
    return not any(
        licence in STANDALONE and opens_with(text, licence) for licence in names
    )


def licence_ish(path: str) -> bool:
    """Whether a file inside a component is a licence or notice at all.

    :param path: the file's path inside the component
    :return: whether it is worth reading as one
    """
    name = path.rsplit("/", 1)[-1]
    suffix = name[name.rfind(".") :].lower() if "." in name else ""
    if suffix not in TEXT_SUFFIX:
        return False
    return bool(NOTICE_NAME.search(name) or LICENCE_NAME.search(name))


def readable(raw: bytes) -> str | None:
    """Decodes a licence file, or refuses it.

    Licence files are text. One that does not decode as UTF-8 is a file whose
    name matched and whose content is something else — a class file called
    ``LICENSE.class``, which Bouncy Castle ships — and it is not a notice.

    :param raw: the file's bytes
    :return: the text, or None when it is not text
    """
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if "\x00" in text:
        return None
    return text.replace("\r\n", "\n").rstrip() + "\n"


# --------------------------------------------------------------------------
# Reading the artifacts
# --------------------------------------------------------------------------


def from_jar(path: pathlib.Path) -> tuple[tuple[str, str], ...]:
    """Pulls every licence and notice file out of one dependency jar.

    Everything found, not only what travels: `wanted` decides which of these
    the component carries into the notice, and the rest are what the appendix
    is built from.

    :param path: the jar
    :return: path inside the jar and text, for each licence-ish file
    """
    found: list[tuple[str, str]] = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.namelist():
            if entry.endswith("/") or not licence_ish(entry):
                continue
            text = readable(archive.read(entry))
            if text and text.strip():
                found.append((entry, text))
    return tuple(sorted(found))


def jars_of(artifact: Artifact) -> list[tuple[str, str, pathlib.Path | tuple[pathlib.Path, str]]]:
    """Lists the dependency jars the artifact carries, by reading the artifact.

    Two shapes, because the two Java images are built differently: the
    application is one boot jar with `BOOT-INF/lib` inside it, the OIDC plugin
    is `installDist`'s directory of jars (`plugins/oidc/Dockerfile`).

    :param artifact: the artifact
    :return: name, version and where to read each jar from
    :raises Missing: when the artifact has not been built
    """
    if artifact.carrier.is_dir() and artifact.key != "app":
        jars = sorted(artifact.carrier.glob("*.jar"))
        if not jars:
            raise Missing(f"{artifact.carrier} holds no jars — run `{artifact.build}` first")
        return [(*split_jar_name(jar.name), jar) for jar in jars]

    boot = sorted(
        candidate
        for candidate in artifact.carrier.glob("*.jar")
        if not candidate.name.endswith("-plain.jar")
    )
    if not boot:
        raise Missing(f"no boot jar in {artifact.carrier} — run `{artifact.build}` first")
    found = []
    with zipfile.ZipFile(boot[-1]) as archive:
        for entry in archive.namelist():
            if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                name = entry.rsplit("/", 1)[-1]
                found.append((*split_jar_name(name), (boot[-1], entry)))
    return sorted(found)


def split_jar_name(file_name: str) -> tuple[str, str]:
    """Splits ``name-version.jar`` the way Maven coordinates put it together.

    The version is the first segment that starts with a digit — `jakarta.json-api-2.1.3.jar`
    splits at `2.1.3` and `slf4j-api-2.0.18.jar` at `2.0.18`, while a name that
    itself ends in a number keeps it.

    :param file_name: the jar's file name
    :return: the component name and its version, or the stem and an empty
        version where there is no version at all
    """
    stem = file_name[: -len(".jar")]
    match = re.match(r"^(.*?)-(\d[\w.+-]*)$", stem)
    return (match.group(1), match.group(2)) if match else (stem, "")


def maven_components(
    artifact: Artifact, declared: dict[str, tuple[str, ...]], ours: frozenset[str]
) -> list[Component]:
    """Every jar the Java artifact carries, with its licence and its texts.

    :param artifact: the artifact
    :param declared: licence identifiers from the SBOM, by component name
    :param ours: the names the SBOM marks as this repository's own
    :return: the components, first-party ones left out
    :raises Missing: when a component can be attributed to no licence text
    """
    components: list[Component] = []
    unattributed: list[str] = []
    for name, version, where in jars_of(artifact):
        if is_first_party(name, ours):
            continue
        licences = (
            declared.get(f"{name}@{version}")
            or declared.get(name)
            or override_licences("maven", name)
            or ()
        )
        if isinstance(where, tuple):
            # A jar inside a jar. `zipfile` cannot read a nested archive from a
            # stream, so it is unpacked to a temporary file and read from there
            # — outside the repository, which is where a build artifact of a
            # tool that only reads should stay.
            outer, entry = where
            with tempfile.TemporaryDirectory(prefix="homeinv-notices-") as scratch:
                inner = pathlib.Path(scratch) / entry.rsplit("/", 1)[-1]
                with zipfile.ZipFile(outer) as archive:
                    inner.write_bytes(archive.read(entry))
                found = from_jar(inner)
        else:
            found = from_jar(where)
        components.append(assemble("maven", name, version, licences, found, unattributed))
    refuse(unattributed, artifact)
    return components


def from_tree(root: pathlib.Path) -> tuple[tuple[str, str], ...]:
    """Pulls every licence and notice file out of an unpacked package or crate.

    Only the package's own top level and one directory below it, because a
    node package's `node_modules` and a crate's `tests` hold other people's
    files that the artifact does not carry.

    :param root: the package or crate directory
    :return: relative path and text, for each licence-ish file
    """
    found: list[tuple[str, str]] = []
    for candidate in sorted(root.glob("*")) + sorted(root.glob("*/*")):
        if not candidate.is_file():
            continue
        relative = candidate.relative_to(root).as_posix()
        # A package that vendors another one nests it, and the nested one is a
        # component in its own right rather than part of this one. Tested
        # against the path INSIDE the package: every npm package on disk lives
        # under a `node_modules`, so testing the whole path found nothing at
        # all — seven packages, every one of them carrying a LICENSE.
        if "node_modules" in relative.split("/"):
            continue
        if not licence_ish(relative):
            continue
        text = readable(candidate.read_bytes())
        if text and text.strip():
            found.append((relative, text))
    return tuple(found)


def assemble(
    ecosystem: str,
    name: str,
    version: str,
    licences: tuple[str, ...],
    found: tuple[tuple[str, str], ...],
    unattributed: list[str],
) -> Component:
    """Builds one component, taking the override where the artifact carries nothing.

    :param ecosystem: maven, npm or cargo
    :param name: the component's name
    :param version: its version
    :param licences: its declared licences
    :param found: every licence-ish file the component itself carries
    :param unattributed: collects what could not be attributed
    :return: the component
    """
    texts = tuple((path, text) for path, text in found if travels(path, text, licences))
    needs_own = not licences or any(licence not in STANDALONE for licence in licences)
    if needs_own and not texts:
        recorded = override_for(ecosystem, name)
        if recorded:
            texts = (("tools/notices/overrides", recorded[0]),)
        else:
            unattributed.append(
                f"{ecosystem}/{name}@{version} ({', '.join(licences) or 'no licence declared'})"
            )
    return Component(
        name=name, version=version, licences=licences, texts=texts, carried=found
    )


def refuse(unattributed: list[str], artifact: Artifact) -> None:
    """Stops on anything that could not be attributed.

    :param unattributed: what was found without a notice
    :param artifact: the artifact being generated
    :raises Missing: always, when there is anything on the list
    """
    if not unattributed:
        return
    lines = "\n".join(f"  {entry}" for entry in sorted(unattributed))
    raise Missing(
        f"{artifact.key}: {len(unattributed)} component(s) carry no licence text, and their "
        f"licence needs one:\n{lines}\n\n"
        "Each needs a file under tools/notices/overrides/<ecosystem>/<name>.txt holding the "
        "notice verbatim, with the source it was taken from above a `---8<---` line."
    )


def npm_components(
    artifact: Artifact, declared: dict[str, tuple[str, ...]], ours: frozenset[str]
) -> list[Component]:
    """Every package the bundle carries, with its licence and its texts.

    The list is **Rollup's**, from `dist/bundled-packages.json`, and not the
    SBOM's: a production install of this client resolves `typescript`, because
    `i18next` and `react-i18next` declare it as a peer dependency, and no line
    of it reaches a browser. The SBOM is right about the dependency tree and
    the notice is about the artifact, so the two differ here by design — twelve
    resolved packages against seven bundled ones — and `vite.config.ts` is
    where the second number comes from.

    :param artifact: the artifact
    :param declared: licence identifiers from the SBOM, by component name
    :param ours: the names the SBOM marks as this repository's own
    :return: the components, first-party ones left out
    :raises Missing: when a component can be attributed to no licence text
    """
    if artifact.sbom is None or not artifact.sbom.is_file():
        raise Missing(f"no SBOM at {artifact.sbom} — run `{artifact.build}` first")
    bundled = artifact.sbom.parent / "bundled-packages.json"
    if not bundled.is_file():
        raise Missing(f"no bundle report at {bundled} — run `{artifact.build}` first")
    document = json.loads(artifact.sbom.read_text(encoding="utf-8"))
    versions = {
        (f"{entry['group']}/{entry['name']}" if entry.get("group") else entry["name"]): entry.get(
            "version", ""
        )
        for entry in document.get("components", [])
    }
    components: list[Component] = []
    unattributed: list[str] = []
    for name in json.loads(bundled.read_text(encoding="utf-8")):
        if is_first_party(name, ours) or name == "home-inv-web":
            continue
        version = versions.get(name, "")
        bare = name.rsplit("/", 1)[-1]
        licences = declared.get(f"{bare}@{version}") or declared.get(bare) or ()
        root = artifact.carrier / pathlib.PurePosixPath(name)
        found = from_tree(root) if root.is_dir() else ()
        components.append(assemble("npm", name, version, licences, found, unattributed))
    refuse(unattributed, artifact)
    return sorted(components, key=lambda component: component.name)


def cargo_components(
    artifact: Artifact, declared: dict[str, tuple[str, ...]], ours: frozenset[str]
) -> list[Component]:
    """Every crate the binary is linked from, with its licence and its texts.

    The texts come from the registry's unpacked sources, which is where a crate
    keeps its `LICENSE` — a compiled binary carries no files at all, which is
    the whole reason this notice is generated ahead of the build and compiled
    into it.

    :param artifact: the artifact
    :param declared: licence identifiers from the SBOM, by component name
    :param ours: the names the SBOM marks as this repository's own
    :return: the components, first-party ones left out
    :raises Missing: when a component can be attributed to no licence text
    """
    if artifact.sbom is None or not artifact.sbom.is_file():
        raise Missing(f"no SBOM at {artifact.sbom} — run `{artifact.build}` first")
    document = json.loads(artifact.sbom.read_text(encoding="utf-8"))
    sources = sorted(
        (pathlib.Path.home() / ".cargo" / "registry" / "src").glob("*/"),
        key=lambda path: path.name,
    )
    components: list[Component] = []
    unattributed: list[str] = []
    for entry in document.get("components", []):
        name, version = entry["name"], entry.get("version", "")
        if is_first_party(name, ours):
            continue
        licences = declared.get(f"{name}@{version}") or declared.get(name) or ()
        found: tuple[tuple[str, str], ...] = ()
        for registry in sources:
            unpacked = registry / f"{name}-{version}"
            if unpacked.is_dir():
                found = from_tree(unpacked)
                break
        components.append(assemble("cargo", name, version, licences, found, unattributed))
    refuse(unattributed, artifact)
    return sorted(components, key=lambda component: (component.name, component.version))


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def looks_like(text: str, identifier: str) -> bool:
    """Whether a file is the licence it would be filed under.

    :param text: the file's content
    :param identifier: the SPDX identifier it would supply the text for
    :return: whether every marker phrase for that licence is in it
    """
    markers = MARKERS.get(identifier)
    if not markers:
        return False
    flattened = " ".join(text.split())
    return all(marker in flattened for marker in markers)


def opens_with(text: str, identifier: str) -> bool:
    """Whether a file is the licence and nothing before it.

    A licence file may carry more than the licence — a statement about bundled
    code, a dual-licence sentence, a project's own heading — and the difference
    decides two things: whether the file can supply the appendix's copy for
    every other component under the same licence, and whether it has to travel
    with its own component. Both are answered by where the licence starts.

    :param text: the file's content
    :param identifier: the SPDX identifier
    :return: whether the licence's opening phrase is at the top of the file
    """
    markers = MARKERS.get(identifier)
    if not markers or not looks_like(text, identifier):
        return False
    flattened = " ".join(text.split())
    return markers[0] in flattened[:OPENING]


def appendix(components: Iterable[Component]) -> list[dict[str, str]]:
    """The full text of every standalone licence the components are under.

    Taken from the canonical text this repository stores, and where it stores
    none, from a component in the artifact that carries the licence cleanly.

    That order was the other way round until the first generated document was
    read: a text taken from an artifact is the one that project distributes,
    which sounds like the better provenance and is not — `byte-buddy`'s
    `META-INF/LICENSE` opens with a sentence about the ASM it bundles, and that
    sentence would then have headed the Apache licence for all 130 components
    under it. The artifact is the fallback, so a dependency bringing a licence
    nobody stored yields a correct notice with its provenance named in the
    document rather than a failed build — and `MARKERS` decides whether a file
    is the licence at all, because `logback`'s names two in two sentences and
    reproduces neither.

    :param components: the artifact's components
    :return: identifier, text and where the text came from, by identifier
    :raises Missing: when a licence's text is nowhere to be found
    """
    components = list(components)
    used = sorted(
        {
            licence
            for component in components
            for licence in component.licences
            if licence in STANDALONE
        }
    )
    texts: list[dict[str, str]] = []
    absent: list[str] = []
    for identifier in used:
        chosen: tuple[str, str] | None = None
        for stored in (
            STORE / "licences" / f"{identifier}.txt",
            REPOSITORY / "LICENSES" / f"{identifier}.txt",
        ):
            if stored.is_file():
                chosen = (
                    stored.relative_to(REPOSITORY).as_posix(),
                    stored.read_text(encoding="utf-8"),
                )
                break
        if chosen is None:
            for component in sorted(components, key=lambda entry: (entry.name, entry.version)):
                if identifier not in component.licences:
                    continue
                for path, text in component.carried:
                    if opens_with(text, identifier):
                        chosen = (f"{component.name} {component.version}, {path}", text)
                        break
                if chosen:
                    break
        if chosen is None:
            absent.append(identifier)
            continue
        texts.append({"id": identifier, "source": chosen[0], "text": chosen[1].rstrip() + "\n"})
    if absent:
        raise Missing(
            "No text found for: "
            + ", ".join(absent)
            + "\n\nNothing in the artifact carries it and this repository stores none. Add it "
            f"under {(STORE / 'licences').relative_to(REPOSITORY)}/<id>.txt, verbatim from the "
            "licence steward, and record the source in that directory's README."
        )
    return texts


def as_json(artifact: Artifact, components: list[Component]) -> str:
    """Renders the notice as the document the application serves and the client shows.

    No timestamp and no generator version: this file is committed, and a field
    that changes on every run would make every dependency bump look like a
    change to something else.

    :param artifact: the artifact
    :param components: what it carries
    :return: the document, with a closing newline
    """
    document = {
        "artifact": artifact.key,
        "title": artifact.title,
        "components": [
            {
                "name": component.name,
                "version": component.version,
                "licences": list(component.licences),
                "notices": [{"path": path, "text": text} for path, text in component.texts],
            }
            for component in components
        ],
        "licences": appendix(components),
    }
    return json.dumps(document, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


BANNER = """\
THIRD-PARTY LICENCE NOTICES
{title}

GENERATED — DO NOT EDIT. Run `python tools/notices.py {key}` after a dependency
changes; CI regenerates this and fails on any difference (REQ-CON-013).

This artifact carries the components listed below, each under the licence named
with it. Where a licence's text is a template around a copyright line, that
component's own notice is reproduced under it; where a licence is a standalone
document, its full text is reproduced once at the end.

{count} components.
"""


def as_text(artifact: Artifact, components: list[Component]) -> str:
    """Renders the notice as the document a binary can print.

    :param artifact: the artifact
    :param components: what it carries
    :return: the document, with a closing newline
    """
    out = [BANNER.format(title=artifact.title, key=artifact.key, count=len(components))]
    for component in components:
        out.append("=" * 78)
        out.append(f"{component.name} {component.version}".strip())
        out.append(f"Licence: {' AND '.join(component.licences) or 'not declared'}")
        for path, text in component.texts:
            out.append(f"\n--- {path} ---\n{text.rstrip()}")
        out.append("")
    texts = appendix(components)
    if texts:
        out.append("=" * 78)
        out.append("LICENCE TEXTS")
        out.append("")
        for entry in texts:
            out.append("-" * 78)
            out.append(entry["id"])
            out.append(f"(reproduced from {entry['source']})")
            out.append("")
            out.append(entry["text"].rstrip())
            out.append("")
    return "\n".join(out).rstrip() + "\n"


def generate(artifact: Artifact) -> str:
    """Produces the notice for one artifact.

    :param artifact: the artifact
    :return: the document
    :raises Missing: when the artifact has not been built, or a component
        cannot be attributed
    """
    declared: dict[str, tuple[str, ...]] = {}
    ours: frozenset[str] = frozenset()
    if artifact.sbom and artifact.sbom.is_file():
        document = json.loads(artifact.sbom.read_text(encoding="utf-8"))
        declared = declared_licences(document)
        ours = first_party(document)
    elif artifact.ecosystem == "maven":
        raise Missing(f"no SBOM at {artifact.sbom} — run `{artifact.build}` first")

    if artifact.ecosystem == "maven":
        components = maven_components(artifact, declared, ours)
    elif artifact.ecosystem == "npm":
        components = npm_components(artifact, declared, ours)
    else:
        components = cargo_components(artifact, declared, ours)

    if not components:
        raise Missing(f"{artifact.key}: no components found — run `{artifact.build}` first")
    return as_json(artifact, components) if artifact.form == "json" else as_text(artifact, components)


def main() -> int:
    """Generates or checks the notices.

    :return: the exit code, non-zero when a notice is missing or has drifted
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("artifacts", nargs="*", help="which to generate; default is every built one")
    parser.add_argument("--check", action="store_true", help="compare instead of write")
    parser.add_argument("--list", action="store_true", help="print the artifacts and exit")
    arguments = parser.parse_args()

    if arguments.list:
        for artifact in ARTIFACTS:
            print(f"{artifact.key:26} {artifact.ecosystem:6} {artifact.output.relative_to(REPOSITORY)}")
        return 0

    chosen = [BY_KEY[key] for key in arguments.artifacts] if arguments.artifacts else list(ARTIFACTS)
    if arguments.artifacts:
        unknown = [key for key in arguments.artifacts if key not in BY_KEY]
        if unknown:
            print(f"Unknown artifact(s): {', '.join(unknown)}. Try --list.", file=sys.stderr)
            return 2

    drifted: list[str] = []
    skipped: list[str] = []
    written = 0
    for artifact in chosen:
        try:
            document = generate(artifact)
        except Missing as absent:
            if arguments.artifacts:
                print(f"{artifact.key}: {absent}", file=sys.stderr)
                return 1
            skipped.append(f"{artifact.key}: {str(absent).splitlines()[0]}")
            continue
        current = artifact.output.read_text(encoding="utf-8") if artifact.output.is_file() else None
        if current == document:
            continue
        if arguments.check:
            drifted.append(artifact.key)
            continue
        artifact.output.parent.mkdir(parents=True, exist_ok=True)
        artifact.output.write_text(document, encoding="utf-8", newline="\n")
        written += 1

    for note in skipped:
        print(f"skipped — {note}")

    if arguments.check and drifted:
        print(
            "These notices no longer describe what the artifact carries: "
            + ", ".join(drifted)
            + "\n\nRun `python tools/notices.py` and commit the result (REQ-CON-013).",
            file=sys.stderr,
        )
        return 1
    if arguments.check:
        print(f"{len(chosen) - len(skipped)} notice(s) match what the artifacts carry.")
        return 0
    print(f"Wrote {written} notice(s); {len(chosen) - len(skipped) - written} already current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
