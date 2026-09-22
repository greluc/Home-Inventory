// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The `BlobStore` service itself.

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_stream::StreamExt;
use tonic::{Request, Response, Status, Streaming};
use tracing::{debug, warn};

use crate::proto::blob_store_server::BlobStore;
use crate::proto::{
    AppendStagedRequest, BlobRef as BlobRefMessage, CallContext, DeleteRequest, DeleteResponse,
    GetRequest, GetResponse, HeadRequest, HeadResponse, PutRequest, PutResponse,
    StagedRef as StagedRefMessage, StagedRequest, StagedResponse,
};
use crate::store::{BlobRef, StagedRef};

/// How much of a blob travels in one frame.
///
/// 256 KiB: large enough that a 25 MB upload is a hundred frames rather than
/// thousands, small enough to stay well inside gRPC's 4 MB default message
/// limit without either side having to raise it.
const CHUNK_BYTES: usize = 256 * 1024;

/// The most a single staged upload may grow to (`REQ-MED-008`).
///
/// The real limit is the caller's: `api` refuses an upload that declares more
/// than `REQ-SEC-037`'s 25 MB, and refuses it before a byte arrives. This one
/// exists because `internal` is not a trust boundary (ADR-0044) — a caller that
/// reaches this service could otherwise fill the volume one append at a time,
/// and a store with no bound of its own is a store that trusts the network it
/// sits on. Above the caller's ceiling on purpose: whichever limit fires first
/// decides what a person is told, and the caller's is the one that answers with
/// a problem document.
const STAGED_MAX_BYTES: u64 = 32 * 1024 * 1024;

/// A blob store backed by a directory.
pub struct FilesystemBlobStore {
    root: PathBuf,

    /// Which staged uploads are being appended to right now.
    ///
    /// tus asks a server to refuse a second concurrent `PATCH` on one upload,
    /// and the reason is not tidiness: two appends that both read the length
    /// before either writes would both be at the right offset and would both
    /// write, leaving a file of the right size and the wrong bytes — which
    /// nothing notices until the digest at the very end.
    ///
    /// In this process and not in Valkey, because this IS the process: one
    /// service holds the volume ([ADR-0043]), so a lock held here covers every
    /// caller rather than only the ones that agreed to take it.
    appending: std::sync::Mutex<std::collections::HashSet<PathBuf>>,
}

/// Holds one staged upload for the duration of an append.
///
/// A guard rather than a pair of calls: an append has four ways out — a bad
/// offset, a full disk, a client that vanished, success — and a release that
/// has to be written on each of them is a release that is missing from one.
struct Appending<'a> {
    store: &'a FilesystemBlobStore,
    path: PathBuf,
}

impl Drop for Appending<'_> {
    fn drop(&mut self) {
        if let Ok(mut held) = self.store.appending.lock() {
            held.remove(&self.path);
        }
    }
}

impl FilesystemBlobStore {
    /// Serves blobs from a directory.
    pub fn new(root: PathBuf) -> Self {
        Self {
            root,
            appending: std::sync::Mutex::new(std::collections::HashSet::new()),
        }
    }

