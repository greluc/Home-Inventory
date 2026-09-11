/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.nio.file.Path;

/**
 * Re-encoding and derivative generation (REQ-MED-005, REQ-MED-006).
 *
 * <p>Re-encoding is a security measure before it is a convenience. An image file can carry a
 * payload in a segment no decoder reads — a comment block, an appended archive, a polyglot header —
 * and re-encoding from decoded pixels leaves none of it. {@code 12 §12} lists it as the fifth
 * defence for exactly that reason, which is why even a {@code full} variant exists at the original
 * size: serving the uploaded bytes back would undo the measure.
 *
 * <p>Stripping EXIF is part of the same pass. GPS coordinates in a holiday photo are a privacy leak
 * the uploader did not intend and usually does not know about (REQ-MED-006).
 */
public interface ImageProcessor {

  /**
   * Reads an image's dimensions without decoding it.
   *
   * <p>Called <em>before</em> any decode, because a decompression bomb is a small file that becomes
   * a very large bitmap: the defence is to refuse it on its declared dimensions rather than to
   * discover the size by allocating it (REQ-SEC-040).
   *
   * @param source the file to inspect
   * @return the dimensions
   * @throws ImageProcessingException when the file is not a decodable image
   */
  Dimensions probe(Path source);

  /**
   * Re-encodes an image into a derivative, stripping all metadata.
   *
   * @param source the original file
   * @param target where to write the derivative
   * @param maxEdge the longest edge of the result; the image is never enlarged, because an upscaled
   *     thumbnail is bigger than the original and no clearer
   * @param format the output format
   * @return the dimensions of what was written
   * @throws ImageProcessingException when the conversion fails
   */
  Dimensions derive(Path source, Path target, int maxEdge, OutputFormat format);

  /**
   * The formats a derivative may be written in.
   *
   * <p>A closed set. The output format is decided by this application and never by the upload —
   * accepting a format from the uploader would be accepting a code path from the uploader.
   */
  enum OutputFormat {
    /** For photographs, which is most of what this application stores. */
    AVIF,
    /** For screenshots and anything with flat colour, where AVIF gains little. */
    WEBP
  }

  /**
   * An image's size in pixels.
   *
   * @param width the width
   * @param height the height
   */
  record Dimensions(int width, int height) {

    /**
     * The total pixel count, as a {@code long}.
     *
     * <p>A {@code long} because the product is what the limit is checked against, and two
     * {@code int} dimensions that each look reasonable can overflow an {@code int} when multiplied —
     * which would turn a decompression bomb into a negative number that passes every check.
     *
     * @return width times height
     */
    public long pixels() {
      return (long) width * (long) height;
    }
  }
}
