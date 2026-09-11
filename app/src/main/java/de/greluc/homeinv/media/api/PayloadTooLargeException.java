/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

/**
 * The upload is larger than a limit allows — in bytes or in pixels.
 *
 * <p>One exception for both because the answer is the same, and because the pixel limit is the more
 * surprising of the two: a decompression bomb is a small file. A user whose 120-megapixel panorama
 * is refused needs to be told it was the pixels and not the megabytes, which is why the message
 * carries the number rather than a generic sentence.
 */
public class PayloadTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the limit was and what was presented, phrased for a person
   */
  public PayloadTooLargeException(String message) {
    super(message);
  }
}
