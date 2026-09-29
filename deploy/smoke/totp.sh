#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

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

totp_code() {
    hexkey=$(totp_hexkey "$1")
    counter=$(( $(date +%s) / 30 ))

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
