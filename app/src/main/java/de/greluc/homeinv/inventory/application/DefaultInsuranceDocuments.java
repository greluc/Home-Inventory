/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.inventory.api.InsuranceDocuments;
import de.greluc.homeinv.inventory.api.InsuranceReport;
import de.greluc.homeinv.inventory.api.ItemEvidence;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The insurance report as a rendered document (REQ-LIFE-016, ADR-0070).
 *
 * <p>The core describes the document and a plugin renders it. An instance with no renderer
 * installed still produces the figures and the table — the endpoints for those are separate, and
 * this one answers with a problem naming what is missing rather than an empty file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultInsuranceDocuments implements InsuranceDocuments {

  /**
   * How long a render may take.
   *
   * <p>A minute, which is long for a plugin call and short for a document with two hundred
   * photographs in it. The envelope's own limit applies on top; this is the ceiling for this one
   * call because it is the one call in the system that is expected to take seconds.
   */
  private static final int DEADLINE_MILLIS = 60_000;

  private final InsuranceReport reports;
  private final ItemEvidence evidence;
  private final ExtensionRegistry extensions;

  /**
   * Renders the report.
   *
   * @param root the place to report on, or null for everything
   * @param limit how many items at most
   * @param mediaType what to produce, {@code application/pdf} unless a renderer offers something
   *     else and a caller asks for it
   * @param language what the caller reads, passed to the renderer in the envelope
   * @return the rendered document
   * @throws NoRendererException when this tenant has no document renderer installed
   */
  @Override
  @Transactional(readOnly = true)
  public DocumentRenderer.Rendered render(
      UUID root, int limit, String mediaType, String language) {
    UUID tenantId = TenantContext.require();
    DocumentRenderer renderer =
        extensions
            .lookup(DocumentRenderer.class, tenantId)
            .orElseThrow(
                () ->
                    new NoRendererException(
                        "No document renderer is installed, so this report cannot be rendered. "
                            + "The same report is available as data and as a table."));

    InsuranceReport.Report report = reports.of(root, limit);

    // The pictures are fetched here rather than by the report, because the
    // report is also served as JSON and as CSV -- and neither of those carries
    // bytes. A report of two hundred items would otherwise read two hundred
    // blobs to answer a question about numbers.
    Map<UUID, ItemEvidence.Picture> photos = new LinkedHashMap<>();
    for (InsuranceReport.Room room : report.rooms()) {
      for (InsuranceReport.Line line : room.lines()) {
        if (line.photo() != null) {
          evidence
              .pictureOf(line.photo().mediaObjectId())
              .ifPresent(picture -> photos.put(line.itemId(), picture));
        }
      }
    }

    DocumentRenderer.Document document =
        InsuranceDocument.of(report, photos, TenantContext.require().toString());
    log.debug(
        "Rendering an insurance report of {} room(s) with {} picture(s)",
        report.rooms().size(),
        photos.size());
    return renderer.render(
        new CallContext(tenantId, org.slf4j.MDC.get("traceId"), language, DEADLINE_MILLIS),
        document,
        mediaType);
  }

}
