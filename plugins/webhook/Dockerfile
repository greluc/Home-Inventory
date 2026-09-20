# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# `plugin-webhook` (ADR-0072).
#
# The runtime stage is `scratch`: the binary, and nothing else. No shell, no
# package manager, no libc — so there is nothing in the image for an attacker
# who reaches this process to run, and nothing to patch on a CVE announcement
# for a package it never used. The same shape as `blobstore/` and
# `egress-proxy/`, for the same reasons.
#
# It carries no CA bundle either, and does not need one: the public roots are
# compiled in as data (`webpki-roots`), which is what lets a `scratch` image
# verify a receiver's certificate at all.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM docker.io/library/rust:1-alpine AS build

# musl-dev and the rest are for `ring`, which compiles a little C and assembly.
RUN apk add --no-cache musl-dev perl make

WORKDIR /src

# The manifests first, so a source change does not invalidate the layer that
# fetched and built the dependency graph. `cargo` needs a source file to exist
# for the crate to resolve at all, hence the stub — and a build script, because
# this crate has one.
COPY plugins/webhook/Cargo.toml plugins/webhook/Cargo.lock ./plugins/webhook/
RUN mkdir -p plugins/webhook/src \
    && echo 'fn main() {}' > plugins/webhook/src/main.rs \
    && echo 'fn main() {}' > plugins/webhook/build.rs
WORKDIR /src/plugins/webhook
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY plugins/webhook/ ./plugins/webhook/

# Statically linked, so the runtime stage needs no libc at all.
WORKDIR /src/plugins/webhook
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-plugin-webhook /homeinv-plugin-webhook
WORKDIR /src

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-plugin-webhook /homeinv-plugin-webhook

# The uid the service matrix names. A numeric uid and not a name: `scratch` has
# no /etc/passwd to resolve one, and a rootless runtime maps it through subuid
# by number anyway.
USER 10001:10001

# Documentation only; the matrix decides what is published, and a plugin never
# is — it is reachable from its own segment and nowhere else (ADR-0037).
EXPOSE 8200

ENTRYPOINT ["/homeinv-plugin-webhook"]
