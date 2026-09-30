# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev perl make

WORKDIR /src

COPY blobstore/Cargo.toml blobstore/Cargo.lock ./blobstore/
RUN mkdir -p blobstore/src && echo 'fn main() {}' > blobstore/src/main.rs
WORKDIR /src/blobstore
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY blobstore/ ./blobstore/

WORKDIR /src/blobstore
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-blobstore /homeinv-blobstore
WORKDIR /src

RUN mkdir -p /blobs

FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-blobstore /homeinv-blobstore
COPY --from=build --chown=10001:10001 /blobs /var/lib/homeinv/blobs

USER 10001:10001

EXPOSE 8100

ENTRYPOINT ["/homeinv-blobstore"]
