#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# What a running stack proves and a description cannot.
#
#     deploy/smoke/connectivity.sh docker|podman
#
# Six requirements say, in so many words, that their gate is a refused
# connection rather than a flag in a file — and until this script existed CI read
# the deployment descriptions and never opened a socket:
#
#   REQ-SEC-099   the management port answers on `internal` and nowhere else
#   REQ-SEC-102   exactly two containers sit on a non-internal segment
#   REQ-SEC-104   every store inside the deployment authenticates its callers
#   REQ-SEC-105   `web` reaches `api` and nothing else
#   REQ-PRIV-003  `api` and `worker` have no outbound route out of the deployment
#   REQ-NFR-014   the WAL archive actually receives segments
#   REQ-NFR-067   every container that declares a health check reports healthy
#   ADR-0036      the scanner's signature updater actually initialised
#   ADR-0037      a plugin's segment reaches the core and the proxy, and nothing else
#   REQ-PLG-013   the generated topology is the one that runs
#
# A segment flag is a claim; a refused connection is evidence. That distinction is
# ADR-0044's, and it is the reason this file exists rather than another grep over
# `services.yaml`.
set -eu

RUNTIME="${1:-docker}"
FAILURES=0

# Timeouts everywhere: a refused connection is instant, and a DROPped one hangs
# until something gives up. Without a bound, "refused" and "the job ran out of
# time" look the same from here.
CONNECT_TIMEOUT=5

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

section() { printf '\n%s\n' "$1"; }

# Runs a command inside a container and reports whether it succeeded.
inside() {
    container="$1"
    shift
    "$RUNTIME" exec "$container" "$@" >/dev/null 2>&1
}

# Whether a TCP connection from `container` to `host:port` is established.
#
# `nc -w N host port` and not `nc -z`: busybox's netcat, which is the one in the
# alpine-based `web` image, has no `-z`. Redirecting stdin from /dev/null makes it
# close the connection immediately instead of waiting for input, so a successful
# connection exits 0 at once and a refused one exits non-zero.
connects() {
    "$RUNTIME" exec "$1" nc -w "$CONNECT_TIMEOUT" "$2" "$3" </dev/null >/dev/null 2>&1
}

# Asserts that a connection from `container` to `host:port` is REFUSED.
refused() {
    container="$1"
    host="$2"
    port="$3"
    what="$4"
    if connects "$container" "$host" "$port"; then
        fail "$what — the connection SUCCEEDED and must not"
    else
        pass "$what"
    fi
}

# Asserts that a connection from `container` to `host:port` is ACCEPTED.
#
# The positive half matters as much as the negative one: a suite in which every
# connection is refused would pass for the wrong reason — a stack that is not
# running refuses everything.
reaches() {
    container="$1"
    host="$2"
    port="$3"
    what="$4"
    if connects "$container" "$host" "$port"; then
        pass "$what"
    else
        fail "$what — the connection was refused and must not be"
    fi
}

section "REQ-SEC-105: web reaches api and nothing else"
reaches homeinv-web api 8080 "web reaches api on the frontend segment"
refused homeinv-web postgres 5432 "web cannot reach postgres"
refused homeinv-web valkey 6379 "web cannot reach valkey"
refused homeinv-web rabbitmq 5672 "web cannot reach rabbitmq"
refused homeinv-web blobstore 8100 "web cannot reach the blob store"

section "REQ-SEC-099: the management port is bound to internal"
refused homeinv-web api 8090 "web cannot reach api's management port"
refused homeinv-web worker 8090 "web cannot reach worker's management port"
if inside homeinv-worker curl --fail --silent --max-time "$CONNECT_TIMEOUT" \
        http://api:8090/actuator/health/readiness; then
    pass "a container on internal reaches api's management port"
else
    fail "a container on internal cannot reach api's management port — bindTo is too narrow"
fi

section "REQ-PRIV-003: api and worker have no route out of the deployment"
for core in homeinv-api homeinv-worker; do
    # A name and an address, because they fail differently: a name fails at
    # resolution when there is no resolver, and an address fails at routing.
    # Only the second proves there is no route.
    if inside "$core" curl --silent --max-time "$CONNECT_TIMEOUT" https://example.com; then
        fail "$core reached example.com"
    else
        pass "$core cannot reach example.com"
    fi
    if inside "$core" curl --silent --max-time "$CONNECT_TIMEOUT" https://1.1.1.1; then
        fail "$core reached 1.1.1.1 — a name is not what is being blocked"
    else
        pass "$core cannot reach 1.1.1.1"
    fi
