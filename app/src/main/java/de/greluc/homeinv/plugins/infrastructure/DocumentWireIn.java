/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import de.greluc.homeinv.plugin.v1.Block;
import java.util.List;
import java.util.Set;

/**
 * Turns the contract's blocks back into the document model (ADR-0071).
 *
 * <p>The other direction of {@code DocumentRendererAdapter}'s translation: that one is used when
 * the <b>core</b> describes a document, this one when a <b>plugin</b> does and the core has to
 * understand what arrived. Two directions of one mapping, which is why they sit beside each other
 * rather than in the blocks that use them.
 *
 * <p>A block the contract does not carry — a {@code kind} nobody set — is dropped rather than
 * refused. A caller built against a newer minor version may send something this one does not know,
 * and a document with an unknown block left out is a document; a refusal is not.
 */
final class DocumentWireIn {

  private DocumentWireIn() {}

  /**
   * One block, as the core understands it.
   *
   * @param block what arrived
   * @return the block, or a spacer of zero when the message carried no kind
   */
  static DocumentRenderer.Block blockOf(Block block) {
    return switch (block.getKindCase()) {
      case HEADING ->
          new DocumentRenderer.Heading(block.getHeading().getLevel(), block.getHeading().getText());
      case PARAGRAPH -> new DocumentRenderer.Paragraph(block.getParagraph().getText());
      case FACTS ->
          new DocumentRenderer.Facts(
              nullIfBlank(block.getFacts().getCaption()),
              block.getFacts().getEntriesList().stream()
                  .map(fact -> new DocumentRenderer.Fact(fact.getLabel(), fact.getValue()))
                  .toList());
      case TABLE ->
          new DocumentRenderer.Table(
              nullIfBlank(block.getTable().getCaption()),
              block.getTable().getColumnsList(),
              block.getTable().getRowsList().stream()
                  .map(row -> (List<String>) row.getCellsList())
                  .toList(),
              Set.copyOf(block.getTable().getNumericColumnsList()));
      case IMAGE ->
          new DocumentRenderer.Image(
              block.getImage().getContent().toByteArray(),
              block.getImage().getMediaType(),
              nullIfBlank(block.getImage().getCaption()),
              block.getImage().getWidthMillimetres());
      case PAGE_BREAK -> new DocumentRenderer.PageBreak();
      case SPACER -> new DocumentRenderer.Spacer(block.getSpacer().getMillimetres());
      case KIND_NOT_SET -> new DocumentRenderer.Spacer(0);
    };
  }

  /**
   * The text, or null when the wire said nothing.
   *
   * <p>Protobuf has no null for a string, so an absent caption arrives as an empty one. The model
   * says null, and turning an empty caption into a caption would put a blank line in a document.
   *
   * @param text what arrived
   * @return it, or null
   */
  private static String nullIfBlank(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
