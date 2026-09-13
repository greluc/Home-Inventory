/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import com.webauthn4j.server.ServerProperty;
import de.greluc.homeinv.identity.api.InvalidSecondFactorException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two WebAuthn ceremonies, and the one piece of configuration that decides them (REQ-AUTH-002).
 *
 * <h2>What the relying party is</h2>
 *
 * <p>The {@code rpId} is the host of {@code HOMEINV_PUBLIC_BASE_URL} and the accepted origin is
 * that URL. A passkey is bound to the relying party it was created for: change the base URL and
 * every passkey on the instance stops working, which is the same warning
 * {@code 10 §10.2.1} gives about the value being printed onto labels — this is the second thing
 * that outlives a change to it.
 *
 * <h2>What is deliberately not verified</h2>
 *
 * <p>Attestation. The manager is the non-strict one: it accepts {@code none} and self attestation
 * and validates no certificate path. A self-hosted instance has no business deciding which
 * authenticator makes a person trustworthy, and the alternative needs FIDO's metadata service —
 * an outbound connection the core does not make (ADR-0026). What <em>is</em> verified is everything
 * that decides whether this response belongs to this challenge: the origin, the relying party, the
 * challenge itself, the signature, and the authenticator's counter.
 */
@Component
@Slf4j
public class WebAuthnCeremonies {

  /** Long enough that guessing one is not a strategy; the specification's floor is sixteen. */
  private static final int CHALLENGE_BYTES = 32;

  /** How long a ceremony may take, in milliseconds. A person fetching a key needs a minute. */
  private static final long TIMEOUT_MS = 120_000;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final WebAuthnManager manager;
  private final ObjectConverter converter = new ObjectConverter();
  private final AttestedCredentialDataConverter credentialData =
      new AttestedCredentialDataConverter(converter);

  private final Origin origin;
  private final String relyingPartyId;
  private final String relyingPartyName;

  /**
   * Reads where this instance answers, which is what a passkey is bound to.
   *
   * @param publicBaseUrl {@code HOMEINV_PUBLIC_BASE_URL}, the address people reach this instance at
   * @param relyingPartyName what the authenticator shows in its own list
   * @throws IllegalStateException when the base URL has no host, which would make every ceremony
   *     fail later with a message about a challenge rather than about configuration
   */
  public WebAuthnCeremonies(
      @Value("${homeinv.public-base-url}") String publicBaseUrl,
      @Value("${homeinv.security.totp-issuer:Home Inventory}") String relyingPartyName) {
    URI base = URI.create(publicBaseUrl);
    if (base.getHost() == null) {
      throw new IllegalStateException(
          "HOMEINV_PUBLIC_BASE_URL (" + publicBaseUrl + ") has no host, so no passkey could be"
              + " bound to this instance.");
    }
    this.origin = new Origin(publicBaseUrl);
    this.relyingPartyId = base.getHost();
    this.relyingPartyName = relyingPartyName;
    this.manager = WebAuthnManager.createNonStrictWebAuthnManager(converter);
  }

  /**
   * A fresh challenge, base64url as the browser and the stored copy both use it.
   *
   * @return the challenge
   */
  public String newChallenge() {
    byte[] bytes = new byte[CHALLENGE_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The options a browser needs to create a passkey.
   *
   * @param challenge the challenge the caller has stored
   * @param userId the account, which becomes the user handle
   * @param account the address, shown by the authenticator
   * @param displayName what to call the person in the authenticator's list
   * @param existing the credential ids this account already has, so a key is not registered twice
   * @return the options as JSON, ready for {@code navigator.credentials.create()}
   */
  public String registrationOptions(
      String challenge, UUID userId, String account, String displayName, List<String> existing) {
    PublicKeyCredentialCreationOptions options =
        new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(relyingPartyId, relyingPartyName),
            new PublicKeyCredentialUserEntity(handleOf(userId), account, displayName),
            challengeOf(challenge),
            List.of(
                new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256)),
            TIMEOUT_MS,
            existing.stream()
                .map(
                    id ->
                        new PublicKeyCredentialDescriptor(
                            PublicKeyCredentialType.PUBLIC_KEY, decode(id), null))
                .toList(),
            // A second factor, not a replacement for the password (REQ-AUTH-002),
            // so no resident key is asked for: the account is already known when
            // the passkey is used, and a discoverable credential would take a slot
            // on a hardware key for nothing.
            new AuthenticatorSelectionCriteria(
                null, ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.PREFERRED),
            null,
            null);
    return converter.getJsonConverter().writeValueAsString(options);
  }

  /**
   * The options a browser needs to prove a passkey.
   *
   * @param challenge the challenge the caller has stored
   * @param credentialIds which credentials this account has
   * @return the options as JSON, ready for {@code navigator.credentials.get()}
   */
  public String authenticationOptions(String challenge, List<String> credentialIds) {
    PublicKeyCredentialRequestOptions options =
        new PublicKeyCredentialRequestOptions(
            challengeOf(challenge),
            TIMEOUT_MS,
            relyingPartyId,
            credentialIds.stream()
                .map(
                    id ->
                        new PublicKeyCredentialDescriptor(
                            PublicKeyCredentialType.PUBLIC_KEY, decode(id), null))
                .toList(),
            UserVerificationRequirement.PREFERRED,
            null);
    return converter.getJsonConverter().writeValueAsString(options);
  }

