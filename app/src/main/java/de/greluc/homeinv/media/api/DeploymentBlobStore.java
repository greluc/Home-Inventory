/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * The store that belongs to <b>this deployment</b> (ADR-0043).
 *
 * <h2>Why this is a separate type and not {@link BlobStore}</h2>
 *
 * <p>They carry the same four methods and they are two different roles. {@link BlobStore} is what
 * {@code media} asks for — "where do this tenant's bytes go" — and the answer depends on the tenant:
 * one that granted a storage plugin keeps its photographs in its own bucket. This one is the
 * deployment's own {@code blobstore} service, which every tenant falls back to and which opens
 * nothing outward, so it is a service rather than a plugin (ADR-0026).
 *
 * <p>Keeping them separate is what lets {@code TenantBlobStore} be the one implementation of
 * {@link BlobStore} in the application: a second implementation of the same interface would make
 * every injection point ambiguous, and resolving that with {@code @Primary} in two places is how a
 * test ends up exercising a different store from the one production uses.
 *
 * <p>The methods are documented on {@link BlobStore}; the contract is identical, including the
 * per-tenant content addressing of ADR-0032.
 */
public interface DeploymentBlobStore {

  /**
   * Stores bytes under a content address, or does nothing if they are already there.
   *
   * @param tenantId the owning tenant
   * @param sha256 the hex digest of the content
   * @param content the bytes
   * @return {@code true} when this call wrote them
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
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @throws IOException when the store cannot be written
   */
  void delete(UUID tenantId, String sha256) throws IOException;
}
