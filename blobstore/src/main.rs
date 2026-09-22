// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The in-deployment `BlobStore` (ADR-0043, ADR-0050).
//!
//! It exists so that `api` and `worker` stay stateless: the default media store
//! is a directory, a directory has to live somewhere, and mounting it into the
//! application layer would have cost `REQ-NFR-008` — two `api` instances cannot
//! share a local filesystem, and the failure appears as *media that exist on one
//! instance and 404 on the other*, which is the hardest class of bug to
//! attribute.
//!
//! It is written in Rust because ADR-0043 budgeted it at 32 MB reserved and
//! 128 MB limit, and that sizing is what justified adding a container at all. A
//! JVM does not run in 128 MB. Between the languages that do, this one is chosen
//! because this process touches every byte of every tenant's media and has no
//! other job: no domain logic, no database, nothing but memory handling on
//! input somebody else supplied (ADR-0050).

mod service;
mod store;
mod tls;

/// The generated contract from `proto/home_inv/plugin/v1/blob_store.proto`.
///
/// `dead_code` is allowed here and nowhere else. The contract gained a `CallContext`
/// on 2026-09-21, which pulled `common.proto` into this module with every message
/// it carries — `Money`, `Check`, `Problem` and the rest, none of which this service
/// constructs and all of which a plugin does. The alternative would be a second
/// generated module per service, which is a build that knows what each consumer uses.
#[allow(dead_code)]
mod proto {
    tonic::include_proto!("home_inv.plugin.v1");
}

use std::net::SocketAddr;
use std::path::PathBuf;

use tonic::transport::Server;
use tracing::info;

use crate::proto::blob_store_server::BlobStoreServer;
use crate::service::FilesystemBlobStore;

/// Where the `blobdata` volume is mounted (06 §6.7).
const DEFAULT_ROOT: &str = "/var/lib/homeinv/blobs";

/// The port the service matrix names. Never published to the host.
const DEFAULT_PORT: u16 = 8100;

/// Where the runtime mounts this service's own identity.
const DEFAULT_IDENTITY: &str = "/run/secrets/mtls-blobstore";

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // THE LICENCE NOTICE THIS BINARY CARRIES (REQ-CON-013).
    //
    // A permissive licence asks for its notice in every copy, and a statically
    // linked binary is a copy. This image is `scratch` — one binary and nothing
    // else, which CI asserts — so there is no file to put beside it and the
    // notice is compiled in, exactly as the public roots are in the plugins
    // that speak TLS.
    //
    // First, before the logger: somebody reading a licence should get the
    // licence and not a JSON log line above it. `tools/notices.py` generates
    // the file and CI fails when it no longer describes what is linked in.
    if std::env::args().any(|argument| argument == "--licences") {
        print!("{}", include_str!("../THIRD-PARTY-NOTICES.txt"));
        return Ok(());
    }

    // JSON lines, like every other service in the deployment (REQ-NFR-041). An
    // operator reading one log format is an operator who can grep across
    // services.
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let root = PathBuf::from(env_or(DEFAULT_ROOT, "HOMEINV_BLOB_ROOT"));
    let identity_path = PathBuf::from(env_or(DEFAULT_IDENTITY, "HOMEINV_MTLS_BLOBSTORE_FILE"));

    if std::env::args().any(|argument| argument == "--health") {
        // The health command the service matrix names. The image is `scratch`:
        // there is no shell to run a script and no curl to call, so the binary
        // answers the question about itself.
        //
        // It asks whether the volume is writable, which is the failure this
        // service actually has: a read-only mount, a full disk, or a UID mapping
        // that changed under it. "The process is running" is not worth reporting
        // — the runtime already knows that.
        return match health(&root).await {
            Ok(()) => Ok(()),
            Err(failure) => {
                eprintln!("unhealthy: {failure}");
                std::process::exit(1);
            }
        };
    }

    tokio::fs::create_dir_all(&root).await?;

    let address: SocketAddr = format!("0.0.0.0:{}", env_port()).parse()?;
    let identity = tls::server_config(&identity_path)?;

    info!(
        root = %root.display(),
        address = %address,
        "the blob store is listening, mTLS required"
    );

    Server::builder()
        .tls_config(identity)?
        .add_service(BlobStoreServer::new(FilesystemBlobStore::new(root)))
        // The runtime stops a container with SIGTERM. Without this the process
        // is killed after the grace period instead of finishing the transfer it
        // is in the middle of.
        .serve_with_shutdown(address, async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;

    Ok(())
}

/// Writes and removes a probe file, to establish that the volume is usable.
async fn health(root: &std::path::Path) -> Result<(), std::io::Error> {
    let probe = root.join(".health");
    tokio::fs::write(&probe, b"ok").await?;
    tokio::fs::remove_file(&probe).await
}

fn env_or(default: &str, variable: &str) -> String {
    std::env::var(variable).unwrap_or_else(|_| default.to_owned())
}

fn env_port() -> u16 {
    std::env::var("HOMEINV_BLOBSTORE_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(DEFAULT_PORT)
}

#[cfg(test)]
mod licences {
    /// The notice is compiled into the binary rather than read from a file
    /// (`REQ-CON-013`). A `scratch` image has no filesystem to read one from,
    /// so an absent notice would be a link error here and a licence breach in
    /// production; this makes it the former.
    #[test]
    fn the_notice_travels_with_the_binary() {
        let notice = include_str!("../THIRD-PARTY-NOTICES.txt");

        assert!(notice.starts_with("THIRD-PARTY LICENCE NOTICES"));
        assert!(notice.contains("tonic"));
        assert!(
            notice.len() > 10_000,
            "a notice this short is a generator that failed"
        );
    }
}