    /// Claims a staged upload for this append, or refuses.
    ///
    /// # Arguments
    ///
    /// * `path` — where the staged upload lives
    ///
    /// # Errors
    ///
    /// Returns `ABORTED` when another append holds it, which the caller turns
    /// into the `423 Locked` tus asks for.
    fn claim(&self, path: &Path) -> Result<Appending<'_>, Status> {
        let mut held = self
            .appending
            .lock()
            .map_err(|_| Status::internal("the staging lock is poisoned"))?;
        if !held.insert(path.to_path_buf()) {
            return Err(Status::aborted(
                "another append is in flight for this upload",
            ));
        }
        Ok(Appending {
            store: self,
            path: path.to_path_buf(),
        })
    }

    /// Validates the reference a request carries, against the envelope beside it.
    ///
    /// The two say different things and always about the same tenant: the
    /// reference is the ADDRESS, which contains the tenant because addressing is
    /// per tenant (ADR-0032), and the envelope is WHO THE CALL IS FOR (ADR-0073).
    /// This service needs nothing from the envelope — it is configured once for
    /// the whole deployment — and it checks the one thing it can: that a caller
    /// which sent both did not name two different tenants. The contract asks
    /// every store to refuse that, and this is the reference implementation the
    /// others are compared to.
    ///
    /// # Arguments
    ///
    /// * `message` — the address
    /// * `context` — the envelope, absent from a caller older than 2026-09-21
    fn reference(
        message: Option<&BlobRefMessage>,
        context: Option<&CallContext>,
    ) -> Result<BlobRef, Status> {
        let message = message.ok_or_else(|| Status::invalid_argument("blob is required"))?;
        if let Some(envelope) = context.map(|context| context.tenant_id.trim()) {
            if !envelope.is_empty() && envelope != message.tenant_id.trim() {
                return Err(Status::invalid_argument(
                    "the envelope and the address name different tenants",
                ));
            }
        }
        BlobRef::parse(&message.tenant_id, &message.sha256)
            .map_err(|failure| Status::invalid_argument(failure.message()))
    }

    /// Validates a staged reference, against the envelope beside it.
    ///
    /// The same pairing [`Self::reference`] checks, for the same reason: the
    /// address carries the tenant because staging is per tenant, and the
    /// envelope says who the call is for. A caller that names two different
    /// tenants is refused rather than served under either.
    ///
    /// # Arguments
    ///
    /// * `message` — the address
    /// * `context` — the envelope
    fn staged(
        message: Option<&StagedRefMessage>,
        context: Option<&CallContext>,
    ) -> Result<StagedRef, Status> {
        let message = message.ok_or_else(|| Status::invalid_argument("staged is required"))?;
        if let Some(envelope) = context.map(|context| context.tenant_id.trim()) {
            if !envelope.is_empty() && envelope != message.tenant_id.trim() {
                return Err(Status::invalid_argument(
                    "the envelope and the address name different tenants",
                ));
            }
        }
        StagedRef::parse(&message.tenant_id, &message.upload_id)
            .map_err(|failure| Status::invalid_argument(failure.message()))
    }
}

#[tonic::async_trait]
impl BlobStore for FilesystemBlobStore {
    async fn put(
        &self,
        request: Request<Streaming<PutRequest>>,
    ) -> Result<Response<PutResponse>, Status> {
        let mut frames = request.into_inner();

        let first = frames
            .next()
            .await
            .transpose()?
            .ok_or_else(|| Status::invalid_argument("the stream carried no frames"))?;
        let reference = Self::reference(first.blob.as_ref(), first.context.as_ref())?;
        let target = reference.path_under(&self.root);

        if tokio::fs::try_exists(&target).await.unwrap_or(false) {
            // Already there. Content-addressed, so identical address means
            // identical bytes and there is nothing to write: the same photograph
            // uploaded twice by one tenant costs no second copy (ADR-0032).
            let byte_size = tokio::fs::metadata(&target)
                .await
                .map_err(|failure| Status::internal(failure.to_string()))?
                .len();
            // The rest of the stream is drained rather than dropped. Dropping it
            // resets the HTTP/2 stream, which the client sees as a transport
            // failure rather than as the success this is.
            while frames.next().await.transpose()?.is_some() {}
            return Ok(Response::new(PutResponse {
                created: false,
                byte_size,
            }));
        }

        let parent = target
            .parent()
            .ok_or_else(|| Status::internal("the blob path has no parent"))?;
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|failure| Status::internal(failure.to_string()))?;

        // Written beside the target and renamed at the end. A reader that finds
        // the final name finds a complete file — without this, a transfer
        // interrupted halfway leaves a truncated blob at exactly the address a
        // later `Head` would report as present.
        let temporary = parent.join(format!(".incoming-{}", uuid::Uuid::now_v7()));
        let mut digest = Sha256::new();
        let mut written: u64 = 0;

