#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
set -eu

BASE="${1:-http://localhost:8080}"
HERE=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
# shellcheck source=totp.sh
. "$HERE/smoke/totp.sh"
JAR=$(mktemp)
OUT=$(mktemp)
HEADERS=$(mktemp)
trap 'rm -f "$JAR" "$OUT" "$HEADERS"' EXIT

say()  { printf '  %s\n' "$*"; }
die() {
    printf 'FAILED: %s\n' "$*" >&2
    if [ -s "$OUT" ]; then
        head -c 800 "$OUT" >&2
        printf '\n' >&2
    fi
    exit 1
}

EMAIL=$(sed -n 's/^HOMEINV_BOOTSTRAP_EMAIL=//p' "$HERE/compose/.env")
PASSWORD=$(cat "$HERE/secrets/bootstrap-password")
[ -n "$EMAIL" ] || die "HOMEINV_BOOTSTRAP_EMAIL is not in compose/.env"

RUN="smoke$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"

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

etag() {
    api GET "$1" > /dev/null
    sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' "$HEADERS" | tr -d '\r'
}

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$OUT" | head -n 1
}

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

sleep $(( 30 - $(date +%s) % 30 ))

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

status=$(api GET "/api/v1/items/$ITEM")
[ "$status" = "200" ] || die "reading the item back answered $status"
[ "$(field locationId)" = "$LOCATION" ] || die "the item did not stay where it was"

status=$(api POST "/api/v1/locations/$GARAGE/move" \
    --header 'Content-Type: application/json' \
    --header "If-Match: $(etag "/api/v1/locations/$GARAGE")" \
    --data "{\"parentId\":\"$LOCATION\"}")
[ "$status" = "409" ] || die "a cycle answered $status and should have answered 409"
grep -q 'problems/invalid-move' "$OUT" || die "the refusal did not name invalid-move"
say "moved $LOCATION into $GARAGE; the item stayed put and a cycle was refused"

printf '\nREQ-SEC-092: an infected upload never becomes retrievable\n'
eicar() { printf 'X5O!P%%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'; }

bytes=$(eicar | wc -c | tr -d ' ')
[ "$bytes" = "68" ] || die "the EICAR test string is $bytes bytes, not 68"
status=$(eicar | api POST "/api/v1/media?targetKind=ITEM&targetId=$ITEM" \
    --form 'file=@-;filename=eicar.txt;type=text/plain')
[ "$status" = "202" ] || die "the upload answered $status and should have answered 202"

grep -qE '"scanState":"(PENDING_SCAN|INFECTED)"' "$OUT" \
    || die "the accepted upload came back in an unexpected state"

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

status=$(await_verdict "$REFERENCE")
[ "$status" = "200" ] || die "the reference image answered $status after the scan"

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
