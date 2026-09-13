/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.api.RegistrationClosedException;
import de.greluc.homeinv.identity.api.RegistrationMode;
import de.greluc.homeinv.identity.infrastructure.RegistrationPolicy;
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
  @DisplayName("stops the instance when it is open and nothing can confirm an address")
  void openNeedsAMailSender() {
    // REQ-AUTH-004's open mode confirms the address first, and confirming it needs
    // plugin-smtp, which this build has no runtime for. The alternative to
    // refusing is a sign-up page nobody could ever finish.
    assertThatThrownBy(() -> new RegistrationPolicy("open"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("plugin-smtp");
  }
}
