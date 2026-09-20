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
import json
import pathlib
import re
import shlex
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
GENERATED_DIR = HERE / "generated"

# A generated file is delivered through each runtime's SECRET mount, because a
# read-only root filesystem and the no-bind-mount rule (ADR-0022) leave no other
# way to get a file into a container. It is not thereby a secret; only the
# delivery path is shared with one.
SECRET_PLACEHOLDER = "@SECRET:{name}@"

# `${HOMEINV_PUBLIC_BASE_URL}` and its kind: a value the operator supplies,
# which Compose interpolates from compose/.env and systemd does not.
#
# The `${VAR:-default}` form counts too, and did not until 2026-09-15. Compose
# interpolates it and systemd passes the twelve-odd characters through, so
# `HOMEINV_SEARCH_ENGINE` reached the container as the literal text
# `${HOMEINV_SEARCH_ENGINE:-postgresql}` — which Spring read as a placeholder
# referring to itself and refused to start on ("Circular placeholder reference").
# The rootless smoke suite caught it under Podman while Docker passed, which is
# exactly the difference the two-runtime matrix exists to find.
#
# A placeholder with a default is omitted from the unit like any other: the
# default belongs to the application (`@Value("${homeinv.search.engine:postgresql}")`)
# and stating it a second time in a generated unit would be a second place for it
# to be wrong.
PLACEHOLDER = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*(?::-[^}]*)?\}")

BANNER = (
    "# GENERATED FROM ../services.yaml — DO NOT EDIT.\n"
    "#\n"
    "# Run `python deploy/generate.py` after changing the matrix. CI regenerates\n"
    "# this file and fails on any difference, so an edit here is lost and noticed\n"
    "# rather than lost and not (REQ-NFR-063).\n"
)


# The scanner's configuration. Compared byte for byte against
# deploy/expected/clamav-freshclam.conf, which was written before this generator
# existed precisely so that it could be wrong against something.
FRESHCLAM_TEMPLATE = """# GENERATED — do not edit. Source: deploy/services.yaml, services.clamav.egress
#
# THIS FILE IS A FIXTURE, NOT A LIVE CONFIGURATION. It is the exact output the
# generator must produce for the `clamav` service's `files[]` entry
# (A6 in docs/adr/0000-open-points.md, ADR-0036). The generator is compared
# against it in CI, the same way the Quadlet units and compose.yaml are compared
# against their own expected output (REQ-NFR-063).
#
# It is here because "contracts before implementations" (01 §1.5, principle 3):
# the artifact is specified now, so the generator has something to be wrong
# against rather than something to invent.
#
# Delivered read-only at {target} through each runtime's file
# mount — `podman secret … type=mount`, a Docker secret, a Kubernetes ConfigMap.
# It is NOT a secret; only the delivery path is shared with secrets, because it
# is the one mechanism all three runtimes have for getting a file into a
# container with a read-only root filesystem and no bind mounts (ADR-0022).

DatabaseDirectory /var/lib/clamav
LogTime yes
Foreground yes

# NO `UpdateLogFile`. With `Foreground yes` freshclam writes to stdout on its
# own, which is where a container's logs belong; naming the file explicitly is
# what broke it. `/dev/stdout` is a symlink to `/proc/self/fd/1`, and under
# rootless Podman opening that resolved back on itself — freshclam reported
# "Failed to open log file /dev/stdout: Symbolic link loop", then "libfreshclam
# init failed", and exited. `clamd` started anyway on the signatures baked into
# the image, so uploads were still scanned and the SIGNATURES NEVER UPDATED —
# a scanner quietly falling behind, which is the shape of failure this project
# refuses (CLAUDE.md rule 12, ADR-0036). Found in the smoke suite on
# 2026-09-15, under Podman only.

# WHY A PROXY AT ALL, AND WHY IT IS CONFIGURED HERE RATHER THAN IN THE ENVIRONMENT
#
# The scanner sits on the `scanner` segment, which is internal and has no
# gateway. Its only route out is the {proxy}, which for this one reason runs
# in every profile and carries the mirror as a fixed deployment allowlist entry
# (ADR-0036, REQ-SEC-097).
#
# freshclam does NOT read HTTPS_PROXY from the environment the way the plugin
# SDKs do — it reads these two keys. That single fact is why this file has to
# exist at all, and why the scanner is the one service in the stack that needs a
# generated config rather than an environment variable.
HTTPProxyServer {proxy}
HTTPProxyPort {port}

# The mirror. DatabaseMirror is a hostname on purpose: it resolves to rotating
# CDN addresses, which is exactly the case ADR-0027 cites for allowlisting by
# name rather than by CIDR.
DatabaseMirror {mirror}

# Daily, as 13 §13.8 requires. A signature age above 48 h is an alert
# (REQ-SEC-093); a failed fetch appears in the proxy's access log attributed to
# `clamav`, so the alert has a cause and not only a symptom.
Checks 1

# Fail visibly rather than quietly: without these, a proxy that refuses the host
# looks like a slow update rather than a blocked one.
ConnectTimeout 30
ReceiveTimeout 60
"""

# NO COMMENTS AND NO BLANK LINES. Valkey parses an ACL file strictly: every line
# must begin with the `user` keyword, and anything else aborts start-up with
# "should start with user keyword followed by the username" — for every line. The
# first version of this template carried the usual explanation in the file and the
# server refused to start, which the connectivity suite found as "valkey answered
# an unauthenticated PING": the server was running from an earlier start, without
# the ACL.
#
# So the reasoning lives here instead:
#
#   * `default off` — leaving the built-in account on with `nopass` is the
#     default, and it is how an authenticated Valkey ends up accepting anonymous
#     commands anyway.
#   * one user, allowed exactly what sessions, the login throttle and the
#     short-lived OIDC state need. After ADR-0044 the only members of `internal`
#     that can reach the port are `api` and `worker`.
#   * the password is a `@SECRET:` placeholder; `deploy/setup.sh` substitutes the
#     operator's secret into a copy under deploy/secrets/, which is git-ignored.
#     Valkey held no credential at all until ADR-0044, which meant every session
#     and every rate-limit counter was readable by anything that could open port
#     6379 on `internal` — `web` included, at the time.
#   * THREE CHANNELS, and they are not optional. An ACL grants no pub/sub channel
#     at all by default, and the indexed session store subscribes to exactly
#     three: the keyspace notifications Valkey emits when a session key is
#     deleted or expires, and Spring Session's own "created" pattern. They are
#     what keeps the per-account session index of REQ-AUTH-009 from filling with
#     sessions that are gone. Without them the application does not degrade — it
#     refuses to start, with `NOPERM No permissions to access a channel`, which is
#     how this line came to be written.
#
#     They are spelled out rather than globbed because Valkey matches them
#     differently for the two commands: a SUBSCRIBE channel is glob-matched
#     against the allowed patterns, but a PSUBSCRIBE pattern has to be one of them
#     LITERALLY. `&__key*__:*` therefore looks like it covers everything here and
#     covers nothing — which is what the first attempt did.
#
#     The `0` is the database index. The deployment does not set one, so it is
#     Valkey's default; an operator who changes it changes these three lines with
#     it.
VALKEY_ACL_TEMPLATE = (
    "user default off\n"
    "user {user} on >{password} ~*"
    " &__keyevent@0__:del &__keyevent@0__:expired &spring:session:event:0:created:*"
    " +@all\n"
)