        let outcome = async {
            let mut file = tokio::fs::File::create(&temporary).await?;
            // The first frame may carry bytes as well as the reference, and a
            // client that sends them is not wrong — so they count.
            if !first.chunk.is_empty() {
                digest.update(&first.chunk);
                written += first.chunk.len() as u64;
                file.write_all(&first.chunk).await?;
            }
            while let Some(frame) = frames
                .next()
                .await
                .transpose()
                .map_err(std::io::Error::other)?
            {
                if frame.blob.is_some() {
                    return Err(std::io::Error::other("only the first frame may carry blob"));
                }
                digest.update(&frame.chunk);
                written += frame.chunk.len() as u64;
                file.write_all(&frame.chunk).await?;
            }
            file.sync_all().await?;
            Ok::<_, std::io::Error>(())
        }
        .await;

        if let Err(failure) = outcome {
            let _ = tokio::fs::remove_file(&temporary).await;
            return Err(Status::internal(failure.to_string()));
        }

        let computed = hex::encode(digest.finalize());
        if computed != reference.sha256() {
            // The caller's address and the caller's bytes disagree. Believing
            // the address would make this content-addressed in name only, and
            // the first corrupted transfer would be indistinguishable from a
            // different file.
            let _ = tokio::fs::remove_file(&temporary).await;
            warn!("a Put was refused: the content does not hash to the address given");
            return Err(Status::invalid_argument(
                "the content does not hash to the sha256 given",
            ));
        }

        tokio::fs::rename(&temporary, &target)
            .await
            .map_err(|failure| Status::internal(failure.to_string()))?;

