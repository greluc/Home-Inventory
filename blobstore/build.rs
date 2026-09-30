// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = PathBuf::from("..").join("proto");
    let contract = proto_root.join("home_inv/plugin/v1/blob_store.proto");

    println!("cargo:rerun-if-changed={}", contract.display());

    let descriptors = protox::compile([&contract], [&proto_root])?;

    tonic_prost_build::configure()
        .build_client(false)
        .compile_fds(descriptors)?;

    Ok(())
}
