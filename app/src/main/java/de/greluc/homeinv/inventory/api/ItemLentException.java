/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

/**
 * The item is out, and what was asked cannot be done while it is (REQ-LIFE-005).
 *
 * <p>Two callers raise it, and the answer to both is the same action. {@link LoanLog#lend} refuses
 * to hand out something somebody already has, and {@link ItemService#delete} refuses to trash it —
 * putting a lent thing in the bin would throw away the only record of who to ask for it back.
 *
 * <p>A conflict rather than a malformed request. Nothing about the call is wrong and the same call
 * works once the thing is back; what refuses it is the state of the item.
 */
public class ItemLentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param reason what is wrong, in a sentence meant for a person
   */
  public ItemLentException(String reason) {
    super(reason);
  }
}
