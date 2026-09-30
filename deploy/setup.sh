#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
set -eu

HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
SECRETS="$HERE/secrets"
GENERATED="$HERE/generated"

RED=''
BOLD=''
PLAIN=''
if [ -t 1 ]; then
    RED=$(printf '\033[31m')
    BOLD=$(printf '\033[1m')
    PLAIN=$(printf '\033[0m')
fi

say()  { printf '%s\n' "$*"; }
step() { printf '%s==>%s %s\n' "$BOLD" "$PLAIN" "$*"; }
die()  { printf '%sFATAL:%s %s\n' "$RED" "$PLAIN" "$*" >&2; exit 1; }

# shellcheck source=generated/host-prerequisites.sh
. "$GENERATED/host-prerequisites.sh"

check_rootless() {
    missing=0

    if [ "$(id -u)" = "0" ]; then
        die "Run this as your own user, not as root. Rootless is the only supported way (ADR-0022)."
    fi

    user=$(id -un)

    for file in /etc/subuid /etc/subgid; do
        if ! grep -q "^${user}:" "$file" 2>/dev/null; then
            say "MISSING: $user has no range in $file."
            say "         Without it a rootless container has a single uid to map into,"
            say "         and every image that drops privileges inside cannot start."
            say "         Fix: sudo usermod --add-subuids 100000-165535 \\"
            say "                            --add-subgids 100000-165535 $user"
            missing=1
        fi
    done

    if command -v loginctl >/dev/null 2>&1; then
        if [ "$(loginctl show-user "$user" --property=Linger --value 2>/dev/null || echo no)" != "yes" ]; then
            say "MISSING: lingering is off for $user."
            say "         The stack would stop at logout and not come back at boot."
            say "         Fix: sudo loginctl enable-linger $user"
            missing=1
        fi
    fi

    if [ ! -f /sys/fs/cgroup/cgroup.controllers ]; then
        say "MISSING: cgroup v2 is not mounted (no /sys/fs/cgroup/cgroup.controllers)."
        say "         Fix: boot with systemd.unified_cgroup_hierarchy=1"
        missing=1
    else
        delegated="/sys/fs/cgroup/user.slice/user-$(id -u).slice/cgroup.controllers"
        if [ -f "$delegated" ] && ! grep -q memory "$delegated"; then
            say "MISSING: the memory controller is not delegated to your user slice."
            say "         Every memory limit below would be accepted and ignored."
            say "         Fix: sudo mkdir -p /etc/systemd/system/user@.service.d && \\"
            say "              printf '[Service]\\nDelegate=memory cpu pids io\\n' | \\"
            say "                sudo tee /etc/systemd/system/user@.service.d/delegate.conf && \\"
            say "              sudo systemctl daemon-reload"
            missing=1
        fi
    fi

    return $missing
}

# shellcheck source=generated/secret-kinds.sh
. "$GENERATED/secret-kinds.sh"

CA_KEY="$SECRETS/deployment-ca.key"
CA_CERT="$SECRETS/deployment-ca.crt"

ensure_ca() {
    [ -f "$CA_CERT" ] && return 0
    command -v openssl >/dev/null 2>&1 || die "openssl is needed to create the deployment CA."
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
        -keyout "$CA_KEY" -out "$CA_CERT" \
        -subj "/CN=Home Inventory deployment CA" >/dev/null 2>&1
    chmod 0600 "$CA_KEY" "$CA_CERT"
    say "  deployment-ca — created (valid ten years, local to this deployment)"
}

generate_mtls() {
    name=$1
    service=$2
    ensure_ca
    key="$SECRETS/$name.key"
    csr="$SECRETS/$name.csr"
    crt="$SECRETS/$name.crt"
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$key" -out "$csr" \
        -subj "/CN=$service" >/dev/null 2>&1
    openssl x509 -req -in "$csr" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
        -days 825 -out "$crt" \
        -extfile /dev/stdin >/dev/null 2>&1 <<EXT
subjectAltName = DNS:$service
extendedKeyUsage = serverAuth, clientAuth
EXT
    cat "$key" "$crt" "$CA_CERT" > "$SECRETS/$name"
    rm -f "$csr"
    chmod 0600 "$key" "$crt"
}

