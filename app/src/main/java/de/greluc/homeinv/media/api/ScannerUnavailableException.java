/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

/**
 * The malware scanner could not be reached.
 *
 * <p>Distinct from a positive verdict on purpose. "Infected" is a statement about the file and the
 * upload is discarded; "unavailable" is a statement about the system and the upload may be retried.
 * Collapsing them would tell a user their photo contains a virus because a container was restarting.
 */
public class ScannerUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong, for the log
   * @param cause the underlying failure
   */
  public ScannerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
