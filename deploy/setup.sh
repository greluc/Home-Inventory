#!/bin/sh
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# One command, from a fresh checkout to a running stack (REQ-NFR-028, REQ-NFR-051).
#
#     ./deploy/setup.sh docker      # rootless Docker with Compose v2
#     ./deploy/setup.sh podman      # rootless Podman with Quadlet
#     ./deploy/setup.sh check       # prerequisites only, change nothing
#
# A second argument selects the profile (default `minimal`); it decides which
# host prerequisites apply, because vm.max_map_count is OpenSearch's and
# OpenSearch does not run in `minimal`.
#
# It does three things and refuses to do any of them badly:
#
#   1. CHECKS THE HOST. subuid/subgid, lingering, cgroup v2 delegation and the
#      sysctls the matrix declares. Each missing one aborts with the command that
#      fixes it — because the failure without this check is not a clear error, it
#      is a container that starts and then behaves strangely (REQ-NFR-060).
#   2. GENERATES THE SECRETS it does not already find, with the system random
#      source, into deploy/secrets/ with mode 0600. It never overwrites: a
#      regenerated key means every session invalid and every signed URL broken,
#      and a setup script is not where that decision should be made.
#   3. RENDERS THE TEMPLATES from deploy/generated/ into deploy/secrets/,
#      substituting those values.
#
# It is POSIX sh on purpose. It runs before anything else is installed, which is
# the one moment it cannot assume bash.
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

# ---------------------------------------------------------------------------
# 1. The host
# ---------------------------------------------------------------------------
# shellcheck source=generated/host-prerequisites.sh
. "$GENERATED/host-prerequisites.sh"

check_rootless() {
    missing=0

    if [ "$(id -u)" = "0" ]; then
        # Not a warning. ADR-0022 has no supported path with a root daemon or a
        # root container, and running this as root would set up exactly that.
        die "Run this as your own user, not as root. Rootless is the only supported way (ADR-0022)."
    fi

    user=$(id -un)

    # subuid/subgid: without a range, a rootless container has one uid to map
    # into and every service that does not run as uid 0 inside fails to start.
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

    # Lingering: without it systemd tears the user's services down at logout,
    # which for a server is "the stack stops when you close the terminal".
    if command -v loginctl >/dev/null 2>&1; then
        if [ "$(loginctl show-user "$user" --property=Linger --value 2>/dev/null || echo no)" != "yes" ]; then
            say "MISSING: lingering is off for $user."
            say "         The stack would stop at logout and not come back at boot."
            say "         Fix: sudo loginctl enable-linger $user"
            missing=1
        fi
    fi

    # cgroup v2 with delegated controllers: without memory delegation every
    # memory limit in the generated descriptions is accepted and ignored, which
    # is worse than rejecting them - the stack runs unbounded and looks fine.
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

# ---------------------------------------------------------------------------
# 2. Secrets
# ---------------------------------------------------------------------------
# The names AND their kinds come from deploy/generated/secret-kinds.sh, which is
# rendered from services.yaml. "Generate a secret" means three different things
# here, and a wrong guess produces a file the service accepts and cannot use:
# RabbitMQ with a PEM file as its password starts and refuses every connection.
# shellcheck source=generated/secret-kinds.sh
. "$GENERATED/secret-kinds.sh"

CA_KEY="$SECRETS/deployment-ca.key"
CA_CERT="$SECRETS/deployment-ca.crt"

# One CA for the deployment, created once. Every mTLS identity below is signed by
# it, and `HOMEINV_BLOBSTORE_FINGERPRINT` pins the certificate that matters
# (REQ-SEC-056) — `internal` is not a trust boundary, so reachability is not
# authorisation (ADR-0044).
ensure_ca() {
    [ -f "$CA_CERT" ] && return 0
    command -v openssl >/dev/null 2>&1 || die "openssl is needed to create the deployment CA."
    openssl req -x509 -newkey ed25519 -nodes -days 3650 \
        -keyout "$CA_KEY" -out "$CA_CERT" \
        -subj "/CN=Home Inventory deployment CA" >/dev/null 2>&1
    chmod 0600 "$CA_KEY" "$CA_CERT"
    say "  deployment-ca — created (valid ten years, local to this deployment)"
}

# A certificate and its key, concatenated with the CA so the peer can verify
# without a second mount. `service` is the name the other side connects to, and
# it has to be in the SAN or every TLS handshake in the deployment fails on
# hostname verification.
generate_mtls() {
    name=$1
    service=$2
    ensure_ca
    key="$SECRETS/$name.key"
    csr="$SECRETS/$name.csr"
    crt="$SECRETS/$name.crt"
    openssl req -newkey ed25519 -nodes -keyout "$key" -out "$csr" \
        -subj "/CN=$service" >/dev/null 2>&1
    openssl x509 -req -in "$csr" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
        -days 825 -out "$crt" \
        -extfile /dev/stdin >/dev/null 2>&1 <<EXT
subjectAltName = DNS:$service
extendedKeyUsage = serverAuth, clientAuth
EXT
    cat "$key" "$crt" "$CA_CERT" > "$SECRETS/$name"
    rm -f "$csr"
    chmod 0600 "$SECRETS/$name" "$key" "$crt"
}

generate_secret() {
    name=$1
    kind=$2
    target="$SECRETS/$name"
    if [ -f "$target" ]; then
        # Never overwritten. A regenerated key means every session invalid and
        # every signed URL broken, and a setup script is not where that decision
        # belongs.
        say "  $name — kept (it already exists)"
        return 0
    fi
    case "$kind" in
        random)
            # 32 bytes of the system random source. Never a passphrase, never a
            # date, never a hostname: these are read by machines only, and a
            # memorable secret is a guessable one.
            head -c 32 /dev/urandom | base64 | tr -d '\n' > "$target"
            ;;
        ed25519)
            command -v openssl >/dev/null 2>&1 || die "openssl is needed for the $name key pair."
            openssl genpkey -algorithm ed25519 -out "$target" >/dev/null 2>&1
            ;;
        mtls-client) generate_mtls "$name" "api" ;;
        mtls-server) generate_mtls "$name" "$(echo "$name" | sed 's/^mtls-//')" ;;
        *) die "Unknown secret kind '$kind' for $name. services.yaml and this script disagree." ;;
    esac
    chmod 0600 "$target"
    say "  $name — generated ($kind)"
}

