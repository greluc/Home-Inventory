/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * Getting back into an account whose password is gone (REQ-SEC-018).
 *
 * <h2>Four properties, and each one is load-bearing</h2>
 *
 * <ul>
 *   <li><b>Single use.</b> A token spent is a token that stops working, so a reset mail forwarded
 *       or found later opens nothing.
 *   <li><b>Thirty minutes.</b> Long enough to find the message, short enough that an old mailbox is
 *       not a standing key to the account.
 *   <li><b>Every session ends.</b> Somebody who took the account over is signed out by the real
 *       owner's reset — otherwise the reset returns the password and leaves the intruder logged in.
 *   <li><b>The old address is told.</b> The half that matters: if this was not you, you find out at
 *       the address the attacker does not control.
 * </ul>
 *
 * <h2>Asking never tells the asker anything</h2>
 *
 * <p>{@link #request} answers the same way for an address that has an account and one that does
 * not, and takes about as long either way. A reset endpoint that said "no such account" would be a
 * way to ask the instance who is registered on it (REQ-SEC-025).
 */
public interface PasswordReset {

  /**
   * Starts a reset, and says nothing about whether there was anything to reset.
   *
   * <p>For an address with an account: a token is minted, the old one — if any — is replaced, and
   * the message goes out through the notification block. For an address without one: nothing
   * happens, and the caller cannot tell which of the two it was.
   *
   * @param email the address the reset was asked for
   * @param ip where it was asked from, for the message, for the throttle, and for an operator
   *     reading a run of requests
   * @throws TooManyAttemptsException when this address or this caller has asked too often — the
   *     throttle protects the recipient's mailbox and has counters of its own, so it never locks
   *     anybody out of signing in
   */
  void request(String email, String ip);

  /**
   * Redeems a token and sets the new password.
   *
   * <p>Ends every session the account has open, then tells the old address that the password
   * changed. In that order: the notification is the last step because it is the one that can fail
   * on somebody else's mail server, and a reset that rolled back because a message could not be
   * sent would leave the person locked out with no way to try again.
   *
   * @param token what the message carried
   * @param newPassword the new password. Checked against {@link PasswordPolicy} here rather
     than by the caller, and before the token is spent, so that a refusal costs nobody their
     link
   * @throws InvalidResetTokenException when the token is unknown, expired or already spent — one
   *     exception for all three, because telling them apart tells somebody holding a stolen token
   *     which one they have
   * @throws WeakPasswordException when the new password does not meet {@link PasswordPolicy}
   */
  void complete(String token, String newPassword);

  /** What an unusable token gets, whatever is wrong with it. */
  class InvalidResetTokenException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** Says nothing about which of the three it was. */
    public InvalidResetTokenException() {
      super("This password reset link is not valid. Ask for a new one.");
    }
  }
}
