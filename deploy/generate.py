#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generates the deployment descriptions from ``services.yaml``.

``deploy/services.yaml`` is the source of truth for the topology (REQ-NFR-063).
This script turns it into a Docker Compose file and a set of Quadlet units, and
CI runs it again and fails on any difference — so a hand edit to a generated file
is caught rather than quietly diverging from the matrix everything else reads.

Why two outputs from one source, rather than two hand-written descriptions: the
project supports both runtimes as equals (ADR-0021), and two hand-written
descriptions drift. They drift *silently*, because each is tested on its own —
and the first sign is a security setting that is present in one and absent in the
other.

Run it as ``python deploy/generate.py`` (writes) or with ``--check`` (compares).
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    sys.exit("PyYAML is required: pip install pyyaml")

# The matrix writes `${SECRET_FILE:name}` where a value is the *path* to a
# mounted secret. It is the matrix's own notation, not shell or Compose
# interpolation - Compose rejects it outright - so it is translated here, in the
# one place that knows where each runtime mounts secrets.
SECRET_FILE = re.compile(r"\$\{SECRET_FILE:([a-z0-9-]+)\}")

HERE = pathlib.Path(__file__).parent
MATRIX = HERE / "services.yaml"
COMPOSE_OUT = HERE / "compose" / "compose.yaml"
QUADLET_DIR = HERE / "quadlet"

BANNER = (
    "# GENERATED FROM ../services.yaml — DO NOT EDIT.\n"
    "#\n"
    "# Run `python deploy/generate.py` after changing the matrix. CI regenerates\n"
    "# this file and fails on any difference, so an edit here is lost and noticed\n"
    "# rather than lost and not (REQ-NFR-063).\n"
)


def load() -> dict:
    """Reads the service matrix.

    :return: the parsed matrix
    """
    return yaml.safe_load(MATRIX.read_text(encoding="utf-8"))


def resolve_secrets(value: str) -> str:
    """Turns the matrix's secret-path notation into a real mount path.

    Both runtimes mount secrets at ``/run/secrets/<name>``, so one translation
    serves both. It happens here rather than in the matrix because the matrix
    describes *what* a service needs, and where a file lands is a property of the
    runtime that mounts it.

    :param value: an environment value, possibly containing the notation
    :return: the value with every occurrence replaced by its mount path
    """
    return SECRET_FILE.sub(lambda m: f"/run/secrets/{m.group(1)}", str(value))


def memory(value: str, target: str) -> str:
    """Translates a Kubernetes-style memory quantity for one runtime.

    The matrix is written in Kubernetes units (``768Mi``) because that is the
    notation the Helm chart needs and the only one of the three that is
    unambiguous. Compose rejects the ``i`` suffix outright and systemd wants an
    uppercase letter, so each target gets its own spelling. All three mean powers
    of 1024, so no arithmetic is involved - only the letter changes.

    :param value: the quantity as the matrix writes it
    :param target: ``compose`` or ``systemd``
    :return: the quantity in that runtime's notation
    """
    quantity = str(value).strip()
    if not quantity.endswith("i"):
        return quantity
    stripped = quantity[:-1]                      # 768Mi -> 768M
    return stripped.lower() if target == "compose" else stripped.upper()


