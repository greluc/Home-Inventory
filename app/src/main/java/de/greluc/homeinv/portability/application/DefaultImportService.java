/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ArchiveStore;
import de.greluc.homeinv.portability.api.ImportService;
import de.greluc.homeinv.portability.api.MappingProfile;
import de.greluc.homeinv.portability.infrastructure.ImportJobQueries;
import de.greluc.homeinv.portability.infrastructure.MappingProfiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting an archive and queueing it (REQ-PORT-003, REQ-PORT-007).
 *
 * <p>The request does as little as it can: the bytes go to a temporary file, their digest falls out
 * of the write, the file goes to the blob store and a job row says it is waiting. Nothing is
 * parsed. Reading an archive is minutes of work, and a request that did it would be a request
 * holding a connection open for minutes on behalf of somebody who uploaded a file — which is also
 * how one upload becomes a way to occupy the instance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultImportService implements ImportService {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_JOBS = 200;

  private final ImportJobQueries jobs;
  private final ArchiveStore blobs;
  private final MappingProfiles profiles;

  @Override
  public ImportJobView accept(
      UUID actor, InputStream archive, boolean dryRun, String profileKey)
      throws IOException {
    UUID tenantId = TenantContext.require();
    if (profileKey != null && profiles.profileOf(profileKey).isEmpty()) {
      // Refused here, in the request that named it, rather than by the worker
      // minutes later: a typo in a profile name is a mistake somebody can fix
      // while they are still looking at the screen.
      throw new IllegalArgumentException(
          "No mapping profile is called '" + profileKey + "'. "
              + profiles.all().stream().map(MappingProfile::key).toList());
    }
    Path temporary = Files.createTempFile("homeinv-import-", ".upload");
    try {
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
      }
      // Through the file rather than into memory: the digest has to be known
      // before the blob store will take the bytes, and holding the archive in a
      // byte array to hash it would put the ceiling on how large an inventory
      // may be -- which is the wrong place for a ceiling, on the way in as much
      // as on the way out.
      long size;
      try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
        size = archive.transferTo(out);
      }
      if (size == 0) {
        throw new IllegalArgumentException("The uploaded file is empty.");
      }
      String sha256 = hex(digest.digest());
      try (InputStream stored = Files.newInputStream(temporary)) {
        blobs.store(tenantId, sha256, stored);
      }

      // Straight to the query object, which carries its own `@Transactional`.
      // A wrapper here would be a self-invocation -- `this.queue(...)` does not
      // pass through the proxy -- so the annotation would do nothing at all and
      // the tests would still pass, because they call through the bean.
      UUID id = jobs.queue(tenantId, actor, sha256, size, dryRun, profileKey);
      log.info("An archive of {} byte(s) was accepted for tenant {}", size, tenantId);
      return jobs.byId(tenantId, id).orElseThrow();
    } finally {
      deleteQuietly(temporary);
    }
  }

  @Override
  public List<MappingProfile> profiles() {
    return profiles.all();
  }

  @Override
  @Transactional(readOnly = true)
  public ImportJobView job(UUID id) {
    return jobs
        .byId(TenantContext.require(), id)
        .orElseThrow(() -> new NotFoundException("import job", id));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ImportJobView> jobs(int limit) {
    return jobs.all(TenantContext.require(), Math.clamp(limit, 1, MAX_JOBS));
  }

  /**
   * A digest as lower-case hex.
   *
   * @param bytes the digest
   * @return its hex form
   */
  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return out.toString();
  }

  /**
   * Removes the temporary file, saying so rather than failing when it cannot.
   *
   * @param file the file, possibly null
   */
  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException leftBehind) {
      log.debug("A temporary import file could not be removed: {}", file, leftBehind);
    }
  }
}
