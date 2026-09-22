/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

/**
 * Another request is already writing to this upload (REQ-MED-008).
 *
 * <p>tus asks a server to refuse a second concurrent write to one upload, and the reason is not
 * tidiness: two appends that both read the offset before either writes are both at the right place
 * and both write, leaving a file of the right length and the wrong bytes — which nothing notices
 * until the digest at the very end.
 *
 * <p>The refusal comes from the blob store, which is one process holding one volume and is
 * therefore the only place a lock covers every caller rather than only the ones that agreed to take
 * it. It becomes {@code 423 Locked}, which is the status tus names.
 */
public class UploadBusyException extends RuntimeException {

  /**
   * Says that somebody else has it.
   *
   * @param message what the store said
   */
  public UploadBusyException(String message) {
    super(message);
  }
}
