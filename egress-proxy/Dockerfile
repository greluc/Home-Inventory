# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM docker.io/library/rust:1-alpine AS build

RUN apk add --no-cache musl-dev

WORKDIR /src

COPY egress-proxy/Cargo.toml egress-proxy/Cargo.lock ./egress-proxy/
RUN mkdir -p egress-proxy/src && echo 'fn main() {}' > egress-proxy/src/main.rs
WORKDIR /src/egress-proxy
RUN cargo fetch --locked
WORKDIR /src

COPY egress-proxy/ ./egress-proxy/

WORKDIR /src/egress-proxy
RUN RUSTFLAGS='-C target-feature=+crt-static' \
       cargo build --release --locked --target x86_64-unknown-linux-musl \
    && cp target/x86_64-unknown-linux-musl/release/homeinv-egress-proxy /homeinv-egress-proxy
WORKDIR /src

RUN mkdir -p /etc/homeinv

FROM scratch

COPY --from=build --chown=10001:10001 /homeinv-egress-proxy /homeinv-egress-proxy
COPY --from=build --chown=10001:10001 /etc/homeinv /etc/homeinv

USER 10001:10001

EXPOSE 8118

ENTRYPOINT ["/homeinv-egress-proxy"]