  /**
   * What a registered passkey leaves behind.
   *
   * @param credentialId base64url of the raw credential id, which an assertion presents
   * @param attestedCredentialData base64 of the credential id and the public key together
   * @param signCount the authenticator's counter as it stood
   * @param transports how the authenticator can be reached, comma-separated, or null
   */
  public record Registered(
      String credentialId, String attestedCredentialData, long signCount, String transports) {}

  /**
   * Verifies a registration response and returns what to store.
   *
   * @param credentialJson what the browser produced, as JSON
   * @param challenge the challenge this ceremony was started with
   * @return the credential to store
   * @throws InvalidSecondFactorException when the response does not verify — a wrong origin, a
   *     wrong challenge, a broken signature. One exception for all of them, for
   *     {@code InvalidSecondFactorException}'s own reason
   */
  public Registered verifyRegistration(String credentialJson, String challenge) {
    try {
      RegistrationData data =
          manager.verifyRegistrationResponseJSON(
              credentialJson,
              new RegistrationParameters(
                  new ServerProperty(origin, relyingPartyId, challengeOf(challenge)), null, false));

      // Read once and checked rather than assumed. A verified response has all
      // three — the library would not have returned otherwise — but the types say
      // they are nullable because the same class carries a response that has only
      // been parsed, and a response that verified while carrying no public key is
      // a library bug this should refuse rather than dereference.
      AttestationObject attestation = data.getAttestationObject();
      AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authenticator =
          attestation == null ? null : attestation.getAuthenticatorData();
      AttestedCredentialData attested =
          authenticator == null ? null : authenticator.getAttestedCredentialData();
      if (attested == null) {
        throw new InvalidSecondFactorException();
      }

      Set<AuthenticatorTransport> transports = data.getTransports();
      return new Registered(
          Base64.getUrlEncoder().withoutPadding().encodeToString(attested.getCredentialId()),
          Base64.getEncoder().encodeToString(credentialData.convert(attested)),
          authenticator.getSignCount(),
          transports == null || transports.isEmpty()
              ? null
              : String.join(",", transports.stream().map(Object::toString).toList()));
    } catch (RuntimeException refused) {
      log.info("A passkey registration did not verify: {}", refused.toString());
      throw new InvalidSecondFactorException();
    }
  }

  /**
   * Verifies an assertion against a stored credential.
   *
   * @param credentialJson what the browser produced, as JSON
   * @param challenge the challenge this ceremony was started with
   * @param attestedCredentialData the stored credential, base64
   * @param signCount the counter as it was stored
   * @return the counter as the authenticator now reports it, to be stored
   * @throws InvalidSecondFactorException when the response does not verify, the counter went
   *     backwards, or the credential is not the one the challenge was issued for
   */
  public long verifyAssertion(
      String credentialJson, String challenge, String attestedCredentialData, long signCount) {
    try {
      CredentialRecord record =
          new CredentialRecordImpl(
              null,
              null,
              null,
              null,
              signCount,
              credentialData.convert(Base64.getDecoder().decode(attestedCredentialData)),
              null,
              null,
              null,
              null);
      AuthenticationData data =
          manager.verifyAuthenticationResponseJSON(
              credentialJson,
              new AuthenticationParameters(
                  new ServerProperty(origin, relyingPartyId, challengeOf(challenge)),
                  record,
                  null,
                  false));
      AuthenticatorData<AuthenticationExtensionAuthenticatorOutput> authenticator =
          data.getAuthenticatorData();
      if (authenticator == null) {
        throw new InvalidSecondFactorException();
      }
      return authenticator.getSignCount();
    } catch (RuntimeException refused) {
      log.info("A passkey assertion did not verify: {}", refused.toString());
      throw new InvalidSecondFactorException();
    }
  }

  /**
   * The credential id an assertion presents, without verifying anything.
   *
   * <p>Read from the response so the right stored credential can be loaded before the verification
   * that needs it. Nothing is trusted about it: an id that names no credential of this account is
   * refused, and one that names the wrong one fails the signature check.
   *
   * @param credentialJson what the browser produced
   * @return the credential id, base64url
   * @throws InvalidSecondFactorException when the response cannot be parsed at all
   */
  public String credentialIdOf(String credentialJson) {
    try {
      AuthenticationData data = manager.parseAuthenticationResponseJSON(credentialJson);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getCredentialId());
    } catch (RuntimeException unreadable) {
      throw new InvalidSecondFactorException();
    }
  }

  /**
   * The user handle a passkey carries, which is this instance's account id.
   *
   * @param userId the account
   * @return sixteen bytes
   */
  private static byte[] handleOf(UUID userId) {
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
    buffer.putLong(userId.getMostSignificantBits());
    buffer.putLong(userId.getLeastSignificantBits());
    return buffer.array();
  }

  private static Challenge challengeOf(String base64url) {
    return new DefaultChallenge(decode(base64url));
  }

  private static byte[] decode(String base64url) {
    return Base64.getUrlDecoder().decode(base64url);
  }
}
