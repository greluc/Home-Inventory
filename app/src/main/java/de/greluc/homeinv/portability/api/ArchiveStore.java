/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Where a finished export archive is kept (REQ-PORT-003, REQ-PORT-005).
 *
 * <h2>Why this is a port and not a call to `BlobStore`</h2>
 *
 * <p>Because that call is a cycle. {@code inventory} implements {@link ExportSource}, so
 * {@code inventory → portability}; {@code media} depends on {@code inventory}; a direct call from
 * here to {@code media.BlobStore} adds {@code portability → media} and closes the loop. The module
 * check found it the first time this block tried to store anything.
 *
 * <p>So the direction is inverted, as it is for every other edge this block has: <b>nothing points
 * out of {@code portability}</b>. Each block implements what it can contribute, and {@code media}
 * implements where the bytes live. That is not an accident of this one dependency — a block that
 * collects from everybody has to be the one everybody points at, or it is in a cycle with all of
 * them.
 *
 * <p>An archive is addressed by content like every other blob (ADR-0032), which is why {@code
 * store} takes a digest the caller has already computed rather than returning one: the writer
 * hashes as it writes, and hashing twice would mean reading tens of megabytes twice.
 */
public interface ArchiveStore {

  /**
   * Keeps an archive.
   *
   * @param tenantId whose archive — blobs are addressed per tenant, so one tenant's archive is
   *     never served to another even when two happen to be byte-identical (ADR-0032)
   * @param sha256 its content address, lower-case hex
   * @param content the bytes, which this reads to the end and does not close
   * @return whether it was written; {@code false} when the store already had it
   * @throws IOException when the store cannot be written
   */
  boolean store(UUID tenantId, String sha256, InputStream content) throws IOException;

  /**
   * Opens an archive.
   *
   * @param tenantId whose
   * @param sha256 its content address
   * @return the bytes, which the caller closes
   * @throws IOException when the store cannot be read
   */
  InputStream open(UUID tenantId, String sha256) throws IOException;
}