OPENSEARCH_USERS_TEMPLATE = """# GENERATED FROM ../services.yaml — DO NOT EDIT.
#
# A TEMPLATE. `deploy/setup.sh` replaces each placeholder with the bcrypt hash of
# the matching secret. No password and no hash is in this repository.
#
# DISABLE_INSTALL_DEMO_CONFIG removes the demo users as well as the demo
# certificates, and nothing replaced either — so without this file the cluster
# has no account at all (ADR-0044).

_meta:
  type: "internalusers"
  config_version: 2

admin:
  hash: "{admin}"
  reserved: true
  backend_roles:
    - "admin"
  description: "Cluster administration. Deliberately not the account api and worker use."

homeinv:
  hash: "{client}"
  reserved: true
  backend_roles:
    - "homeinv_client"
  description: "The core's client. Read and write on the tenant indices, nothing else."
"""


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


def secret_environment(service: dict) -> dict[str, str]:
    """The ``HOMEINV_*_FILE`` variables for the secrets a service mounts.

    A mounted secret is a file, and the application is told where it is by an
    environment variable naming the *path* — never by one holding the value
    (06 §6.11, REQ-SEC-050). The derivation is mechanical: ``db-password``
    becomes ``HOMEINV_DB_PASSWORD_FILE``. It is mechanical on purpose, so that a
    new secret cannot be mounted and left unreachable, which is the state every
    secret in this matrix was in until 2026-09-11 — mounted by both runtimes and
    named by no variable, so the application resolved ``${HOMEINV_DB_PASSWORD}``
    to nothing and refused to start.

    :param service: one service from the matrix
    :return: variable name to mount path, in the order the secrets are declared
    """
    # Only the services running OUR image read these. `postgres` mounts
    # db-password too, but it is told where by POSTGRES_PASSWORD_FILE and its own
    # init script; handing it HOMEINV_* variables would be noise that reads like
    # configuration.
    if service.get("role") not in {"api", "worker", "migrate", "bootstrap"}:
        return {}
    return {
        "HOMEINV_" + name.upper().replace("-", "_") + "_FILE": f"/run/secrets/{name}"
        for name in service.get("secrets", [])
    }