done

section "REQ-SEC-104: every store authenticates its callers"

# Every one of these is asked OVER THE NETWORK, from a throwaway container that
# shares `api`'s network namespace — not from inside the store itself. A database
# trusts its own loopback by default, so an `exec` into the container proves the
# opposite of what it looks like: the first version of this check ran `psql` on
# 127.0.0.1 and reported that postgres accepts any password.
from_internal() {
    "$RUNTIME" run --rm --network "container:homeinv-api" "$@" 2>&1
}

# PostgreSQL: the right user, the wrong password.
if from_internal --entrypoint sh docker.io/library/postgres:18-alpine -c \
        'PGPASSWORD=definitely-not-the-password psql -h postgres -U homeinv_app -d homeinv -c "select 1"' \
        | grep -qi "authentication failed\|password authentication"; then
    pass "postgres refuses a wrong password"
else
    fail "postgres accepted a wrong password"
fi

# Valkey: `default` is disabled and every caller is an ACL user, so an
# unauthenticated PING is refused rather than answered.
if from_internal docker.io/valkey/valkey:8-alpine valkey-cli -h valkey ping \
        | grep -qi "NOAUTH\|WRONGPASS\|denied"; then
    pass "valkey refuses an unauthenticated caller"
else
    fail "valkey answered an unauthenticated PING"
fi

# RabbitMQ: `guest` is the account every default installation has.
if "$RUNTIME" exec homeinv-rabbitmq rabbitmqctl authenticate_user guest guest >/dev/null 2>&1; then
    fail "rabbitmq accepted guest/guest"
else
    pass "rabbitmq refuses guest/guest"
fi

# The blob store: mTLS with a pinned fingerprint. A caller with no client
# certificate does not get past the handshake (ADR-0044, REQ-SEC-056).
if inside homeinv-api curl --silent --insecure --max-time "$CONNECT_TIMEOUT" \
        https://blobstore:8100; then
    fail "the blob store answered a caller with no client certificate"
else
    pass "the blob store refuses a caller with no client certificate"
fi

section "ADR-0037 and REQ-PLG-013: what a plugin's segment reaches"
# The plugin runs in `standard` and `ha`; this stack is `minimal`, so it is
# started here BY NAME. What is proved is the shape of the generated network —
# which segment reaches what — and that shape is the same in every profile,
# because it comes from one entry in services.yaml.
#
# The protocol on top of it is proved elsewhere, deliberately: `HostChannelIT`
# drives the host channel over real TLS with real certificates, which a suite
# that opens TCP sockets with `nc` could never do.
plugin=homeinv-plugin-webhook
started_here=0

# Every check FROM the plugin runs in a throwaway container sharing its network
# namespace, exactly as the datastore checks share `api`'s. It is not a
# convenience: the plugin's image is `scratch` — no shell, no `nc`, nothing to
# exec at all — and that is a property worth keeping rather than working around.
from_plugin() {
    "$RUNTIME" run --rm --network "container:$plugin" \
        docker.io/library/postgres:18-alpine \
        nc -w "$CONNECT_TIMEOUT" "$1" "$2" </dev/null >/dev/null 2>&1
}

refused_from_plugin() {
    if from_plugin "$1" "$2"; then
        fail "$3 — the connection SUCCEEDED and must not"
    else
        pass "$3"
    fi
}

reaches_from_plugin() {
    if from_plugin "$1" "$2"; then
        pass "$3"
    else
        fail "$3 — the connection was refused and must not be"
    fi
}

if ! "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$plugin"; then
    case "$RUNTIME" in
        docker)
            ( cd "$(dirname "$0")/../compose" \
              && docker compose --profile minimal up -d plugin-webhook >/dev/null 2>&1 ) \
                && started_here=1
            ;;
        podman)
            systemctl --user start homeinv-plugin-webhook >/dev/null 2>&1 && started_here=1
            ;;
    esac
    # The moment a container needs to be listed. There is no health check to wait
    # for: the image is `scratch`, and the core asks this plugin about itself over
    # the contract's own health service instead (09 §9.5).
    waited=0
    while [ "$waited" -lt 15 ] && ! "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$plugin"; do
        waited=$((waited + 1))
        sleep 1
    done
