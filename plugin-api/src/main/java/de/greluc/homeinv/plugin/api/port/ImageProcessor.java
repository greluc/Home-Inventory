/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.io.InputStream;

/**
 * Makes the derivatives of an uploaded image (09 §9.2).
 *
 * <p>libvips is in the core image; ImageMagick and anything else is a plugin.
 *
 * <p>Bytes rather than file paths, which is the one place this port differs from the core's own
 * interface of the same name: a plugin is a separate container and shares no filesystem with the
 * core, so a path would name a file that is not there. The in-core implementation keeps its path
 * form because it has the file.
 *
 * <p>Stage 0 (REQ-MED-004).
 */
public interface ImageProcessor {

  /**
   * Reads an image's dimensions without decoding all of it.
   *
   * <p>Called before deriving, so that an image whose pixel count is beyond what the instance
   * allows is refused before anything decodes it — a decompression bomb costs its memory at decode
   * time, not at upload time.
   *
   * @param context who it is for
   * @param source the image
   * @return its dimensions
   * @throws de.greluc.homeinv.plugin.api.PluginException when the bytes are not an image this
   *     implementation reads
   */
  Dimensions probe(CallContext context, InputStream source);

  /**
   * Produces one derivative.
   *
   * <p>Metadata does not survive: EXIF, XMP and IPTC are dropped, GPS coordinates among them. A
   * thumbnail that carried the location of somebody's house would be a privacy leak through a
   * derived file, and derived files are the ones that get shared (REQ-PRIV-006).
   *
   * <p>Orientation <i>is</i> applied before the metadata goes — a picture that came out sideways
   * because the EXIF rotation was dropped is a bug, not a privacy measure.
   *
   * @param context who it is for
   * @param source the original
   * @param request how large, and in what
   * @return the derivative
   * @throws de.greluc.homeinv.plugin.api.PluginException when the source cannot be read or the
   *     format cannot be written
   */
  Derivative derive(CallContext context, InputStream source, DeriveRequest request);

  /**
   * How a derivative should come out.
   *
   * @param maxEdge the longest edge in pixels. The aspect ratio is kept and the image is never
   *     enlarged: a 300-pixel original asked for 1024 comes back at 300
   * @param format what to write
   * @param quality 1 to 100, the encoder's own scale. Zero means the implementation's default,
   *     which is what a caller without an opinion should send
   */
  record DeriveRequest(int maxEdge, OutputFormat format, int quality) {}

  /**
   * A produced derivative.
   *
   * @param mediaType what it is
   * @param content the bytes
   * @param dimensions how large it came out, which is what the core stores beside it
   */
  record Derivative(String mediaType, byte[] content, Dimensions dimensions) {}

  /**
   * An image's size in pixels.
   *
   * @param width in pixels
   * @param height in pixels
   */
  record Dimensions(int width, int height) {

    /**
     * How many pixels there are altogether.
     *
     * <p>As a {@code long}, because two {@code int} edges multiply past {@code int} at around
     * 46 000 square — and the whole reason to count pixels is to catch an image that is absurdly
     * large, which is exactly where the overflow would land.
     *
     * @return width times height
     */
    public long pixels() {
      return (long) width * (long) height;
    }
  }

  /** What a derivative can be written as. */
  enum OutputFormat {
    /** AVIF, the smaller of the two and the default. */
    AVIF,
    /** WebP, for clients that do not decode AVIF. */
    WEBP
  }
}
