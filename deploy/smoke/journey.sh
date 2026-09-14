#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Stage 0's definition of done, against a running stack.
#
#     deploy/smoke/journey.sh [base-url]
#
# "An item can be created, photographed, stored and found again — through a
# hardened instance reachable from the internet" (04 Roadmap). Every part of that
# has unit and integration tests; none of them goes through `web`, the published
# port, the session cookie, the CSRF token and the malware scanner, which is where
# a deployment fails while every test passes.
#
# It also carries the two gates no unit test can. `REQ-SEC-092` is fail-closed
# scanning "verified with an EICAR test file", and EICAR needs a real ClamAV with
# real signatures. `REQ-SEC-042` is "a test with a reference image": the stripping
# is libvips doing it, and every test in the JVM suite replaces libvips with a stub
# that writes a fixed string.
set -eu

BASE="${1:-http://localhost:8080}"
HERE=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
# A one-time password, because the owner has to enrol one before the role can be
# used at all (REQ-AUTH-003) and the bootstrap one-shot has nobody to ask.
# shellcheck source=totp.sh
. "$HERE/smoke/totp.sh"
JAR=$(mktemp)
OUT=$(mktemp)
HEADERS=$(mktemp)
trap 'rm -f "$JAR" "$OUT" "$HEADERS"' EXIT

say()  { printf '  %s\n' "$*"; }
# The `if` is not decoration. Written as `[ -s "$OUT" ] && head -c 800 "$OUT"`,
# the test is the last command before `exit 1` — and under `set -e` a false test
# ends the shell right there, so a failure with an EMPTY body printed nothing at
# all and the run looked like it had simply stopped.
die() {
    printf 'FAILED: %s\n' "$*" >&2
    if [ -s "$OUT" ]; then
        head -c 800 "$OUT" >&2
        printf '\n' >&2
    fi
    exit 1
}

# The credentials the `bootstrap` one-shot used (ADR-0053). Reading them from the
# same two files the deployment reads is the point: a journey that invented its
# own account would prove nothing about the account an operator ends up with.
EMAIL=$(sed -n 's/^HOMEINV_BOOTSTRAP_EMAIL=//p' "$HERE/compose/.env")
PASSWORD=$(cat "$HERE/secrets/bootstrap-password")
[ -n "$EMAIL" ] || die "HOMEINV_BOOTSTRAP_EMAIL is not in compose/.env"

# Everything this journey creates is named after THIS run. Locations are unique
# among their siblings (`location_sibling_name`), so a fixed name makes the suite
# runnable exactly once against a given deployment and a `409` on every run after
# — and a smoke suite that cannot be repeated is one an operator runs once, at the
# worst possible moment. The token is also what the search step looks for, which
# is stricter than the old `q=drill`: with several runs' items in the index, a
# bounded result page need not contain the one this run created.
RUN="smoke$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"

# Every mutating call carries the CSRF token from the cookie Spring writes. A
# header and not a form field: a cross-site form can carry a body and cannot set
# a header.
csrf() {
    sed -n 's/.*XSRF-TOKEN[[:space:]]*//p' "$JAR" | tail -n 1
}

api() {
    method="$1"
    path="$2"
    shift 2
    curl --silent --show-error --output "$OUT" --write-out '%{http_code}' \
        --dump-header "$HEADERS" \
        --cookie "$JAR" --cookie-jar "$JAR" \
        --header "X-XSRF-TOKEN: $(csrf)" \
        --request "$method" "$BASE$path" "$@"
}

# The entity tag of a resource, for the `If-Match` every write on one needs
# (REQ-API-004). A GET and then one header out of the response: the tag is the
# version, so this is also what tells a reader that the write below is acting on
# the state it just read and not on whatever is there by the time it arrives.
etag() {
    api GET "$1" > /dev/null
    sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' "$HEADERS" | tr -d '\r'
}

field() {
    # One value out of a JSON body, without a parser: the shapes here are flat
    # and this script runs in an alpine-sized world where jq is another install.
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$OUT" | head -n 1
}

