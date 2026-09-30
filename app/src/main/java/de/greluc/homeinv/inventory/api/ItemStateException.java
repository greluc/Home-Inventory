/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

/**
 * The item is in a state where what was asked cannot be done (04 §4.4).
 *
 * <p>Sibling of {@link ItemLentException} and deliberately not the same token: "somebody has it,
 * ask for it back" and "you sold it in March" lead a caller to different places, and a client that
 * branched on the detail text rather than on the {@code type} would be doing what a stable type
 * exists to prevent.
 *
 * <p>A conflict rather than a malformed request: nothing about the call is wrong, and the same call
 * against an item in another state works.
 */
public class ItemStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param reason what is wrong, in a sentence meant for a person
   */
  public ItemStateException(String reason) {
    super(reason);
  }
}
