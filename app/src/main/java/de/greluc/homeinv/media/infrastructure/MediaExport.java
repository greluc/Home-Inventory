/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code media} puts in an export: the rows <b>and the bytes</b> (REQ-PORT-003).
 *
 * <p>The requirement says "data, configuration, <b>media</b> and a manifest", and the rows alone
 * would be an archive of captions with no pictures. So every blob goes in too, at {@code
 * media/blobs/<sha256>} — content-addressed, which is what lets a row point at a file without a
 * second index that could disagree with the first.
 *
 * <h2>The originals, not the derivatives</h2>
 *
 * <p>Thumbnails and previews are <b>recomputed</b> from the original, which is why {@code
 * media_variant} is carried as rows and its blobs are not. Shipping them would double the size of
 * the largest part of the archive to save the receiving instance some work it does about to do
 * anyway — and a derivative that disagreed with its source would be the copy somebody trusted.
 *
 * <h2>What is infected does not travel</h2>
 *
 * <p>A blob the scanner called {@code INFECTED} is not retrievable here (REQ-MED-013) and is not
 * written into an archive either. Putting it in would be handing somebody a file this deployment
 * refuses to serve them, in a container that hides it from the scanner on the other side. Its row
 * still goes, so the archive says the thing existed and what happened to it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaExport implements ExportSource {

  private static final String OBJECTS =
      """
      select id, sha256, media_type, byte_size, width_px, height_px,
             scan_state, scan_verdict, scanned_at, ref_count, unreferenced_since,
             thumb_sha256, preview_sha256, derived_at,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from media.media_object
      order by created_at, id
      """;

  private static final String ATTACHMENTS =
      """
      select id, media_object_id, target_kind, target_id, primary_image, display_order, role,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from media.attachment
      order by target_kind, target_id, display_order
      """;

  private static final String VARIANTS =
      """
      select id, media_object_id, kind, sha256, media_type, byte_size, width_px, height_px,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from media.media_variant
      order by media_object_id, kind
      """;

  /** The originals worth carrying: what the scanner cleared, and what is still there. */
  private static final String CLEAN_BLOBS =
      """
      select sha256
      from media.media_object
      where scan_state = 'CLEAN' and deleted_at is null
      order by sha256
      """;

  private final JdbcClient jdbc;
  private final BlobStore blobs;

  @Override
  public String block() {
    return "media";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("media-objects", jdbc.sql(OBJECTS).query(MediaExport::row).list().stream());
    sink.write("attachments", jdbc.sql(ATTACHMENTS).query(MediaExport::row).list().stream());
    sink.write("variants", jdbc.sql(VARIANTS).query(MediaExport::row).list().stream());

    UUID tenantId = TenantContext.require();
    List<String> digests = jdbc.sql(CLEAN_BLOBS).query(String.class).list();
    for (String sha256 : digests) {
      try (InputStream bytes = blobs.open(tenantId, sha256)) {
        sink.writeFile("media/blobs/" + sha256, bytes);
      } catch (IOException unreadable) {
        // Logged and skipped rather than failing the export. A blob the store
        // cannot produce is one the archive is missing, and an archive missing
        // one photograph is worth more than no archive at all -- the manifest
        // lists what went in, so the gap is visible rather than assumed away.
        log.warn("A blob could not be read into the export and is missing from it: {}", sha256);
      }
    }
  }

  /**
   * One row as an ordered map, carrying the stored column names.
   *
   * @param rs the row
   * @param rowNum which row
   * @return the row
   * @throws SQLException when it cannot be read
   */
  private static Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    ResultSetMetaData meta = rs.getMetaData();
    for (int column = 1; column <= meta.getColumnCount(); column++) {
      row.put(meta.getColumnLabel(column), rs.getObject(column));
    }
    return row;
  }
}
