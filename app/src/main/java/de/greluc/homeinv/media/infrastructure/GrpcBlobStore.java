/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.plugin.v1.BlobRef;
import de.greluc.homeinv.plugin.v1.BlobStoreGrpc;
import de.greluc.homeinv.plugin.v1.DeleteRequest;
import de.greluc.homeinv.plugin.v1.GetRequest;
import de.greluc.homeinv.plugin.v1.GetResponse;
import de.greluc.homeinv.plugin.v1.HeadRequest;
import de.greluc.homeinv.plugin.v1.HeadResponse;
import de.greluc.homeinv.plugin.v1.PutRequest;
import de.greluc.homeinv.plugin.v1.PutResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The in-core {@code filesystem} adapter — a client of the in-deployment {@code blobstore}.
 *
 * <h2>Why this is not a directory</h2>
 *
 * <p>It was, and that cost {@code REQ-NFR-008}: two {@code api} instances cannot share a local
 * filesystem, and the failure shows up as media that exist on one instance and 404 on the other
 * (ADR-0043). The bytes now live behind a service that owns the volume, and {@code api} and
 * {@code worker} stay stateless.
 *
 * <h2>Why mTLS to a service on the internal segment</h2>
 *
 * <p>Because {@code internal} is not a trust boundary (ADR-0044). It is a segment several services
 * share, and this one holds every tenant's media — so the store authenticates its callers, and this
 * client <b>pins the certificate it expects</b> rather than trusting a CA. A pinned fingerprint is
 * what makes "the right service answered" a property rather than a hope, and it is the same
 * mechanism a plugin registration uses ({@code REQ-SEC-056}).
 *
 * <h2>Streaming, in both directions</h2>
 *
 * <p>A 25 MB upload in one message would mean both sides holding it entirely in memory, and gRPC's
 * default message limit is 4 MB. Reads come back as a stream that is turned into an
 * {@link InputStream}, so the caller never sees a blob in one piece either.
 */
@Slf4j
@Component
public class GrpcBlobStore implements BlobStore {

  /** How much travels in one frame. Matches the server's own chunking. */
  private static final int CHUNK_BYTES = 256 * 1024;

  /** How long a single operation may take before it is abandoned. */
  private static final long DEADLINE_SECONDS = 120;

  private final ManagedChannel channel;
  private final BlobStoreGrpc.BlobStoreStub asyncStub;
  private final BlobStoreGrpc.BlobStoreBlockingStub blockingStub;

  /**
   * Opens the channel.
   *
   * @param channels the factory that builds a pinned, mutually authenticated channel
   */
  public GrpcBlobStore(BlobStoreChannelFactory channels) {
    this.channel = channels.open();
    this.asyncStub = BlobStoreGrpc.newStub(channel);
    this.blockingStub = BlobStoreGrpc.newBlockingStub(channel);
  }

  @Override
  public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
    BlobRef reference = reference(tenantId, sha256);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<PutResponse> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    StreamObserver<PutRequest> frames =
        asyncStub
            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .put(
                new StreamObserver<>() {
                  @Override
                  public void onNext(PutResponse response) {
                    result.set(response);
                  }

                  @Override
                  public void onError(Throwable thrown) {
                    failure.set(thrown);
                    finished.countDown();
                  }

                  @Override
                  public void onCompleted() {
                    finished.countDown();
                  }
                });

    try {
      // The first frame carries the reference and no bytes; every later one
      // carries bytes and no reference. The server enforces that, and sending it
      // any other way is a protocol error rather than a quietly different result.
      frames.onNext(PutRequest.newBuilder().setBlob(reference).build());

      byte[] buffer = new byte[CHUNK_BYTES];
      int read;
      while ((read = content.read(buffer)) > 0) {
        frames.onNext(
            PutRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build());
      }
      frames.onCompleted();
    } catch (IOException | RuntimeException thrown) {
      // Cancels the call rather than leaving the server waiting for frames that
      // will never arrive, and for a deadline to notice.
      frames.onError(thrown);
      throw thrown instanceof IOException io ? io : new IOException("The upload failed", thrown);
    }

