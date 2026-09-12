# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The egress proxy (ADR-0027, ADR-0036, ADR-0054).
#
# `scratch`, like the blob store and for the same reason: this container is the
# one with a route out of the deployment, so there is nothing in it for anybody
# who reaches the process to run — no shell, no package manager, no libc.
#
# DNS still works: the binary is statically linked against musl, whose resolver
# reads /etc/resolv.conf directly rather than through NSS plugins, and every
# runtime mounts that file into the container.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev

WORKDIR /src

# The manifests first, so a source change does not invalidate the layer that
# fetched the dependency graph. Cargo needs a source file for the crate to
# resolve at all, hence the stub.
COPY egress-proxy/Cargo.toml egress-proxy/Cargo.lock ./egress-proxy/
RUN mkdir -p egress-proxy/src \
    && echo 'fn main() {}' > egress-proxy/src/main.rs \
    && cd egress-proxy \
    && cargo fetch --locked

COPY egress-proxy/ ./egress-proxy/

RUN cd egress-proxy \
    && RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-egress-proxy /homeinv-egress-proxy

# The mount point for the generated allowlist, created here because `scratch` has
# no shell to create it and the process runs as uid 10001, which cannot write to
# a root-owned `/`. A runtime mounts the file over it; without one the proxy
# refuses to start, which is the behaviour ADR-0036 asks for.
RUN mkdir -p /etc/homeinv

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-egress-proxy /homeinv-egress-proxy
COPY --from=build --chown=10001:10001 /etc/homeinv /etc/homeinv

# The uid the service matrix names. A numeric uid and not a name: `scratch` has
# no /etc/passwd to resolve one, and a rootless runtime maps it through subuid by
# number anyway.
USER 10001:10001

# Documentation only; the matrix decides what is published, and this one never
# is — it is reachable from the segments it serves and from nowhere else.
EXPOSE 8118

ENTRYPOINT ["/homeinv-egress-proxy"]
