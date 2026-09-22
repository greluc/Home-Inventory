/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.FederatedSignIn;
import de.greluc.homeinv.identity.api.RegistrationMode;
import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.identity.infrastructure.FederatedIdentityQueries;
import de.greluc.homeinv.identity.infrastructure.FederatedLoginQueries;
import de.greluc.homeinv.identity.infrastructure.RegistrationPolicy;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.IdentityProvider;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Federated sign-in, as REQ-AUTH-005 and REQ-AUTH-006 define it between them.
 *
 * <h2>The three questions, in the order they are asked</h2>
 *
 * <ol>
 *   <li><b>Is this identity linked?</b> By {@code (issuer, subject)}. If it is, that is the
 *       account, whatever the token says the address is.
 *   <li><b>Was this flow a link?</b> Then it attaches to the account that started it — which was
 *       signed in and re-confirmed before the redirect, because a flow cannot choose its own
 *       purpose on the way back.
 *   <li><b>May this instance create an account?</b> Only when the mode is {@code open}, the
 *       provider <i>verified</i> the address, and no account holds it already.
 * </ol>
 *
 * <p>Nothing else is tried. In particular a verified address that matches an existing account is
 * <b>refused with the same sentence as an unknown one</b>: linking by address is the takeover
 * REQ-AUTH-006 names, and a distinguishable refusal would answer "does this instance know this
 * address" for anybody who asks.
 *
 * <h2>The provider is the instance's, never a tenant's</h2>
 *
 * <p>Resolved with {@link ExtensionRegistry#lookupForInstance} and called with an instance-scoped
 * envelope (ADR-0066). A sign-in happens before any tenant is known — it is what decides which
 * memberships a session may act on — so a per-tenant resolution would have nothing to key on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultFederatedSignIn implements FederatedSignIn {

  /** How long a started flow stays usable. */
  private static final Duration LIFETIME = Duration.ofMinutes(10);

  /** The path a provider is told to come back to, under the public base URL. */
  private static final String CALLBACK_PATH = "/api/v1/auth/federated/callback";

  private final ExtensionRegistry extensions;
  private final FederatedLoginQueries flows;
  private final FederatedIdentityQueries links;
  private final UserProvisioning provisioning;
  private final RegistrationPolicy registration;
  private final Clock clock;

  /**
   * Where this instance is reached, which is where a provider sends the browser back.
   *
   * <p>The same value labels are printed with, and for the same reason it is handled carefully: a
   * redirect URI is registered with the provider, so changing it invalidates the registration
   * rather than merely pointing somewhere new.
   */
  @Value("${homeinv.public-base-url}")
  private String publicBaseUrl;

  @Override
  public Optional<Provider> installed() {
    try {
      return provider()
          .map(plugin -> plugin.describe(instanceCall()))
          .map(descriptor -> new Provider(descriptor.providerKey(), descriptor.displayName()));
    } catch (PluginException unreachable) {
      // A sign-in page that failed to render because a plugin is down would take
      // the password login with it. The button is absent instead, and the reason
      // is in the log where an operator looks.
      log.warn("The identity provider could not be described", unreachable);
      return Optional.empty();
    }
  }

  @Override
  @Transactional
  public Started begin(Purpose purpose, UUID userId, String returnTo, String loginHint) {
    String local = localPathOrNull(returnTo);
    IdentityProvider plugin =
        provider()
            .orElseThrow(
                () ->
                    new NoIdentityProviderException(
                        "This instance has no identity provider installed."));

    String handle = SingleUseTokens.mint();
    String verifier = SingleUseTokens.mint();
    String nonce = SingleUseTokens.mint();
    String redirectUri = publicBaseUrl + CALLBACK_PATH;

    IdentityProvider.Descriptor descriptor = plugin.describe(instanceCall());
    IdentityProvider.Authorization authorization =
        plugin.begin(
            instanceCall(),
            new IdentityProvider.AuthorizationRequest(
                redirectUri,
                handle,
                nonce,
                SingleUseTokens.challengeFor(verifier),
                loginHint == null ? "" : loginHint));

    // Written after the plugin answered: a provider that refuses to start a flow
    // leaves no row, and a row that outlived its own failure would be a handle
    // nothing can ever present.
    flows.start(
        SingleUseTokens.hash(handle),
        descriptor.providerKey(),
        purpose,
        userId,
        verifier,
        nonce,
        redirectUri,
        local,
        clock.instant().plus(LIFETIME));

    return new Started(authorization.authorizationUrl());
  }

  @Override
  @Transactional
  public Outcome complete(String state, String code) {
    Instant now = clock.instant();
    FederatedLoginQueries.Flow flow =
        flows
            .consume(SingleUseTokens.hash(state == null ? "" : state), now)
            .orElseThrow(UnknownFlowException::new);

    IdentityProvider plugin =
        provider()
            .orElseThrow(
                () ->
                    new NoIdentityProviderException(
                        "The identity provider that started this sign-in is no longer installed."));

    IdentityProvider.Identity identity =
        plugin.complete(
            instanceCall(),
            new IdentityProvider.CompletionRequest(
                code, flow.codeVerifier(), flow.redirectUri(), flow.nonce()));

    Optional<UUID> linked = links.accountFor(identity.issuer(), identity.subject());

    if (flow.purpose() == Purpose.LINK) {
      return linkTo(flow, identity, linked);
    }
    if (linked.isPresent()) {
      links.used(identity.issuer(), identity.subject(), now);
      log.info("User {} signed in through {}", linked.get(), identity.issuer());
      return new Outcome(Result.SIGNED_IN, linked.get(), flow.returnTo());
    }
    return maybeCreate(flow, identity);
  }

  /**
   * Attaches an identity to the account that started a link flow.
   *
   * @param flow what was started, carrying the account
   * @param identity who the provider says this is
   * @param linked the account that already holds this identity, if any
   * @return what happened
   */
  private Outcome linkTo(
      FederatedLoginQueries.Flow flow, IdentityProvider.Identity identity, Optional<UUID> linked) {
    if (linked.isPresent()) {
      // Including the case where it is already linked to THIS account: saying
      // "already linked elsewhere" to somebody who linked it themselves is
      // confusing, so that one is a success with nothing to do.
      if (linked.get().equals(flow.userId())) {
        return new Outcome(Result.LINKED, flow.userId(), flow.returnTo());
      }
      log.info(
          "Refused to link {}: the identity at {} already belongs to another account",
          flow.userId(),
          identity.issuer());
      return new Outcome(Result.LINKED_ELSEWHERE, flow.userId(), flow.returnTo());
    }
    boolean made =
        links.link(
            flow.userId(),
            flow.providerKey(),
            identity.issuer(),
            identity.subject(),
            identity.emailVerified() ? emptyToNull(identity.email()) : null);
    if (!made) {
      // Somebody linked the same identity between the read above and this insert.
      // The unique index decided, which is the point of letting it.
      return new Outcome(Result.LINKED_ELSEWHERE, flow.userId(), flow.returnTo());
    }
    log.info("User {} linked an identity at {}", flow.userId(), identity.issuer());
    return new Outcome(Result.LINKED, flow.userId(), flow.returnTo());
  }

  /**
   * Creates an account for an unlinked identity, where the instance allows it.
   *
   * @param flow what was started
   * @param identity who the provider says this is
   * @return what happened, which is a refusal on every instance that is not {@code open}
   */
  private Outcome maybeCreate(FederatedLoginQueries.Flow flow, IdentityProvider.Identity identity) {
    if (registration.mode() != RegistrationMode.OPEN) {
      // The same answer whether or not the address is one this instance knows.
      // A different one for a known address would be an account oracle, and
      // acting on the match itself is the takeover REQ-AUTH-006 forbids.
      log.info(
          "Refused an unlinked identity at {}: registration is {}",
          identity.issuer(),
          registration.mode());
      return new Outcome(Result.NOT_LINKED, null, flow.returnTo());
    }
    if (!identity.emailVerified() || identity.email().isBlank()) {
      // An unverified address is a claim about somebody else: creating an account
      // from one would let a provider that lets people type anything create
      // accounts here in other people's names.
      return new Outcome(Result.ADDRESS_UNVERIFIED, null, flow.returnTo());
    }

    Optional<UUID> created =
        provisioning.createIfAbsent(
            identity.email(),
            identity.displayName().isBlank() ? identity.email() : identity.displayName(),
            "en",
            // A password nobody has and nobody needs: this account signs in
            // through the provider. The column is NOT NULL, and a person who
            // later wants a local password asks for a reset like anybody else.
            SingleUseTokens.mint());
    if (created.isEmpty()) {
      // The address already has an account. It is NOT linked automatically:
      // that is exactly REQ-AUTH-006, and the person signs in and links it from
      // their own settings.
      log.info("Refused to create an account at {}: the address already has one", identity.issuer());
      return new Outcome(Result.ADDRESS_TAKEN, null, flow.returnTo());
    }

    links.link(
        created.get(),
        flow.providerKey(),
        identity.issuer(),
        identity.subject(),
        identity.email());
    log.info("Created account {} from an identity at {}", created.get(), identity.issuer());
    return new Outcome(Result.ACCOUNT_CREATED, created.get(), flow.returnTo());
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.List<Link> linksOf(UUID userId) {
    return links.of(userId).stream()
        .map(
            link ->
                new Link(
                    link.id(),
                    link.providerKey(),
                    link.issuer(),
                    link.email(),
                    link.linkedAt(),
                    link.lastUsedAt()))
        .toList();
  }

  @Override
  @Transactional
  public boolean unlink(UUID userId, UUID id) {
    boolean removed = links.unlink(userId, id);
    if (removed) {
      log.info("User {} removed a linked identity", userId);
    }
    return removed;
  }

  /** The provider the operator installed, resolved for the instance (ADR-0066). */
  private Optional<IdentityProvider> provider() {
    return extensions.lookupForInstance(IdentityProvider.class);
  }

  /** The envelope a sign-in carries: for the instance, because there is no tenant yet. */
  private CallContext instanceCall() {
    return CallContext.forInstance("", "en", 0);
  }

  /**
   * Accepts a local path and refuses anything else.
   *
   * <p>A {@code returnTo} that could be absolute would be an open redirect with a sign-in in front
   * of it, which is how a phishing page borrows somebody else's domain. {@code //host} is refused
   * too: it is protocol-relative and a browser reads it as another site.
   *
   * @param returnTo what the caller asked for, or null
   * @return the path, or null when there was none
   * @throws IllegalArgumentException when it is not a local path
   */
  private static String localPathOrNull(String returnTo) {
    if (returnTo == null || returnTo.isBlank()) {
      return null;
    }
    if (!returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")) {
      throw new IllegalArgumentException(
          "returnTo has to be a path on this instance, beginning with a single '/'");
    }
    return returnTo;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