fi

if "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$plugin"; then
    # THE CORE REACHES IT. Without this the refusals below would all pass on a
    # plugin that is simply unreachable, which is how a segment test fools itself.
    # `curl` rather than `nc`, because the api image has one and not the other;
    # exit 7 is "could not connect" and anything else means the socket opened —
    # this endpoint speaks gRPC over TLS, so a TLS error IS a successful connection.
    "$RUNTIME" exec homeinv-api curl --silent --max-time "$CONNECT_TIMEOUT" \
        "http://plugin-webhook:8200" >/dev/null 2>&1
    if [ "$?" -eq 7 ]; then
        fail "api cannot reach the plugin on its segment — it would be installed and uncallable"
    else
        pass "api reaches the plugin on its own segment"
    fi

    # AND THE PLUGIN REACHES ALMOST NOTHING. The management port first: it is the
    # one REQ-SEC-099 names, and it was reachable from every plugin until
    # ADR-0037 gave each one a segment of its own.
    refused_from_plugin api 8090 "the plugin cannot reach api's management port"
    refused_from_plugin worker 8090 "the plugin cannot reach worker's management port"
    refused_from_plugin postgres 5432 "the plugin cannot reach postgres"
    refused_from_plugin valkey 6379 "the plugin cannot reach valkey"
    refused_from_plugin rabbitmq 5672 "the plugin cannot reach rabbitmq"
    refused_from_plugin blobstore 8100 "the plugin cannot reach the blob store"
    refused_from_plugin clamav 3310 "the plugin cannot reach the scanner"
    refused_from_plugin web 8080 "the plugin cannot reach the ingress"

    # THE ONE ROUTE OUT is the proxy, which sits on this segment for that purpose
    # and applies this plugin's own allowlist to what it asks for (ADR-0027).
    reaches_from_plugin egress-proxy 8118 "the plugin reaches the egress proxy, which is its one route out"

    if [ "$started_here" -eq 1 ]; then
        case "$RUNTIME" in
            docker)
                ( cd "$(dirname "$0")/../compose" \
                  && docker compose stop plugin-webhook >/dev/null 2>&1 )
                ;;
            podman) systemctl --user stop homeinv-plugin-webhook >/dev/null 2>&1 ;;
        esac
    fi
else
    fail "the plugin container could not be started, so its segment proved nothing"
fi

section "REQ-SEC-102: exactly two containers sit on a non-internal segment"
# Read from the running stack rather than from the file that describes it. The
# descriptions are checked elsewhere; this is the check that notices a container
# somebody attached by hand.
outside=0
for container in $("$RUNTIME" ps --format '{{.Names}}'); do
    case "$container" in
        homeinv-web|homeinv-egress-proxy) outside=$((outside + 1)) ;;
        *) ;;
    esac
done
if [ "$outside" -eq 2 ]; then
    pass "web and egress-proxy are the two, and they are both running"
else
    fail "expected web and egress-proxy to be running; found $outside of them"
fi

section "REQ-NFR-067: every health check the matrix declares actually passes"
# A check that cannot run looks exactly like one that has not run yet, and under
# Podman not one of them could: `HealthCmd` was written in Compose's
# `["CMD", ...]` shape, and Podman answers that by re-splitting the raw text of
# the array on spaces — so `api` was checked by running the four words `["CMD",`
# and `"/usr/bin/healthcheck"]`. Ten containers carried a check that could never
# pass, and the only two that said so were the two whose units wait for one:
# `api` and `worker` timed out after starting perfectly.
#
# `starting` is neither pass nor fail, so a container inside its start period is
# waited for rather than judged.
health_of() {
    "$RUNTIME" inspect --format '{{.State.Health.Status}}' "$1" 2>/dev/null | tr -d "\r"
}

containers=$("$RUNTIME" ps --format '{{.Names}}' | grep '^homeinv-' || true)