generate_secret() {
    name=$1
    kind=$2
    seed=$3
    target="$SECRETS/$name"
    if [ -s "$target" ]; then
        say "  $name — kept (it already exists)"
        chmod 0444 "$target"
        return 0
    fi
    case "$kind" in
        random)
            head -c 32 /dev/urandom | base64 | tr -d '\n' > "$target"
            ;;
        ed25519)
            command -v openssl >/dev/null 2>&1 || die "openssl is needed for the $name key pair."
            openssl genpkey -algorithm ed25519 -out "$target" >/dev/null 2>&1
            ;;
        mtls-client) generate_mtls "$name" "api" ;;
        mtls-server) generate_mtls "$name" "$(echo "$name" | sed 's/^mtls-//')" ;;
        cosign-public)
            if [ -n "$seed" ] && [ -f "$HERE/$seed" ]; then
                cat "$HERE/$seed" > "$target"
                say "  $name — seeded from $seed"
            else
                : > "$target"
                say "  $name — EMPTY. Put the publisher's cosign public key in $target,"
                say "      or leave it empty and let the plugin register as unsigned."
            fi
            ;;
        external)
            : > "$target"
            say "  $name — EMPTY. Write the credential into $target before starting."
            ;;
        *) die "Unknown secret kind '$kind' for $name. services.yaml and this script disagree." ;;
    esac
    chmod 0444 "$target"
    say "  $name — generated ($kind)"
}

install_secret() {
    if [ ! -s "$SECRETS/$1" ]; then
        say "  $1 — NOT installed: it is empty. Write the credential into $SECRETS/$1 and run this script again."
        return 0
    fi
    podman secret exists "$1" 2>/dev/null \
        || podman secret create "$1" "$SECRETS/$1" >/dev/null
}