    awaitCompletion(finished);
    rethrow(failure.get(), "store");

    PutResponse response = result.get();
    return response != null && response.getCreated();
  }

  @Override
  public InputStream open(UUID tenantId, String sha256) throws IOException {
    Iterator<GetResponse> frames;
    boolean hasFirst;
    try {
      frames =
          blockingStub
              .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
              .get(GetRequest.newBuilder().setBlob(reference(tenantId, sha256)).build());
      // The blocking stub returns a LAZY iterator: the call has not been made
      // when it hands one back, so a missing blob's NOT_FOUND would surface on
      // the first read rather than here. Callers expect `open` to be the method
      // that fails, and one that returned a stream which throws on its first
      // byte would turn "no such blob" into a truncated file.
      hasFirst = frames.hasNext();
    } catch (StatusRuntimeException refused) {
      throw asIoException(refused, "open");
    }

    // A SequenceInputStream over the frames, so nothing holds the whole blob.
    // The alternative - collecting the frames into a byte array - would put a
    // 25 MB allocation on the request path of every photograph served.
    boolean more = hasFirst;
    return new SequenceInputStream(
        new Enumeration<>() {
          private boolean firstPending = more;

          @Override
          public boolean hasMoreElements() {
            return firstPending || frames.hasNext();
          }

          @Override
          public InputStream nextElement() {
            firstPending = false;
            return new ByteArrayInputStream(frames.next().getChunk().toByteArray());
          }
        });
  }

  @Override
  public boolean exists(UUID tenantId, String sha256) {
    try {
      HeadResponse response =
          blockingStub
              .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
              .head(HeadRequest.newBuilder().setBlob(reference(tenantId, sha256)).build());
      return response.getExists();
    } catch (StatusRuntimeException refused) {
      // False rather than an exception. Every caller of this method is deciding
      // whether to do work, and "the store could not be asked" and "the blob is
      // not there" lead to the same next step: do the work. Logging it keeps the
      // difference visible to an operator.
      log.warn("The blob store could not be asked about {}: {}", sha256, refused.getStatus());
      return false;
    }
  }

  @Override
  public void delete(UUID tenantId, String sha256) throws IOException {
    try {
      blockingStub
          .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
          .delete(DeleteRequest.newBuilder().setBlob(reference(tenantId, sha256)).build());
    } catch (StatusRuntimeException refused) {
      throw asIoException(refused, "delete");
    }
  }

  /** Closes the channel when the context shuts down. */
  @PreDestroy
  public void close() {
    channel.shutdown();
    try {
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
    }
  }

  private static BlobRef reference(UUID tenantId, String sha256) {
    return BlobRef.newBuilder().setTenantId(tenantId.toString()).setSha256(sha256).build();
  }

  private static void awaitCompletion(CountDownLatch finished) throws IOException {
    try {
      if (!finished.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
        throw new IOException("The blob store did not answer within " + DEADLINE_SECONDS + "s");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while storing a blob", interrupted);
    }
  }

  private static void rethrow(Throwable failure, String operation) throws IOException {
    if (failure == null) {
      return;
    }
    if (failure instanceof StatusRuntimeException status) {
      throw asIoException(status, operation);
    }
    throw new IOException("The blob store failed during " + operation, failure);
  }

  private static IOException asIoException(StatusRuntimeException refused, String operation) {
    if (refused.getStatus().getCode() == Status.Code.NOT_FOUND) {
      return new java.io.FileNotFoundException("No such blob");
    }
    // The status DESCRIPTION is included and the blob address is not: the
    // description is written by our own service, and the address would end up in
    // a log line that an operator may paste somewhere.
    return new IOException(
        "The blob store refused %s: %s".formatted(operation, refused.getStatus()));
  }
}
