/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import java.util.UUID;

/**
 * The insurance report as a rendered document (REQ-LIFE-016, ADR-0070).
 *
 * <p>The core describes the document and a plugin renders it. Published here rather than left in
 * {@code application} because the REST adapter calls it, and a block is reached only through its
 * api — the modularity check says so, and said so about this one.
 */
public interface InsuranceDocuments {

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
  DocumentRenderer.Rendered render(UUID root, int limit, String mediaType, String language);

  /**
   * Raised when there is nothing installed that can render a document.
   *
   * <p>Its own exception rather than a generic failure, because the answer a caller needs is
   * specific: the figures are available, the document is not, and an operator has to install
   * something. A {@code 409} with that sentence in it is actionable; a {@code 500} is not.
   */
  class NoRendererException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Says what is missing.
     *
     * @param message the sentence a caller is shown
     */
    public NoRendererException(String message) {
      super(message);
    }
  }
}
