/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.BlobStore;
import de.greluc.homeinv.plugin.v1.BlobRef;
import de.greluc.homeinv.plugin.v1.BlobStoreGrpc;
import de.greluc.homeinv.plugin.v1.DeleteRequest;
import de.greluc.homeinv.plugin.v1.GetRequest;
import de.greluc.homeinv.plugin.v1.GetResponse;
import de.greluc.homeinv.plugin.v1.HeadRequest;
import de.greluc.homeinv.plugin.v1.HeadResponse;
import de.greluc.homeinv.plugin.v1.PutRequest;
import de.greluc.homeinv.plugin.v1.PutResponse;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Speaks {@code BlobStore} to a plugin (REQ-MED-009, ADR-0007, ADR-0026).
 *
 * <p>The port a tenant reaches its <b>own</b> storage through: S3, Nextcloud, anything else somebody
 * writes. The in-deployment {@code blobstore} service is <b>not</b> reached this way — it opens
 * nothing outward and is therefore a service rather than a plugin (ADR-0043), and the core speaks to
 * it directly.
 *
 * <h2>Both directions stream, and neither is buffered here</h2>
 *
 * <p>A photograph is up to 32 MB and a tenant may store many; an adapter that collected an upload
 * into a byte array before sending it would put a ceiling on how much anybody may own, in the same
 * place and for the same bad reason an export held in memory would. So {@code put} writes chunks as
 * it reads them, and {@code get} hands back a stream that pulls the next chunk when the caller asks
 * for it.
 *
 * <p>The read side uses a pipe rather than a collected buffer. A blocking-stub iterator is the
 * natural gRPC shape and an {@link InputStream} is what {@code media} expects, so one thread feeds
 * the other — which is also what makes the deadline mean something: a plugin that stops sending
 * halfway leaves the reader with an exception rather than with a truncated file.
 */
@Component
public class BlobStoreAdapter implements PortAdapter<BlobStore> {

  /** How large a chunk is on the wire. Below gRPC's 4 MB message limit with room to spare. */
  private static final int CHUNK = 256 * 1024;

  @Override
  public Class<BlobStore> port() {
    return BlobStore.class;
  }

  @Override
  public BlobStore adapt(Channel channel, int deadlineMillis) {
    return new Grpc(channel, deadlineMillis);
  }

  /**
   * One plugin's blob store, over gRPC.
   *
   * @param channel the plugin's channel
   * @param deadlineMillis how long a call may take
   */
  private record Grpc(Channel channel, int deadlineMillis) implements BlobStore {

    @Override
    public boolean put(CallContext context, String sha256, InputStream content) {
      AtomicReference<PutResponse> answer = new AtomicReference<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);

      StreamObserver<PutRequest> requests =
          BlobStoreGrpc.newStub(channel)
              .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
              .put(
                  new StreamObserver<PutResponse>() {
                    @Override
                    public void onNext(PutResponse response) {
                      answer.set(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                      failed.set(error);
                      finished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      finished.countDown();
                    }
                  });

      try {
        // The first message carries the reference and no bytes, which is the
        // contract's own framing: a store can create its target before the
        // first chunk arrives.
        requests.onNext(PutRequest.newBuilder().setBlob(refOf(context, sha256)).build());

        byte[] buffer = new byte[CHUNK];
        int read;
        while ((read = content.read(buffer)) > 0) {
          requests.onNext(
              PutRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build());
        }
        requests.onCompleted();
      } catch (IOException unreadable) {
        requests.onError(unreadable);
        throw new UncheckedIOException("The bytes to store could not be read", unreadable);
      } catch (StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }

      await(finished, failed, "store a blob");
      // `created` false means the bytes were already there, which is not a
      // failure: the address is the hash, so storing the same bytes twice is the
      // same file (ADR-0032).
      return answer.get() != null && answer.get().getCreated();
    }

    @Override
    public InputStream get(CallContext context, String sha256) {
      Iterator<GetResponse> chunks;
      try {
        chunks =
            BlobStoreGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .get(GetRequest.newBuilder().setBlob(refOf(context, sha256)).build());
      } catch (StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }

      PipedInputStream reading = new PipedInputStream(CHUNK);
      PipedOutputStream writing;
      try {
        writing = new PipedOutputStream(reading);
      } catch (IOException impossible) {
        throw new UncheckedIOException("A pipe could not be opened", impossible);
      }

      // A daemon thread: a caller that abandons the stream must not keep the
      // application alive, and closing the read end raises here, which ends it.
      Thread pump =
          new Thread(
              () -> {
                try (PipedOutputStream out = writing) {
                  while (chunks.hasNext()) {
                    out.write(chunks.next().getChunk().toByteArray());
                  }
                } catch (IOException | StatusRuntimeException stopped) {
                  // The reader closed early, or the plugin stopped sending. Both
                  // end this thread; the reader sees the broken pipe, which is
                  // what a truncated read should look like rather than a short
                  // file that looks complete.
                  Thread.currentThread().interrupt();
                }
              },
              "blob-" + sha256.substring(0, Math.min(8, sha256.length())));
      pump.setDaemon(true);
      pump.start();
      return reading;
    }

    @Override
    public Optional<Long> head(CallContext context, String sha256) {
      try {
        HeadResponse response =
            BlobStoreGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .head(HeadRequest.newBuilder().setBlob(refOf(context, sha256)).build());
        return response.getExists() ? Optional.of(response.getByteSize()) : Optional.empty();
      } catch (StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }
    }

    @Override
    public void delete(CallContext context, String sha256) {
      try {
        BlobStoreGrpc.newBlockingStub(channel)
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .delete(DeleteRequest.newBuilder().setBlob(refOf(context, sha256)).build());
      } catch (StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }
    }

    /**
     * Waits for a streamed call to finish, and turns what went wrong into a failure a caller can
     * act on.
     *
     * @param finished the latch the observer counts down
     * @param failed what the observer caught, if anything
     * @param what the call, for the message
     */
    private void await(CountDownLatch finished, AtomicReference<Throwable> failed, String what) {
      try {
        // A little longer than the stub's own deadline, so a plugin that answers
        // just inside it is not cut off by the wait rather than by the policy.
        if (!finished.await(deadlineMillis + 1_000L, TimeUnit.MILLISECONDS)) {
          throw new PluginException(
              PluginException.Kind.DEADLINE_EXCEEDED,
              "The store did not " + what + " within " + deadlineMillis + " ms");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new PluginException(
            PluginException.Kind.INTERNAL, "Waiting for the store was interrupted", interrupted);
      }
      if (failed.get() instanceof StatusRuntimeException failure) {
        throw PluginWire.failureOf(failure, channel.authority());
      }
      if (failed.get() != null) {
        throw new PluginException(
            PluginException.Kind.INTERNAL, "The store could not " + what, failed.get());
      }
    }

    /**
     * The blob's address on the wire.
     *
     * <p>The tenant is in the reference and not only in the envelope, because the address is
     * {@code sha256/<tenant>/<hash>} (ADR-0032): the same bytes stored by two tenants are two
     * objects, and a store that keyed on the hash alone would deduplicate across a tenant boundary.
     *
     * @param context who the call is for
     * @param sha256 the content address
     * @return the reference
     */
    private static BlobRef refOf(CallContext context, String sha256) {
      return BlobRef.newBuilder()
          .setTenantId(context.requireTenantId().toString())
          .setSha256(sha256)
          .build();
    }
  }
}