# ---------------------------------------------------------------------------
# 2b. The deployment's own variables
# ---------------------------------------------------------------------------
# compose.yaml interpolates a handful of values that are deployment configuration
# rather than secrets. Without a .env file Compose substitutes empty strings and
# the stack starts misconfigured rather than failing (06 §6.11).
write_environment() {
    env_file="$HERE/compose/.env"
    if [ -f "$env_file" ]; then
        say "  compose/.env — kept (it already exists)"
        return 0
    fi
    fingerprint=$(openssl x509 -in "$SECRETS/mtls-blobstore.crt" -noout -fingerprint -sha256 \
                  | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
    cat > "$env_file" <<ENV
# Written by deploy/setup.sh on first run. Edit freely; it is never overwritten.
#
# These are deployment CONFIGURATION, not secrets — secrets are files under
# deploy/secrets/ and are mounted, never interpolated.

HOMEINV_PROFILE=$profile

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

# The address the management listener binds to. It must be the container's
# address on the internal segment and nothing wider (REQ-SEC-099).
INTERNAL_ADDR=0.0.0.0

# The blobstore certificate this deployment just created, pinned by fingerprint.
HOMEINV_BLOBSTORE_FINGERPRINT=$fingerprint
ENV
    chmod 0600 "$env_file"
    say "  compose/.env — written"
}

# ---------------------------------------------------------------------------
# 3. Templates
# ---------------------------------------------------------------------------
# Each file in deploy/generated/ that carries an @SECRET:name@ placeholder is
# rendered into deploy/secrets/ with the value substituted. The rest are already
# complete and are copied.
# OpenSearch stores a bcrypt hash, not the password. There is no bcrypt in POSIX
# shell, and the tool that produces the right one ships inside the image — so it
# is computed there, by the same image the deployment runs.
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
            # Not deliverables: one is prose, the other two are sourced by this
            # script itself.
            README.md|host-prerequisites.sh|secret-kinds.sh) continue ;;
            # OpenSearch runs in `standard` and `ha` only. Rendering its accounts
            # in `minimal` would need a hash from an image that is not pulled.
            opensearch-*) [ "$profile" = "minimal" ] && continue ;;
        esac
        target="$SECRETS/$name"
        cp "$source" "$target"
        for secret in $(grep -o '@SECRET:[a-z0-9-]*@' "$source" | sed 's/@SECRET://; s/@//' | sort -u); do
            value_file="$SECRETS/$secret"
            [ -f "$value_file" ] || die "$name needs the secret '$secret' and it was not generated."
            value=$(cat "$value_file")
            case "$name" in
                opensearch-internal_users.yml) value=$(opensearch_hash "$container_runtime" "$value") ;;
            esac
            # awk rather than sed: a base64 value contains / and + and would end
            # a sed expression, and gsub takes the replacement literally enough
            # for an alphabet with no `&` in it.
            awk -v needle="@SECRET:$secret@" -v value="$value" \
                '{ gsub(needle, value); print }' "$target" > "$target.tmp"
            mv "$target.tmp" "$target"
        done
        chmod 0600 "$target"
        say "  $name — rendered"
    done
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
mode=${1:-check}
profile=${2:-minimal}
# `check` needs no runtime; the two real modes name their own.
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
echo "$SECRET_KINDS" | while read -r name kind; do
    [ -n "$name" ] && generate_secret "$name" "$kind"
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
        mkdir -p "$units"
        cp "$HERE"/quadlet/* "$units/"
        echo "$SECRET_KINDS" | while read -r name _; do
            [ -z "$name" ] && continue
            podman secret exists "$name" 2>/dev/null \
                || podman secret create "$name" "$SECRETS/$name" >/dev/null
        done
        # The generated files travel the same way: a secret mount is the one
        # mechanism every runtime has for getting a file into a container with a
        # read-only root filesystem and no bind mounts (ADR-0022).
        for rendered in "$SECRETS"/*.conf "$SECRETS"/*.acl "$SECRETS"/*.yml; do
            [ -f "$rendered" ] || continue
            secret_name=$(basename "$rendered")
            podman secret exists "$secret_name" 2>/dev/null \
                || podman secret create "$secret_name" "$rendered" >/dev/null
        done
        systemctl --user daemon-reload
        say "  units installed into $units"
        say "  start with: systemctl --user start homeinv-api"
        ;;
    *)
        die "Unknown mode '$mode'. Use: check | docker | podman"
        ;;
esac

step "Done"
