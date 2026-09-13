/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

/**
 * Where the last row of a page sat, so the next page can resume from it.
 *
 * <p>A holder created per call and handed to the row mapper, <b>not</b> a field on the adapter. An
 * adapter is a singleton: a field is shared by every request in flight, and two listings running at
 * once hand each other's positions out. That is a cursor silently resuming the wrong listing — a
 * page skipped or repeated under load, and never in a test.
 */
public final class LastRow {

  private CursorCodec.Position position;

  /**
   * Records where a row sat. Called once per row; the last call wins, which is the row the cursor
   * has to resume from.
   *
   * @param createdAt the row's creation instant
   * @param id the row's id, which breaks a tie between two of the same instant
   */
  public void at(java.time.Instant createdAt, java.util.UUID id) {
    this.position = new CursorCodec.Position(createdAt, id);
  }

  /**
   * The position of the last row read.
   *
   * @return the position, or {@code null} when the page was empty
   */
  public CursorCodec.Position position() {
    return position;
  }
}
