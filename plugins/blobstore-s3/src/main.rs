// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! `plugin-blobstore-s3` — a tenant's media in S3-compatible object storage.
//!
//! The third of the five first-party plugins
//! ([ADR-0072](../../../docs/adr/0072-first-party-plugins-live-here.md)) and the
//! first implementation of the `BlobStore` port that is not the deployment's own
//! service. It exists as a plugin and not as a core adapter for one reason: it
//! opens a connection **outside** the deployment, and everything that does is a
//! plugin on its own segment behind the egress proxy
//! ([ADR-0026](../../../docs/adr/0026-core-outbound-via-plugins.md)).
//!
//! # Configured twice (ADR-0073)
//!
//! | | Who decides | Where |
//! |---|---|---|
//! | the deployment's endpoint, bucket, region and keys | the operator | this container's environment and a mounted secret |
//! | a tenant's **own** endpoint, bucket, region, prefix and keys | that tenant | the call envelope, the secret sealed in the core |
//! | which hosts may be reached at all | the operator | the manifest's allowlist, enforced by the proxy |
//!
//! A tenant that configures nothing uses the deployment's bucket; the tenant id
//! is part of every key, so one bucket holds many tenants and their addresses
//! never collide ([ADR-0032](../../../docs/adr/0032-per-tenant-blob-addressing.md)).
//!
//! # What it refuses
//!
//! A `sha256` that is not 64 characters of lower-case hex: the address is the
//! content hash and a store that accepted anything else would be
//! content-addressed in name only. Bytes whose hash is not the one the core
//! declared — verified as they stream, and an upload already begun is aborted
//! rather than completed. A call whose envelope names a different tenant from
//! its address, which is a bug in the caller and would put one tenant's bytes
//! under another's key.
//!
//! # Granting this plugin moves nothing
//!
//! What was stored before the grant stays in the deployment's own store and is
//! still readable from there
//! ([ADR-0074](../../../docs/adr/0074-granting-a-store-routes-and-does-not-move.md)).
//! This plugin therefore answers `Head` with "no" for blobs it has never been
//! given, and that answer is load-bearing rather than a failure.

mod s3;
mod sigv4;
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
use std::time::Duration;

use homeinv_plugin_common::encoding::hex;
use homeinv_plugin_common::mac::Running;
use homeinv_plugin_common::tls;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::Stream;
use tonic::transport::Server;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::proto::blob_store_server::{BlobStore, BlobStoreServer};
use crate::proto::plugin_health_server::{PluginHealth, PluginHealthServer};
use crate::proto::{
    BlobRef, CallContext, Check, DeleteRequest, DeleteResponse, GetRequest, GetResponse,
    HeadRequest, HeadResponse, HealthRequest, HealthResponse, HealthState, PutRequest, PutResponse,
};
use crate::s3::{failure, Body};
use crate::target::{Defaults, Target};

/// The port the service matrix names for this plugin.
const DEFAULT_PORT: u16 = 8202;

/// Where the runtime mounts this plugin's own identity.
const DEFAULT_IDENTITY: &str = "/run/secrets/mtls-plugin-blobstore-s3";

/// Where the runtime mounts the deployment's secret access key.
const DEFAULT_SECRET_FILE: &str = "/run/secrets/plugin-blobstore-s3-secret-key";

/// How long one request to the store may take, tunnel included.
const DEFAULT_TIMEOUT_SECONDS: u64 = 60;

/// How large a multipart part is.
///
/// Above S3's five-megabyte minimum with room to spare, and small enough that
/// two of them fit in this container's budget. An object that never reaches this
/// size is uploaded in one request and never becomes a multipart upload at all.
const PART: usize = 8 * 1024 * 1024;

/// How many chunks of a download may wait for the core to read them.
const GET_BUFFER: usize = 4;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
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
        endpoint = defaults.endpoint.as_deref().unwrap_or("none"),
        bucket = defaults.bucket.as_deref().unwrap_or("none"),
        "plugin-blobstore-s3 is listening"
    );

    let unready = unready(&defaults, proxy.as_deref());
    for missing in &unready {
        // Said once, at startup, and each one names what to set. A plugin that
        // cannot store is a plugin an operator has to be able to diagnose
        // without reading its source (REQ-PLG-015).
        warn!("plugin-blobstore-s3 is not ready: {missing}");
    }
    if !defaults.touched() {
        info!(
            "no deployment default is configured; every tenant that uses this plugin brings its \
             own endpoint, bucket and keys (ADR-0073)"
        );
    }

    let store = Store {
        proxy,
        defaults,
        timeout: Duration::from_secs(
            env("HOMEINV_S3_TIMEOUT_SECONDS")
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
            info!("plugin-blobstore-s3 is stopping");
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
        endpoint: env("HOMEINV_S3_ENDPOINT"),
        region: env("HOMEINV_S3_REGION"),
        bucket: env("HOMEINV_S3_BUCKET"),
        prefix: env("HOMEINV_S3_PREFIX"),
        addressing: env("HOMEINV_S3_ADDRESSING"),
        access_key_id: env("HOMEINV_S3_ACCESS_KEY_ID"),
        secret_access_key: read_secret(
            &env("HOMEINV_S3_SECRET_KEY_FILE").unwrap_or(DEFAULT_SECRET_FILE.into()),
        ),
    }
}

