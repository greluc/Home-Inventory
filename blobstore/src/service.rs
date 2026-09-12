// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The `BlobStore` service itself.

use std::path::PathBuf;

use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_stream::StreamExt;
use tonic::{Request, Response, Status, Streaming};
use tracing::{debug, warn};

use crate::proto::blob_store_server::BlobStore;
use crate::proto::{
    BlobRef as BlobRefMessage, DeleteRequest, DeleteResponse, GetRequest, GetResponse, HeadRequest,
    HeadResponse, PutRequest, PutResponse,
};
use crate::store::BlobRef;

/// How much of a blob travels in one frame.
///
/// 256 KiB: large enough that a 25 MB upload is a hundred frames rather than
/// thousands, small enough to stay well inside gRPC's 4 MB default message
/// limit without either side having to raise it.
const CHUNK_BYTES: usize = 256 * 1024;

/// A blob store backed by a directory.
pub struct FilesystemBlobStore {
    root: PathBuf,
}

impl FilesystemBlobStore {
    /// Serves blobs from a directory.
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    /// Validates the reference a request carries.
    fn reference(message: Option<&BlobRefMessage>) -> Result<BlobRef, Status> {
        let message = message.ok_or_else(|| Status::invalid_argument("blob is required"))?;
        BlobRef::parse(&message.tenant_id, &message.sha256)
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
        let reference = Self::reference(first.blob.as_ref())?;
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
        let reference = Self::reference(request.into_inner().blob.as_ref())?;
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
        let reference = Self::reference(request.into_inner().blob.as_ref())?;
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

    async fn delete(
        &self,
        request: Request<DeleteRequest>,
    ) -> Result<Response<DeleteResponse>, Status> {
        let reference = Self::reference(request.into_inner().blob.as_ref())?;
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
