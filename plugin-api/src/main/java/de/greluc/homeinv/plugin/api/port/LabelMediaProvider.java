/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;

/**
 * Contributes label geometries (09 §9.2, 10 §10.5).
 *
 * <p>The Avery Zweckform catalogue is in the core image, because it is data rather than behaviour.
 * Herma, continuous rolls and a manufacturer's own catalogue are plugins.
 *
 * <p><b>{@link LabelMedium#verified()} is the load-bearing field of this whole port.</b> Size and
 * count do not determine where the leftover space sits: 64 labels of 48.5 mm across 210 mm leave
 * 16 mm that could be side margins, column gaps, or both — and all three look identical on the
 * first label and diverge from the fourth column. A geometry that was calculated rather than
 * measured is {@code false} here, and the core then makes a person print a calibration sheet before
 * a bulk run (REQ-LBL-004, REQ-LBL-007). One unsourced value makes the whole entry unverified.
 *
 * <p>Every distance is in <b>micrometres</b>. The reference catalogue is written in millimetres to
 * one decimal; micrometres carry that exactly, integers compare and add without surprises, and
 * there is no floating-point value anywhere on a path that ends at a physical object.
 *
 * <p>Stage 2, written at stage 1 with the rest of the contract (REQ-PLG-001).
 */
public interface LabelMediaProvider {

  /**
   * The geometries this provider contributes.
   *
   * <p>Read at registration and when an administrator asks for a refresh, not per print job.
   *
   * @param context who is asking
   * @return the media, possibly empty
   * @throws de.greluc.homeinv.plugin.api.PluginException when the catalogue cannot be read
   */
  List<LabelMedium> media(CallContext context);

  /**
   * One label geometry.
   *
   * @param mediumKey the stable key, for example {@code herma-4387}, prefixed by the provider so
   *     that two catalogues cannot collide
   * @param vendor who makes it
   * @param articleNumber their article number, as printed on the box
   * @param name what a person sees
   * @param kind sheet or roll, which decides whether the grid or the printable window applies
   * @param pageWidth the sheet width in micrometres, or the roll width
   * @param pageHeight the sheet height in micrometres, or zero for a continuous roll
   * @param columns labels across; one for a roll
   * @param rows labels down; one for a roll
   * @param labelWidth one label's width in micrometres
   * @param labelHeight one label's height in micrometres
   * @param cornerRadius the corner radius in micrometres, zero for square corners
   * @param marginLeft the unused space at the left edge, in micrometres
   * @param marginTop the unused space at the top edge, in micrometres
   * @param pitchX the distance from one label's left edge to the next one's, which is the label
   *     width plus any gap. Not derivable from the other fields, which is this record's whole point
   * @param pitchY the same downwards
   * @param printableWidth what the printer can actually put on the medium, in micrometres, or zero
   *     when it equals the label width. Never assume it does: a 29 mm Brother label offers 25.9 mm
   * @param printableOffsetX where that print window starts, measured from the medium's left edge.
   *     Not derivable from the width either — the window is not centred on the head
   * @param verified {@code true} only when every value above came from a data sheet or a verified
   *     template, {@code false} when even one was derived. Binary, and it stays binary: a third
   *     state would be read as "good enough"
   * @param source where the numbers came from, so that a reader can check them. Required when
   *     {@code verified} is {@code true}
   */
  record LabelMedium(
      String mediumKey,
      String vendor,
      String articleNumber,
      String name,
      Kind kind,
      int pageWidth,
      int pageHeight,
      int columns,
      int rows,
      int labelWidth,
      int labelHeight,
      int cornerRadius,
      int marginLeft,
      int marginTop,
      int pitchX,
      int pitchY,
      int printableWidth,
      int printableOffsetX,
      boolean verified,
      String source) {}

  /** What the medium physically is. */
  enum Kind {
    /** A sheet with a grid of labels on it. */
    SHEET,
    /** A continuous roll, where the height is the cut length and there is one column. */
    ROLL
  }
}
