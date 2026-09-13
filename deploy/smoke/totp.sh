#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# A time-based one-time password, RFC 6238, in POSIX shell.
#
#     . "$(dirname "$0")/totp.sh"
#     code=$(totp_code "$SECRET_IN_BASE32")
#
# Sourced by journey.sh, which needs one because `REQ-AUTH-003` refuses an OWNER
# with no second factor — and the owner the `bootstrap` one-shot creates has
# none, by construction: a one-shot has nobody to ask for a code.
#
# It uses `openssl` and `awk`, both of which the deployment already requires
# (setup.sh creates the CA with openssl). `oathtool` would be one line and
# another thing an operator has to install before they can smoke-test their own
# instance.

# The shared secret, base32 as RFC 4648 writes it, to hex for openssl's hexkey.
totp_hexkey() {
    printf '%s' "$1" | awk '
        BEGIN {
            alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            for (i = 1; i <= 32; i++) { value[substr(alphabet, i, 1)] = i - 1 }
        }
        {
            buffer = 0; bits = 0; out = ""
            for (i = 1; i <= length($0); i++) {
                c = substr($0, i, 1)
                if (!(c in value)) { continue }
                buffer = buffer * 32 + value[c]
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    byte = int(buffer / (2 ^ bits))
                    buffer -= byte * (2 ^ bits)
                    out = out sprintf("%02x", byte)
                }
            }
            print out
        }'
}

# The code for the current thirty-second step.
totp_code() {
    hexkey=$(totp_hexkey "$1")
    counter=$(( $(date +%s) / 30 ))

    # The counter as eight big-endian bytes, written with octal escapes because
    # that is what POSIX printf can produce.
    escapes=""
    i=0
    while [ "$i" -lt 8 ]; do
        shift_bits=$(( 8 * (7 - i) ))
        byte=$(( (counter / (1 << shift_bits)) % 256 ))
        escapes="$escapes\\$(printf '%03o' "$byte")"
        i=$(( i + 1 ))
    done

    counter_file=$(mktemp)
    # shellcheck disable=SC2059  # the format string IS the data here.
    printf "$escapes" > "$counter_file"

    mac=$(openssl dgst -sha1 -mac HMAC -macopt "hexkey:$hexkey" -hex "$counter_file" \
        | sed 's/.*= *//')
    rm -f "$counter_file"

    # RFC 4226 §5.4: the low nibble of the last byte says where to read four
    # bytes from, the top bit of those four is cleared, and the six digits are
    # what is left modulo a million.
    printf '%s' "$mac" | awk '
        # Written out rather than using strtonum, which is a GNU extension: a
        # runner is as likely to have mawk, and a smoke suite that works on one
        # distribution and not the other is one nobody trusts on either.
        function hexval(s,   i, c, n) {
            n = 0
            for (i = 1; i <= length(s); i++) {
                c = tolower(substr(s, i, 1))
                n = n * 16 + (index("0123456789abcdef", c) - 1)
            }
            return n
        }
        {
            mac = $0
            offset = hexval(substr(mac, length(mac), 1))
            binary = hexval(substr(mac, offset * 2 + 1, 8))
            binary = binary % 2147483648          # clears the sign bit, RFC 4226 5.4
            printf "%06d\n", binary % 1000000
        }'
}
