/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

/**
 * A request is larger than a limit allows — in bytes, in pixels, or in entries.
 *
 * <p>One exception for all of them because the answer is the same: {@code 413} with the
 * {@code payload-too-large} type, which the registry defines as "the request body exceeds the JSON
 * limit, or a bulk operation exceeds its entry limit" rather than as anything about uploads.
 *
 * <p>It lives in {@code platform} for that reason, and not in {@code media} where it started: the
 * JSON body limit of {@code REQ-SEC-065} raises it too, and an access adapter reaching into the
 * media block for a limit that has nothing to do with media would be a boundary crossed for no
 * reason.
 *
 * <p>The message carries the number. The pixel limit is the surprising one — a decompression bomb
 * is a small file — and a user whose 120-megapixel panorama is refused needs to be told it was the
 * pixels and not the megabytes.
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
