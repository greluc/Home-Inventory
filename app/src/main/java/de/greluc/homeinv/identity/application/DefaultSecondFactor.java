/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.InvalidSecondFactorException;
import de.greluc.homeinv.identity.api.SecondFactor;
import de.greluc.homeinv.identity.api.SecondFactorAlreadyEnrolledException;
import de.greluc.homeinv.identity.domain.Credential;
import de.greluc.homeinv.identity.infrastructure.CredentialKey;
import de.greluc.homeinv.identity.infrastructure.CredentialRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrols, verifies and removes second factors (REQ-AUTH-002).
 *
 * <h2>What is stored, and what is not</h2>
 *
 * <p>The TOTP secret is sealed under the instance's credential key, because verifying a code means
 * generating one and a hash cannot do that. A recovery code is a password by another name — used
 * once, typed by a person — so it is hashed with the same Argon2id encoder the password uses, and
 * is unreadable afterwards even here.
 *
 * <h2>Why a wrong code costs an Argon2id verification</h2>
 *
 * <p>A code that is not a valid TOTP code is checked against every unspent recovery code, and that
 * is the expensive path. It is also the only correct one: a recovery code has no lookup key, and
 * short-circuiting on "this looks like six digits" would tell an attacker which kind of code the
 * account is waiting for.
 */
@Service
@Slf4j
public class DefaultSecondFactor implements SecondFactor {

  /** How many recovery codes a confirmation issues. */
  private static final int RECOVERY_CODES = 10;

  /** Ten characters from an unambiguous alphabet: about 50 bits, and readable off paper. */
  private static final int RECOVERY_CODE_LENGTH = 10;

  /**
   * No {@code I}, {@code O}, {@code 0} or {@code 1}.
   *
   * <p>A recovery code is copied by hand from a sheet of paper, usually under some pressure, and
   * the characters people confuse are the ones that turn a working code into a support case.
   */
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final CredentialRepository credentials;
  private final CredentialKey credentialKey;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final String issuer;

  /**
   * @param credentials where authenticators are stored
   * @param credentialKey what seals a TOTP secret at rest
   * @param passwordEncoder the Argon2id encoder, which hashes recovery codes as well as passwords
   * @param clock the clock use cases read time from
   * @param issuer what an authenticator app calls this instance in its list, from
   *     {@code HOMEINV_TOTP_ISSUER}. It is shown to the person and nothing depends on its value —
   *     which is why this is the one setting here with a default
   */
  public DefaultSecondFactor(
      CredentialRepository credentials,
      CredentialKey credentialKey,
      PasswordEncoder passwordEncoder,
      Clock clock,
      @Value("${homeinv.security.totp-issuer:Home Inventory}") String issuer) {
    this.credentials = credentials;
    this.credentialKey = credentialKey;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.issuer = issuer;
  }

