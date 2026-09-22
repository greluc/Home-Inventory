// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Compiles the NotificationChannel contract from `proto/`.
//
// `protox` parses the `.proto` files in pure Rust, so no `protoc` binary has to
// exist on the machine doing the build — the same reasoning as
// `blobstore/build.rs`, and the same consequence: one fewer version-pinned
// native dependency in every CI image and on every contributor's machine.

use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = PathBuf::from("..").join("..").join("proto");
    let contract = proto_root.join("home_inv/plugin/v1/notification.proto");
    let health = proto_root.join("home_inv/plugin/v1/health.proto");

    println!("cargo:rerun-if-changed={}", contract.display());
    println!("cargo:rerun-if-changed={}", health.display());

    let descriptors = protox::compile([&contract, &health], [&proto_root])?;

    tonic_prost_build::configure()
        .build_client(false)
        .compile_fds(descriptors)?;

    Ok(())
}
