# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev perl make

WORKDIR /src

COPY plugins/common/ ./plugins/common/
COPY plugins/blobstore-nextcloud/Cargo.toml plugins/blobstore-nextcloud/Cargo.lock ./plugins/blobstore-nextcloud/
RUN mkdir -p plugins/blobstore-nextcloud/src \
    && echo 'fn main() {}' > plugins/blobstore-nextcloud/src/main.rs \
    && echo 'fn main() {}' > plugins/blobstore-nextcloud/build.rs
WORKDIR /src/plugins/blobstore-nextcloud
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY plugins/blobstore-nextcloud/ ./plugins/blobstore-nextcloud/

WORKDIR /src/plugins/blobstore-nextcloud
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-plugin-blobstore-nextcloud /homeinv-plugin-blobstore-nextcloud
WORKDIR /src

FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-plugin-blobstore-nextcloud /homeinv-plugin-blobstore-nextcloud

USER 10001:10001

EXPOSE 8203

ENTRYPOINT ["/homeinv-plugin-blobstore-nextcloud"]