def seconds(duration):
    """Reads a systemd-style duration as a whole number of seconds.

    :param duration: a string like ``90s``, ``2m`` or ``15``
    :return: the number of seconds
    """
    text = str(duration).strip()
    if text.endswith("ms"):
        return max(1, int(text[:-2]) // 1000)
    if text.endswith("s"):
        return int(text[:-1])
    if text.endswith("m"):
        return int(text[:-1]) * 60
    if text.endswith("h"):
        return int(text[:-1]) * 3600
    return int(text)

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


def file_secret_name(service: str, target: str) -> str:
    """The secret name a service's generated file is mounted under.

    :param service: the service the file belongs to
    :param target: the absolute path inside the container
    :return: a name usable as a Compose secret and as a Podman secret
    """
    return f"{service}-{pathlib.PurePosixPath(target).name}"


def freshclam_conf(matrix: dict) -> str:
    """Renders the scanner's configuration from the matrix.

    `freshclam` does not read `HTTPS_PROXY` from the environment the way the
    plugin SDKs do; it reads `HTTPProxyServer` and `HTTPProxyPort` from its own
    file. That one fact is why the scanner is the only service in the stack that
    needs a generated configuration rather than an environment variable
    (ADR-0036, REQ-SEC-097).

    Every value below comes from the matrix: the proxy from ``clamav.egress.via``,
    its port from that service's own port declaration, and the mirror from the
    deployment allowlist entry the egress block names. The output is compared
    against ``deploy/expected/clamav-freshclam.conf``, which was written before
    this generator existed so that the generator had something to be wrong
    against.

    :param matrix: the parsed matrix
    :return: the file content
    """
    clamav = matrix["services"]["clamav"]
    egress = clamav["egress"]
    proxy_name = egress["via"]
    proxy = matrix["services"][proxy_name]
    proxy_port = proxy["ports"][0]["container"]
    entry = next(e for e in proxy["allowlist"]["deployment"]
                 if e["id"] == egress["allowlistEntry"])
    mirror = entry["hosts"][0]
    target = clamav["files"][0]["target"]

    return FRESHCLAM_TEMPLATE.format(
        target=target, proxy=proxy_name, port=proxy_port, mirror=mirror)


def valkey_acl(matrix: dict) -> str:
    """Renders the Valkey ACL, with the password left as a placeholder.

    The password is not in this repository and never will be, so what is
    generated is a template; ``deploy/setup.sh`` substitutes the operator's
    secret into it before the stack starts. Everything that is a *decision* —
    which users exist, what they may do, that ``default`` is off — is generated
    and therefore checked.

    :param matrix: the parsed matrix
    :return: the template content
    """
    user = matrix["services"]["api"]["env"]["HOMEINV_VALKEY_USER"]
    return VALKEY_ACL_TEMPLATE.format(
        user=user, password=SECRET_PLACEHOLDER.format(name="valkey-password"))


def opensearch_internal_users(matrix: dict) -> str:
    """Renders the OpenSearch internal users, with the passwords as placeholders.

    ``DISABLE_INSTALL_DEMO_CONFIG`` removes the demo users as well as the demo
    certificates, and nothing replaced either — so without this file the service
    has no account at all (ADR-0044).

    :param matrix: the parsed matrix
    :return: the template content
    """
    return OPENSEARCH_USERS_TEMPLATE.format(
        admin=SECRET_PLACEHOLDER.format(name="opensearch-admin-password"),
        client=SECRET_PLACEHOLDER.format(name="search-password"))


def plugin_services(matrix: dict) -> dict[str, dict]:
    """Every service the matrix marks as a plugin.

    One mark, `role: plugin`, and everything else follows from it: the network
    segment, the allowlist section, the entry in the list the core reads. A
    second declaration somewhere else would be a second place to forget.

    :param matrix: the parsed matrix
    :return: the plugin services, by name, in the matrix's order
    """
    return {
        name: service
        for name, service in matrix["services"].items()
        if service.get("role") == "plugin"
    }


def plugin_networks(matrix: dict) -> dict[str, str]:
    """The network segment each plugin gets, and the subnet it is on.

    The subnet is stated in the matrix rather than assigned here, because it is
    the operator's network and because the egress proxy identifies a caller by
    the interface a connection arrived on, which is a comparison against exactly
    this value (`ADR-0037`, `ADR-0027`).

    :param matrix: the parsed matrix
    :return: network name to CIDR
    :raises SystemExit: when a plugin declares no subnet, or two declare the same
    """
    networks: dict[str, str] = {}
    for name, service in plugin_services(matrix).items():
        subnet = (service.get("segment") or {}).get("subnet")
        if not subnet:
            raise SystemExit(
                f"services.yaml marks {name} as a plugin and gives it no segment.subnet. "
                "The egress proxy keys a plugin's allowances on it, so a plugin without one "
                "would reach only the deployment half of the allowlist and nobody would see why.")
        if subnet in networks.values():
            raise SystemExit(
                f"services.yaml gives {name} the subnet {subnet}, which another plugin already "
                "has. Two plugins on one subnet are two plugins the proxy cannot tell apart, "
                "and each would reach what the other declared.")
        networks[name] = subnet
    return networks


def networks_of(service: dict, matrix: dict) -> list[str]:
    """Every network a service joins, generated memberships included.

    `networksByPlugin: all` is what `api`, `worker` and `egress-proxy` carry: a
    membership per installed plugin, which is how a plugin is reachable by the
    core and by the proxy and by nothing else (ADR-0037). Rendered here rather
    than written out, because writing it out would mean editing three services
    every time a fourth is installed.

    :param service: one service from the matrix
    :param matrix: the parsed matrix
    :return: the network names
    """
    joined = list(service.get("networks") or [])
    if service.get("networksByPlugin") == "all":
        for network in plugin_networks(matrix):
            if network not in joined:
                joined.append(network)
    return joined


def plugin_manifest(matrix: dict, name: str) -> str:
    """One plugin's manifest, as this deployment installs it.

    The plugin's own manifest with `hosts` substituted into its
    `network:outbound` capability: which receivers a deployment allows is the
    operator's decision and not the plugin author's, and the manifest is where
    the egress allowlist is compiled from (`ADR-0027`).

    :param matrix: the parsed matrix
    :param name: the plugin service
    :return: the rendered manifest
    :raises SystemExit: when the source manifest is missing or declares no
        `network:outbound` to substitute into
    """
    service = matrix["services"][name]
    declared = service["plugin"]
    source = HERE.parent / declared["manifest"]
    if not source.is_file():
        raise SystemExit(
            f"services.yaml points {name} at {declared['manifest']}, which does not exist.")

    document = yaml.safe_load(source.read_text(encoding="utf-8"))
    hosts = declared.get("hosts") or []
    # `tcp:` is the other shape a manifest declares, and it means something
    # different: a `host:port` for a protocol that is not HTTP -- SMTP
    # submission, which is why O11 gave the proxy a tunnel rather than a fetch.
    tcp = declared.get("tcp") or []
    outbound = [
        capability
        for capability in document["spec"].get("capabilities") or []
        if capability["id"] == "network:outbound"
    ]
    if (hosts or tcp) and not outbound:
        raise SystemExit(
            f"{declared['manifest']} declares no network:outbound capability, and services.yaml "
            f"gives {name} somewhere to reach. A target that is not in a manifest is a target the "
            "proxy refuses, so the two have to agree.")
    for capability in outbound:
        if hosts:
            capability["hosts"] = list(hosts)
        if tcp:
            capability["tcp"] = list(tcp)

    if document["metadata"]["id"] != declared["id"]:
        raise SystemExit(
            f"services.yaml calls {name} {declared['id']} and its manifest calls it "
            f"{document['metadata']['id']}. The core refuses a disagreement here, because a "
            "grant is recorded against the id.")

    rendered = yaml.safe_dump(document, sort_keys=False, allow_unicode=True, width=100)
    return rendered


def plugins_list(matrix: dict) -> str:
    """The list the core reads at start-up (`REQ-PLG-013`).

    One file for the deployment, carrying each manifest **inline**: one file
    rather than one per plugin plus a path in each entry, and a path is a thing
    that can be right in the list and wrong in the mount.

    The fingerprint is a placeholder. The certificate does not exist until
    `setup.sh` creates it, so the value is substituted there — the same shape the
    secret placeholders already use.

    :param matrix: the parsed matrix
    :return: the rendered list
    """
    lines = [
        BANNER,
        "#",
        "# WHAT THE OPERATOR INSTALLED. The core reads this and nothing else: it",
        "# fetches nothing and it installs nothing, because installation is an",
        "# operator's act outside the running system (REQ-PLG-013).",
        "#",
        "# Each entry carries its manifest INLINE. The alternative -- a path per",
        "# entry and a file per plugin -- is a path that can be right here and",
        "# wrong in the mount, and nothing would say which.",
        "",
        "plugins:",
    ]
    for name, service in plugin_services(matrix).items():
        declared = service["plugin"]
        port = (service.get("ports") or [{}])[0].get("container")
        lines.append(f"  - id: {declared['id']}")
        lines.append(f"    endpoint: \"{name}:{port}\"")
        lines.append(f"    fingerprint: \"@FINGERPRINT:{declared['fingerprintFrom']}@\"")
        lines.append(f"    signed: {str(bool(declared.get('signed'))).lower()}")
        lines.append("    manifestInline: |")
        for line in plugin_manifest(matrix, name).splitlines():
            lines.append(f"      {line}" if line else "")
    return "\n".join(lines).rstrip() + "\n"


def egress_allowlist(matrix: dict) -> str:
    """The hosts the egress proxy may reach, one per line.

    Every value comes from the matrix: the deployment allowlist of `ADR-0036`,
    which today holds one entry — the ClamAV signature mirror — and the hosts each
    plugin service declares. Neither half is ever merged in at run time: a host a
    plugin may reach is one an operator wrote down, which is what makes
    "installation is an operator's act" true of the network as well (REQ-PLG-013).

    A flat list rather than YAML or JSON: the proxy reads it once at start-up, and
    a parser there would be a dependency and a class of failure a container with
    32 MiB and a route out of the deployment does not need.

    Since 2026-09-20 it has two halves. The **deployment** half above is every
    line before the first section header; a **per-plugin** section follows for
    each installed plugin, headed by the subnet of its segment and its id. The
    proxy applies a section to the connections that arrived on that segment and
    to no others, so a plugin reaches what its own manifest declared and not what
    another one did (`ADR-0037`).

    :param matrix: the parsed matrix
    :return: the rendered allowlist
    """
    allowlist = matrix["services"]["egress-proxy"]["allowlist"]
    lines = [
        "# GENERATED — do not edit. Source: deploy/services.yaml, "
        "services.egress-proxy.allowlist.deployment",
        "#",
        "# The CLOSED deployment allowlist (ADR-0036, REQ-SEC-097): one entry per",
        "# in-deployment service that needs a named external target and has no",
        "# manifest. Adding one requires a row in services.yaml with a sentence",
        "# saying why that service cannot be a plugin — deliberately more friction",
        "# than adding a manifest host, because this is the one list no tenant ever",
        "# consents to.",
        "#",
        "# The PER-PLUGIN half follows, one section per segment: [<subnet> <plugin id>].",
        "# The proxy applies a section to the connections that arrived on that segment",
        "# and to no others, so a plugin reaches what its own manifest declared and not",
        "# what another plugin's did (ADR-0037).",
        "",
    ]
    for entry in allowlist.get("deployment") or []:
        lines.append(f"# {entry['id']} — for {entry['caller']}")
        for host in entry["hosts"]:
            lines.append(host)
        lines.append("")

    # The per-plugin half, one section per segment. The proxy keys it on the
    # interface a connection arrived on, which is why the subnet is in the header
    # and why two plugins may not share one (ADR-0037).
    subnets = plugin_networks(matrix)
    for name, service in plugin_services(matrix).items():
        declared = service["plugin"]
        lines.append(f"[{subnets[name]} {declared['id']}]")
        lines.append(f"# {name} — the targets services.yaml allows it, compiled from its manifest")
        for host in declared.get("hosts") or []:
            lines.append(host)
        # A `host:port` allows THAT port and no other; a bare host allows any,
        # which is what `freshclam` needs on port 80. The proxy reads the
        # difference (ADR-0027, O11).
        for target in declared.get("tcp") or []:
            lines.append(target)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def rabbitmq_conf(matrix: dict) -> str:
    """Renders the broker's configuration from the matrix.

    RabbitMQ 4 refuses to start when it finds `RABBITMQ_DEFAULT_USER` or
    `RABBITMQ_DEFAULT_PASS_FILE` in its environment — they are deprecated, and
    the entrypoint treats finding them as an error rather than a warning. The
    supported way to give a fresh broker its first user is this file.

    The password is left as a `@SECRET:` placeholder for `setup.sh` to
    substitute, the same way the Valkey ACL is: the repository holds the shape
    and never the value.

    :param matrix: the parsed matrix
    :return: the rendered configuration
    """
    user = matrix["services"]["api"]["env"]["HOMEINV_MQ_USER"]
    return (
        "# GENERATED - do not edit. Source: deploy/services.yaml, services.rabbitmq\n"
        "#\n"
        "# The broker's first user. RabbitMQ 4 refuses to start when it finds the\n"
        "# deprecated RABBITMQ_DEFAULT_* variables, so the credential travels as a\n"
        "# configuration file - rendered by deploy/setup.sh, which substitutes the\n"
        "# value from deploy/secrets/mq-password and never commits it.\n"
        "#\n"
        "# `guest` is loopback-only by design and is left alone: api and worker\n"
        "# connect over the network and could never have used it (ADR-0044).\n"
        "\n"
        f"default_user = {user}\n"
        "default_pass = @SECRET:mq-password@\n"
        "\n"
        "# The management plugin listens on 15672 and is not published anywhere.\n"
        "# It stays on because the image is the `-management` tag and removing it\n"
        "# would mean a different image, not a smaller one.\n"
        "loopback_users.guest = true\n"
    )


def generated_files(matrix: dict) -> dict[pathlib.Path, str]:
    """Every file a service needs delivered into it, rendered.

    :param matrix: the parsed matrix
    :return: output path to content
    """
    renderers = {
        ("clamav", "egress"): freshclam_conf,
        ("egress-proxy", "allowlist"): egress_allowlist,
        ("valkey", "secrets"): valkey_acl,
        ("rabbitmq", "secrets"): rabbitmq_conf,
        ("opensearch", "secrets"): opensearch_internal_users,
        # The same content for both core roles, mounted into each. Two copies of
        # one file rather than a shared mount, because a secret is delivered per
        # container on both runtimes and the alternative is a bind mount, which
        # is on the forbidden list (ADR-0022).
        ("api", "plugins"): plugins_list,
        ("worker", "plugins"): plugins_list,
    }
    outputs: dict[pathlib.Path, str] = {}
    for name, service in matrix["services"].items():
        for entry in service.get("files") or []:
            if entry.get("source"):
                # A directory of certificates, delivered from a secret as it
                # stands. There is nothing to render: the secret IS the content.
                continue
            render = renderers.get((name, entry.get("generatedFrom")))
            if render is None:
                # A declared file nothing renders would be mounted as an empty
                # secret, fail at run time, and look like the image's fault.
                raise SystemExit(
                    f"services.yaml declares {name}:{entry['target']} as generatedFrom "
                    f"{entry.get('generatedFrom')!r} and generate.py has no renderer for it.")
            outputs[GENERATED_DIR / file_secret_name(name, entry["target"])] = render(matrix)
    return outputs


def delivered_files(service: dict, name: str) -> list[tuple[str, str]]:
    """The (secret name, mount target) pairs a service's files turn into.

    :param service: one service from the matrix
    :param name: the service's name
    :return: one pair per declared file
    """
    return [(entry.get("source") or file_secret_name(name, entry["target"]), entry["target"])
            for entry in service.get("files") or []]


def settings_arguments(service: dict) -> list[str]:
    """The container command a service's ``settings`` block turns into.

    Only ``postgresArgs`` produces a command; an ``env`` service carries its
    settings as environment variables instead, which is what its image reads. A
    settings block with no declared delivery fails the build rather than being
    dropped — the symptom of dropping it is WAL archiving that is configured in
    every document and running nowhere (ADR-0045).

    :param service: one service from the matrix
    :return: the command, or an empty list
    """
    if not service.get("settings"):
        return []
    if service.get("settingsDelivery") != "postgresArgs":
        return []
    command = ["postgres"]
    for key, value in service["settings"].items():
        command += ["-c", f"{key}={value}"]
    return command


def settings_environment(service: dict) -> dict[str, str]:
    """A service's ``settings`` block as environment variables.

    :param service: one service from the matrix
    :return: the variables, or an empty mapping
    """
    if not service.get("settings") or service.get("settingsDelivery") != "env":
        return {}
    return {key: str(value) for key, value in service["settings"].items()}


def host_prerequisites(matrix: dict) -> str:
    """Renders the sysctl checks the matrix declares, as a shell fragment.

    A host prerequisite is a setting no rootless container can make for itself —
    ``vm.max_map_count`` is the one the matrix carries today, and OpenSearch does
    not start without it. Generating the check keeps the list in
    ``services.yaml`` rather than in a script that has to be remembered when a
    service is added.

    :param matrix: the parsed matrix
    :return: a POSIX shell fragment defining ``check_host_prerequisites``
    """
    lines = [
        "# GENERATED FROM ../services.yaml — DO NOT EDIT.",
        "#",
        "# Sourced by setup.sh. Each entry is a setting a rootless container cannot",
        "# make for itself, so it is a one-time root action at install time and the",
        "# only sensible thing the setup script can do about it is refuse to",
        "# continue with a clear sentence (REQ-NFR-060).",
        "",
        "# The profile decides which checks apply: vm.max_map_count is OpenSearch's,",
        "# and OpenSearch does not run in `minimal`. Demanding it there would teach",
        "# an operator that the script's output is advisory.",
        "check_host_prerequisites() {",
        "    profile=${1:-minimal}",
        "    missing=0",
    ]
    for name, service in matrix["services"].items():
        for prerequisite in service.get("hostPrerequisites") or []:
            key = prerequisite["key"]
            value = prerequisite["value"]
            profiles = "|".join(service.get("profiles") or [])
            lines += [
                f"    # required by: {name} (profiles: {profiles.replace('|', ', ')})",
                f'    case "$profile" in {profiles})',
                f'    actual=$(sysctl -n {key} 2>/dev/null || echo 0)',
                f'    if [ "$actual" -lt {value} ]; then',
                f'        echo "MISSING: {key} is $actual, and {name} needs at least {value}." >&2',
                '        echo "         It is a HOST setting; a rootless container cannot set it." >&2',
                f'        echo "         Fix: echo {key}={value} | sudo tee /etc/sysctl.d/99-home-inv.conf" >&2',
                '        echo "              sudo sysctl --system" >&2',
                "        missing=1",
                "    fi",
                "    ;; esac",
            ]
    lines += [
        "    return $missing",
        "}",
        "",
    ]
    return "\n".join(lines)


def secret_catalogue(matrix: dict) -> str:
    """Renders the secret names and kinds as a shell fragment.

    ``setup.sh`` has to create these before anything starts, and "create a
    secret" means three different things here: 32 random bytes, an Ed25519 key
    pair, or a certificate signed by the deployment's own CA. Guessing wrong
    produces a file the service accepts and cannot use, so the kind comes from
    the matrix rather than from the name.

    Generated rather than parsed, because the alternative is YAML parsing in
    POSIX shell on a host where nothing is installed yet.

    :param matrix: the parsed matrix
    :return: a shell fragment defining ``SECRET_KINDS``
    """
    lines = [
        "# GENERATED FROM ../services.yaml — DO NOT EDIT.",
        "#",
        "# Sourced by setup.sh. One line per secret: `<name> <kind>`.",
        "",
        "SECRET_KINDS='"
        + "\n".join(f"{name} {spec['kind']}" for name, spec in matrix["secrets"].items())
        + "'",
        "",
    ]
    return "\n".join(lines)


def check_settings_delivery(matrix: dict) -> None:
    """Fails when a service declares settings that reach nothing.

    :param matrix: the parsed matrix
    :raises SystemExit: when a settings block has no declared delivery
    """
    for name, spec in matrix["secrets"].items():
        if not spec.get("kind"):
            raise SystemExit(
                f"services.yaml declares the secret {name} without a `kind:`. "
                f"setup.sh would not know whether to generate random bytes, a key pair "
                f"or a certificate, and a wrong guess produces a file the service "
                f"accepts and cannot use.")
    for name, service in matrix["services"].items():
        if service.get("settings") and not service.get("settingsDelivery"):
            raise SystemExit(
                f"services.yaml gives {name} a `settings:` block and no `settingsDelivery:`. "
                f"It would be generated into nothing and take effect nowhere.")


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
        reference = f"{image['name']}@{image['digest']}" if image.get("digest") \
            else f"{image['name']}:{image.get('tag', 'latest')}"

        lines.append(f"  {name}:")
        lines.append(f"    image: {reference}")
        # The same name both runtimes use. Quadlet sets `ContainerName=` already,
        # and without this Compose would call it `home-inv-api-1` — so every
        # command an operator is given (`docker logs homeinv-api`,
        # `journalctl --user -u homeinv-api`) would be right on one runtime and
        # wrong on the other, and so would every check in the smoke suite.
        # Nothing in this matrix scales, which is the one thing `container_name`
        # would prevent.
        lines.append(f"    container_name: homeinv-{name}")
        lines.append(f"    user: \"{service.get('user', d['user'])}\"")
        lines.append("    read_only: true" if service.get("readOnlyRootFilesystem", d["readOnlyRootFilesystem"]) else "    read_only: false")
        lines.append("    cap_drop:")
        for capability in service.get("dropCapabilities", d["dropCapabilities"]):
            lines.append(f"      - {capability}")
        lines.append("    security_opt:")
        lines.append("      - no-new-privileges:true")
        # A one-shot has finished when it exits, and `on-failure` would restart a
        # deterministic failure — a missing HOMEINV_BOOTSTRAP_EMAIL, a migration
        # that cannot apply — until somebody noticed. Dependants wait on
        # `service_completed_successfully`, which a restarting container never
        # reaches. services.yaml said this was generated; it was not.
        restart = service.get("restart", d["restart"])
        if service.get("lifecycle") == "oneShot":
            # Quoted: YAML 1.1 reads a bare `no` as false, and a Compose file
            # is parsed by more than one implementation.
            restart = '"no"'
        lines.append(f"    restart: {restart}")

        if service.get("profiles"):
            lines.append("    profiles:")
            for profile in service["profiles"]:
                lines.append(f"      - {profile}")

        joined = networks_of(service, matrix)
        if joined:
            lines.append("    networks:")
            aliases = service.get("networkAliases") or {}
            for network in joined:
                alias = aliases.get(network)
                if alias:
                    # The long form, for the alias. It is what makes
                    # `management.server.address` bindable to ONE segment: a name
                    # declared on a network resolves to the container's address on
                    # THAT network, and a listener bound to it is unreachable from
                    # every other (REQ-SEC-099).
                    lines.append(f"      {network}:")
                    lines.append("        aliases:")
                    lines.append(f"          - {alias}")
                else:
                    lines.append(f"      {network}: {{}}")

        # A service's own `command:` wins; `settings_arguments` is the
        # postgres-shaped path that builds one from the `settings` block.
        command = service.get("command") or settings_arguments(service)
        if command:
            lines.append("    command: [" + ", ".join(f"\"{part}\"" for part in command) + "]")

        environment = {}
        for key, value in (service.get("env") or {}).items():
            environment[key] = resolve_secrets(value)
        environment.update(settings_environment(service))
        environment.update(secret_environment(service))
        if environment:
            lines.append("    environment:")
            for key, value in environment.items():
                lines.append(f"      {key}: \"{value}\"")

        files = delivered_files(service, name)
        if service.get("secrets") or files:
            lines.append("    secrets:")
            # A delivered file is mounted at a path of its own; a plain secret
            # lands at /run/secrets/<name>, which is what the application's
            # HOMEINV_*_FILE variables point at.
            for secret, target in files:
                lines.append(f"      - source: {secret}")
                lines.append(f"        target: {target}")
            for secret in service.get("secrets", []):
                lines.append(f"      - {secret}")

        if service.get("volumes") or service.get("tmpfs"):
            lines.append("    volumes:")
            for volume in service.get("volumes") or []:
                # Named volumes only. A bind mount into a host directory is on the
                # forbidden list, because with rootless user namespaces the file
                # ownership on the host is not the ownership in the container.
                lines.append(f"      - {volume['name']}:{volume['path']}")
            for path in service.get("tmpfs") or []:
                # A read-only root filesystem still needs somewhere to write — and
                # the mode is the part that makes it true. A tmpfs mounted without
                # one arrives root-owned and 0755, so the non-root user every
                # container runs as cannot write to the directory that was mounted
                # for it. `0o1777` is what /tmp is on any host.
                #
                # Found by starting the stack: clamav could not create its lock
                # file and never became healthy, so nothing that waits for it
                # started — and `api` would have failed its first upload, because
                # the pipeline spools to /tmp.
                #
                # The long form rather than the `tmpfs:` short one, which takes a
                # path and no options. Compose accepts both forms in one list.
                lines.append("      - type: tmpfs")
                lines.append(f"        target: {path}")
                lines.append("        tmpfs:")
                lines.append("          mode: 0o1777")

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
                # A one-shot has no health check and never becomes healthy, so
                # `service_healthy` on it would block its dependants for ever.
                # What is actually required of `migrate` is that it EXITED 0
                # before `api` starts (ADR-0041, 06 §6.12).
                if matrix["services"][dependency].get("lifecycle") == "oneShot":
                    lines.append("        condition: service_completed_successfully")
                else:
                    lines.append("        condition: service_healthy")

        health = service.get("health")
        if health and health.get("command"):
            lines.append("    healthcheck:")
            # Split into argv rather than passed as one string. Compose runs a
            # single-element CMD as one executable name, so `valkey-cli ping`
            # became a search for a binary called "valkey-cli ping" and the
            # container was never healthy — and a dependant gated on
            # `service_healthy` therefore never started. CMD-SHELL is not the
            # answer either: two of these images are `scratch` and have no shell.
            argv = ", ".join(f'"{part}"' for part in shlex.split(health["command"]))
            lines.append(f"      test: [\"CMD\", {argv}]")
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
    for name, subnet in plugin_networks(matrix).items():
        # One segment per plugin, generated from the plugin service itself
        # (ADR-0037). `internal: true` is the whole of "a plugin has no route
        # out": what it reaches instead is the proxy, on this same segment.
        lines.append(f"  {name}:")
        lines.append("    internal: true")
        lines.append("    ipam:")
        lines.append("      config:")
        lines.append(f"        - subnet: {subnet}")
    for name, network in matrix["networks"].items():
        if network.get("generated"):
            # The template, which describes the rule the loop above applies.
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
    for service_name, service in matrix["services"].items():
        for secret, _ in delivered_files(service, service_name):
            if secret in matrix["secrets"]:
                continue
            lines.append(f"  {secret}:")
            lines.append(f"    file: ../secrets/{secret}")
    for name in matrix["secrets"]:
        # Files, not environment variables: an environment variable is readable by
        # anything that can list the process and survives in crash dumps.
        lines.append(f"  {name}:")
        lines.append(f"    file: ../secrets/{name}")
    lines.append("")

    return "\n".join(lines)


def literal(value: str) -> str:
    """A value systemd must pass through rather than read specifiers in.

    systemd expands ``%`` specifiers in ``Exec=``, ``Environment=`` and the rest of a
    unit, and Quadlet hands our values to it unchanged. PostgreSQL's ``archive_command``
    is written in ``%f`` and ``%p``, which are also two of systemd's: ``homeinv-postgres``
    started with ``cp homeinv-postgres /var/lib/homeinv/wal-archive//homeinv/postgres``
    and logged "archive command failed" for every segment — WAL archiving doing nothing at
    all, which is the failure ADR-0045 exists to prevent, announced only in a log nobody
    reads. ``%%`` is how systemd is told to keep the one character.

    :param value: the text as the matrix wrote it
    :return: the text with every ``%`` doubled
    """
    return value.replace("%", "%%")


def profiles(matrix: dict) -> list[str]:
    """Every deployment profile the matrix names, in the order it names them.

    Derived rather than listed: a profile exists because a service is in it, and a
    second place to write `minimal, standard, ha` is a second place to forget one.

    :param matrix: the parsed matrix
    :return: the profile names, first appearance first
    """
    found: list[str] = []
    for service in matrix["services"].values():
        for profile in service.get("profiles") or []:
            if profile not in found:
                found.append(profile)
    return found


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
            # The template, which describes what the per-plugin loop below emits.
            continue
        body = [BANNER.replace("#", ";"), "", "[Unit]", f"Description=Home Inventory network {name}", "", "[Network]", f"NetworkName=homeinv-{name}"]
        if network.get("internal"):
            body.append("Internal=true")
        body += ["", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{name}.network"] = "\n".join(body)

    for name, subnet in plugin_networks(matrix).items():
        # One segment per plugin (ADR-0037), with its subnet stated: the egress
        # proxy identifies a caller by the interface a connection arrived on, and
        # that is a comparison against this value.
        body = [
            BANNER.replace("#", ";"), "", "[Unit]",
            f"Description=Home Inventory network {name}", "", "[Network]",
            f"NetworkName=homeinv-{name}", "Internal=true", f"Subnet={subnet}",
            "", "[Install]", "WantedBy=default.target", "",
        ]
        files[f"homeinv-{name}.network"] = "\n".join(body)

    for name in matrix["volumes"]:
        body = [BANNER.replace("#", ";"), "", "[Unit]", f"Description=Home Inventory volume {name}", "", "[Volume]", f"VolumeName=homeinv-{name}", "", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{name}.volume"] = "\n".join(body)

    for name, service in matrix["services"].items():
        image = service["image"]
        reference = f"{image['name']}@{image['digest']}" if image.get("digest") \
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

        for network in networks_of(service, matrix):
            # EVERY membership carries the service's own name as an alias, and
            # that is what makes the two runtimes equals rather than similar
            # (REQ-NFR-051). Compose gives a service its short name on every
            # network it joins, for free; Quadlet names the container
            # `homeinv-<service>` and aliases nothing, so `postgres` resolved
            # under Docker and raised UnknownHostException under Podman — the
            # migrate one-shot could not reach the database, and every service
            # gated on it stayed down. Every URL in this matrix uses the short
            # name, so this is the line that makes them true on both.
            aliases = [name]
            extra = (service.get("networkAliases") or {}).get(network)
            if extra and extra not in aliases:
                aliases.append(extra)
            body.append(
                f"Network=homeinv-{network}.network:"
                + ",".join(f"alias={alias}" for alias in aliases)
            )
        for volume in service.get("volumes", []):
            body.append(f"Volume=homeinv-{volume['name']}.volume:{volume['path']}")
        for path in service.get("tmpfs", []):
            # `mode=1777` for the reason the Compose side gives: without it the
            # mount is root-owned and 0755, and the container's own user cannot
            # write to it.
            body.append(f"Tmpfs={path}:mode=1777")
        # The operator's own variables come from a file, exactly as they do on the
        # Compose side. systemd does NOT interpolate `${VAR}` in `Environment=` —
        # it passes the eight characters — so a unit written that way handed the
        # container the literal text, and `bootstrap` reported "set
        # HOMEINV_BOOTSTRAP_EMAIL and run this service again" on a deployment
        # where the operator had set it. `EnvironmentFile=` is read first and a
        # later `Environment=` overrides it, so a placeholder must not be written
        # twice; the loop below skips them.
        if any(
            PLACEHOLDER.fullmatch(str(value))
            for value in (service.get("env") or {}).values()
        ):
            body.append("EnvironmentFile=%h/.config/homeinv/homeinv.env")

        for key, value in (service.get("env") or {}).items():
            if PLACEHOLDER.fullmatch(str(value)):
                continue
            body.append(f"Environment={key}={literal(resolve_secrets(value))}")
        for key, value in settings_environment(service).items():
            body.append(f"Environment={key}={value}")
        for key, value in secret_environment(service).items():
            body.append(f"Environment={key}={value}")
        for secret in service.get("secrets", []):
            body.append(f"Secret={secret},type=mount")
        for secret, target in delivered_files(service, name):
            body.append(f"Secret={secret},type=mount,target={target}")
        # A service's own `command:` wins; `settings_arguments` is the
        # postgres-shaped path that builds one from the `settings` block.
        command = service.get("command") or settings_arguments(service)
        if command:
            # systemd splits Exec= on whitespace, and `archive_command` contains
            # some. Quoted, or PostgreSQL starts with an archive command that is
            # the first word of one - and WAL archiving silently does nothing,
            # which is the failure ADR-0045 exists to prevent.
            body.append("Exec=" + " ".join(literal(shlex.quote(part)) for part in command))
        for port in service.get("ports") or []:
            if port.get("host"):
                body.append(f"PublishPort={port['host']}:{port['container']}")

        health = service.get("health")
        if health and health.get("command"):
            # The array WITHOUT Compose's `CMD` prefix, which is the one form
            # Podman reads as a command.
            #
            # Given `["CMD", ...]` Podman sees the keyword, decides the value is
            # Docker's string form after all, and re-splits the RAW TEXT on
            # whitespace — so the check it stored for `api` was the four words
            # `["CMD",` and `"/usr/bin/healthcheck"]`, brackets and quotes and
            # all. It could never pass. `api` started in fourteen seconds, ran
            # perfectly, and was killed at 210 s for never reporting healthy,
            # because `Notify=healthy` waits for a check that cannot run.
            #
            # Without the prefix, Podman takes an array of TWO OR MORE words as
            # the command to execute, and a single word as something to hand to
            # `/bin/sh -c`. That is why the two `scratch` images — `blobstore`
            # and `egress-proxy`, which have no shell — declare their check with
            # an argument: `/homeinv-blobstore --health` is executed, and a bare
            # `/homeinv-blobstore` would have been handed to a shell that is not
            # in the image. `deploy/smoke/connectivity.sh` asserts that every
            # container the stack starts actually reports healthy, on both
            # runtimes, which is what makes that a caught mistake rather than a
            # silent one.
            body.append(
                "HealthCmd=" + literal(json.dumps(shlex.split(health["command"])))
            )
            body.append(f"HealthInterval={health.get('interval', '15s')}")
            body.append(f"HealthStartPeriod={health.get('startPeriod', '30s')}")
            if health.get("notify") == "healthy":
                # systemd waits for the container to report healthy before it
                # considers the unit started, which is what makes Requires=/After=
                # mean "ready" rather than "process exists" (REQ-NFR-067).
                body.append("Notify=healthy")

        one_shot = service.get("lifecycle") == "oneShot"

        resources = service.get("resources")
        body += ["", "[Service]"]

        # A unit that waits for the container to report HEALTHY needs longer than
        # systemd's default 90 s to start, because the health check is not even
        # consulted until the start period is over. `api` declares a 90 s start
        # period, so systemd gave up at the exact moment the container became
        # eligible to pass — "Job for homeinv-api.service failed because a timeout
        # was exceeded", with nothing wrong and nothing in a failed state.
        #
        # Derived from the matrix rather than picked: the start period, four
        # intervals for the check to actually run and pass, and a minute for a
        # cold image on slow storage.
        health = service.get("health") or {}
        if health.get("notify") == "healthy":
            start_period = seconds(health.get("startPeriod", "0s"))
            interval = seconds(health.get("interval", "30s"))
            body.append(f"TimeoutStartSec={start_period + 4 * interval + 60}")

        if resources:
            body.append(f"MemoryMax={memory(resources.get('memoryLimit', '512Mi'), 'systemd')}")
            body.append(f"MemoryLow={memory(resources.get('memoryReservation', '128Mi'), 'systemd')}")
        if one_shot:
            # Without these the unit is "started" the moment the container is
            # launched, so a `Requires=`/`After=` dependant starts alongside the
            # migration rather than after it — which is the ordering REQ-NFR-067
            # asks for and the generator did not produce. `RemainAfterExit` keeps
            # the unit active once it has exited 0, so the dependency holds for the
            # rest of the boot instead of going away with the process.
            body.append("Type=oneshot")
            body.append("RemainAfterExit=yes")
            body.append("Restart=no")
        else:
            body.append(f"Restart={service.get('restart', d['restart'])}")

        # No [Install] on a container, deliberately. Every unit carried
        # `WantedBy=default.target` until 2026-09-12, which enabled all of them:
        # a `minimal` deployment would have started OpenSearch at the operator's
        # next login, because nothing in a Quadlet unit knows what a profile is.
        # The profile target below carries the [Install] instead, so enabling one
        # target enables exactly the deployment that was installed.
        files[f"homeinv-{name}.container"] = "\n".join(body)

    # A target per profile, because systemd has no profiles and Compose does.
    #
    # `deploy/setup.sh podman` used to install the units and say
    # "start with: systemctl --user start homeinv-api". That starts `api` and
    # what `api` requires — and leaves `web` down, which is the only published
    # port in the deployment, so the documented command produced a stack with
    # nothing to connect to. The website meanwhile promised a `homeinv.target`
    # that did not exist.
    #
    # `Requires=` and `After=`, not `Wants=`: the target is reached once the
    # whole profile is up, so `systemctl --user start homeinv-minimal.target`
    # returns when the deployment is running and fails when it is not. That is
    # what makes it the one command of REQ-NFR-028 on this runtime.
    for profile in profiles(matrix):
        members = [
            f"homeinv-{name}.service"
            for name, service in matrix["services"].items()
            if profile in (service.get("profiles") or [])
        ]
        body = [
            BANNER.replace("#", ";"),
            "",
            "[Unit]",
            f"Description=Home Inventory, {profile} profile",
        ]
        body += [f"Requires={member}" for member in members]
        body += [f"After={member}" for member in members]
        body += ["", "[Install]", "WantedBy=default.target", ""]
        files[f"homeinv-{profile}.target"] = "\n".join(body)

    return files


def check_expected_fixtures(matrix: dict) -> None:
    """Compares a rendered file against the fixture the matrix names for it.

    ``services.clamav.files[0].expected`` points at a file that was written
    before this generator existed, so that the generator had something to be
    wrong against rather than something to invent. Comparing here rather than
    only in CI means the failure arrives at the moment the renderer changes.

    :param matrix: the parsed matrix
    :raises SystemExit: when a rendered file differs from its fixture
    """
    rendered = generated_files(matrix)
    for name, service in matrix["services"].items():
        for entry in service.get("files") or []:
            fixture = entry.get("expected")
            if not fixture:
                continue
            path = GENERATED_DIR / file_secret_name(name, entry["target"])
            expected = (HERE / fixture).read_text(encoding="utf-8")
            if rendered.get(path) != expected:
                raise SystemExit(
                    f"The rendered {path.name} differs from the fixture {fixture} that "
                    f"services.yaml names as its expected output.")


def check_setup_writes_every_variable(rendered: str) -> None:
    """Every variable ``compose.yaml`` interpolates is written by ``setup.sh``.

    Compose substitutes an unset variable with an empty string and says nothing,
    so a service reached by a variable nobody wrote starts misconfigured rather
    than failing — which is the failure mode `06 §6.11` exists to prevent. The
    matrix generates ``compose.yaml``; ``compose/.env`` is written by hand in
    ``setup.sh``, and the two have no other connection.

    :param rendered: the generated compose file
    :raises SystemExit: when a variable is interpolated and never written
    """
    interpolated = set(re.findall(r"\$\{([A-Z_][A-Z0-9_]*)\}", rendered))
    setup = (HERE / "setup.sh").read_text(encoding="utf-8")
    written = set(re.findall(r"^([A-Z_][A-Z0-9_]*)=", setup, flags=re.MULTILINE))

    missing = sorted(interpolated - written)
    if missing:
        raise SystemExit(
            "compose.yaml interpolates variables that deploy/setup.sh never writes into\n"
            "compose/.env, so Compose would substitute an empty string and the stack would\n"
            "start misconfigured rather than fail:\n  "
            + "\n  ".join(missing)
        )


def check_no_placeholder_reaches_a_unit(units: dict) -> None:
    """No Quadlet unit hands a container an un-interpolated ``${...}``.

    systemd does not interpolate ``Environment=``: it passes the characters. A
    unit written that way gives the container the literal text, and what happens
    next depends on who reads it — ``bootstrap`` once reported "set
    HOMEINV_BOOTSTRAP_EMAIL and run this service again" on a deployment where the
    operator had, and on 2026-09-15 ``worker`` refused to start at all because
    Spring read ``${HOMEINV_SEARCH_ENGINE:-postgresql}`` as a placeholder
    referring to itself.

    Both were the same bug in two spellings, and the second one slipped through
    because the rule only knew the first. This check does not know any spelling:
    a ``${`` in a value is a placeholder that did not get resolved, whatever
    shape it has.

    :param units: the rendered Quadlet units, by filename
    :raises SystemExit: when a unit carries one
    """
    offenders = []
    for filename, content in units.items():
        for line in content.splitlines():
            if line.startswith("Environment=") and "${" in line:
                offenders.append(f"{filename}: {line}")

    if offenders:
        raise SystemExit(
            "These Quadlet units pass an un-interpolated placeholder to a container.\n"
            "systemd does not substitute it — the container receives the characters:\n  "
            + "\n  ".join(offenders)
            + "\n\nA value the operator supplies belongs in the environment file, which the\n"
            "unit already reads; a default belongs to the application."
        )


def main() -> int:
    """Writes or checks the generated files.

    :return: the process exit code
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="compare instead of writing; exit non-zero on any difference")
    args = parser.parse_args()

    matrix = load()
    check_settings_delivery(matrix)
    check_expected_fixtures(matrix)
    rendered_compose = compose(matrix)
    check_setup_writes_every_variable(rendered_compose)
    outputs = {COMPOSE_OUT: rendered_compose}
    units = quadlet(matrix)
    check_no_placeholder_reaches_a_unit(units)
    for filename, content in units.items():
        outputs[QUADLET_DIR / filename] = content
    outputs.update(generated_files(matrix))
    outputs[GENERATED_DIR / "host-prerequisites.sh"] = host_prerequisites(matrix)
    outputs[GENERATED_DIR / "secret-kinds.sh"] = secret_catalogue(matrix)

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
