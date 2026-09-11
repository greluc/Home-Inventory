/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Where the bytes go.
 *
 * <p>A port with a filesystem adapter in the core and S3 and Nextcloud adapters as plugins
 * (ADR-0007, ADR-0026). The core never opens an outbound connection itself, so anything that is not
 * the local filesystem is reached through a plugin.
 *
 * <p>Paths are content-addressed within a tenant: {@code sha256/<tenantId>/<hash>} (ADR-0032). The
 * tenant is part of the path rather than only of the metadata, because the two were once scoped
 * differently and the mismatch is what made the deduplication shortcut leak.
 */
public interface BlobStore {

  /**
   * Stores bytes under a content address, or does nothing if they are already there.
   *
   * <p>Idempotent by construction: the address is the hash, so storing the same bytes twice writes
   * the same file. That is what makes an interrupted upload safe to retry.
   *
   * @param tenantId the owning tenant
   * @param sha256 the hex digest of the content
   * @param content the bytes
   * @return {@code true} when this call wrote them, {@code false} when they were already stored
   * @throws IOException when the store cannot be written
   */
  boolean store(UUID tenantId, String sha256, InputStream content) throws IOException;

  /**
   * Opens stored bytes for reading.
   *
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @return the bytes
   * @throws IOException when the blob is missing or unreadable
   */
  InputStream open(UUID tenantId, String sha256) throws IOException;

  /**
   * Whether a blob exists for this tenant.
   *
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @return {@code true} when it is stored
   */
  boolean exists(UUID tenantId, String sha256);

  /**
   * Removes a blob.
   *
   * <p>Called only when the last reference within the tenant is gone. Another tenant holding the
   * same bytes holds its own copy and is unaffected, which is the property per-tenant content
   * addressing exists to give.
   *
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @throws IOException when the store cannot be written
   */
  void delete(UUID tenantId, String sha256) throws IOException;
}
