/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What an account authenticates with besides its password (REQ-AUTH-002, 12 §12.4).
 *
 * <p>Three things happen here and they are deliberately separate calls: enrolling an authenticator,
 * proving it once so the enrolment counts, and answering a challenge at login. An enrolment that
 * counted before a code had been generated from it would lock somebody out of their own account for
 * scanning a QR code and closing the app.
 *
 * <p>Every method takes the account explicitly rather than reading a session. The one caller that
 * has no session is the login: between the password and the session there is a person who has
 * proved one factor and is being asked for the second, and that is exactly when this is needed.
 */
public interface SecondFactor {

  /** What kind of authenticator answered, or is being asked for. */
  enum Kind {
    /** A time-based one-time password, RFC 6238. */
    TOTP,

    /** One of the single-use codes issued when TOTP was confirmed. */
    RECOVERY_CODE
  }

  /**
   * What an account holds.
   *
   * @param totpConfirmed whether a confirmed TOTP secret exists, which is what makes the second
   *     factor required at login
   * @param totpEnrolledAt when that secret was confirmed, or null
   * @param recoveryCodesLeft how many single-use codes are unspent. Zero with a confirmed TOTP is a
   *     state worth showing: losing the phone then loses the account
   */
  record Enrolment(boolean totpConfirmed, Instant totpEnrolledAt, int recoveryCodesLeft) {}

  /**
   * A secret that has just been generated, shown once and never again.
   *
   * @param secret the shared secret in base32, for somebody typing it in by hand
   * @param provisioningUri the {@code otpauth://} URI a QR code carries
   */
  record TotpEnrolment(String secret, String provisioningUri) {}

  /**
   * What an account holds, for its own overview.
   *
   * @param userId the account
   * @return its enrolment
   */
  Enrolment enrolmentOf(UUID userId);

  /**
   * Whether a login by this account has to answer a challenge.
   *
   * @param userId the account
   * @return true when it holds a confirmed second factor
   */
  boolean isRequiredFor(UUID userId);

  /**
   * Begins an enrolment: generates a secret, seals it, and returns it once.
   *
   * <p>Replaces an enrolment that was never confirmed, so somebody who lost the QR code can simply
   * ask again. It refuses to replace a <em>confirmed</em> one: that is a removal, and a removal
   * asks for a code first.
   *
   * @param userId the account enrolling
   * @param account the address to label the entry in the authenticator app with
   * @return the secret and the provisioning URI, which the caller shows and does not store
   * @throws SecondFactorAlreadyEnrolledException when a confirmed secret is already there
   */
  TotpEnrolment beginTotpEnrolment(UUID userId, String account);

  /**
   * Confirms an enrolment with a code generated from the new secret, and issues recovery codes.
   *
   * @param userId the account enrolling
   * @param code the six digits the authenticator app shows
   * @return the recovery codes, in clear, for the only time they are readable
   * @throws InvalidSecondFactorException when the code is wrong, or there is nothing to confirm
   */
  List<String> confirmTotpEnrolment(UUID userId, String code);

  /**
   * Issues a fresh set of recovery codes, invalidating whatever is left of the old set.
   *
   * @param userId the account
   * @return the new codes, in clear, for the only time they are readable
   * @throws InvalidSecondFactorException when the account holds no confirmed second factor, which
   *     would make recovery codes the <em>only</em> factor and therefore not a second one
   */
  List<String> reissueRecoveryCodes(UUID userId);

  /**
   * Verifies a code, whether it is a TOTP code or a recovery code.
   *
   * <p>One entry point on purpose: a caller that had to say which kind it was passing would be a
   * caller that can be told which kinds exist, and the login form asks for "your code".
   *
   * @param userId the account
   * @param code what was typed
   * @return which kind answered, so the caller can tell somebody they have just spent a recovery
   *     code
   * @throws InvalidSecondFactorException when nothing matches
   */
  Kind verify(UUID userId, String code);

  /**
   * Removes the second factor, with a code as the proof.
   *
   * <p>The code is required for the reason the enrolment is confirmed: somebody who has walked away
   * from an open session must not be able to strip the factor that would have stopped them.
   *
   * @param userId the account
   * @param code a current code, or a recovery code
   * @throws InvalidSecondFactorException when the code is wrong or there is nothing to remove
   */
  void removeTotp(UUID userId, String code);
}
