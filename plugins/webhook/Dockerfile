# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev perl make

WORKDIR /src

COPY plugins/common/ ./plugins/common/
COPY plugins/webhook/Cargo.toml plugins/webhook/Cargo.lock ./plugins/webhook/
RUN mkdir -p plugins/webhook/src \
    && echo 'fn main() {}' > plugins/webhook/src/main.rs \
    && echo 'fn main() {}' > plugins/webhook/build.rs
WORKDIR /src/plugins/webhook
RUN cargo fetch --locked
WORKDIR /src

COPY proto/ ./proto/
COPY plugins/webhook/ ./plugins/webhook/

WORKDIR /src/plugins/webhook
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-plugin-webhook /homeinv-plugin-webhook
WORKDIR /src

FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-plugin-webhook /homeinv-plugin-webhook

USER 10001:10001

EXPOSE 8200

ENTRYPOINT ["/homeinv-plugin-webhook"]