# The upload is answered 202 before the scanner has said anything: the scan runs
# in the worker (ADR-0054). So every upload here is followed by polling the
# resource the Location header names, until it stops saying "no verdict yet".
#
# Echoes the final status, so the caller asserts on the outcome rather than on
# the upload. ClamAV is the slow part — a cold container loads a gigabyte of
# signatures — hence sixty seconds rather than the five that would do locally.
await_verdict() {
    tries=0
    while [ "$tries" -lt 120 ]; do
        status=$(api GET "/api/v1/media/$1")
        if [ "$status" != "503" ]; then
            printf '%s' "$status"
            return 0
        fi
        tries=$((tries + 1))
        sleep 0.5
    done
    printf '503'
}

printf '\nSigning in as the owner the bootstrap service created\n'
status=$(curl --silent --output "$OUT" --write-out '%{http_code}' \
    --cookie-jar "$JAR" \
    --header 'Content-Type: application/json' \
    --data "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    "$BASE/api/v1/auth/login")
[ "$status" = "200" ] || die "login answered $status"
TENANT=$(field tenantId)
say "signed in, tenant $TENANT"

printf '\nSetting up the second factor the OWNER role requires\n'
# REQ-AUTH-003: the membership is granted and every request in the tenant is
# refused until an authenticator exists. The password alone gets a session and
# nothing to do with it, which is exactly what an operator sees on a fresh
# deployment — so the journey does what they do.
status=$(api GET /api/v1/locations/categories)
[ "$status" = "403" ] || die "an owner with no second factor was not refused: $status"
grep -q 'second-factor-missing' "$OUT" \
    || die "the refusal did not name second-factor-missing"

status=$(api POST /api/v1/auth/mfa/totp)
[ "$status" = "200" ] || die "the enrolment answered $status"
SECRET=$(field secret)
[ -n "$SECRET" ] || die "no secret came back from the enrolment"

status=$(api POST /api/v1/auth/mfa/totp/confirmation \
    --header 'Content-Type: application/json' \
    --data "{\"code\":\"$(totp_code "$SECRET")\"}")
[ "$status" = "200" ] || die "confirming the second factor answered $status"
say "second factor enrolled, ten recovery codes issued"

# The confirmation spent this time step, and a code from a step already accepted
# is refused (RFC 6238 5.2). Waiting for the next one is what a person does
# without noticing; a test cannot, so it says so. At most thirty seconds, once.
sleep $(( 30 - $(date +%s) % 30 ))

# And the login is two calls from here on, which is what a person will meet.
status=$(curl --silent --output "$OUT" --write-out '%{http_code}' \
    --cookie-jar "$JAR" \
    --header 'Content-Type: application/json' \
    --data "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    "$BASE/api/v1/auth/login")
[ "$status" = "401" ] || die "the login did not ask for the second factor: $status"
grep -q 'second-factor-required' "$OUT" || die "the login did not name second-factor-required"

status=$(api POST /api/v1/auth/mfa \
    --header 'Content-Type: application/json' \
    --data "{\"code\":\"$(totp_code "$SECRET")\"}")
[ "$status" = "200" ] || die "answering the second factor at login gave $status"
say "signed in again, with the code"

printf '\nCreating somewhere to put things\n'
status=$(api GET /api/v1/locations/categories)
[ "$status" = "200" ] || die "the categories answered $status"
CATEGORY=$(field id)
[ -n "$CATEGORY" ] || die "no category came back"

status=$(api POST /api/v1/locations \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"Cellar $RUN\",\"categoryId\":\"$CATEGORY\"}")
[ "$status" = "201" ] || die "creating a location answered $status"
LOCATION=$(field id)
say "created location $LOCATION"

printf '\nCreating an item in it\n'
status=$(api POST /api/v1/items \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"Cordless drill $RUN\",\"kind\":\"PHYSICAL\",\"locationId\":\"$LOCATION\"}")
case "$status" in
    200|201) ;;
    *) die "creating an item answered $status" ;;
esac
ITEM=$(field id)
say "created item $ITEM"

printf '\nREQ-CORE-043: the box moves and the drill goes with it\n'
status=$(api POST /api/v1/locations \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"Garage $RUN\",\"categoryId\":\"$CATEGORY\"}")
[ "$status" = "201" ] || die "creating the second location answered $status"
GARAGE=$(field id)

