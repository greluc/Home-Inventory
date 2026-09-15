/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.audit.api.AuditTrail;
import de.greluc.homeinv.audit.api.ChainVerification;
import de.greluc.homeinv.audit.infrastructure.ChainAnchors;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asking the log whether it has been altered (REQ-SEC-070, REQ-SEC-096, REQ-SEC-107, ADR-0046).
 *
 * <p>Every assertion here is about telling three states apart, and the whole design exists because
 * two of them look identical from a distance: a chain that was <b>truncated by a retention run</b>
 * and one that somebody <b>removed entries from</b>. Without the marker, honouring REQ-PRIV-010
 * would make the instance raise its own tampering alert daily.
 *
 * <p>The tests reach past the ports to alter rows, which is the one thing the application itself
 * cannot do — {@code homeinv_app} holds no {@code UPDATE} or {@code DELETE} on the table. They use
 * the superuser the test container runs as, which is exactly the attacker this mechanism is about.
 */
@DisplayName("Verifying the audit chain")
class AuditChainVerificationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AuditLog audit;
  @Autowired private ChainVerification verification;
  @Autowired private ChainAnchors anchors;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("says a chain nobody touched is intact")
  void anUntouchedChain() throws Exception {
    UUID tenant = aTenantWithEntries("intact", 5);

    ChainVerification.ChainResult result =
        TenantContext.callAs(tenant, verification::verifyChain);

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.INTACT);
    assertThat(result.checked()).isEqualTo(5);
    assertThat(result.firstBrokenSeq()).isEqualTo(-1);
  }

  @Test
  @DisplayName("finds an altered entry, and says which one")
  void anAlteredEntry() throws Exception {
    UUID tenant = aTenantWithEntries("altered", 4);

    // The attacker this exists for: somebody with database access rewriting what
    // an action was. The application cannot do this at all.
    asSuperuser(
        "update audit.audit_entry set action = 'item.read' where tenant_id = '"
            + tenant
            + "' and seq = 2");

    ChainVerification.ChainResult result =
        TenantContext.callAs(tenant, verification::verifyChain);

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.BROKEN);
    assertThat(result.firstBrokenSeq()).isEqualTo(2);
    assertThat(result.detail()).contains("altered");
  }

  @Test
  @DisplayName("finds a removed entry even though every remaining hash is genuine")
  void aRemovedEntry() throws Exception {
    UUID tenant = aTenantWithEntries("removed", 4);

    asSuperuser(
        "delete from audit.audit_entry where tenant_id = '" + tenant + "' and seq = 2");

    // Nothing was forged: entries 1, 3 and 4 still hash to what they say. What
    // gives it away is that 3 names a predecessor that is no longer there.
    ChainVerification.ChainResult result =
        TenantContext.callAs(tenant, verification::verifyChain);

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.BROKEN);
    assertThat(result.firstBrokenSeq()).isEqualTo(3);
  }

  @Test
  @DisplayName("calls a recorded truncation truncated, not broken")
  void aRecordedTruncation() throws Exception {
    // REQ-SEC-107 in one test. The same deletion as above, with the marker a
    // retention run writes — and the outcome has to be a different word, or
    // honouring REQ-PRIV-010 would look exactly like an attack.
    UUID tenant = aTenantWithEntries("truncated", 5);

    // The retention run's two steps, as `homeinv_housekeeping` performs them:
    // remove the oldest entries and record where the chain now begins.
    asSuperuser(
        "insert into audit.chain_truncation"
            + " (tenant_id, oldest_seq, oldest_hash, removed_count, reason)"
            + " select tenant_id, seq, entry_hash, 2, 'retention' from audit.audit_entry"
            + " where tenant_id = '" + tenant + "' and seq = 3");
    asSuperuser(
        "delete from audit.audit_entry where tenant_id = '" + tenant + "' and seq < 3");

    ChainVerification.ChainResult result =
        TenantContext.callAs(tenant, verification::verifyChain);

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.TRUNCATED);
    assertThat(result.checked()).isEqualTo(3);
    assertThat(result.detail()).contains("retention").contains("removed 2");
  }

  @Test
  @DisplayName("anchors every closed window, including one in which nothing happened")
  void anchorsEveryWindow() throws Exception {
    forgetEveryAnchor();
    aTenantWithEntries("anchored", 3);

    // The windows up to two hours from now, so that the hour the entries were
    // written in counts as closed. The current hour is never anchored: entries
    // are still going into it.
    assertThat(anchors.anchorDueWindows(Instant.now().plus(2, ChronoUnit.HOURS)))
        .as("the hour the entries were written in is now closed and had no anchor")
        .isGreaterThanOrEqualTo(1);

    ChainVerification.AnchorResult result =
        verification.verifyAnchors(
            Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.HOURS));

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.INTACT);
    assertThat(result.checked()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("an anchored window whose entry was removed no longer reproduces")
  void anAnchorNoticesARemoval() throws Exception {
    forgetEveryAnchor();
    UUID tenant = aTenantWithEntries("anchor-removal", 3);
    anchors.anchorDueWindows(Instant.now().plus(2, ChronoUnit.HOURS));

    // The chain alone can be recomputed by whoever can rewrite the rows. The
    // anchor cannot: it spans every tenant and chains to its predecessor
    // (ADR-0031). Removing an entry leaves the window's root unreproducible.
    asSuperuser(
        "delete from audit.audit_entry where tenant_id = '" + tenant + "' and seq = 2");

    ChainVerification.AnchorResult result =
        verification.verifyAnchors(
            Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.HOURS));

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.BROKEN);
    assertThat(result.brokenWindows()).isNotEmpty();
  }

  @Test
  @DisplayName("a window marked pruned keeps its place and is not recomputed")
  void aPrunedWindowIsNotRecomputed() throws Exception {
    forgetEveryAnchor();
    UUID tenant = aTenantWithEntries("pruned", 3);
    anchors.anchorDueWindows(Instant.now().plus(2, ChronoUnit.HOURS));

    Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);
    asSuperuser("delete from audit.audit_entry where tenant_id = '" + tenant + "'");
    asSuperuser(
        "update audit.chain_anchor set pruned = true where window_start = '" + window + "'");

    // The row and its place in the anchor chain stay; what stops is expecting a
    // recomputation over contents a retention run removed on purpose.
    ChainVerification.AnchorResult result =
        verification.verifyAnchors(
            Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.HOURS));

    assertThat(result.outcome()).isEqualTo(ChainVerification.Outcome.INTACT);
    assertThat(result.pruned()).isGreaterThanOrEqualTo(1);
  }

  // -------------------------------------------------------------------------

  /**
   * Removes every anchor, so that a test's assertions are about what that test anchored.
   *
   * <p>These tests share a class, a Spring context and therefore an hour: the window is anchored
   * once, and whichever test ran first would decide what the others see. Nothing in production
   * removes an anchor — they are never pruned (ADR-0031) — which is why this is here and not behind
   * a port.
   *
   * @throws Exception when the statement cannot be run
   */
  private static void forgetEveryAnchor() throws Exception {
    asSuperuser("delete from audit.chain_anchor");
  }

  /**
   * Runs one statement as the database superuser.
   *
   * <p>Not a convenience: it is the threat model. {@code homeinv_app} holds no {@code UPDATE} and
   * no {@code DELETE} on {@code audit.audit_entry} at all (REQ-SEC-069), so the application cannot
   * produce any of the damage these tests check for. Somebody with database access can, and that is
   * the person the chain and the anchors exist to catch.
   *
   * <p>The values interpolated below are UUIDs and timestamps this test generated. No caller
   * reaches this method, and nothing in production looks like it.
   *
   * @param sql the statement
   * @throws Exception when it cannot be run
   */
  private static void asSuperuser(String sql) throws Exception {
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /**
   * A tenant with a chain of the given length.
   *
   * @param name distinguishes this test's tenant
   * @param entries how many actions to record
   * @return the tenant
   * @throws Exception when provisioning fails
   */
  private UUID aTenantWithEntries(String name, int entries) throws Exception {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "chain-" + name + "@example.org",
                    "Owner",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    UUID tenant = provisioning.provision("Chaining " + name, userId);

    AuditTrail.runWith(
        AuditTrail.Origin.ofUser(userId, "198.51.100.4", "homeinv-test/1.0", "trace-" + name),
        () ->
            TenantContext.runAs(
                tenant,
                () ->
                    transactions.executeWithoutResult(
                        status -> {
                          for (int number = 1; number <= entries; number++) {
                            audit.record(
                                "item.updated", "item", UUID.randomUUID(),
                                Map.of("name", "change " + number));
                          }
                        })));
    return tenant;
  }
}
