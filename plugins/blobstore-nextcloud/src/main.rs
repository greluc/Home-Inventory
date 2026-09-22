// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! `plugin-blobstore-nextcloud` — a tenant's media in its own Nextcloud.
//!
//! The fourth of the five first-party plugins
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)) and the
//! second implementation of the `BlobStore` port that is not the deployment's
//! own service. It is the path [ADR-0007](../../../docs/adr/0007-media-storage.md)
//! named first: photographs end up in a store that is already backed up,
//! synchronised and shared, and the person who owns them can open the folder.
//!
//! # Configured twice (ADR-0073)
//!
//! | | Who decides | Where |
//! |---|---|---|
//! | the deployment's instance, account and app password | the operator | this container's environment and a mounted secret |
//! | a tenant's **own** instance, account, app password and folder | that tenant | the call envelope, the secret sealed in the core |
//! | which hosts may be reached at all | the operator | the manifest's allowlist, enforced by the proxy |
//!
//! # What it refuses
//!
//! A `sha256` that is not a content address, bytes whose hash is not the one the
//! core declared, and a call whose envelope names a different tenant from its
//! address — the same three refusals `plugin-blobstore-s3` makes, for the same
//! reasons and with the same wording.
//!
//! # Granting this plugin moves nothing
//!
//! What was stored before the grant stays in the deployment's own store and is
//! still readable from there
//! ([ADR-0074](../../../docs/adr/0074-granting-a-store-routes-and-does-not-move.md)).
//! This plugin therefore answers `Head` with "no" for blobs it has never been
//! given, and that answer is load-bearing rather than a failure.

mod dav;
mod target;

/// The generated contract from `proto/home_inv/plugin/v1/`.
///
/// `dead_code` is allowed here and nowhere else: the generated module carries
/// every message of `common.proto`, and this plugin constructs three of them.
#[allow(dead_code)]
mod proto {
    tonic::include_proto!("home_inv.plugin.v1");
}

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use homeinv_plugin_common::blob::is_content_address;
use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::http::Body;
use homeinv_plugin_common::mac::Running;
use homeinv_plugin_common::time::now;
use homeinv_plugin_common::tls;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::Stream;
use tonic::transport::Server;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::dav::{failure, make_collection, MAX_ERROR_BODY};
use crate::proto::blob_store_server::{BlobStore, BlobStoreServer};
use crate::proto::plugin_health_server::{PluginHealth, PluginHealthServer};
use crate::proto::{
    AppendStagedRequest, BlobRef, CallContext, Check, DeleteRequest, DeleteResponse, GetRequest,
    GetResponse, HeadRequest, HeadResponse, HealthRequest, HealthResponse, HealthState, PutRequest,
    PutResponse, StagedRequest, StagedResponse,
};
use crate::target::{Defaults, Target};

/// The port the service matrix names for this plugin.
const DEFAULT_PORT: u16 = 8203;

/// Where the runtime mounts this plugin's own identity.
const DEFAULT_IDENTITY: &str = "/run/secrets/mtls-plugin-blobstore-nextcloud";

/// Where the runtime mounts the deployment's app password.
const DEFAULT_PASSWORD_FILE: &str = "/run/secrets/plugin-blobstore-nextcloud-password";

/// How long one request may take, tunnel included.
const DEFAULT_TIMEOUT_SECONDS: u64 = 60;

/// How large a chunk of a chunked upload is.
///
/// The same 8 MiB the S3 plugin uses, and for the same reason: two of them fit
/// in this container's budget, and an object that never reaches this size is
/// uploaded in one request and never becomes a chunked upload at all.
const CHUNK: usize = 8 * 1024 * 1024;

/// How many chunks of a download may wait for the core to read them.
const GET_BUFFER: usize = 4;