# Without `If-Match` the write is refused, which is the half of REQ-API-004 an
# operator never sees until a client forgets it.
status=$(api POST "/api/v1/locations/$LOCATION/move" \
    --header 'Content-Type: application/json' \
    --data "{\"parentId\":\"$GARAGE\"}")
[ "$status" = "428" ] || die "a move without If-Match answered $status, not 428"

status=$(api POST "/api/v1/locations/$LOCATION/move" \
    --header 'Content-Type: application/json' \
    --header "If-Match: $(etag "/api/v1/locations/$LOCATION")" \
    --data "{\"parentId\":\"$GARAGE\"}")
[ "$status" = "200" ] || die "moving a location answered $status"
[ "$(field parentId)" = "$GARAGE" ] || die "the move did not change the parent"

# The assertion that matters, and the reason this step is worth a smoke run at
# all: the item was never touched. It names the place it is in, that place is the
# same place, and moving a box is one operation however much is inside it.
status=$(api GET "/api/v1/items/$ITEM")
[ "$status" = "200" ] || die "reading the item back answered $status"
[ "$(field locationId)" = "$LOCATION" ] || die "the item did not stay where it was"

# And a move into its own subtree is refused, which is what keeps the tree a
# tree now that a parent is no longer fixed at creation (REQ-CORE-045).
status=$(api POST "/api/v1/locations/$GARAGE/move" \
    --header 'Content-Type: application/json' \
    --header "If-Match: $(etag "/api/v1/locations/$GARAGE")" \
    --data "{\"parentId\":\"$LOCATION\"}")
[ "$status" = "409" ] || die "a cycle answered $status and should have answered 409"
grep -q 'problems/invalid-move' "$OUT" || die "the refusal did not name invalid-move"
say "moved $LOCATION into $GARAGE; the item stayed put and a cycle was refused"

printf '\nREQ-SEC-092: an infected upload never becomes retrievable\n'
# The EICAR test string, assembled rather than written out, and never put on
# disk at all. Two different scanners object to it:
#
#   * the one on the machine that checks out this repository, if the 68 bytes
#     appeared verbatim in this file — a strange way to break a build. `%%` and
#     the doubled backslash are what printf needs anyway, and they also mean
#     the literal signature is not in the source;
#   * the one on the machine that RUNS this, which quarantines the temporary
#     file between `printf` and `curl`. That is not hypothetical: it happened
#     on the first run with a correct string, as `Permission denied`.
#
# So the bytes go straight into the request body through `@-`.
eicar() { printf 'X5O!P%%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'; }

# 68 bytes, and a signature match is exact — so a typo here does not produce a
# weaker test, it produces a PASSING upload and a suite that reports a scanner
# it never exercised. This string carried `%%P%%` where the standard has `P%`
# until 2026-09-12: 69 bytes, which ClamAV rightly called clean, and the step
# announced REQ-SEC-092 while proving nothing about it.
bytes=$(eicar | wc -c | tr -d ' ')
[ "$bytes" = "68" ] || die "the EICAR test string is $bytes bytes, not 68"
status=$(eicar | api POST "/api/v1/media?targetKind=ITEM&targetId=$ITEM" \
    --form 'file=@-;filename=eicar.txt;type=text/plain')
[ "$status" = "202" ] || die "the upload answered $status and should have answered 202"

# PENDING_SCAN on the first run of a deployment, INFECTED on every run after it:
# the bytes are identical, so the second upload deduplicates onto the object the
# first one left behind and gets its verdict back at once (ADR-0032). Both are
# correct and the suite has to survive being run twice.
grep -qE '"scanState":"(PENDING_SCAN|INFECTED)"' "$OUT" \
    || die "the accepted upload came back in an unexpected state"

# This one holds either way and is the assertion that matters: no URL is minted
# for anything that is not CLEAN (REQ-MED-013).
grep -q '"urls":{}' "$OUT" \
    || die "an upload with no clean verdict was offered a URL"
INFECTED=$(field id)

status=$(await_verdict "$INFECTED")
[ "$status" = "422" ] || die "the infected object answered $status and should have answered 422"
grep -q 'malware-detected' "$OUT" \
    || die "the refusal did not carry the malware-detected problem type"