        debug!(bytes = written, "stored a blob");
        Ok(Response::new(PutResponse {
            created: true,
            byte_size: written,
        }))
    }

    type GetStream = std::pin::Pin<
        Box<dyn futures_core::Stream<Item = Result<GetResponse, Status>> + Send + 'static>,
    >;

    async fn get(&self, request: Request<GetRequest>) -> Result<Response<Self::GetStream>, Status> {
        let message = request.into_inner();
        let reference = Self::reference(message.blob.as_ref(), message.context.as_ref())?;
        let path = reference.path_under(&self.root);

        let mut file = tokio::fs::File::open(&path).await.map_err(|failure| {
            if failure.kind() == std::io::ErrorKind::NotFound {
                Status::not_found("no such blob")
            } else {
                Status::internal(failure.to_string())
            }
        })?;

        let stream = async_stream::stream! {
            let mut buffer = vec![0_u8; CHUNK_BYTES];
            loop {
                match file.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => yield Ok(GetResponse { chunk: buffer[..read].to_vec() }),
                    Err(failure) => {
                        yield Err(Status::internal(failure.to_string()));
                        break;
                    }
                }
            }
        };

        Ok(Response::new(Box::pin(stream) as Self::GetStream))
    }

    async fn head(&self, request: Request<HeadRequest>) -> Result<Response<HeadResponse>, Status> {
        let message = request.into_inner();
        let reference = Self::reference(message.blob.as_ref(), message.context.as_ref())?;
        let path = reference.path_under(&self.root);

        match tokio::fs::metadata(&path).await {
            Ok(metadata) => Ok(Response::new(HeadResponse {
                exists: true,
                byte_size: metadata.len(),
            })),
            // Absence is an answer, not an error: `Head` exists to be asked about
            // blobs that may not be there.
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {
                Ok(Response::new(HeadResponse {
                    exists: false,
                    byte_size: 0,
                }))
            }
            Err(failure) => Err(Status::internal(failure.to_string())),
        }
    }

    async fn append_staged(
        &self,
        request: Request<Streaming<AppendStagedRequest>>,
    ) -> Result<Response<StagedResponse>, Status> {
        let mut frames = request.into_inner();

        let first = frames
            .next()
            .await
            .transpose()?
            .ok_or_else(|| Status::invalid_argument("the stream carried no frames"))?;
        let staged = Self::staged(first.staged.as_ref(), first.context.as_ref())?;
        let target = staged.path_under(&self.root);
        let parent = target
            .parent()
            .ok_or_else(|| Status::internal("the staged path has no parent"))?;
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|failure| Status::internal(failure.to_string()))?;

        // Held until this call returns, by whichever path it returns on.
        let _appending = self.claim(&target)?;

        // What is already there decides whether this append is the one the
        // client thinks it is. A mismatch is refused rather than reconciled:
        // writing at the wrong place would produce a file that is the right
        // length and the wrong bytes, and the only thing that would notice is
        // the digest at the very end.
        let present = match tokio::fs::metadata(&target).await {
            Ok(metadata) => metadata.len(),
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => 0,
            Err(failure) => return Err(Status::internal(failure.to_string())),
        };
        if first.offset != present {
            return Err(Status::failed_precondition(format!(
                "the upload is at {present} bytes and the append names {}",
                first.offset
            )));
        }

        let mut written = present;
        let outcome = async {
            let mut file = tokio::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&target)
                .await?;
            // The first frame may carry bytes as well as the address, and a
            // client that sends them is not wrong -- so they count.
            let mut chunk = first.chunk;
            loop {
                if !chunk.is_empty() {
                    written += chunk.len() as u64;
                    if written > STAGED_MAX_BYTES {
                        return Err(std::io::Error::other("staged upload too large"));
                    }
                    file.write_all(&chunk).await?;
                }
                match frames
                    .next()
                    .await
                    .transpose()
                    .map_err(std::io::Error::other)?
                {
                    None => break,
                    Some(frame) => {
                        if frame.staged.is_some() {
                            return Err(std::io::Error::other(
                                "only the first frame may carry staged",
                            ));
                        }
                        chunk = frame.chunk;
                    }
                }
            }
            // Durable before the offset is reported. A client that is told "you
            // are at 5 MB" and then finds 3 MB after a power cut would resume
            // from a place that does not exist, which is the one failure a
            // resumable upload exists to prevent (REQ-MED-008).
            file.sync_all().await?;
            Ok::<_, std::io::Error>(())
        }
        .await;

        if let Err(failure) = outcome {
            // The bytes that did arrive stay. The offset is the file's length
            // either way, so a client that asks what arrived gets a truthful
            // answer and continues from there -- which is exactly what a broken
            // connection looks like from here.
            let length = tokio::fs::metadata(&target)
                .await
                .map(|metadata| metadata.len())
                .unwrap_or(present);
            if length > STAGED_MAX_BYTES {
                let _ = tokio::fs::remove_file(&target).await;
                warn!("a staged upload was discarded for exceeding the size limit");
                return Err(Status::resource_exhausted("the staged upload is too large"));
            }
            return Err(Status::internal(failure.to_string()));
        }

        debug!(bytes = written, "staged an upload");
        Ok(Response::new(StagedResponse {
            exists: true,
            byte_size: written,
        }))
    }

    async fn head_staged(
        &self,
        request: Request<StagedRequest>,
    ) -> Result<Response<StagedResponse>, Status> {
        let message = request.into_inner();
        let staged = Self::staged(message.staged.as_ref(), message.context.as_ref())?;
        let target = staged.path_under(&self.root);

        match tokio::fs::metadata(&target).await {
            Ok(metadata) => Ok(Response::new(StagedResponse {
                exists: true,
                byte_size: metadata.len(),
            })),
            // Absent is an answer rather than an error: an upload that expired
            // and one that was never begun look the same from here, and the
            // caller's next move is the same for both.
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {
                Ok(Response::new(StagedResponse {
                    exists: false,
                    byte_size: 0,
                }))
            }
            Err(failure) => Err(Status::internal(failure.to_string())),
        }
    }

    type GetStagedStream = std::pin::Pin<
        Box<dyn futures_core::Stream<Item = Result<GetResponse, Status>> + Send + 'static>,
    >;

    async fn get_staged(
        &self,
        request: Request<StagedRequest>,
    ) -> Result<Response<Self::GetStagedStream>, Status> {
        let message = request.into_inner();
        let staged = Self::staged(message.staged.as_ref(), message.context.as_ref())?;
        let target = staged.path_under(&self.root);

        let mut file = tokio::fs::File::open(&target).await.map_err(|failure| {
            if failure.kind() == std::io::ErrorKind::NotFound {
                Status::not_found("no such staged upload")
            } else {
                Status::internal(failure.to_string())
            }
        })?;

        let stream = async_stream::stream! {
            let mut buffer = vec![0_u8; CHUNK_BYTES];
            loop {
                match file.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => yield Ok(GetResponse { chunk: buffer[..read].to_vec() }),
                    Err(failure) => {
                        yield Err(Status::internal(failure.to_string()));
                        break;
                    }
                }
            }
        };
        Ok(Response::new(Box::pin(stream) as Self::GetStagedStream))
    }

    async fn delete_staged(
        &self,
        request: Request<StagedRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        let message = request.into_inner();
        let staged = Self::staged(message.staged.as_ref(), message.context.as_ref())?;
        let target = staged.path_under(&self.root);

        match tokio::fs::remove_file(&target).await {
            Ok(()) => Ok(Response::new(DeleteResponse { removed: true })),
            // Nothing to remove is not a failure, for the reason `Delete` gives:
            // this is called again after a partial failure, and a retry that
            // failed because the work was already done is a retry nobody can
            // make succeed.
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {
                Ok(Response::new(DeleteResponse { removed: false }))
            }
            Err(failure) => Err(Status::internal(failure.to_string())),
        }
    }

    async fn delete(
        &self,
        request: Request<DeleteRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        let message = request.into_inner();
        let reference = Self::reference(message.blob.as_ref(), message.context.as_ref())?;
        let path = reference.path_under(&self.root);

        match tokio::fs::remove_file(&path).await {
            Ok(()) => Ok(Response::new(DeleteResponse { removed: true })),
            // Nothing to remove is a success. Deletion is retried after a partial
            // failure, and a retry that failed because the work was already done
            // would be a retry nobody could make succeed.
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {
                Ok(Response::new(DeleteResponse { removed: false }))
            }
            Err(failure) => Err(Status::internal(failure.to_string())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HASH: &str = "ab00000000000000000000000000000000000000000000000000000000000000";
    const TENANT: &str = "0191e2aa-0000-7000-8000-000000000001";

    fn address(tenant: &str) -> BlobRefMessage {
        BlobRefMessage {
            tenant_id: tenant.to_string(),
            sha256: HASH.to_string(),
        }
    }

    fn envelope(tenant: &str) -> CallContext {
        CallContext {
            tenant_id: tenant.to_string(),
            ..Default::default()
        }
    }

    #[test]
    fn an_envelope_naming_the_same_tenant_is_accepted() {
        assert!(
            FilesystemBlobStore::reference(Some(&address(TENANT)), Some(&envelope(TENANT))).is_ok()
        );
    }

    #[test]
    fn an_envelope_naming_another_tenant_is_refused() {
        // The one that would write one tenant's bytes under another's address.
        let failure = FilesystemBlobStore::reference(
            Some(&address(TENANT)),
            Some(&envelope("0191e2aa-0000-7000-8000-000000000002")),
        )
        .expect_err("two tenants");
        assert_eq!(failure.code(), tonic::Code::InvalidArgument);
    }

    #[test]
    fn no_envelope_at_all_is_still_a_correct_caller() {
        // The contract had no envelope before 2026-09-21, and the field is
        // additive: a caller that sends none is old, not wrong.
        assert!(FilesystemBlobStore::reference(Some(&address(TENANT)), None).is_ok());
        assert!(
            FilesystemBlobStore::reference(Some(&address(TENANT)), Some(&envelope(""))).is_ok()
        );
    }

    #[test]
    fn an_address_that_is_not_one_is_refused_whatever_the_envelope_says() {
        let failure = FilesystemBlobStore::reference(
            Some(&BlobRefMessage {
                tenant_id: TENANT.to_string(),
                sha256: "not-a-digest".to_string(),
            }),
            Some(&envelope(TENANT)),
        )
        .expect_err("not an address");
        assert_eq!(failure.code(), tonic::Code::InvalidArgument);
    }
}
