/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import lombok.Getter;

/**
 * A resumable upload request that is not shaped like one (REQ-MED-008).
 *
 * <p>One class for the three ways a tus request can be malformed, because they differ only in the
 * status they deserve and a class each would be three classes that say the same thing: the version
 * header is missing or names another version ({@code 412}, which is what lets a client written
 * against a later version find out from the status rather than from a half-completed upload), the
 * declared length is absent or not a number ({@code 400}), or the metadata does not say what the
 * file will be attached to ({@code 400}).
 *
 * <p>It carries its own {@link ProblemType} rather than being mapped to one in the handler, because
 * the three statuses are the point.
 */
@Getter
public class TusRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What the response says, and with which status. */
  private final transient ProblemType type;

  /**
   * Creates the refusal.
   *
   * @param type what the response says
   * @param message the sentence meant for a person
   */
  public TusRequestException(ProblemType type, String message) {
    super(message);
    this.type = type;
  }
}