say "refused with malware-detected"

printf '\nPhotographing it\n'
photo=$(mktemp)
# A one-pixel JPEG, base64. Small enough to inline and real enough to decode,
# which the re-encoding step needs (REQ-SEC-041).
printf '%s' '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a
HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA
AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==' \
    | base64 -d > "$photo"
status=$(api POST "/api/v1/media?targetKind=ITEM&targetId=$ITEM" --form "file=@$photo")
rm -f "$photo"
[ "$status" = "202" ] || die "the upload answered $status"
PHOTO=$(field id)

status=$(await_verdict "$PHOTO")
[ "$status" = "200" ] || die "the photograph answered $status after the scan"
say "stored, and the scan cleared it"

status=$(api GET "/api/v1/media?targetKind=ITEM&targetId=$ITEM")
[ "$status" = "200" ] || die "listing the photographs answered $status"
grep -q '"primaryImage":true' "$OUT" \
    || die "the first photograph is not the primary one (REQ-MED-002)"
say "the first photograph is the one lists show"

printf '\nREQ-SEC-042: the photograph keeps no EXIF, and no GPS\n'
# A JPEG carrying an EXIF block with a GPS position and a marker string in
# ImageDescription. The marker is what makes the assertion precise: if it comes
# back, the metadata came back with it, and "no GPS" would be a claim rather than
# a result. Built once and inlined, because a repository holding a real
# photograph with real coordinates has a privacy problem of its own.
exif=$(mktemp)
printf '%s' '/9j/4QB4RXhpZgAASUkqAAgAAAACAA4BAgAUAAAARAAAACWIBAABAAAAJgAAAAAAAAACAAEAAgACAAAA
TgAAAAIABQADAAAAWAAAAAAAAABIT01FSU5WLUVYSUYtTUFSS0VSADAAAAABAAAACAAAAAEAAADS
BAAAZAAAAP/gABBKRklGAAEBAQBgAGAAAP/bAEMACAYGBwYFCAcHBwkJCAoMFA0MCwsMGRITDxQd
Gh8eHRocHCAkLicgIiwjHBwoNyksMDE0NDQfJzk9ODI8LjM0Mv/AAAsIAAEAAQEBEQD/xAAUAAEA
AAAAAAAAAAAAAAAAAAAJ/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAAPwAqn//Z' \
    | tr -d '\n' | base64 -d > "$exif"
grep -q 'HOMEINV-EXIF-MARKER' "$exif" || die "the reference image carries no EXIF marker to look for"

status=$(api POST "/api/v1/media?targetKind=ITEM&targetId=$ITEM" --form "file=@$exif")
rm -f "$exif"
[ "$status" = "202" ] || die "the reference upload answered $status"
REFERENCE=$(field id)

# The signed URL exists only once the scan has cleared it, so this waits before
# it looks for one (REQ-MED-013).
status=$(await_verdict "$REFERENCE")
[ "$status" = "200" ] || die "the reference image answered $status after the scan"

# Fetched through the media hostname, which is where every stored byte is served
# from (REQ-MED-010). `--resolve` because `media.localhost` is a name the runner
# does not resolve and the deployment does not need it to.
url=$(sed -n 's/.*"full"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OUT" | head -n 1)
[ -n "$url" ] || die "the stored image came back with no signed URL"
stored=$(mktemp)
curl --silent --show-error --output "$stored" \
    --resolve "media.localhost:8080:127.0.0.1" "$url" || die "the signed URL could not be fetched"
if grep -q 'HOMEINV-EXIF-MARKER' "$stored"; then
    rm -f "$stored"
    die "the stored image still carries its EXIF block, GPS position included"
fi
rm -f "$stored"
say "stored without its metadata"

printf '\nFinding it again\n'
status=$(api GET "/api/v1/items?q=$RUN&language=en&limit=10")
[ "$status" = "200" ] || die "search answered $status"
grep -q "$ITEM" "$OUT" || die "the item this journey created was not found by search"
say "found by search"

printf '\nAn item can be created, photographed, stored and found again.\n'
