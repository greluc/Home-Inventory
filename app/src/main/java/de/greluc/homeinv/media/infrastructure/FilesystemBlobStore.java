/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The in-core {@link BlobStore}: files on a mounted volume.
 *
 * <p>The default and the only adapter that opens no connection (ADR-0007, ADR-0026). S3 and
 * Nextcloud are plugins, because the core has no outbound route.
 *
 * <h2>The layout, and the two things it guards against</h2>
 *
 * <p>{@code <root>/sha256/<tenantId>/<ab>/<cd>/<hash>}. The tenant is part of the path rather than
 * only of the metadata (ADR-0032): two tenants holding the same bytes hold two files, so a
 * deduplication shortcut cannot answer "does another tenant have this file" by timing.
 *
 * <p>The two intermediate directories from the first four hex characters keep any one directory to
 * a few thousand entries. A flat directory with a million files is slow on every filesystem and
 * pathological on some.
 *
 * <p>Both path components are validated rather than trusted. The tenant is a {@link UUID} and the
 * hash is checked against {@link #HEX_64}: a hash carrying {@code ../} would otherwise write outside
 * the root, and "it is always a digest" is an argument that holds until somebody adds a caller.
 */
@Component
@Slf4j
public class FilesystemBlobStore implements BlobStore {

  private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

  private final Path root;

  /**
   * Creates the store.
   *
   * @param root the volume the blobs live on, from {@code HOMEINV_BLOB_ROOT}
   * @throws IOException when the root cannot be created
   */
  public FilesystemBlobStore(@Value("${homeinv.media.blob-root:/var/lib/homeinv/blobs}") String root)
      throws IOException {
    this.root = Path.of(root);
    Files.createDirectories(this.root);
  }

  @Override
  public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
    Path target = pathFor(tenantId, sha256);
    if (Files.exists(target)) {
      // Content addressing makes this idempotent: the same bytes have the same
      // name, so an interrupted upload is safe to retry.
      return false;
    }
    Files.createDirectories(target.getParent());

    // Written beside the target and then moved. A crash halfway through a direct
    // write would leave a truncated file under the name of a hash it does not
    // have - and every later reader would trust the name.
    Path temporary = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
    try {
      Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.FileAlreadyExistsException raced) {
        // Another request stored the same bytes first. Its file is byte-identical
        // by construction, so there is nothing to reconcile.
        return false;
      }
      return true;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public InputStream open(UUID tenantId, String sha256) throws IOException {
    return Files.newInputStream(pathFor(tenantId, sha256));
  }

  @Override
  public boolean exists(UUID tenantId, String sha256) {
    return Files.exists(pathFor(tenantId, sha256));
  }

  @Override
  public void delete(UUID tenantId, String sha256) throws IOException {
    Path target = pathFor(tenantId, sha256);
    boolean removed = Files.deleteIfExists(target);
    if (removed) {
      log.debug("Removed blob {} of tenant {}", sha256, tenantId);
    }
    // The directories are left in place. Removing them would race with a
    // concurrent store into the same prefix, and an empty directory costs an
    // inode.
  }

  /**
   * The file a content address maps to.
   *
   * @param tenantId the owning tenant
   * @param sha256 the hex digest
   * @return the absolute path
   * @throws IllegalArgumentException when the digest is not 64 lowercase hex characters. A path
   *     component that came from anywhere but a digest is refused before it can escape the root
   */
  private Path pathFor(UUID tenantId, String sha256) {
    String hash = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
    if (!HEX_64.matcher(hash).matches()) {
      throw new IllegalArgumentException("A blob address must be 64 hex characters");
    }
    return root.resolve("sha256")
        .resolve(tenantId.toString())
        .resolve(hash.substring(0, 2))
        .resolve(hash.substring(2, 4))
        .resolve(hash);
  }
}
