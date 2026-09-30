/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.portability.api.ArchiveStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Export archives live in the blob store (REQ-PORT-003).
 *
 * <p>The implementation of {@link ArchiveStore}, and it lives here rather than in {@code
 * portability} for the reason the port exists: {@code media} depends on {@code inventory}, and
 * {@code inventory} implements {@code portability}'s export port, so a call from {@code
 * portability} into {@code media} closes a three-hop cycle.
 *
 * <p>Thin, because there is nothing to decide. An archive is a file, and this deployment already
 * has exactly one place files live — content-addressed, per tenant, behind mTLS
 * ([ADR-0043](../adr/0043-blobstore-as-its-own-service.md)). Giving exports a second store would be
 * a second thing to back up, scan and reclaim.
 */
@Component
@RequiredArgsConstructor
public class BlobArchiveStore implements ArchiveStore {

  private final BlobStore blobs;

  @Override
  public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
    return blobs.store(tenantId, sha256, content);
  }

  @Override
  public InputStream open(UUID tenantId, String sha256) throws IOException {
    return blobs.open(tenantId, sha256);
  }
}
