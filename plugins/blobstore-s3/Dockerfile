# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev perl make

WORKDIR /src

COPY plugins/common/ ./plugins/common/
COPY plugins/blobstore-s3/Cargo.toml plugins/blobstore-s3/Cargo.lock ./plugins/blobstore-s3/
RUN mkdir -p plugins/blobstore-s3/src \
    && echo 'fn main() {}' > plugins/blobstore-s3/src/main.rs \
    && echo 'fn main() {}' > plugins/blobstore-s3/build.rs
WORKDIR /src/plugins/blobstore-s3
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY plugins/blobstore-s3/ ./plugins/blobstore-s3/

WORKDIR /src/plugins/blobstore-s3
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-plugin-blobstore-s3 /homeinv-plugin-blobstore-s3
WORKDIR /src

FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-plugin-blobstore-s3 /homeinv-plugin-blobstore-s3

USER 10001:10001

EXPOSE 8202

ENTRYPOINT ["/homeinv-plugin-blobstore-s3"]
