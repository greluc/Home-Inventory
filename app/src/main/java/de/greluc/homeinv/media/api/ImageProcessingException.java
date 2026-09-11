/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

/**
 * An image could not be read or converted.
 *
 * <p>Carries no detail from the converter into the response. libvips echoes the file path in its
 * diagnostics, and the path is derived from content the uploader chose; the message goes to the log
 * and the caller learns only that the conversion failed.
 */
public class ImageProcessingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong, for the log
   * @param cause the underlying failure, or {@code null}
   */
  public ImageProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