/// Reads a mounted secret, trimming the newline a file usually ends with.
///
/// Absent and empty are the same answer here, because `setup.sh` creates this
/// file EMPTY on purpose: a key to somebody else's storage is not one this
/// deployment may invent.
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

impl Store {
    /// The proxy, or the refusal that says there is no route out at all.
    fn proxy(&self) -> Result<&str, Status> {
        self.proxy.as_deref().ok_or_else(|| {
            Status::failed_precondition(
                "this plugin has no egress proxy configured, so it has no route out at all",
            )
        })
    }

    /// Where this call's bytes go, and the key they go under.
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
    /// says which bucket to use, listing every missing piece at once.
    fn resolve(
        &self,
        blob: &Option<BlobRef>,
        context: &Option<CallContext>,
    ) -> Result<(Target, String), Status> {
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
                    // bytes under another's key. Refused rather than resolved
                    // towards either of them.
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
        let key = target.key_for(tenant, &blob.sha256);
        Ok((target, key))
    }
}

/// Whether a string is a SHA-256 as this contract spells one.
fn is_content_address(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

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
        let (target, key) = self.resolve(&first.blob, &first.context)?;
        let declared = first.blob.as_ref().expect("resolved above").sha256.clone();

        // Already there? The address is the content hash, so a key that exists
        // holds these exact bytes and uploading them again would spend the
        // tenant's bandwidth to arrive at the same object (ADR-0007).
        if let Some(size) = self.head_key(&proxy, &target, &key).await? {
            drain(&mut stream).await?;
            return Ok(Response::new(PutResponse {
                created: false,
                byte_size: size,
            }));
        }

        let mut buffer: Vec<u8> = Vec::with_capacity(PART);
        let mut running = Running::new();
        let mut total: u64 = 0;
        let mut upload: Option<Upload> = None;

        buffer.extend_from_slice(&first.chunk);
        running.update(&first.chunk);
        total += first.chunk.len() as u64;

        loop {
            let next = stream.message().await?;
            match next {
                Some(message) => {
                    running.update(&message.chunk);
                    total += message.chunk.len() as u64;
                    buffer.extend_from_slice(&message.chunk);
                }
                None => break,
            }
            while buffer.len() >= PART {
                let part: Vec<u8> = buffer.drain(..PART).collect();
                let started = match upload.as_mut() {
                    Some(started) => started,
                    None => {
                        upload = Some(self.begin(&proxy, &target, &key).await?);
                        upload.as_mut().expect("just set")
                    }
                };
                started.add(self, &proxy, &target, &key, &part).await?;
            }
        }

        let actual = hex(&running.finish());
        if actual != declared {
            // Refused, and an upload already begun is abandoned rather than
            // completed. A store that kept these bytes under the declared
            // address would be content-addressed in name only, and the next read
            // would return a file that is not the one asked for.
            if let Some(started) = upload {
                started.abort(self, &proxy, &target, &key).await;
            }
            return Err(Status::invalid_argument(format!(
                "the bytes hash to {actual} and the address says {declared}"
            )));
        }

        match upload {
            None => {
                self.put_single(&proxy, &target, &key, &buffer).await?;
            }
            Some(mut started) => {
                if !buffer.is_empty() {
                    let last = std::mem::take(&mut buffer);
                    started.add(self, &proxy, &target, &key, &last).await?;
                }
                started.complete(self, &proxy, &target, &key).await?;
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
        let (target, key) = self.resolve(&message.blob, &message.context)?;

        let mut answer = s3::request(&proxy, &target, self.timeout, "GET", &key, &[], Body::Empty)
            .await
            .map_err(Status::unavailable)?;

        if answer.status == 404 {
            return Err(Status::not_found(format!(
                "{key} is not in this tenant's bucket"
            )));
        }
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(8 * 1024).await.unwrap_or_default();
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
        let (target, key) = self.resolve(&message.blob, &message.context)?;

        let found = self.head_key(&proxy, &target, &key).await?;
        Ok(Response::new(HeadResponse {
            exists: found.is_some(),
            byte_size: found.unwrap_or(0),
        }))
    }

    async fn delete(
        &self,
        request: Request<DeleteRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        let proxy = self.proxy()?.to_string();
        let message = request.into_inner();
        let (target, key) = self.resolve(&message.blob, &message.context)?;

        let mut answer = s3::request(
            &proxy,
            &target,
            self.timeout,
            "DELETE",
            &key,
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
            let body = answer.read_all(8 * 1024).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(Response::new(DeleteResponse { removed: true }))
    }
}

impl Store {
    /// `HEAD` on one key: its size, or `None` when it is not there.
    async fn head_key(
        &self,
        proxy: &str,
        target: &Target,
        key: &str,
    ) -> Result<Option<u64>, Status> {
        let mut answer = s3::request(proxy, target, self.timeout, "HEAD", key, &[], Body::Empty)
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
                let body = answer.read_all(8 * 1024).await.unwrap_or_default();
                Err(Status::unavailable(failure(status, &body)))
            }
        }
    }

    /// The whole object in one request.
    async fn put_single(
        &self,
        proxy: &str,
        target: &Target,
        key: &str,
        bytes: &[u8],
    ) -> Result<(), Status> {
        let mut answer = s3::request(
            proxy,
            target,
            self.timeout,
            "PUT",
            key,
            &[],
            Body::Bytes(bytes),
        )
        .await
        .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(8 * 1024).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(())
    }

    /// Starts a multipart upload and returns its id.
    async fn begin(&self, proxy: &str, target: &Target, key: &str) -> Result<Upload, Status> {
        let mut answer = s3::request(
            proxy,
            target,
            self.timeout,
            "POST",
            key,
            &[("uploads", String::new())],
            Body::Empty,
        )
        .await
        .map_err(Status::unavailable)?;
        let body = answer
            .read_all(64 * 1024)
            .await
            .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        let text = String::from_utf8_lossy(&body).to_string();
        let id = s3::between(&text, "<UploadId>", "</UploadId>").ok_or_else(|| {
            Status::unavailable("the store started an upload and did not say which one")
        })?;
        Ok(Upload {
            id,
            etags: Vec::new(),
        })
    }
}