write_environment() {
    env_file="$HERE/compose/.env"
    if [ -f "$env_file" ]; then
        say "  compose/.env — kept (it already exists)"
        return 0
    fi
    bootstrap_email="${HOMEINV_BOOTSTRAP_EMAIL:-owner@example.invalid}"
    [ -f "$SECRETS/mtls-blobstore.crt" ] \
        || die "$SECRETS/mtls-blobstore.crt is missing; the secrets step did not finish."
    fingerprint=$(openssl x509 -in "$SECRETS/mtls-blobstore.crt" -noout -fingerprint -sha256 \
                  | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
    [ -n "$fingerprint" ] || die "the blob store certificate produced no fingerprint."
    [ -f "$SECRETS/mtls-search.crt" ] \
        || die "$SECRETS/mtls-search.crt is missing; the secrets step did not finish."
    search_fingerprint=$(openssl x509 -in "$SECRETS/mtls-search.crt" -noout -fingerprint -sha256 \
                  | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
    [ -n "$search_fingerprint" ] || die "the OpenSearch certificate produced no fingerprint."
    plugins_file="/run/secrets/plugins.yaml"
    [ "$profile" = "minimal" ] && plugins_file=""
    cat > "$env_file" <<ENV
# Written by deploy/setup.sh on first run. Edit freely; it is never overwritten.
#
# These are deployment CONFIGURATION, not secrets — secrets are files under
# deploy/secrets/ and are mounted, never interpolated.

HOMEINV_PROFILE=$profile

# Where the generated list of installed plugins is, inside api and worker.
# EMPTY in \`minimal\`, which runs no plugin -- a complete deployment rather
# than a degraded one (REQ-PLG-013). An instance with no list registers
# nothing, fetches nothing, and says so once at start-up.
HOMEINV_PLUGINS_FILE=$plugins_file

# ⚠ PRINTED ONTO EVERY LABEL. Changing it later invalidates every label already
# printed (10 §10.2.1). For anything beyond a local trial, set it before the
# first label is printed and not after.
HOMEINV_PUBLIC_BASE_URL=http://localhost:8080

# A DEDICATED hostname for media, same-site with the application host so that
# Cross-Origin-Resource-Policy: same-site does not block our own pages.
HOMEINV_MEDIA_BASE_URL=http://media.localhost:8080

# Both hops in front of api: the operator's reverse proxy and web's address on
# the frontend segment (REQ-SEC-103). The default covers a local container
# network; a real deployment narrows it.
HOMEINV_TRUSTED_PROXIES=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16

# The port on which api answers the plugins that call IT (ADR-0071). 0 is off,
# and off is right until a plugin is installed that holds host:render-document —
# a listener nothing can authenticate to is still a listener. Set it to 8091 with
# that plugin, and to nothing else: it is the only port in the deployment where
# the direction of a call reverses.
HOMEINV_PLUGIN_HOST_PORT=0

# Where the operator's OTLP collector listens (REQ-NFR-044, 13 §13.4). EMPTY is
# the default and means NO TRACING AT ALL: no tracer, no spans, no sampler, and
# the `traceId` in the logs and in every error document is unchanged. Set it and
# `api` and `worker` export spans there.
#
# It names a collector INSIDE the deployment, on the `internal` segment. These
# containers have no route to the internet (ADR-0026) and this does not give them
# one; what your collector forwards to afterwards is yours to decide. The
# sampling policy of 13 §13.4 -- everything that failed or was slow, a fraction
# of the rest -- belongs there too: the application sends everything, because a
# tail decision can only be made where the whole trace is.
#
#   HOMEINV_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces
HOMEINV_TRACING_ENDPOINT=

# The blobstore certificate this deployment just created, pinned by fingerprint.
HOMEINV_BLOBSTORE_FINGERPRINT=$fingerprint

# The same for OpenSearch. Unused in the minimal profile, which has none; the
# variable is written regardless, because a profile switched on later must not
# need a second run of this script to become reachable.
HOMEINV_SEARCH_FINGERPRINT=$search_fingerprint

# The first owner. The one-shot bootstrap service creates this account and the
# tenant it owns, once, and does nothing on every run after that (ADR-0053). Its
# password is a file like every other secret: deploy/secrets/bootstrap-password,
# generated on the first run and never overwritten — write your own there before
# the first start if you would rather choose it.
HOMEINV_BOOTSTRAP_EMAIL=$bootstrap_email
HOMEINV_BOOTSTRAP_DISPLAY_NAME=Owner
HOMEINV_BOOTSTRAP_LOCALE=en
HOMEINV_BOOTSTRAP_TENANT_NAME=Home
ENV
    chmod 0600 "$env_file"
    say "  compose/.env — written"
}

opensearch_hash() {
    runtime=$1
    password=$2
    image=$(grep -A1 '^  opensearch:' "$HERE/services.yaml" \
            | sed -n 's/.*name: \([^,]*\), tag: "\([^"]*\)".*/\1:\2/p')
    [ -n "$image" ] || die "Could not read the OpenSearch image from services.yaml."
    "$runtime" run --rm "$image" \
        ./plugins/opensearch-security/tools/hash.sh -p "$password" 2>/dev/null | tail -n 1
}

render_templates() {
    for source in "$GENERATED"/*; do
        name=$(basename "$source")
        case "$name" in
            README.md|host-prerequisites.sh|secret-kinds.sh) continue ;;
            opensearch-*) [ "$profile" = "minimal" ] && continue ;;
        esac
        target="$SECRETS/$name"
        case "$name" in
            *-plugins.yaml)
                if [ "$profile" = "minimal" ]; then
                    printf '# The minimal profile runs no plugin (REQ-PLG-013).\nplugins: []\n' \
                        > "$target"
                    chmod 0444 "$target"
                    say "  $name — empty, this profile runs no plugin"
                    continue
                fi
                ;;
        esac
        cp "$source" "$target"
        for secret in $(grep -o '@SECRET:[a-z0-9-]*@' "$source" | sed 's/@SECRET://; s/@//' | sort -u); do
            value_file="$SECRETS/$secret"
            [ -f "$value_file" ] || die "$name needs the secret '$secret' and it was not generated."
            value=$(cat "$value_file")
            case "$name" in
                opensearch-internal_users.yml) value=$(opensearch_hash "$container_runtime" "$value") ;;
            esac
            awk -v needle="@SECRET:$secret@" -v value="$value" \
                '{ gsub(needle, value); print }' "$target" > "$target.tmp"
            mv "$target.tmp" "$target"
        done
        for pin in $(grep -o '@FINGERPRINT:[a-z0-9-]*@' "$source" \
                     | sed 's/@FINGERPRINT://; s/@//' | sort -u); do
            certificate="$SECRETS/$pin.crt"
            [ -f "$certificate" ] \
                || die "$name pins the certificate '$pin' and it was not generated."
            value=$(openssl x509 -in "$certificate" -noout -fingerprint -sha256 \
                    | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
            [ -n "$value" ] || die "the certificate '$pin' produced no fingerprint."
            awk -v needle="@FINGERPRINT:$pin@" -v value="$value" \
                '{ gsub(needle, value); print }' "$target" > "$target.tmp"
            mv "$target.tmp" "$target"
        done
        for key in $(grep -o '@PUBKEY:[a-z0-9-]*@' "$source" \
                     | sed 's/@PUBKEY://; s/@//' | sort -u); do
            key_file="$SECRETS/$key"
            if [ -s "$key_file" ]; then
                value=$(tr -d '\r\n' < "$key_file")
            else
                value=""
            fi
            awk -v needle="@PUBKEY:$key@" -v value="$value" \
                '{ gsub(needle, value); print }' "$target" > "$target.tmp"
            mv "$target.tmp" "$target"
        done
        chmod 0444 "$target"
        say "  $name — rendered"
    done
}

mode=${1:-check}
profile=${2:-minimal}
container_runtime=$mode

step "Checking the host for the $profile profile"
host_ok=0
check_rootless || host_ok=1
check_host_prerequisites "$profile" || host_ok=1
[ "$host_ok" -eq 0 ] || die "The host is not ready. Fix the points above and run this again."
say "  rootless prerequisites: present"

[ "$mode" = "check" ] && { say ""; say "Host looks ready. Run '$0 docker' or '$0 podman' to continue."; exit 0; }

step "Creating secrets in deploy/secrets/"
mkdir -p "$SECRETS"
chmod 0700 "$SECRETS"
echo "$SECRET_KINDS" | while read -r name kind seed; do
    [ -n "$name" ] && generate_secret "$name" "$kind" "$seed"
done

step "Rendering the generated files"
render_templates

step "Writing the deployment variables"
write_environment

case "$mode" in
    docker)
        command -v docker >/dev/null 2>&1 || die "docker is not on the PATH."
        docker context inspect 2>/dev/null | grep -q 'rootless' \
            || say "  note: this does not look like a rootless Docker context. ADR-0022 supports no other."
        step "Starting the $profile profile"
        say "  docker compose --profile $profile up -d"
        (cd "$HERE/compose" && docker compose --profile "$profile" up -d)
        ;;
    podman)
        command -v podman >/dev/null 2>&1 || die "podman is not on the PATH."
        step "Installing the Quadlet units"
        units="${XDG_CONFIG_HOME:-$HOME/.config}/containers/systemd"
        targets="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
        mkdir -p "$units" "$targets"
        for unit in "$HERE"/quadlet/*; do
            case "$unit" in
                *.target) cp "$unit" "$targets/" ;;
                *.md) : ;;
                *) cp "$unit" "$units/" ;;
            esac
        done
        echo "$SECRET_KINDS" | while read -r name _; do
            [ -z "$name" ] && continue
            install_secret "$name"
        done
        for source in "$GENERATED"/*; do
            secret_name=$(basename "$source")
            case "$secret_name" in
                README.md|host-prerequisites.sh|secret-kinds.sh) continue ;;
            esac
            [ -f "$SECRETS/$secret_name" ] || continue
            install_secret "$secret_name"
        done
        env_dir="${XDG_CONFIG_HOME:-$HOME/.config}/homeinv"
        mkdir -p "$env_dir"
        cp "$HERE/compose/.env" "$env_dir/homeinv.env"
        chmod 0600 "$env_dir/homeinv.env"

        systemctl --user daemon-reload
        say "  units installed into $units"
        say "  profile targets installed into $targets"
        say "  variables installed into $env_dir/homeinv.env"

        step "Starting the $profile profile"
        say "  systemctl --user start homeinv-$profile.target"
        systemctl --user start "homeinv-$profile.target" \
            || die "homeinv-$profile.target did not come up. systemctl --user list-units 'homeinv-*' says which unit did not."
        say ""
        say "  at boot:        systemctl --user enable homeinv-$profile.target"
        say "  after a logout: loginctl enable-linger \$USER"
        ;;
    *)
        die "Unknown mode '$mode'. Use: check | docker | podman"
        ;;
esac

say ""
say "The first owner is ${bootstrap_email:-the address in compose/.env}."
say "Its password is in deploy/secrets/bootstrap-password — read it once and store it."
say "Change the address in compose/.env BEFORE the first start; afterwards the"
say "account exists and this script will not touch it again."

step "Done"
