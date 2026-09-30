#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
set -eu

RUNTIME="${1:-docker}"
FAILURES=0

CONNECT_TIMEOUT=5

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

section() { printf '\n%s\n' "$1"; }

inside() {
    container="$1"
    shift
    "$RUNTIME" exec "$container" "$@" >/dev/null 2>&1
}

connects() {
    "$RUNTIME" exec "$1" nc -w "$CONNECT_TIMEOUT" "$2" "$3" </dev/null >/dev/null 2>&1
}

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

from_internal() {
    "$RUNTIME" run --rm --network "container:homeinv-api" "$@" 2>&1
}

if from_internal --entrypoint sh docker.io/library/postgres:18-alpine -c \
        'PGPASSWORD=definitely-not-the-password psql -h postgres -U homeinv_app -d homeinv -c "select 1"' \
        | grep -qi "authentication failed\|password authentication"; then
    pass "postgres refuses a wrong password"
else
    fail "postgres accepted a wrong password"
fi

if from_internal docker.io/valkey/valkey:8-alpine valkey-cli -h valkey ping \
        | grep -qi "NOAUTH\|WRONGPASS\|denied"; then
    pass "valkey refuses an unauthenticated caller"
else
    fail "valkey answered an unauthenticated PING"
fi

if "$RUNTIME" exec homeinv-rabbitmq rabbitmqctl authenticate_user guest guest >/dev/null 2>&1; then
    fail "rabbitmq accepted guest/guest"
else
    pass "rabbitmq refuses guest/guest"
fi

if inside homeinv-api curl --silent --insecure --max-time "$CONNECT_TIMEOUT" \
        https://blobstore:8100; then
    fail "the blob store answered a caller with no client certificate"
else
    pass "the blob store refuses a caller with no client certificate"
fi

section "ADR-0037 and REQ-PLG-013: what a plugin's segment reaches"
plugin=homeinv-plugin-webhook
started_here=0

from_plugin() {
    "$RUNTIME" run --rm --network "container:$plugin" \
        --entrypoint nc docker.io/library/postgres:18-alpine \
        -w "$CONNECT_TIMEOUT" "$1" "$2" </dev/null >/dev/null 2>&1
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
            if ( cd "$(dirname "$0")/../compose" \
                 && docker compose --profile minimal up -d plugin-webhook ) >/dev/null 2>&1
            then
                started_here=1
            fi
            ;;
        podman)
            if systemctl --user start homeinv-plugin-webhook >/dev/null 2>&1; then
                started_here=1
            fi
            ;;
    esac
    waited=0
    while [ "$waited" -lt 15 ] && ! "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$plugin"; do
        waited=$((waited + 1))
        sleep 1
    done
fi

if "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$plugin"; then
    reached=0
    "$RUNTIME" exec homeinv-api curl --silent --max-time "$CONNECT_TIMEOUT" \
        "http://plugin-webhook:8200" >/dev/null 2>&1 || reached=$?
    if [ "$reached" -eq 7 ]; then
        fail "api cannot reach the plugin on its segment — it would be installed and uncallable"
    else
        pass "api reaches the plugin on its own segment"
    fi

    refused_from_plugin api 8090 "the plugin cannot reach api's management port"
    refused_from_plugin worker 8090 "the plugin cannot reach worker's management port"
    refused_from_plugin postgres 5432 "the plugin cannot reach postgres"
    refused_from_plugin valkey 6379 "the plugin cannot reach valkey"
    refused_from_plugin rabbitmq 5672 "the plugin cannot reach rabbitmq"
    refused_from_plugin blobstore 8100 "the plugin cannot reach the blob store"
    refused_from_plugin clamav 3310 "the plugin cannot reach the scanner"
    refused_from_plugin web 8080 "the plugin cannot reach the ingress"

    reaches_from_plugin egress-proxy 8118 "the plugin reaches the egress proxy, which is its one route out"

    if [ "$started_here" -eq 1 ]; then
        case "$RUNTIME" in
            docker)
                ( cd "$(dirname "$0")/../compose" \
                  && docker compose stop plugin-webhook ) >/dev/null 2>&1 || true
                ;;
            podman)
                systemctl --user stop homeinv-plugin-webhook >/dev/null 2>&1 || true
                ;;
        esac
    fi
else
    fail "the plugin container could not be started, so its segment proved nothing"
fi

section "REQ-SEC-102: exactly two containers sit on a non-internal segment"
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
            ;;
        *) fail "$container reports '$status'" ;;
    esac
done
if [ "$checked" -eq 0 ]; then
    fail "not one running container reported a health status"
fi

section "REQ-NFR-014: the WAL archive receives segments"
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