def compose(matrix: dict) -> str:
    """Renders the Compose file.

    Every service carries the hardening from ``defaults`` explicitly rather than
    relying on a Compose default, because the forbidden list in the matrix is
    checked against the rendered text: a setting that is merely "not overridden"
    is invisible to that check (REQ-SEC-085).

    :param matrix: the parsed matrix
    :return: the Compose YAML
    """
    d = matrix["defaults"]
    lines = [BANNER, "", "name: home-inv", "", "services:"]

    for name, service in matrix["services"].items():
        image = service["image"]
        # The digest, not the tag. "The same image digests as production" is what
        # makes an integration test mean anything (CLAUDE.md, Build).
        reference = f"{image['name']}@{image['digest']}" if image.get("digest") and image["digest"] != "TODO" \
            else f"{image['name']}:{image.get('tag', 'latest')}"

        lines.append(f"  {name}:")
        lines.append(f"    image: {reference}")
        lines.append(f"    user: \"{service.get('user', d['user'])}\"")
        lines.append("    read_only: true" if service.get("readOnlyRootFilesystem", d["readOnlyRootFilesystem"]) else "    read_only: false")
        lines.append("    cap_drop:")
        for capability in service.get("dropCapabilities", d["dropCapabilities"]):
            lines.append(f"      - {capability}")
        lines.append("    security_opt:")
        lines.append("      - no-new-privileges:true")
        lines.append(f"    restart: {service.get('restart', d['restart'])}")

        if service.get("profiles"):
            lines.append("    profiles:")
            for profile in service["profiles"]:
                lines.append(f"      - {profile}")

        if service.get("networks"):
            lines.append("    networks:")
            for network in service["networks"]:
                lines.append(f"      - {network}")

        if service.get("env"):
            lines.append("    environment:")
            for key, value in service["env"].items():
                lines.append(f"      {key}: \"{resolve_secrets(value)}\"")

        if service.get("secrets"):
            lines.append("    secrets:")
            for secret in service["secrets"]:
                lines.append(f"      - {secret}")

        if service.get("volumes"):
            lines.append("    volumes:")
            for volume in service["volumes"]:
                # Named volumes only. A bind mount into a host directory is on the
                # forbidden list, because with rootless user namespaces the file
                # ownership on the host is not the ownership in the container.
                lines.append(f"      - {volume['name']}:{volume['path']}")

        if service.get("tmpfs"):
            lines.append("    tmpfs:")
            for path in service["tmpfs"]:
                # A read-only root filesystem still needs somewhere to write.
                lines.append(f"      - {path}")

        ports = service.get("ports") or []
        published = [p for p in ports if p.get("host")]
        if published:
            lines.append("    ports:")
            for port in published:
                lines.append(f"      - \"{port['host']}:{port['container']}\"")

        if service.get("dependsOn"):
            lines.append("    depends_on:")
            for dependency in service["dependsOn"]:
                lines.append(f"      {dependency}:")
                lines.append("        condition: service_healthy")

        health = service.get("health")
        if health and health.get("command"):
            lines.append("    healthcheck:")
            lines.append(f"      test: [\"CMD\", \"{health['command']}\"]")
            lines.append(f"      interval: {health.get('interval', '15s')}")
            lines.append(f"      start_period: {health.get('startPeriod', '30s')}")

        resources = service.get("resources")
        if resources:
            lines.append("    deploy:")
            lines.append("      resources:")
            lines.append("        limits:")
            lines.append(f"          memory: {memory(resources.get('memoryLimit', '512Mi'), 'compose')}")
            lines.append("        reservations:")
            lines.append(f"          memory: {memory(resources.get('memoryReservation', '128Mi'), 'compose')}")

        lines.append("")

    lines.append("networks:")
    for name, network in matrix["networks"].items():
        if network.get("generated"):
            # Per-plugin networks are created by the plugin runtime at stage 1.
            continue
        lines.append(f"  {name}:")
        if network.get("internal"):
            # No route out. This is what makes "the core opens no outbound
            # connection" a property of the network rather than a promise in the
            # code (ADR-0026, ADR-0027).
            lines.append("    internal: true")
    lines.append("")

    lines.append("volumes:")
    for name in matrix["volumes"]:
        lines.append(f"  {name}:")
    lines.append("")

    lines.append("secrets:")
    for name in matrix["secrets"]:
        # Files, not environment variables: an environment variable is readable by
        # anything that can list the process and survives in crash dumps.
        lines.append(f"  {name}:")
        lines.append(f"    file: ../secrets/{name}")
    lines.append("")

    return "\n".join(lines)


