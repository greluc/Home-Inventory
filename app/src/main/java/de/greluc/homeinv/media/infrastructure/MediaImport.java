/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the photographs back out of an archive — the rows <b>and the bytes</b> (REQ-PORT-003).
 *
 * <h2>The bytes first, then the row that points at them</h2>
 *
 * <p>A {@code media_object} whose blob is missing is a photograph that 404s for ever, so the file
 * is stored before the row is written and a row whose file is absent from the archive is
 * <b>skipped</b> rather than written. The import report counts those: an archive made by an export
 * that could not read a blob, or one that was truncated, should say which pictures did not arrive
 * rather than leave them as rows pointing at nothing.
 *
 * <h2>Derivatives are rows only, on both sides</h2>
 *
 * <p>An export carries {@code media_variant} as rows and not as bytes, because a thumbnail is
 * recomputed from its original. The rows still travel so that the receiving instance knows what
 * existed; the worker regenerates the files. A variant row is therefore expected to have no blob
 * and is not counted as missing.
 *
 * <h2>What was infected stays where it was</h2>
 *
 * <p>An {@code INFECTED} object has a row in the archive and no bytes, by design. Its row is
 * imported — the archive says the thing existed and what happened to it — and no file is looked
 * for, because none was written.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaImport implements ImportTarget {

  private static final List<String> OBJECT =
      List.of(
          "id", "sha256", "media_type", "byte_size", "width_px", "height_px",
          "scan_state", "scan_verdict", "scanned_at", "ref_count", "unreferenced_since",
          "thumb_sha256", "preview_sha256", "derived_at",
          "created_at", "updated_at", "created_by", "updated_by", "deleted_at", "version");

  private static final List<String> ATTACHMENT =
      List.of(
          "id", "media_object_id", "target_kind", "target_id", "primary_image", "display_order",
          "role",
          "created_at", "updated_at", "created_by", "updated_by", "deleted_at", "version");

  private static final List<String> VARIANT =
      List.of(
          "id", "media_object_id", "kind", "sha256", "media_type", "byte_size", "width_px",
          "height_px", "created_at", "updated_at", "created_by", "updated_by", "deleted_at",
          "version");

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final BlobStore blobs;

  @Override
  public String block() {
    return "media";
  }

  @Override
  public int order() {
    return 40;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    Outcome outcome = objects(archive);
    outcome = outcome.plus(rows(archive, "attachments", "media.attachment", ATTACHMENT));
    outcome = outcome.plus(rows(archive, "variants", "media.media_variant", VARIANT));
    return outcome;
  }

  /**
   * The objects, each with its file when the archive has one.
   *
   * @param archive the archive
   * @return what happened, with a skip for every object whose bytes are missing
   */
  private Outcome objects(Archive archive) {
    UUID tenantId = TenantContext.require();
    int inserted = 0;
    int overwritten = 0;
    int skipped = 0;
    for (Map<String, Object> row : archive.rows("media", "media-objects")) {
      String sha256 = ImportSql.text(row.get("sha256"));
      boolean infected = "INFECTED".equals(ImportSql.text(row.get("scan_state")));
      String path = "media/blobs/" + sha256;

      if (!infected && !archive.hasFile(path) && !blobs.exists(tenantId, sha256)) {
        // No bytes here and none in the archive. A row would be a picture that
        // cannot be shown, for ever, and nothing would ever say why.
        log.warn("An archive holds a media row with no file and none stored: {}", sha256);
        skipped++;
        continue;
      }
      if (archive.hasFile(path) && !blobs.exists(tenantId, sha256)) {
        try (InputStream bytes = archive.openFile(path)) {
          blobs.store(tenantId, sha256, bytes);
        } catch (IOException unwritable) {
          throw new UncheckedIOException(
              "A photograph from the archive could not be stored: " + sha256, unwritable);
        }
      }

      Map<String, Object> resolved = new LinkedHashMap<>(row);
      if (write("media.media_object", OBJECT, resolved)) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, skipped);
  }

  /**
   * One dataset of rows, upserted by id.
   *
   * @param archive the archive
   * @param dataset its name in the archive
   * @param table where it goes
   * @param columns its columns
   * @return what happened
   */
  private Outcome rows(Archive archive, String dataset, String table, List<String> columns) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("media", dataset)) {
      if (write(table, columns, row)) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, 0);
  }

  /**
   * Writes one row, saying whether it was new.
   *
   * @param table where it goes
   * @param columns its columns
   * @param row the row
   * @return whether it was inserted rather than overwritten
   */
  private boolean write(String table, List<String> columns, Map<String, Object> row) {
    return Boolean.TRUE.equals(
        jdbc.sql(ImportSql.upsert(table, columns, "tenant_id, id"))
            .param(TenantContext.require())
            .param(ImportSql.asJson(json, row, Set.of()))
            .query(Boolean.class)
            .single());
  }
}
