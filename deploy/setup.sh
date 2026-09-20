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
#      source, into deploy/secrets/ — a 0700 directory holding 0444 files, for
#      the reason given at generate_secret(). It never overwrites: a
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
    # P-256 and not Ed25519. The mTLS material has to be readable by everything
    # that presents or verifies it, and grpc-java's key manager parses RSA, DSA
    # and EC only — an Ed25519 key reaches it as "Neither RSA, DSA nor EC worked"
    # and `api` refuses to start. Found by starting the stack; the JWT signing key
    # stays Ed25519, because that one is read by our own code.
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
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
    # P-256, for the reason `ensure_ca` gives.
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
    # The combined file is a mounted secret and its caller sets its mode; the
    # two halves it was built from are read by nobody and stay unreadable.
    chmod 0600 "$key" "$crt"
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
        # Re-applied even when kept: a deployment set up before the modes were
        # corrected holds files its own containers cannot read.
        chmod 0444 "$target"
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
    # 0444 in a 0700 directory, and the directory is the protection. Compose
    # mounts a secret by bind-mounting this very file, so the mode here is the
    # mode the container sees — and under ROOTLESS Docker the host user maps to
    # uid 0 inside the container while the service runs as 10001, which makes a
    # 0600 file unreadable. postgres, valkey, clamav, blobstore and the egress
    # proxy each died on "Permission denied" for a file that was plainly there.
    # Compose will not fix it per mount either: it answers uid/gid/mode with
    # "not supported, they will be ignored". Nothing on the host gains access,
    # because a file inside a 0700 directory cannot be reached to be read.
    # Podman copies these into its own secret store, which mounts them 0444 too.
    chmod 0444 "$target"
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
    # A placeholder rather than a guess. The bootstrap service refuses to invent an
    # account, so an address that is not this one's is a deployment with no owner
    # and a clear message saying so — which is better than an account at an address
    # nobody reads.
    bootstrap_email="${HOMEINV_BOOTSTRAP_EMAIL:-owner@example.invalid}"
    # The certificate has to exist by now — it is generated a step earlier. If
    # it does not, the fingerprint below is empty, Compose interpolates an empty
    # string, and `api` fails to start much later with a message about a pin
    # rather than about a missing file. `compose/.env` is never overwritten, so a
    # wrong value written here is a wrong value for ever.
    [ -f "$SECRETS/mtls-blobstore.crt" ] \
        || die "$SECRETS/mtls-blobstore.crt is missing; the secrets step did not finish."
    fingerprint=$(openssl x509 -in "$SECRETS/mtls-blobstore.crt" -noout -fingerprint -sha256 \
                  | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
    [ -n "$fingerprint" ] || die "the blob store certificate produced no fingerprint."
    # The same for OpenSearch, for the same reason: the CA signs every service
    # here, so the certificate that may answer as the index is named rather than
    # accepted merely because something signed it (REQ-SEC-056, ADR-0044).
    [ -f "$SECRETS/mtls-search.crt" ] \
        || die "$SECRETS/mtls-search.crt is missing; the secrets step did not finish."
    search_fingerprint=$(openssl x509 -in "$SECRETS/mtls-search.crt" -noout -fingerprint -sha256 \
                  | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')
    [ -n "$search_fingerprint" ] || die "the OpenSearch certificate produced no fingerprint."
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

# The port on which api answers the plugins that call IT (ADR-0071). 0 is off,
# and off is right until a plugin is installed that holds host:render-document —
# a listener nothing can authenticate to is still a listener. Set it to 8091 with
# that plugin, and to nothing else: it is the only port in the deployment where
# the direction of a call reverses.
HOMEINV_PLUGIN_HOST_PORT=0

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
        # 0444 for the same reason as a generated secret; see generate_secret().
        chmod 0444 "$target"
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
        targets="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
        mkdir -p "$units" "$targets"
        # Two destinations, because these are two kinds of file. The `.container`,
        # `.volume` and `.network` files are Quadlet's, and it reads only its own
        # directory; the profile targets are ordinary systemd units, and systemd
        # never reads Quadlet's directory. A target copied to the wrong one of the
        # two is a file that nothing at all looks at. The README travelled with
        # them until now and belongs in neither.
        for unit in "$HERE"/quadlet/*; do
            case "$unit" in
                *.target) cp "$unit" "$targets/" ;;
                *.md) : ;;
                *) cp "$unit" "$units/" ;;
            esac
        done
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
        # The same variables Compose reads from compose/.env, in the place the
        # units name. systemd does not interpolate `${VAR}` in `Environment=`, so
        # the units carry `EnvironmentFile=` and this is that file: one place to
        # edit after installation, exactly as on the Compose side.
        env_dir="${XDG_CONFIG_HOME:-$HOME/.config}/homeinv"
        mkdir -p "$env_dir"
        cp "$HERE/compose/.env" "$env_dir/homeinv.env"
        chmod 0600 "$env_dir/homeinv.env"

        systemctl --user daemon-reload
        say "  units installed into $units"
        say "  profile targets installed into $targets"
        say "  variables installed into $env_dir/homeinv.env"

        # The same promise the Compose branch keeps: one command, and the stack
        # is running (REQ-NFR-028). This branch used to install the units and
        # print "start with: systemctl --user start homeinv-api", which starts
        # `api` and the six things `api` requires — leaving `web` down, and `web`
        # holds the only published port in the deployment. The one command
        # produced a stack with nothing to connect to.
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
