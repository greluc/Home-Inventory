/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a machine-readable code, and claims a raw scan as its own (09 §9.2).
 *
 * <p>QR is in the core image and opens no socket; DataMatrix, Code128, EAN-13, ITF-14, Aztec, GS1
 * Digital Link and NFC are the plugins this port exists for. A new one of them must run without a
 * single line of core change (REQ-PLG-010), which is what {@link #claim} is shaped by: the core
 * hands a raw string to each registered format in priority order and the first that claims it wins.
 * The core therefore never learns what a DataMatrix looks like.
 *
 * <p>Codes are stage 2. The port is here at stage 1 because the contract is written once
 * (REQ-PLG-001, ADR-0028), and because a port added later is a port the runtime was not designed
 * for.
 */
public interface CodeFormat {

  /**
   * The key this format is known by, lowercase and stable.
   *
   * <p>It is stored beside every code this format produced, so changing it orphans them. {@code
   * qr}, {@code datamatrix}, {@code ean13}, {@code gs1-digital-link}.
   *
   * @return the key
   */
  String formatKey();

  /**
   * Claims a raw scan and says what it means, or declines it.
   *
   * <p>One call rather than a {@code claims} and then a {@code parse}: over a process boundary the
   * second call costs another round trip to learn something the first already knew. A format that
   * declines returns an empty {@link Optional} and must not throw — declining is the normal answer
   * for every format but one, and an exception per format per scan would make the resolution chain
   * expensive and noisy.
   *
   * @param context who the scan is for
   * @param raw exactly what the scanner delivered, unmodified. Trimming or case-folding it is this
   *     method's business and not the caller's: what counts as insignificant differs per format
   * @return what the code means, or empty when this format does not recognise it
   */
  Optional<ParsedCode> claim(CallContext context, String raw);

  /**
   * Renders the symbol for a payload.
   *
   * @param context who it is for
   * @param payload what the symbol encodes
   * @param request the geometry and the output the caller wants
   * @return the rendered symbol
   * @throws de.greluc.homeinv.plugin.api.PluginException when the payload cannot be encoded in this
   *     format, or the requested output format is not one this implementation writes
   */
  RenderedCode render(CallContext context, String payload, RenderRequest request);

  /**
   * What a raw scan turned out to be.
   *
   * @param formatKey which format claimed it, so the caller need not remember which one it was
   *     asking
   * @param payload the content, decoded — for a GS1 Digital Link the URL, for an EAN the digits
   * @param attributes what the format could read beyond the payload, for example a GS1 application
   *     identifier or a batch number. Empty for formats that carry nothing but their payload
   */
  record ParsedCode(String formatKey, String payload, Map<String, String> attributes) {}

  /**
   * How a symbol should come out.
   *
   * @param moduleSizeMicrometres the size of one module, in micrometres — not in pixels, because
   *     the symbol goes onto a physical label whose geometry is measured in millimetres (10 §10.5)
   *     and a pixel is a property of a rendering rather than of a label
   * @param quietZoneModules how many modules of clear space the symbol needs around it. Zero lets
   *     the implementation use its specification's own minimum, which is what a caller who does not
   *     know the format should ask for
   * @param mediaType what to produce, for example {@code image/png} or {@code image/svg+xml}
   * @param errorCorrection the level, spelled as the format spells it ({@code L}, {@code M}, {@code
   *     Q}, {@code H} for QR). Empty for formats without the notion
   */
  record RenderRequest(
      int moduleSizeMicrometres, int quietZoneModules, String mediaType, String errorCorrection) {}

  /**
   * A rendered symbol.
   *
   * @param mediaType what it is, which may differ from what was asked for only when the request
   *     left it empty
   * @param content the bytes
   * @param widthMicrometres how wide it is on the medium, quiet zone included. The caller places it
   *     on a label and needs the physical size, not the pixel count
   * @param heightMicrometres how tall it is on the medium
   */
  record RenderedCode(
      String mediaType, byte[] content, int widthMicrometres, int heightMicrometres) {}
}
