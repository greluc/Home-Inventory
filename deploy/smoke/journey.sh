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
JAR=$(mktemp)
OUT=$(mktemp)
trap 'rm -f "$JAR" "$OUT"' EXIT

say()  { printf '  %s\n' "$*"; }
die()  { printf 'FAILED: %s\n' "$*" >&2; [ -s "$OUT" ] && head -c 800 "$OUT" >&2; exit 1; }

# The credentials the `bootstrap` one-shot used (ADR-0053). Reading them from the
# same two files the deployment reads is the point: a journey that invented its
# own account would prove nothing about the account an operator ends up with.
EMAIL=$(sed -n 's/^HOMEINV_BOOTSTRAP_EMAIL=//p' "$HERE/compose/.env")
PASSWORD=$(cat "$HERE/secrets/bootstrap-password")
[ -n "$EMAIL" ] || die "HOMEINV_BOOTSTRAP_EMAIL is not in compose/.env"

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
        --cookie "$JAR" --cookie-jar "$JAR" \
        --header "X-XSRF-TOKEN: $(csrf)" \
        --request "$method" "$BASE$path" "$@"
}

field() {
    # One value out of a JSON body, without a parser: the shapes here are flat
    # and this script runs in an alpine-sized world where jq is another install.
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$OUT" | head -n 1
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

printf '\nCreating somewhere to put things\n'
status=$(api GET /api/v1/locations/categories)
[ "$status" = "200" ] || die "the categories answered $status"
CATEGORY=$(field id)
[ -n "$CATEGORY" ] || die "no category came back"

status=$(api POST /api/v1/locations \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"Cellar\",\"categoryId\":\"$CATEGORY\"}")
[ "$status" = "201" ] || die "creating a location answered $status"
LOCATION=$(field id)
say "created location $LOCATION"

printf '\nCreating an item in it\n'
status=$(api POST /api/v1/items \
    --header 'Content-Type: application/json' \
    --data "{\"name\":\"Cordless drill\",\"kind\":\"PHYSICAL\",\"locationId\":\"$LOCATION\"}")
case "$status" in
    200|201) ;;
    *) die "creating an item answered $status" ;;
esac
ITEM=$(field id)
say "created item $ITEM"

printf '\nREQ-SEC-092: an infected upload is refused, and nothing is stored\n'
# The EICAR test string, assembled rather than written: a file containing it
# verbatim is quarantined by the scanner on the machine that checks out this
# repository, which is a strange way to break a build.
eicar=$(mktemp)
printf 'X5O!%%P%%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > "$eicar"
status=$(api POST "/api/v1/media?targetKind=ITEM&targetId=$ITEM" --form "file=@$eicar")
rm -f "$eicar"
[ "$status" = "422" ] || die "the infected upload answered $status and should have answered 422"
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
[ "$status" = "201" ] || die "the upload answered $status"
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
[ "$status" = "201" ] || die "the reference upload answered $status"

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
status=$(api GET "/api/v1/search?q=drill&language=en&limit=10")
[ "$status" = "200" ] || die "search answered $status"
grep -q "$ITEM" "$OUT" || die "the item this journey created was not found by search"
say "found by search"

printf '\nAn item can be created, photographed, stored and found again.\n'
