/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.api.RegistrationClosedException;
import de.greluc.homeinv.identity.api.RegistrationMode;
import de.greluc.homeinv.identity.infrastructure.OpenRegistrationReadiness;
import de.greluc.homeinv.identity.infrastructure.RegistrationPolicy;
import de.greluc.homeinv.plugin.api.port.IdentityProvider;
import de.greluc.homeinv.plugin.api.port.NotificationChannel;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may come to exist on this instance (REQ-AUTH-004).
 *
 * <p>A unit test and not an integration one: what is under test is a setting and the three readings
 * of it, and each reading is decided before anything else in the application starts. Two of them
 * <em>are</em> the application refusing to start, which is not something a running context can be
 * asked about.
 *
 * <p>What the mode then does to an invitation is proved where invitations are:
 * {@code MembersAndInvitationsIT} accepts one for an unknown address, which is the call
 * {@code closed} refuses.
 */
@DisplayName("The registration mode")
class RegistrationModeIT {

  @Test
  @DisplayName("is invite-only unless the operator says otherwise")
  void theDefault() {
    RegistrationPolicy policy = new RegistrationPolicy("invite_only");
    assertThat(policy.mode()).isEqualTo(RegistrationMode.INVITE_ONLY);
    // An invitation for an address nobody has creates the account, which is what
    // invite-only means.
    policy.requireRegistrationPermitted();
  }

  @Test
  @DisplayName("creates nobody when it is closed, and says what to do instead")
  void closed() {
    RegistrationPolicy policy = new RegistrationPolicy("closed");
    assertThat(policy.mode()).isEqualTo(RegistrationMode.CLOSED);
    assertThatThrownBy(policy::requireRegistrationPermitted)
        .isInstanceOf(RegistrationClosedException.class)
        .hasMessageContaining("Ask the operator");
  }

  @Test
  @DisplayName("is read case-insensitively, because an operator types it by hand")
  void spelling() {
    assertThat(new RegistrationPolicy("  Closed  ").mode()).isEqualTo(RegistrationMode.CLOSED);
    assertThat(new RegistrationPolicy("INVITE_ONLY").mode())
        .isEqualTo(RegistrationMode.INVITE_ONLY);
  }

  @Test
  @DisplayName("stops the instance when it names no mode")
  void anUnknownModeIsNotGuessedAt() {
    assertThatThrownBy(() -> new RegistrationPolicy("invite-only"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not one of invite_only, open or closed");
    assertThatThrownBy(() -> new RegistrationPolicy(""))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("is read on its own, and whether `open` can be served is asked later")
  void openParsesAndIsCheckedWhereTheRegistryIsReadable() {
    // It refused `open` in this constructor while the reason held: confirming an
    // address needs a plugin and there was no plugin runtime. There is one now
    // (ADR-0028), and the question moved to where it can be answered.
    assertThat(new RegistrationPolicy("open").mode()).isEqualTo(RegistrationMode.OPEN);
  }

  @Test
  @DisplayName("stops an open instance that can confirm nothing (REQ-AUTH-004)")
  void openWithoutAnythingToConfirmWith() {
    assertThatThrownBy(() -> readiness("open", false, false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing installed here can confirm one");
  }

  @Test
  @DisplayName("is served by a mail sender, or by an identity provider, or by both")
  void eitherMechanismServesIt() {
    // The requirement asks for an account created AFTER THE ADDRESS IS CONFIRMED and
    // not for a particular mechanism. A provider that verifies a token asserting the
    // address confirms it more strongly than a mailed link, and without this
    // deployment sending anything (REQ-AUTH-005).
    readiness("open", true, false).check();
    readiness("open", false, true).check();
    readiness("open", true, true).check();
  }

  @Test
  @DisplayName("asks nothing of an instance that is not open")
  void theOtherModesNeedNothingInstalled() {
    readiness("invite_only", false, false).check();
    readiness("closed", false, false).check();
  }

  /**
   * The readiness check over a registry that holds what the two flags say.
   *
   * @param mode the configured mode
   * @param channel whether a notification channel is installed for the instance
   * @param provider whether an identity provider is
   * @return the check, ready to run
   */
  private static OpenRegistrationReadiness readiness(
      String mode, boolean channel, boolean provider) {
    ExtensionRegistry registry =
        new ExtensionRegistry() {
          @Override
          public <T> Optional<T> lookup(Class<T> port, UUID tenantId) {
            return Optional.empty();
          }

          @Override
          public <T> Optional<T> lookupForInstance(Class<T> port) {
            boolean installed =
                (port == NotificationChannel.class && channel)
                    || (port == IdentityProvider.class && provider);
            // A stand-in rather than a mock: what the check reads is whether
            // anything is there, and a real plugin would need a socket to
            // answer a question about its own absence. Its methods are never
            // called, so a proxy that answers nothing is the whole of it.
            return installed
                ? Optional.of(
                    port.cast(
                        java.lang.reflect.Proxy.newProxyInstance(
                            port.getClassLoader(),
                            new Class<?>[] {port},
                            (proxy, method, arguments) -> null)))
                : Optional.empty();
          }

          @Override
          public <T> List<T> lookupAll(Class<T> port, UUID tenantId) {
            return List.of();
          }
        };
    return new OpenRegistrationReadiness(new RegistrationPolicy(mode), registry);
  }
}
