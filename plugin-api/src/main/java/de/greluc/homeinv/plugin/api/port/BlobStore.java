/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.io.InputStream;
import java.util.Optional;

/**
 * Stores and returns opaque byte sequences, addressed by their content (09 §9.2, ADR-0007).
 *
 * <p>The filesystem implementation is in the core image and speaks to the in-deployment {@code
 * blobstore} service, which opens nothing outward and is therefore not a plugin (ADR-0043). S3 and
 * Nextcloud/WebDAV are first-party plugins, because they reach a host outside the deployment.
 *
 * <p><b>Addressed by content, so writing is idempotent.</b> The same bytes have the same digest and
 * the second write is a confirmation rather than a change. That is what makes at-least-once
 * delivery safe on this port and why {@link #put} answers whether it wrote anything.
 *
 * <h2>What an implementation deliberately does not do</h2>
 *
 * <p>It does not authorise. The tenant in the context is a partition key and not a permission — the
 * core decided who may read a blob before the call was made (ADR-0010), and a store that decided
 * too would be a second set of rules to keep right. It does not know what a blob <i>is</i> either:
 * no media type, no dimensions, no scan verdict. Those belong to the row in PostgreSQL that points
 * at it.
 *
 * <p>Stage 1 (REQ-MED-009).
 */
public interface BlobStore {

  /**
   * Writes a blob, or confirms that the identical blob is already there.
   *
   * <p>The stream is read once, forwards. An implementation that needs the length in advance has to
   * buffer, and should think about the 25 MB an upload may be before it does.
   *
   * @param context who it is for. The tenant partitions the store, so that removing a tenant is a
   *     prefix to delete rather than a search
   * @param sha256 the lowercase hex digest of the content, which the caller has already computed
   * @param content the bytes
   * @return {@code true} when this call wrote the blob, {@code false} when it was already there
   * @throws de.greluc.homeinv.plugin.api.PluginException when the store could not be written, or
   *     when the content does not hash to {@code sha256} — an implementation that checks says so
   *     with {@link de.greluc.homeinv.plugin.api.PluginException.Kind#INVALID_ARGUMENT}
   */
  boolean put(CallContext context, String sha256, InputStream content);

  /**
   * Reads a blob.
   *
   * @param context who it is for
   * @param sha256 which blob
   * @return the bytes. The caller closes the stream; an implementation must not hold the whole blob
   *     in memory to produce it
   * @throws de.greluc.homeinv.plugin.api.PluginException with {@link
   *     de.greluc.homeinv.plugin.api.PluginException.Kind#NOT_FOUND} when there is no such blob
   */
  InputStream get(CallContext context, String sha256);

  /**
   * Whether a blob is there, and how large, without transferring it.
   *
   * @param context who is asking
   * @param sha256 which blob
   * @return its size in bytes, or empty when it is not there
   */
  Optional<Long> head(CallContext context, String sha256);

  /**
   * Removes a blob.
   *
   * <p>Removing one that is not there succeeds: the caller wants it gone, and it is.
   *
   * <p><b>Reference counting is the caller's.</b> The core decides from its own rows when nothing
   * points at a blob any more and only then calls this. A store that counted references would need
   * to know what a reference is.
   *
   * @param context who it is for
   * @param sha256 which blob
   * @throws de.greluc.homeinv.plugin.api.PluginException when the store could not be written
   */
  void delete(CallContext context, String sha256);
}