  @Override
  @Transactional(readOnly = true)
  public Enrolment enrolmentOf(UUID userId) {
    Optional<Credential> totp = credentials.findTotp(userId);
    boolean confirmed = totp.map(Credential::isUsable).orElse(false);
    return new Enrolment(
        confirmed,
        confirmed ? totp.get().getConfirmedAt() : null,
        credentials.findUnspentRecoveryCodes(userId).size());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isRequiredFor(UUID userId) {
    return credentials.findTotp(userId).map(Credential::isUsable).orElse(false);
  }

  @Override
  @Transactional
  public TotpEnrolment beginTotpEnrolment(UUID userId, String account) {
    Instant now = Instant.now(clock);
    Optional<Credential> existing = credentials.findTotp(userId);
    if (existing.isPresent()) {
      if (existing.get().isUsable()) {
        throw new SecondFactorAlreadyEnrolledException();
      }
      // An enrolment that was never confirmed is somebody who scanned a code and
      // closed the app. Replacing it is what "ask again" means, and the partial
      // unique index would refuse a second live row anyway.
      existing.get().remove(userId, now);
    }

    byte[] secret = TotpCodes.newSecret();
    credentials.save(
        Credential.enrol(userId, Credential.TOTP, credentialKey.seal(secret), null, now));
    log.info("Account {} began enrolling a second factor.", userId);
    return new TotpEnrolment(
        TotpCodes.base32(secret), TotpCodes.provisioningUri(issuer, account, secret));
  }

  @Override
  @Transactional
  public List<String> confirmTotpEnrolment(UUID userId, String code) {
    Instant now = Instant.now(clock);
    Credential totp = credentials.findTotp(userId).orElseThrow(InvalidSecondFactorException::new);
    if (totp.isUsable()) {
      // Already confirmed: this call has nothing to do, and answering "fine"
      // would hand out a second set of recovery codes for one enrolment.
      throw new InvalidSecondFactorException();
    }
    long step = TotpCodes.verify(credentialKey.open(totp.material()), code, now);
    if (step < 0) {
      throw new InvalidSecondFactorException();
    }

    totp.confirm(now);
    // The step is spent by proving it, exactly as it would be by a login: the
    // code that confirmed an enrolment must not also be the code that completes
    // the next sign-in.
    totp.used(TotpCodes.startOf(step), now);
    log.info("Account {} confirmed a second factor.", userId);
    return issueRecoveryCodes(userId, now);
  }

  @Override
  @Transactional
  public List<String> reissueRecoveryCodes(UUID userId) {
    if (!isRequiredFor(userId)) {
      // Recovery codes exist to recover a second factor. Issued without one they
      // would be the only factor, and a set of ten printable passwords is not
      // what REQ-AUTH-002 asks for.
      throw new InvalidSecondFactorException();
    }
    return issueRecoveryCodes(userId, Instant.now(clock));
  }

  @Override
  @Transactional
  public Kind verify(UUID userId, String code) {
    Instant now = Instant.now(clock);
    Optional<Credential> totp = credentials.findTotp(userId).filter(Credential::isUsable);

    if (totp.isPresent()) {
      long step = TotpCodes.verify(credentialKey.open(totp.get().material()), code, now);
      if (step >= 0) {
        // The replay guard of RFC 6238 §5.2: a code from a step already spent is
        // refused, so somebody who read one over a shoulder cannot use it in the
        // thirty seconds it is still arithmetically valid.
        Instant lastUsed = totp.get().getLastUsedAt();
        if (lastUsed != null && TotpCodes.stepOf(lastUsed) >= step) {
          throw new InvalidSecondFactorException();
        }
        totp.get().used(TotpCodes.startOf(step), now);
        return Kind.TOTP;
      }
    }

    for (Credential recovery : credentials.findUnspentRecoveryCodes(userId)) {
      if (passwordEncoder.matches(normalise(code), recovery.material())) {
        recovery.used(now);
        log.warn(
            "Account {} signed in with a recovery code; {} remain.",
            userId,
            credentials.findUnspentRecoveryCodes(userId).size());
        return Kind.RECOVERY_CODE;
      }
    }

    throw new InvalidSecondFactorException();
  }

  @Override
  @Transactional
  public void removeTotp(UUID userId, String code) {
    Instant now = Instant.now(clock);
    Credential totp =
        credentials
            .findTotp(userId)
            .filter(Credential::isUsable)
            .orElseThrow(InvalidSecondFactorException::new);

    // The code first: an open session somebody walked away from must not be able
    // to strip the factor that would have stopped them.
    verify(userId, code);

    totp.remove(userId, now);
    credentials.findRecoveryCodes(userId).forEach(recovery -> recovery.remove(userId, now));
    log.warn("Account {} removed its second factor.", userId);
  }

  /**
   * Issues a fresh set of codes and retires whatever is left of the old one.
   *
   * @param userId the account
   * @param now when
   * @return the codes in clear, for the only time they are readable
   */
  private List<String> issueRecoveryCodes(UUID userId, Instant now) {
    credentials.findRecoveryCodes(userId).forEach(existing -> existing.remove(userId, now));

    List<String> codes = new ArrayList<>(RECOVERY_CODES);
    for (int i = 0; i < RECOVERY_CODES; i++) {
      String code = newRecoveryCode();
      codes.add(code);
      credentials.save(
          Credential.recoveryCode(userId, passwordEncoder.encode(normalise(code)), now));
    }
    return codes;
  }

  /**
   * A code somebody can read off paper.
   *
   * @return ten characters from the unambiguous alphabet, grouped in two halves
   */
  private static String newRecoveryCode() {
    StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH + 1);
    for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
      if (i == RECOVERY_CODE_LENGTH / 2) {
        code.append('-');
      }
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /**
   * What is hashed and compared, so that how a code was typed does not decide whether it works.
   *
   * @param code what was typed
   * @return the code upper-cased with spaces and hyphens removed
   */
  private static String normalise(String code) {
    return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
  }
}
