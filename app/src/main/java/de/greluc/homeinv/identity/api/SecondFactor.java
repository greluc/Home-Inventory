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
    RECOVERY_CODE,

    /** A passkey: a key pair whose private half never leaves the authenticator. */
    PASSKEY
  }

  /**
   * What an account holds.
   *
   * @param totpConfirmed whether a confirmed TOTP secret exists
   * @param totpEnrolledAt when that secret was confirmed, or null
   * @param recoveryCodesLeft how many single-use codes are unspent. Zero beside a confirmed factor
   *     is a state worth showing: losing the phone then loses the account
   * @param passkeys the passkeys this account has registered, newest last
   */
  record Enrolment(
      boolean totpConfirmed,
      Instant totpEnrolledAt,
      int recoveryCodesLeft,
      List<Passkey> passkeys) {

    /**
     * Whether a login by this account has to answer a challenge.
     *
     * @return true when it holds either kind of second factor
     */
    public boolean protectedByASecondFactor() {
      return totpConfirmed || !passkeys.isEmpty();
    }
  }

  /**
   * One registered passkey, as its owner sees it.
   *
   * @param id the credential's row, for removing it
   * @param label what the person called it
   * @param registeredAt when it was registered
   * @param lastUsedAt when it was last used to sign in, or null
   */
  record Passkey(UUID id, String label, Instant registeredAt, Instant lastUsedAt) {}

  /**
   * A ceremony that has been started: what the browser needs, and what the caller must remember.
   *
   * @param optionsJson the options for {@code navigator.credentials}, as JSON
   * @param challenge the challenge, which the caller keeps and hands back with the response. It is
   *     kept in the session rather than returned to be echoed: a challenge the client could choose
   *     is no challenge at all
   */
  record Ceremony(String optionsJson, String challenge) {}

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
   * Begins registering a passkey.
   *
   * @param userId the account
   * @param account the address, which the authenticator shows in its own list
   * @param displayName what to call the person there
   * @return the options and the challenge to remember
   */
  Ceremony beginPasskeyRegistration(UUID userId, String account, String displayName);

  /**
   * Finishes registering a passkey.
   *
   * @param userId the account
   * @param credentialJson what the browser produced
   * @param challenge the challenge this ceremony was started with
   * @param label what to call this authenticator, or null for a generated name
   * @throws InvalidSecondFactorException when the response does not verify
   */
  void confirmPasskeyRegistration(
      UUID userId, String credentialJson, String challenge, String label);

  /**
   * Begins proving a passkey, at a login or a re-confirmation.
   *
   * @param userId the account
   * @return the options and the challenge to remember
   * @throws InvalidSecondFactorException when the account has no passkey, which is the same answer
   *     a wrong code gets: what a client learns is that this way in does not work
   */
  Ceremony beginPasskeyAssertion(UUID userId);

  /**
   * Verifies a passkey assertion.
   *
   * @param userId the account
   * @param credentialJson what the browser produced
   * @param challenge the challenge this ceremony was started with
   * @throws InvalidSecondFactorException when it does not verify, or names no passkey of this
   *     account
   */
  void verifyPasskey(UUID userId, String credentialJson, String challenge);

  /**
   * Removes one passkey.
   *
   * @param userId the account
   * @param passkeyId which one
   * @throws de.greluc.homeinv.platform.NotFoundException when this account has no such passkey
   */
  void removePasskey(UUID userId, UUID passkeyId);

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