/// A multipart upload in progress.
struct Upload {
    /// What the store called it.
    id: String,
    /// The tag of each part, in order, as the store returned them.
    etags: Vec<String>,
}

impl Upload {
    /// Uploads the next part.
    async fn add(
        &mut self,
        store: &Store,
        proxy: &str,
        target: &Target,
        key: &str,
        bytes: &[u8],
    ) -> Result<(), Status> {
        let number = self.etags.len() + 1;
        let mut answer = s3::request(
            proxy,
            target,
            store.timeout,
            "PUT",
            key,
            &[
                ("partNumber", number.to_string()),
                ("uploadId", self.id.clone()),
            ],
            Body::Bytes(bytes),
        )
        .await
        .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            let body = answer.read_all(8 * 1024).await.unwrap_or_default();
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        let etag = answer
            .header("etag")
            .ok_or_else(|| {
                Status::unavailable(format!("the store took part {number} and returned no ETag"))
            })?
            .to_string();
        self.etags.push(etag);
        Ok(())
    }

    /// Completes it, which is the point at which the object exists.
    async fn complete(
        &mut self,
        store: &Store,
        proxy: &str,
        target: &Target,
        key: &str,
    ) -> Result<(), Status> {
        let mut document = String::from("<CompleteMultipartUpload>");
        for (index, etag) in self.etags.iter().enumerate() {
            document.push_str(&format!(
                "<Part><PartNumber>{}</PartNumber><ETag>{}</ETag></Part>",
                index + 1,
                etag
            ));
        }
        document.push_str("</CompleteMultipartUpload>");

        let mut answer = s3::request(
            proxy,
            target,
            store.timeout,
            "POST",
            key,
            &[("uploadId", self.id.clone())],
            Body::Bytes(document.as_bytes()),
        )
        .await
        .map_err(Status::unavailable)?;
        let body = answer
            .read_all(64 * 1024)
            .await
            .map_err(Status::unavailable)?;
        if !(200..300).contains(&answer.status) {
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        // A completion may answer 200 AND carry an error document: the store
        // starts the response before it knows the outcome, so that a slow
        // assembly does not look like a dead connection. A client that read the
        // status alone would report success for a failed upload.
        let text = String::from_utf8_lossy(&body).to_string();
        if text.contains("<Error>") {
            return Err(Status::unavailable(failure(answer.status, &body)));
        }
        Ok(())
    }

    /// Abandons it, so the parts stop occupying the tenant's storage.
    ///
    /// Best effort by design: this runs when something has already gone wrong,
    /// and a failure here must not replace the reason the caller is being told
    /// about. A store that never hears it expires the upload by its own lifecycle
    /// rule, which is what that rule is for.
    async fn abort(self, store: &Store, proxy: &str, target: &Target, key: &str) {
        let outcome = s3::request(
            proxy,
            target,
            store.timeout,
            "DELETE",
            key,
            &[("uploadId", self.id.clone())],
            Body::Empty,
        )
        .await;
        if let Err(reason) = outcome {
            warn!("an incomplete upload of {key} could not be abandoned: {reason}");
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

    fn defaults() -> Defaults {
        Defaults {
            endpoint: Some("objects.example.org".into()),
            region: Some("eu-central-1".into()),
            bucket: Some("home-inv".into()),
            access_key_id: Some("KEY".into()),
            secret_access_key: Some("secret".into()),
            ..Default::default()
        }
    }

    fn store() -> Store {
        Store {
            proxy: Some("egress-proxy:3128".into()),
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
    fn a_resolved_call_names_the_deployment_bucket_and_the_tenants_key() {
        let (target, key) = store()
            .resolve(
                &blob("tenant-a", &"ab".repeat(32)),
                &envelope("tenant-a", &[]),
            )
            .expect("resolves");
        assert_eq!(target.bucket, "home-inv");
        assert!(key.starts_with("sha256/tenant-a/ab/ab/"));
    }

    #[test]
    fn a_tenants_own_bucket_wins_over_the_deployments() {
        let (target, _) = store()
            .resolve(
                &blob("tenant-b", &"cd".repeat(32)),
                &envelope(
                    "tenant-b",
                    &[
                        ("bucket", "mine"),
                        ("accessKeyId", "MINE"),
                        ("secretAccessKey", "also-mine"),
                    ],
                ),
            )
            .expect("resolves");
        assert_eq!(target.bucket, "mine");
        assert_eq!(target.credentials.access_key_id, "MINE");
    }

    #[test]
    fn an_envelope_for_another_tenant_is_refused() {
        // The one that would put one tenant's bytes under another's key.
        let failure = store()
            .resolve(
                &blob("tenant-a", &"ab".repeat(32)),
                &envelope("tenant-b", &[]),
            )
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
            proxy: Some("egress-proxy:3128".into()),
            defaults: Defaults::default(),
            timeout: Duration::from_secs(60),
        };
        let failure = store
            .resolve(
                &blob("tenant-a", &"ab".repeat(32)),
                &envelope("tenant-a", &[]),
            )
            .expect_err("nothing configured");
        assert_eq!(failure.code(), tonic::Code::FailedPrecondition);
        assert!(failure.message().contains("no bucket"));
        assert!(failure.message().contains("no endpoint"));
    }

    #[test]
    fn a_call_without_an_envelope_still_works_on_the_deployments_bucket() {
        // The in-deployment service was the only implementation when this
        // contract was written and sent no envelope. A plugin that refused one
        // would refuse a caller that is still correct.
        let (target, _) = store()
            .resolve(&blob("tenant-a", &"ab".repeat(32)), &None)
            .expect("resolves");
        assert_eq!(target.bucket, "home-inv");
    }

    #[test]
    fn a_deployment_that_configures_nothing_is_ready() {
        // Every tenant brings its own account, which is a supported way to run
        // this and not a half-configured one.
        assert!(unready(&Defaults::default(), Some("egress-proxy:3128")).is_empty());
    }

    #[test]
    fn a_deployment_that_configures_half_is_not() {
        let half = Defaults {
            endpoint: Some("objects.example.org".into()),
            ..Default::default()
        };
        let missing = unready(&half, Some("egress-proxy:3128"));
        assert!(missing
            .iter()
            .any(|line| line.contains("HOMEINV_S3_BUCKET")));
    }

    #[test]
    fn without_a_proxy_nothing_is_ready() {
        assert!(unready(&defaults(), None)
            .iter()
            .any(|line| line.contains("HOMEINV_EGRESS_PROXY")));
    }

    #[test]
    fn a_content_address_is_sixty_four_lower_case_hex_digits() {
        assert!(is_content_address(&"0a".repeat(32)));
        assert!(!is_content_address(&"0A".repeat(32)));
        assert!(!is_content_address(&"0g".repeat(32)));
        assert!(!is_content_address(""));
    }
}
