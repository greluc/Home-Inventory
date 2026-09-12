# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The in-deployment BlobStore (ADR-0043, ADR-0050).
#
# The runtime stage is `scratch`: the binary, and nothing else. No shell, no
# package manager, no libc — so there is nothing in the image for an attacker
# who reaches this process to run, and nothing to patch on a CVE announcement
# for a package this service never used.
#
# That is also why the health check is the binary with a flag rather than a
# script (see main.rs): there is no `sh` to run one, and adding one would mean
# adding a shell to the container that holds every tenant's media.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM docker.io/library/rust:1-alpine AS build

# musl-dev and the rest are for `ring`, which compiles a little C and assembly.
# `--no-cache` rather than a cleanup step: apk writes its index outside the
# layer we would clean.
RUN apk add --no-cache musl-dev perl make

WORKDIR /src

# The manifests first, so a source change does not invalidate the layer that
# fetched and built the dependency graph. `cargo` needs a source file to exist
# for the crate to resolve at all, hence the stub.
COPY blobstore/Cargo.toml blobstore/Cargo.lock ./blobstore/
RUN mkdir -p blobstore/src && echo 'fn main() {}' > blobstore/src/main.rs
WORKDIR /src/blobstore
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY blobstore/ ./blobstore/

# Statically linked, so the runtime stage needs no libc at all.
# `WORKDIR`, not `RUN cd`: the directory is part of the image's state and
# belongs in an instruction that records it (trivy DS-0013).
WORKDIR /src/blobstore
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-blobstore /homeinv-blobstore
WORKDIR /src

# The data directory, created here because `scratch` has no shell to create it
# and the process runs as uid 10001, which cannot write to a root-owned `/`.
# In a deployment the `blobdata` volume is mounted over this path; without it the
# service still starts, which is what makes the image testable on its own.
RUN mkdir -p /blobs

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-blobstore /homeinv-blobstore
COPY --from=build --chown=10001:10001 /blobs /var/lib/homeinv/blobs

# The uid the service matrix names. A numeric uid and not a name: `scratch` has
# no /etc/passwd to resolve one, and a rootless runtime maps it through subuid
# by number anyway.
USER 10001:10001

# Documentation only; the matrix decides what is published, and this one never
# is — it is reachable on `internal` and nowhere else (ADR-0043).
EXPOSE 8100

ENTRYPOINT ["/homeinv-blobstore"]
