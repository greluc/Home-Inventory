/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import lombok.Getter;

/**
 * The detected type is not on the allowlist (REQ-MED-003).
 *
 * <p>Carries what was <em>detected</em>, not what was declared. An uploader who sends a shell script
 * named {@code holiday.jpg} is told the type was rejected and which type it actually was, which is
 * information they already had.
 */
@Getter
public class UnsupportedMediaTypeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The type detected from the magic bytes. */
  private final transient String detectedType;

  /**
   * Creates the exception.
   *
   * @param detectedType the type the magic bytes indicated, or {@code unknown}
   */
  public UnsupportedMediaTypeException(String detectedType) {
    super("Media type " + detectedType + " is not accepted");
    this.detectedType = detectedType;
  }
}
