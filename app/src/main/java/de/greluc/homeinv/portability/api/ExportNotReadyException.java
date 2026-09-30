/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

/**
 * The archive was asked for before it existed (REQ-PORT-005).
 *
 * <p>A {@code 409} and not a {@code 404}: the job is there and the caller is looking at the right
 * thing, it is simply not finished. A 404 would send somebody looking for a job they can see in
 * their own list.
 *
 * <p>Carries the state, because "still running" and "it failed" lead a caller somewhere different —
 * one is waiting and the other is asking again.
 */
public class ExportNotReadyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param state what the job is doing instead
   */
  public ExportNotReadyException(String state) {
    super("That export is not ready to download: it is " + state + ".");
  }
}