def quadlet(matrix: dict) -> dict[str, str]:
    """Renders the Quadlet units.

    One file per service, network and volume, which is how Quadlet works: systemd
    reads them and generates the service units. The security settings are the same
    as in the Compose file and are written out for the same reason.

    :param matrix: the parsed matrix
    :return: filename to content
    """
    d = matrix["defaults"]
    files: dict[str, str] = {}

    for name, network in matrix["networks"].items():
        if network.get("generated"):
            continue
        body = [BANNER.replace("#", ";"), "", "[Unit]", f"Description=Home Inventory network {name}", "", "[Network]", f"NetworkName=homeinv-{name}"]
        if network.get("internal"):
            body.append("Internal=true")
        body += ["", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{name}.network"] = "\n".join(body)

    for name in matrix["volumes"]:
        body = [BANNER.replace("#", ";"), "", "[Unit]", f"Description=Home Inventory volume {name}", "", "[Volume]", f"VolumeName=homeinv-{name}", "", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{name}.volume"] = "\n".join(body)

    for name, service in matrix["services"].items():
        image = service["image"]
        reference = f"{image['name']}@{image['digest']}" if image.get("digest") and image["digest"] != "TODO" \
            else f"{image['name']}:{image.get('tag', 'latest')}"

        body = [BANNER.replace("#", ";"), "", "[Unit]", f"Description=Home Inventory {name}"]
        for dependency in service.get("dependsOn", []):
            body.append(f"Requires=homeinv-{dependency}.service")
            body.append(f"After=homeinv-{dependency}.service")
        body += ["", "[Container]", f"ContainerName=homeinv-{name}", f"Image={reference}",
                 f"User={service.get('user', d['user'])}"]
        if service.get("readOnlyRootFilesystem", d["readOnlyRootFilesystem"]):
            body.append("ReadOnly=true")
        for capability in service.get("dropCapabilities", d["dropCapabilities"]):
            body.append(f"DropCapability={capability}")
        body.append("NoNewPrivileges=true")
        # Not SecurityLabelDisable=true, which is on the forbidden list: it would
        # switch SELinux labelling off instead of letting Podman label the named
        # volumes correctly, which it does on its own.

        for network in service.get("networks", []):
            body.append(f"Network=homeinv-{network}.network")
        for volume in service.get("volumes", []):
            body.append(f"Volume=homeinv-{volume['name']}.volume:{volume['path']}")
        for path in service.get("tmpfs", []):
            body.append(f"Tmpfs={path}")
        for key, value in (service.get("env") or {}).items():
            body.append(f"Environment={key}={resolve_secrets(value)}")
        for secret in service.get("secrets", []):
            body.append(f"Secret={secret},type=mount")
        for port in service.get("ports") or []:
            if port.get("host"):
                body.append(f"PublishPort={port['host']}:{port['container']}")

        health = service.get("health")
        if health and health.get("command"):
            body.append(f"HealthCmd={health['command']}")
            body.append(f"HealthInterval={health.get('interval', '15s')}")
            body.append(f"HealthStartPeriod={health.get('startPeriod', '30s')}")
            if health.get("notify") == "healthy":
                # systemd waits for the container to report healthy before it
                # considers the unit started, which is what makes Requires=/After=
                # mean "ready" rather than "process exists" (REQ-NFR-067).
                body.append("Notify=healthy")

        resources = service.get("resources")
        if resources:
            body += ["", "[Service]"]
            body.append(f"MemoryMax={memory(resources.get('memoryLimit', '512Mi'), 'systemd')}")
            body.append(f"MemoryLow={memory(resources.get('memoryReservation', '128Mi'), 'systemd')}")
            body.append(f"Restart={service.get('restart', d['restart'])}")
        else:
            body += ["", "[Service]", f"Restart={service.get('restart', d['restart'])}"]

        body += ["", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{name}.container"] = "\n".join(body)

    return files


def main() -> int:
    """Writes or checks the generated files.

    :return: the process exit code
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="compare instead of writing; exit non-zero on any difference")
    args = parser.parse_args()

    matrix = load()
    outputs = {COMPOSE_OUT: compose(matrix)}
    for filename, content in quadlet(matrix).items():
        outputs[QUADLET_DIR / filename] = content

    drifted = []
    for path, content in outputs.items():
        if args.check:
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            if current != content:
                drifted.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    if args.check:
        if drifted:
            print("These generated files differ from what services.yaml describes:")
            for path in drifted:
                print(f"  {path}")
            print("\nRun `python deploy/generate.py` and commit the result.")
            return 1
        print(f"{len(outputs)} generated files match services.yaml.")
        return 0

    print(f"Wrote {len(outputs)} files from services.yaml.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