attempt=0
while [ "$attempt" -lt 90 ]; do
    settling=0
    for container in $containers; do
        [ "$(health_of "$container")" = "starting" ] && settling=$((settling + 1))
    done
    [ "$settling" -eq 0 ] && break
    attempt=$((attempt + 1))
    sleep 1
done

checked=0
for container in $containers; do
    status=$(health_of "$container")
    case "$status" in
        healthy)
            checked=$((checked + 1))
            pass "$container reports healthy"
            ;;
        "" | "<no value>")
            # No health check declared for this one; the matrix is the authority
            # on which services have one, and it is checked elsewhere.
            ;;
        *) fail "$container reports '$status'" ;;
    esac
done
if [ "$checked" -eq 0 ]; then
    fail "not one running container reported a health status"
fi

section "REQ-NFR-014: the WAL archive receives segments"
# `pg_stat_archiver`, not the presence of a setting. The `archive_command` was
# right in every description and archived nothing at all, for two reasons at
# once: systemd read its %f and %p as its own specifiers, and the volume's mount
# point did not exist in the image, so both runtimes created it root-owned under
# a server that runs as uid 70. Neither shows anywhere but a log, which is why
# the RPO the deployment promises is asked of the archiver here (ADR-0045).
#
# A switch rather than a wait: `archive_timeout` is 900 s, and a smoke suite that
# waited that long for its evidence is one nobody runs. The counters are reset
# first, so what is measured is this run rather than the history of the volume —
# a single archive failure at any point in the past would otherwise leave
# `failed_count` above zero for the life of the cluster.
#
# The message before the switch is what makes the switch happen at all:
# PostgreSQL skips it when the current segment holds nothing new, so on a quiet
# cluster the check would reset the counters, switch nothing, and then report
# that nothing was archived. `pg_logical_emit_message` writes a WAL record and
# touches no table, which is what this needs and all of it.
if ! "$RUNTIME" exec homeinv-postgres psql -U postgres -d homeinv -At \
        -c "SELECT pg_stat_reset_shared('archiver');" \
        -c "SELECT pg_logical_emit_message(true, 'homeinv-smoke', 'wal archive check');" \
        -c "SELECT pg_switch_wal();" >/dev/null 2>&1; then
    fail "could not ask postgres to switch its WAL segment"
else
    archived=0
    failed=0
    attempt=0
    while [ "$attempt" -lt 30 ]; do
        counts=$("$RUNTIME" exec homeinv-postgres psql -U postgres -d homeinv -At \
            -c "SELECT archived_count, failed_count FROM pg_stat_archiver;" 2>/dev/null)
        archived=${counts%%|*}
        failed=${counts##*|}
        [ "${archived:-0}" -gt 0 ] && break
        attempt=$((attempt + 1))
        sleep 1
    done
    if [ "${archived:-0}" -gt 0 ] && [ "${failed:-0}" -eq 0 ]; then
        pass "postgres archived $archived segment(s), none refused"
    else
        fail "the WAL archive holds $archived segment(s) and refused $failed"
    fi
fi

section "ADR-0036: the scanner's signature updater started"
# A scanner that cannot update is a scanner that quietly falls behind, and it
# looks exactly like a working one: `clamd` starts on the signatures baked into
# the image and scans every upload with them.
#
# That is what happened until 2026-09-15. The generated `freshclam.conf` named
# `/dev/stdout` as its log file; under rootless Podman that symlink resolved back
# on itself, freshclam reported "Symbolic link loop" and "libfreshclam init
# failed" and exited, and nothing else noticed. Docker was unaffected, which is
# why only the two-runtime matrix could have found it.
#
# This asserts INITIALISATION and not a completed download, on purpose: reaching
# the mirror depends on a third party that rate-limits, and a security check that
# flakes is one somebody switches off.
updater=$("$RUNTIME" logs homeinv-clamav 2>&1 || true)
if printf '%s' "$updater" | grep -qiE "libfreshclam init failed|Initialization error|Failed to open log file"; then
    fail "freshclam could not initialise — the signatures will never update"
else
    pass "freshclam initialised; nothing refused it a log or a database directory"
fi

printf '\n'
if [ "$FAILURES" -ne 0 ]; then
    printf '%s connectivity check(s) failed.\n' "$FAILURES"
    exit 1
fi
printf 'Every connectivity check passed.\n'