/// Numbers one upload apart from the next in this process.
static UPLOADS: AtomicU64 = AtomicU64::new(0);

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

    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let port: u16 = env("HOMEINV_PLUGIN_PORT")
        .and_then(|value| value.parse().ok())
        .unwrap_or(DEFAULT_PORT);
    let identity =
        PathBuf::from(env("HOMEINV_MTLS_PLUGIN_FILE").unwrap_or(DEFAULT_IDENTITY.into()));
    let proxy = env("HOMEINV_EGRESS_PROXY");
    let defaults = read_defaults();

    let address: SocketAddr = format!("0.0.0.0:{port}").parse()?;
    info!(
        port = port,
        instance = defaults.url.as_deref().unwrap_or("none"),
        "plugin-blobstore-nextcloud is listening"
    );

    let unready = unready(&defaults, proxy.as_deref());
    for missing in &unready {
        // Said once, at startup, and each one names what to set. A plugin that
        // cannot store is a plugin an operator has to be able to diagnose
        // without reading its source (REQ-PLG-015).
        warn!("plugin-blobstore-nextcloud is not ready: {missing}");
    }
    if !defaults.touched() {
        info!(
            "no deployment default is configured; every tenant that uses this plugin brings its \
             own instance, account and app password (ADR-0073)"
        );
    }

    let store = Store {
        proxy,
        defaults,
        timeout: Duration::from_secs(
            env("HOMEINV_NEXTCLOUD_TIMEOUT_SECONDS")
                .and_then(|value| value.parse().ok())
                .unwrap_or(DEFAULT_TIMEOUT_SECONDS),
        ),
    };
    let health = Health { unready };

    Server::builder()
        .tls_config(tls::server_config(&identity)?)?
        .add_service(BlobStoreServer::new(store))
        .add_service(PluginHealthServer::new(health))
        .serve_with_shutdown(address, async {
            let _ = tokio::signal::ctrl_c().await;
            info!("plugin-blobstore-nextcloud is stopping");
        })
        .await?;
    Ok(())
}

