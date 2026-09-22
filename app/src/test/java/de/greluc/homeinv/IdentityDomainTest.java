/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.domain.Credential;
import de.greluc.homeinv.identity.domain.ServiceAccount;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invariants the identity entities hold on their own (REQ-NFR-025).
 *
 * <h2>Why these are worth a test of their own</h2>
 *
 * <p>Every one of them is reachable through an integration test and none of them was reached by
 * one: a refused blank e-mail, a tenant limit outside its range, a token judged usable after it
 * expired, a recovery code judged unspent after being spent. They are the cases a caller gets wrong,
 * which is exactly why the entity checks them rather than trusting the caller — and a rule nothing
 * exercises is a rule that can be deleted by accident and pass.
 *
 * <p>{@code jacocoTestCoverageVerification} now holds the {@code domain} packages to 90 % and found
 * them (13 lines in {@code identity.domain} on 2026-09-21).
 *
 * <p>No container, no Spring: these are entities with no framework in them.
 */
@DisplayName("An identity entity")
class IdentityDomainTest {

  private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

  @Test
  @DisplayName("refuses a user with no e-mail address")
  void aUserNeedsAnEmail() {
    assertThatThrownBy(() -> AppUser.create(UUID.randomUUID(), "  ", "Name", "en", "hash", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("e-mail");
    assertThatThrownBy(() -> AppUser.create(UUID.randomUUID(), null, "Name", "en", "hash", NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("refuses a user with no display name")
  void aUserNeedsADisplayName() {
    assertThatThrownBy(
            () -> AppUser.create(UUID.randomUUID(), "a@example.invalid", " ", "en", "hash", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("display name");
  }

  @Test
  @DisplayName("refuses a tenant limit outside its range")
  void theTenantLimitIsBounded() {
    AppUser user = user();

    assertThatThrownBy(() -> user.replaceEntitlements(false, true, -1, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 0 and");
    assertThatThrownBy(
            () -> user.replaceEntitlements(false, true, Integer.MAX_VALUE, UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalArgumentException.class);

    // Null is "no limit of their own" and is not a value out of range.
    user.replaceEntitlements(false, true, null, UUID.randomUUID(), NOW);
    assertThat(user.getTenantLimit()).isNull();
  }

  @Test
  @DisplayName("says whether making an account an instance operator changed anything")
  void ensuringAnOperatorIsIdempotentAndSaysSo() {
    AppUser user = user();

    // ADR-0057: `bootstrap` runs on every start and must be able to put the
    // instance back in an administrable state. It has to be able to say "I
    // changed something" rather than log either way, which is what the boolean
    // is for -- and the second run must change nothing.
    assertThat(user.ensureInstanceOperator(NOW)).isTrue();
    assertThat(user.isInstanceOperator()).isTrue();
    assertThat(user.ensureInstanceOperator(NOW.plusSeconds(60))).isFalse();
  }

  @Test
  @DisplayName("records when a service token was last used")
  void aServiceTokenRecordsItsUse() {
    ServiceAccount account = serviceAccount(NOW.plus(30, ChronoUnit.DAYS));
    assertThat(account.getLastUsedAt()).isNull();

    account.used(NOW.plusSeconds(5));

    assertThat(account.getLastUsedAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(account.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  @Test
  @DisplayName("stops being usable when it is revoked or when it expires")
  void aServiceTokenIsUsableUntilItIsNot() {
    ServiceAccount account = serviceAccount(NOW.plus(30, ChronoUnit.DAYS));
    assertThat(account.isUsable(NOW)).isTrue();

    // Expiry and revocation are separate reasons and neither is the other: an
    // unrevoked token past its date is as dead as a revoked one inside it.
    assertThat(account.isUsable(NOW.plus(31, ChronoUnit.DAYS))).isFalse();

    account.revoke(UUID.randomUUID(), NOW.plusSeconds(1));
    assertThat(account.isUsable(NOW.plusSeconds(2))).isFalse();
  }

  @Test
  @DisplayName("spends a recovery code exactly once")
  void aRecoveryCodeIsSpentOnFirstUse() {
    Credential code = Credential.recoveryCode(UUID.randomUUID(), "hash", NOW);
    assertThat(code.isSpent()).isFalse();

    code.used(NOW.plusSeconds(10));

    assertThat(code.isSpent()).as("which is all a recovery code gets").isTrue();
  }

  /**
   * A user with everything the tests below do not care about filled in.
   *
   * @return the user
   */
  private static AppUser user() {
    return AppUser.create(UUID.randomUUID(), "owner@example.invalid", "Owner", "en", "hash", NOW);
  }

  /**
   * A service account expiring at a given moment.
   *
   * @param expiresAt when the token stops working
   * @return the account
   */
  private static ServiceAccount serviceAccount(Instant expiresAt) {
    return ServiceAccount.issue(
        UUID.randomUUID(),
        "importer",
        "the nightly import",
        "EDITOR",
        null,
        "token-hash",
        expiresAt,
        UUID.randomUUID(),
        NOW);
  }
}
