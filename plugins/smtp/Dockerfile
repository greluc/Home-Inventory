# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# `plugin-smtp` (ADR-0072).
#
# Same shape as the webhook plugin's, and the same reasoning: `scratch`, one
# statically linked binary, the public roots compiled in as data because there
# is no system trust store to read.
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
# The shared crate comes first and whole: it is a path dependency, so `cargo`
# resolves nothing at all without it (ADR-0072).
COPY plugins/common/ ./plugins/common/
COPY plugins/smtp/Cargo.toml plugins/smtp/Cargo.lock ./plugins/smtp/
RUN mkdir -p plugins/smtp/src \
    && echo 'fn main() {}' > plugins/smtp/src/main.rs \
    && echo 'fn main() {}' > plugins/smtp/build.rs
WORKDIR /src/plugins/smtp
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY plugins/smtp/ ./plugins/smtp/

# Statically linked, so the runtime stage needs no libc at all.
WORKDIR /src/plugins/smtp
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-plugin-smtp /homeinv-plugin-smtp
WORKDIR /src

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-plugin-smtp /homeinv-plugin-smtp

# The uid the service matrix names. A numeric uid and not a name: `scratch` has
# no /etc/passwd to resolve one, and a rootless runtime maps it through subuid
# by number anyway.
USER 10001:10001

# Documentation only; the matrix decides what is published, and a plugin never
# is — it is reachable from its own segment and nowhere else (ADR-0037).
EXPOSE 8201

ENTRYPOINT ["/homeinv-plugin-smtp"]