/// Reads one setting from the environment, treating blank as absent.
fn env(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// What the operator configured in this container.
fn read_defaults() -> Defaults {
    Defaults {
        url: env("HOMEINV_NEXTCLOUD_URL"),
        username: env("HOMEINV_NEXTCLOUD_USER"),
        password: read_secret(
            &env("HOMEINV_NEXTCLOUD_PASSWORD_FILE").unwrap_or(DEFAULT_PASSWORD_FILE.into()),
        ),
        folder: env("HOMEINV_NEXTCLOUD_FOLDER"),
    }
}

/// Reads a mounted secret, trimming the newline a file usually ends with.
///
/// Absent and empty are the same answer here, because `setup.sh` creates this
/// file EMPTY on purpose: a password to somebody else's Nextcloud is not one
/// this deployment may invent.
fn read_secret(path: &str) -> Option<String> {
    std::fs::read_to_string(Path::new(path))
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// What stands between this plugin and being able to store anything.
///
/// A deployment with **no** default at all is not unready: every tenant may
/// bring its own account, and that is a supported way to run this. A deployment
/// with half a default is unready and says which half.
fn unready(defaults: &Defaults, proxy: Option<&str>) -> Vec<String> {
    let mut missing = Vec::new();
    if proxy.is_none() {
        missing.push(
            "HOMEINV_EGRESS_PROXY is not set, and a plugin segment has no route out of the \
             deployment on its own (ADR-0026)"
                .to_string(),
        );
    }
    if defaults.touched() {
        missing.extend(defaults.incomplete());
    }
    missing
}

/// The store itself.
struct Store {
    /// `host:port` of the egress proxy, the one route out.
    proxy: Option<String>,
    /// What the operator configured.
    defaults: Defaults,
    /// How long one request may take.
    timeout: Duration,
}

/// One call's destination: where the instance is, and what this blob is called
/// there.
///
/// `Debug` is derived and safe: the app password inside the target prints as
/// `<redacted>`.
#[derive(Debug)]
struct Located {
    /// Which instance and as whom.
    target: Target,
    /// The owning tenant, as the address carries it.
    tenant: String,
    /// The content address.
    sha256: String,
}

impl Located {
    /// The blob's WebDAV path.
    fn path(&self) -> String {
        self.target.path_for(&self.tenant, &self.sha256)
    }
}

impl Store {
    /// The proxy, or the refusal that says there is no route out at all.
    fn proxy(&self) -> Result<&str, Status> {
        self.proxy.as_deref().ok_or_else(|| {
            Status::failed_precondition(
                "this plugin has no egress proxy configured, so it has no route out at all",
            )
        })
    }

    /// Where this call's bytes go.
    ///
    /// # Arguments
    ///
    /// * `blob` — the address, which carries the tenant
    /// * `context` — the envelope, which carries that tenant's settings
    ///
    /// # Errors
    ///
    /// `INVALID_ARGUMENT` for an address that is not one, or an envelope naming
    /// a different tenant from the address. `FAILED_PRECONDITION` when nothing
    /// says which instance to use, listing every missing piece at once.
    fn resolve(
        &self,
        blob: &Option<BlobRef>,
        context: &Option<CallContext>,
    ) -> Result<Located, Status> {
        let blob = blob
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("the request carries no blob reference"))?;
        let tenant = blob.tenant_id.trim();
        if tenant.is_empty() {
            return Err(Status::invalid_argument(
                "the blob reference names no tenant, and the tenant is part of the address \
                 (ADR-0032)",
            ));
        }
        if !is_content_address(&blob.sha256) {
            return Err(Status::invalid_argument(format!(
                "{:?} is not a content address: 64 characters of lower-case hexadecimal, which is \
                 what a SHA-256 is",
                blob.sha256
            )));
        }

        let settings: HashMap<String, String> = match context {
            Some(context) => {
                let envelope = context.tenant_id.trim();
                if !envelope.is_empty() && envelope != tenant {
                    // A caller bug, and the one that would put one tenant's
                    // bytes under another's path.
                    return Err(Status::invalid_argument(format!(
                        "the envelope is for tenant {envelope} and the address is for {tenant}"
                    )));
                }
                context.settings.clone()
            }
            None => HashMap::new(),
        };

        let target = self.defaults.resolve(&settings).map_err(|missing| {
            Status::failed_precondition(format!(
                "this plugin is installed and not configured for this tenant: {}",
                missing.join("; ")
            ))
        })?;
        Ok(Located {
            target,
            tenant: tenant.to_string(),
            sha256: blob.sha256.clone(),
        })
    }

    /// `HEAD` on one path: its size, or `None` when it is not there.
    async fn head_path(&self, proxy: &str, where_to: &Located) -> Result<Option<u64>, Status> {
        let mut answer = dav::request(
            proxy,
            &where_to.target,
            self.timeout,
            "HEAD",
            &where_to.path(),
            &[],
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;
        match answer.status {
            404 => Ok(None),
            status if (200..300).contains(&status) => Ok(Some(
                answer
                    .header("content-length")
                    .and_then(|value| value.parse().ok())
                    .unwrap_or(0),
            )),
            status => {
                let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
                Err(Status::unavailable(failure(status, &body)))
            }
        }
    }

    /// Creates every collection a blob's path needs.
    async fn make_the_way(&self, proxy: &str, where_to: &Located) -> Result<(), Status> {
        for collection in where_to
            .target
            .collections_for(&where_to.tenant, &where_to.sha256)
        {
            make_collection(proxy, &where_to.target, self.timeout, &collection)
                .await
                .map_err(Status::unavailable)?;
        }
        Ok(())
    }

    /// The whole object in one request, making its parents if they are missing.
    async fn put_single(
        &self,
        proxy: &str,
        where_to: &Located,
        bytes: &[u8],
    ) -> Result<(), Status> {
        let path = where_to.path();
        let mut answer = dav::request(
            proxy,
            &where_to.target,
            self.timeout,
            "PUT",
            &path,
            &[],
            Body::Bytes(bytes),
        )
        .await
        .map_err(Status::unavailable)?;

        if answer.status == 409 {
            // The collections are not there. Nothing creates them implicitly,
            // so they are created now and the upload is tried once more — once,
            // because a second 409 after that is not about the parents.
            let _ = answer.read_all(MAX_ERROR_BODY).await;
            self.make_the_way(proxy, where_to).await?;
            answer = dav::request(
                proxy,
                &where_to.target,
                self.timeout,
                "PUT",
                &path,
                &[],
                Body::Bytes(bytes),
            )
            .await
            .map_err(Status::unavailable)?;
        }

        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(())
    }
}

/// What this plugin says when asked to hold a half-arrived upload.
///
/// The contract makes the four staging methods optional and says why: the core
/// stages in the in-deployment store and sends a tenant's own store the finished
/// blob (ADR-0084, ADR-0074).
const STAGING_IS_THE_DEPLOYMENTS: &str =
    "staging is the in-deployment store's; this plugin receives the finished blob";

#[tonic::async_trait]
impl BlobStore for Store {
    async fn put(
        &self,
        request: Request<tonic::Streaming<PutRequest>>,
    ) -> Result<Response<PutResponse>, Status> {
        let proxy = self.proxy()?.to_string();
        let mut stream = request.into_inner();

        let first = stream
            .message()
            .await?
            .ok_or_else(|| Status::invalid_argument("the upload sent no message at all"))?;
        let where_to = self.resolve(&first.blob, &first.context)?;

        // Already there? The address is the content hash, so a path that exists
        // holds these exact bytes and uploading them again would spend the
        // tenant's bandwidth to arrive at the same file (ADR-0007).
        if let Some(size) = self.head_path(&proxy, &where_to).await? {
            drain(&mut stream).await?;
            return Ok(Response::new(PutResponse {
                created: false,
                byte_size: size,
            }));
        }

        let mut buffer: Vec<u8> = Vec::with_capacity(CHUNK);
        let mut running = Running::new();
        let mut total: u64 = 0;
        let mut upload: Option<Upload> = None;

        buffer.extend_from_slice(&first.chunk);
        running.update(&first.chunk);
        total += first.chunk.len() as u64;

        loop {
            match stream.message().await? {
                Some(message) => {
                    running.update(&message.chunk);
                    total += message.chunk.len() as u64;
                    buffer.extend_from_slice(&message.chunk);
                }
                None => break,
            }
            while buffer.len() >= CHUNK {
                let part: Vec<u8> = buffer.drain(..CHUNK).collect();
                if upload.is_none() {
                    upload = Some(Upload::begin(self, &proxy, &where_to).await?);
                }
                upload
                    .as_mut()
                    .expect("just set")
                    .add(self, &proxy, &where_to, &part)
                    .await?;
            }
        }

        let actual = hex(&running.finish());
        if actual != where_to.sha256 {
            // Refused, and an upload already begun is abandoned rather than
            // assembled. A store that kept these bytes under the declared
            // address would be content-addressed in name only, and the next read
            // would return a file that is not the one asked for.
            if let Some(started) = upload {
                started.abandon(self, &proxy, &where_to).await;
            }
            return Err(Status::invalid_argument(format!(
                "the bytes hash to {actual} and the address says {}",
                where_to.sha256
            )));
        }

        match upload {
            None => self.put_single(&proxy, &where_to, &buffer).await?,
            Some(mut started) => {
                if !buffer.is_empty() {
                    let last = std::mem::take(&mut buffer);
                    started.add(self, &proxy, &where_to, &last).await?;
                }
                started.assemble(self, &proxy, &where_to).await?;
            }
        }

        Ok(Response::new(PutResponse {
            created: true,
            byte_size: total,
        }))
    }

    type GetStream = Pin<Box<dyn Stream<Item = Result<GetResponse, Status>> + Send + 'static>>;

    async fn get(&self, request: Request<GetRequest>) -> Result<Response<Self::GetStream>, Status> {
        let proxy = self.proxy()?.to_string();
        let message = request.into_inner();
        let where_to = self.resolve(&message.blob, &message.context)?;

        let mut answer = dav::request(
            &proxy,
            &where_to.target,
            self.timeout,
            "GET",
            &where_to.path(),
            &[],
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;

        if answer.status == 404 {
            return Err(Status::not_found(
                "this tenant's Nextcloud does not hold that blob",
            ));
        }
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }

        let (sender, receiver) = tokio::sync::mpsc::channel(GET_BUFFER);
        tokio::spawn(async move {
            loop {
                match answer.next_chunk().await {
                    Ok(Some(chunk)) => {
                        // A send that fails means the core hung up, which is not
                        // an error here: it happens whenever a client stops
                        // reading a download.
                        if sender.send(Ok(GetResponse { chunk })).await.is_err() {
                            break;
                        }
                    }
                    Ok(None) => break,
                    Err(broken) => {
                        let _ = sender.send(Err(Status::unavailable(broken))).await;
                        break;
                    }
                }
            }
        });

        Ok(Response::new(Box::pin(ReceiverStream::new(receiver))))
    }

    async fn head(&self, request: Request<HeadRequest>) -> Result<Response<HeadResponse>, Status> {
        let proxy = self.proxy()?.to_string();
        let message = request.into_inner();
        let where_to = self.resolve(&message.blob, &message.context)?;

        let found = self.head_path(&proxy, &where_to).await?;
        Ok(Response::new(HeadResponse {
            exists: found.is_some(),
            byte_size: found.unwrap_or(0),
        }))
    }

    // -- Staging (REQ-MED-008, ADR-0084) -----------------------------------
    //
    // Answered UNIMPLEMENTED, which the contract expressly permits. The core
    // calls these on the IN-DEPLOYMENT store and on nothing else: an upload
    // that has not finished has no content address, and pushing an unfinished
    // object into a tenant's own bucket would leave litter there that only the
    // deployment knows how to clean up. What this plugin receives is the
    // FINISHED blob, through `Put`, exactly as before (ADR-0074).

    async fn append_staged(
        &self,
        _request: Request<tonic::Streaming<AppendStagedRequest>>,
    ) -> Result<Response<StagedResponse>, Status> {
        Err(Status::unimplemented(STAGING_IS_THE_DEPLOYMENTS))
    }

    async fn head_staged(
        &self,
        _request: Request<StagedRequest>,
    ) -> Result<Response<StagedResponse>, Status> {
        Err(Status::unimplemented(STAGING_IS_THE_DEPLOYMENTS))
    }

    type GetStagedStream =
        Pin<Box<dyn Stream<Item = Result<GetResponse, Status>> + Send + 'static>>;

    async fn get_staged(
        &self,
        _request: Request<StagedRequest>,
    ) -> Result<Response<Self::GetStagedStream>, Status> {
        Err(Status::unimplemented(STAGING_IS_THE_DEPLOYMENTS))
    }

    async fn delete_staged(
        &self,
        _request: Request<StagedRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        Err(Status::unimplemented(STAGING_IS_THE_DEPLOYMENTS))
    }

    async fn delete(
        &self,
        request: Request<DeleteRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        let proxy = self.proxy()?.to_string();
        let message = request.into_inner();
        let where_to = self.resolve(&message.blob, &message.context)?;

        let mut answer = dav::request(
            &proxy,
            &where_to.target,
            self.timeout,
            "DELETE",
            &where_to.path(),
            &[],
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;

        // 404 is `removed: false` rather than an error: deletion is retried
        // after a partial failure, and a retry that failed because the work was
        // already done would be a retry nobody could make succeed.
        if answer.status == 404 {
            return Ok(Response::new(DeleteResponse { removed: false }));
        }
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        // What Nextcloud does with it afterwards is the tenant's own setting: a
        // deleted file lands in that account's trash and is purged on that
        // account's retention rule. The core's own deletion is two-stage for the
        // same reason (REQ-PRIV-004 asks that nothing is lost silently, not that
        // a store forgets instantly).
        Ok(Response::new(DeleteResponse { removed: true }))
    }
}

/// A chunked upload in progress.
///
/// Nextcloud's chunked upload: a collection under `uploads/<user>/`, one `PUT`
/// per chunk into it, and a final `MOVE` of its `.file` onto the destination,
/// which is when the object appears. It exists because the core streams an
/// object whose size is not known in advance, and a single `PUT` needs a
/// `Content-Length` before the first byte.
struct Upload {
    /// This upload's own name, unique within the account.
    id: String,
    /// How many chunks have been written.
    chunks: usize,
}

impl Upload {
    /// Creates the collection the chunks go into.
    async fn begin(store: &Store, proxy: &str, where_to: &Located) -> Result<Self, Status> {
        // Unique within the account: the address, the second, and a counter that
        // separates two uploads of the same bytes in the same second. Two
        // uploads sharing a collection would interleave their chunks and
        // assemble a file that is neither.
        let id = format!(
            "homeinv-{}-{}-{}",
            &where_to.sha256[..16],
            now(),
            UPLOADS.fetch_add(1, Ordering::Relaxed)
        );
        let path = where_to.target.upload_path(&id);
        // `Destination` on the MKCOL is what the Nextcloud clients send: it lets
        // the instance check free space and quota before the first chunk rather
        // than after the last.
        let headers = vec![(
            "Destination".to_string(),
            where_to
                .target
                .destination_url(&where_to.tenant, &where_to.sha256),
        )];
        let mut answer = dav::request(
            proxy,
            &where_to.target,
            store.timeout,
            "MKCOL",
            &path,
            &headers,
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;
        if !matches!(answer.status, 201 | 405) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(Self { id, chunks: 0 })
    }

    /// Writes the next chunk.
    async fn add(
        &mut self,
        store: &Store,
        proxy: &str,
        where_to: &Located,
        bytes: &[u8],
    ) -> Result<(), Status> {
        self.chunks += 1;
        // Zero-padded, so that the order the instance assembles them in is the
        // order they were written whether it sorts them as numbers or as text.
        // `10` before `2` is a corrupted file that nothing reports.
        let name = format!("{:05}", self.chunks);
        let path = format!("{}/{name}", where_to.target.upload_path(&self.id));
        let mut answer = dav::request(
            proxy,
            &where_to.target,
            store.timeout,
            "PUT",
            &path,
            &[],
            Body::Bytes(bytes),
        )
        .await
        .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(())
    }

    /// Moves the assembled file onto its destination, which is when it exists.
    async fn assemble(
        &mut self,
        store: &Store,
        proxy: &str,
        where_to: &Located,
    ) -> Result<(), Status> {
        // The destination's parents first: `MOVE` will not create them either,
        // and a 409 here would be about a collection rather than about the file.
        store.make_the_way(proxy, where_to).await?;

        let source = format!("{}/.file", where_to.target.upload_path(&self.id));
        let headers = vec![
            (
                "Destination".to_string(),
                where_to
                    .target
                    .destination_url(&where_to.tenant, &where_to.sha256),
            ),
            // The address is the content hash, so anything already there is
            // these exact bytes. Overwriting it is a no-op with extra steps and
            // is allowed rather than fought over.
            ("Overwrite".to_string(), "T".to_string()),
        ];
        let mut answer = dav::request(
            proxy,
            &where_to.target,
            store.timeout,
            "MOVE",
            &source,
            &headers,
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(MAX_ERROR_BODY).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(())
    }

    /// Removes the collection and its chunks.
    ///
    /// Best effort by design: this runs when something has already gone wrong,
    /// and a failure here must not replace the reason the caller is being told
    /// about. An instance that never hears it cleans the upload up on its own
    /// schedule, which is what that schedule is for.
    async fn abandon(self, store: &Store, proxy: &str, where_to: &Located) {
        let path = where_to.target.upload_path(&self.id);
        let outcome = dav::request(
            proxy,
            &where_to.target,
            store.timeout,
            "DELETE",
            &path,
            &[],
            Body::Empty,
        )
        .await;
        if let Err(reason) = outcome {
            warn!("an incomplete upload could not be abandoned: {reason}");
        }
    }
}

/// Reads the rest of an upload the store does not need.
async fn drain(stream: &mut tonic::Streaming<PutRequest>) -> Result<(), Status> {
    while stream.message().await?.is_some() {}
    Ok(())
}

/// The health service.
struct Health {
    /// What is missing before it can store anything; empty when nothing is.
    unready: Vec<String>,
}

#[tonic::async_trait]
impl PluginHealth for Health {
    async fn check(
        &self,
        _request: Request<HealthRequest>,
    ) -> Result<Response<HealthResponse>, Status> {
        let checks: Vec<Check> = if self.unready.is_empty() {
            vec![Check {
                name: "configured".into(),
                passed: true,
                detail: String::new(),
            }]
        } else {
            self.unready
                .iter()
                .map(|missing| Check {
                    name: "configured".into(),
                    passed: false,
                    detail: missing.clone(),
                })
                .collect()
        };

        Ok(Response::new(HealthResponse {
            state: if self.unready.is_empty() {
                HealthState::Ok as i32
            } else {
                // NOT_CONFIGURED and not a failure: the difference matters to an
                // operator, and `plugin-health-states.yaml` names it for this
                // reason (REQ-PLG-015).
                HealthState::NotConfigured as i32
            },
            detail: self.unready.join("; "),
            checks,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HASH: &str = "ab00000000000000000000000000000000000000000000000000000000000000";

    fn defaults() -> Defaults {
        Defaults {
            url: Some("https://cloud.example.org".into()),
            username: Some("inventory".into()),
            password: Some("app-password".into()),
            folder: Some("HomeInventory".into()),
        }
    }

    fn store() -> Store {
        Store {
            proxy: Some("egress-proxy:8118".into()),
            defaults: defaults(),
            timeout: Duration::from_secs(60),
        }
    }

    fn blob(tenant: &str, sha256: &str) -> Option<BlobRef> {
        Some(BlobRef {
            tenant_id: tenant.into(),
            sha256: sha256.into(),
        })
    }

    fn envelope(tenant: &str, settings: &[(&str, &str)]) -> Option<CallContext> {
        Some(CallContext {
            tenant_id: tenant.into(),
            settings: settings
                .iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect(),
            ..Default::default()
        })
    }

    #[test]
    fn a_resolved_call_names_the_deployment_account_and_the_tenants_path() {
        let where_to = store()
            .resolve(&blob("tenant-a", HASH), &envelope("tenant-a", &[]))
            .expect("resolves");
        assert_eq!(where_to.target.username, "inventory");
        assert!(where_to
            .path()
            .ends_with(&format!("/HomeInventory/sha256/tenant-a/ab/00/{HASH}")));
    }

    #[test]
    fn a_tenants_own_account_wins_over_the_deployments() {
        let where_to = store()
            .resolve(
                &blob("tenant-b", HASH),
                &envelope(
                    "tenant-b",
                    &[
                        ("url", "https://cloud.tenant.example"),
                        ("username", "them"),
                        ("appPassword", "theirs"),
                    ],
                ),
            )
            .expect("resolves");
        assert_eq!(where_to.target.host, "cloud.tenant.example");
        assert_eq!(where_to.target.username, "them");
    }

    #[test]
    fn an_envelope_for_another_tenant_is_refused() {
        // The one that would put one tenant's bytes under another's path.
        let failure = store()
            .resolve(&blob("tenant-a", HASH), &envelope("tenant-b", &[]))
            .expect_err("mismatched");
        assert_eq!(failure.code(), tonic::Code::InvalidArgument);
        assert!(failure.message().contains("tenant-b"));
    }

    #[test]
    fn an_address_that_is_not_a_digest_is_refused() {
        for address in ["", "not-a-hash", &"AB".repeat(32), &"ab".repeat(31)] {
            let failure = store()
                .resolve(&blob("tenant-a", address), &envelope("tenant-a", &[]))
                .expect_err("not an address");
            assert_eq!(failure.code(), tonic::Code::InvalidArgument);
        }
    }

    #[test]
    fn an_unconfigured_tenant_is_told_what_is_missing() {
        let store = Store {
            proxy: Some("egress-proxy:8118".into()),
            defaults: Defaults::default(),
            timeout: Duration::from_secs(60),
        };
        let failure = store
            .resolve(&blob("tenant-a", HASH), &envelope("tenant-a", &[]))
            .expect_err("nothing configured");
        assert_eq!(failure.code(), tonic::Code::FailedPrecondition);
        assert!(failure.message().contains("no instance"));
        assert!(failure.message().contains("no account"));
    }

    #[test]
    fn a_call_without_an_envelope_still_works_on_the_deployments_account() {
        // The in-deployment service was the only implementation when this
        // contract was written and sent no envelope. A plugin that refused one
        // would refuse a caller that is still correct.
        let where_to = store()
            .resolve(&blob("tenant-a", HASH), &None)
            .expect("resolves");
        assert_eq!(where_to.target.username, "inventory");
    }

    #[test]
    fn a_deployment_that_configures_nothing_is_ready() {
        // Every tenant brings its own account, which is a supported way to run
        // this and not a half-configured one.
        assert!(unready(&Defaults::default(), Some("egress-proxy:8118")).is_empty());
    }

    #[test]
    fn a_deployment_that_configures_half_is_not() {
        let half = Defaults {
            url: Some("https://cloud.example.org".into()),
            ..Default::default()
        };
        let missing = unready(&half, Some("egress-proxy:8118"));
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_NEXTCLOUD_USER")));
    }

    #[test]
    fn without_a_proxy_nothing_is_ready() {
        assert!(unready(&defaults(), None)
            .iter()
            .any(|line| line.contains("HOMEINV_EGRESS_PROXY")));
    }
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
        assert!(notice.contains("rustls"));
        assert!(
            notice.len() > 10_000,
            "a notice this short is a generator that failed"
        );
    }
}
