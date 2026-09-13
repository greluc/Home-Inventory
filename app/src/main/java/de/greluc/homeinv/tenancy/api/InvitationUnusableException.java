/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

/**
 * The invitation cannot be used: there is no such token, or it has been used, withdrawn or has run
 * out (REQ-TEN-004).
 *
 * <p><b>One exception for all four</b>, and one answer. Distinguishing them would let whoever holds
 * a token learn which of the four it is, and "this token was real and has been used" is a different
 * fact from "this token was never real" — the first says somebody was invited here. Single use is
 * the requirement; indistinguishable failure is what makes the single use not also an oracle.
 *
 * <p>Answered as {@code 410 Gone} rather than {@code 404}: the caller followed a link that was
 * meant for them, and "this no longer works" is the true thing to say about every one of the four
 * cases, including the one where the token never existed.
 */
public class InvitationUnusableException extends RuntimeException {

  /** Creates the refusal. */
  public InvitationUnusableException() {
    super("This invitation cannot be used. Ask whoever invited you to send a new one.");
  }
}
