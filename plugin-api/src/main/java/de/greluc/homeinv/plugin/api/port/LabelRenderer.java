/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a template and its data into something printable (09 §9.2).
 *
 * <p>The PDF sheet renderer is in the core image. ZPL, EPL, Brother raster and PNG are plugins,
 * because each of them is a printer language somebody else maintains.
 *
 * <p><b>A template is data, not a program</b> (ADR-0020, REQ-CORE-031). An implementation is handed
 * the template source and the values to put in it, and must not evaluate anything in the template
 * beyond substituting placeholders. A renderer with a scripting engine in it turns the type system
 * into a programming language through the back door.
 *
 * <p>Stage 2, written at stage 1 with the rest of the contract (REQ-PLG-001).
 */
public interface LabelRenderer {

  /**
   * What this renderer can produce.
   *
   * @param context who is asking
   * @return the media types, for example {@code application/pdf} or {@code
   *     application/vnd.zebra.zpl}. The core offers a renderer only for outputs a {@link
   *     PrintTarget} accepts, so the two sets are compared rather than assumed to fit
   */
  Set<String> outputMediaTypes(CallContext context);

  /**
   * Renders one job.
   *
   * @param context who it is for
   * @param request the template, the medium and the rows
   * @return the artifact
   * @throws de.greluc.homeinv.plugin.api.PluginException when the template cannot be read, a
   *     placeholder has no value, or the output format is not one this renderer writes
   */
  Rendered render(CallContext context, Request request);

  /**
   * One rendering job.
   *
   * @param templateKey which template, for the plugin's own logging and caching
   * @param templateSource the template itself, as text. Passed with the call rather than fetched by
   *     the plugin: the core hands out no URL for a plugin to fetch (REQ-SEC-034), and a template
   *     is small
   * @param medium the geometry to lay out on, as {@link LabelMediaProvider} publishes it. Every
   *     distance in it is in micrometres
   * @param rows one map of placeholder to value per label. The order is the order they print in,
   *     and an empty map is a deliberately blank label rather than an error
   * @param startOffset how many label positions to skip on the first sheet, so that a part-used
   *     sheet can be finished. Zero for roll media, where it has no meaning
   * @param outputMediaType which of {@link #outputMediaTypes(CallContext)} to produce
   */
  record Request(
      String templateKey,
      String templateSource,
      LabelMediaProvider.LabelMedium medium,
      List<Map<String, String>> rows,
      int startOffset,
      String outputMediaType) {}

  /**
   * A printable artifact.
   *
   * @param mediaType what it is
   * @param content the bytes, whole. Label jobs are small enough for one message, which is why this
   *     port does not stream and {@link BlobStore} does
   * @param pageCount how many pages or labels came out. The caller shows it before printing,
   *     because "this will use three sheets" is the last chance to notice a mistake
   */
  record Rendered(String mediaType, byte[] content, int pageCount) {}
}
