/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

/**
 * The bytes were offered at a place the upload is not at (REQ-MED-008).
 *
 * <p>The ordinary outcome of a broken connection, and not a fault: the client sent a chunk, the
 * connection dropped somewhere in the middle, and neither side knows how much arrived. The answer
 * is not to guess — it is to ask what arrived and continue from there, which is the whole
 * difference between an upload that survives a network change and one that starts again.
 *
 * <p>It becomes {@code 409 Conflict} at the HTTP surface, which is what the tus protocol asks for.
 */
public class OffsetMismatchException extends RuntimeException {

  /** How far the upload actually got. */
  private final long actual;

  /**
   * Says where the upload really stands.
   *
   * @param actual the bytes that have arrived
   */
  public OffsetMismatchException(long actual) {
    super("The upload is at " + actual + " bytes");
    this.actual = actual;
  }

  /**
   * How far the upload got.
   *
   * @return the authoritative offset, which the client should continue from
   */
  public long actual() {
    return actual;
  }
}
