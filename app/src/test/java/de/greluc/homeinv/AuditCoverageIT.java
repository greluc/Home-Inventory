/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every mutating action leaves a trace, and nothing else does (REQ-SEC-068).
 *
 * <p>The guarantee is structural rather than conscientious: a block that knows what changed records
 * its own entry with a diff, and the boundary records a plain one for any successful mutating
 * request that nothing recorded. So the requirement does not rest on every service remembering, and
 * a service written next year by somebody who never read this still leaves a trace.
 *
 * <p>What the tests below pin down is the other half of that sentence — that a request which
 * changed <b>nothing</b> leaves nothing. A log that recorded reads, refusals and rollbacks would
 * bury the entries an incident is looking for.
 */
@DisplayName("The audit trail of a request")
class AuditCoverageIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "every-change-is-recorded-2026";

  @Autowired private AuditLog audit;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("records a mutating request that no block recorded, with the caller as the actor")
  void theBoundaryRecordsWhatNobodyElseDid() throws Exception {
    Session session = anAdministrator("fallback");

    // A tag: a small mutating endpoint whose service does not record an entry of
    // its own yet. Exactly the case the boundary exists for.
    mockMvc
        .perform(
            post("/api/v1/tags")
                .session(session.session())
                .with(csrf())
                .header("User-Agent", "homeinv-web/1.0")
                .contentType("application/json")
                .content("{\"name\":\"Fragile\"}"))
        .andExpect(status().isCreated());

    List<AuditLog.AuditView> entries = entriesOf(session.tenantId());
    assertThat(entries).hasSize(1);
    AuditLog.AuditView entry = entries.getFirst();
    assertThat(entry.actorKind()).isEqualTo(AuditLog.ActorKind.USER);
    assertThat(entry.actorId()).isEqualTo(session.userId());
    assertThat(entry.action()).isEqualTo("post /api/v1/tags");
    // The pattern and not the concrete path, and no body: the request carries
    // `sensitive` values and an audit log that held them would be a way to read
    // them (REQ-SEC-027).
    assertThat(entry.diff()).isEqualTo("{}");
    assertThat(entry.client()).isEqualTo("homeinv-web/1.0");
  }

  @Test
  @DisplayName("records nothing for a read")
  void readsAreNotRecorded() throws Exception {
    Session session = anAdministrator("read");

    mockMvc.perform(get("/api/v1/tags").session(session.session())).andExpect(status().isOk());

    // A log that recorded reads would bury the entries an incident looks for,
    // and REQ-SEC-068 asks for mutating actions.
    assertThat(entriesOf(session.tenantId())).isEmpty();
  }

  @Test
  @DisplayName("records nothing for a request that was refused")
  void refusalsAreNotRecorded() throws Exception {
    Session session = anAdministrator("refused");

    mockMvc
        .perform(
            post("/api/v1/tags")
                .session(session.session())
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"\"}"))
        .andExpect(status().is4xxClientError());

    // Nothing changed, so there is nothing to record. An entry here would be a
    // record of something that did not happen.
    assertThat(entriesOf(session.tenantId())).isEmpty();
  }

  // -------------------------------------------------------------------------

  private List<AuditLog.AuditView> entriesOf(UUID tenant) {
    Page<AuditLog.AuditView> page =
        TenantContext.callAs(
            tenant,
            () ->
                audit.actions(
                    null,
                    Instant.now().minus(1, ChronoUnit.HOURS),
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    null,
                    50));
    return page.data();
  }

  /**
   * An administrator of a fresh tenant, signed in.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the session, with the ids the assertions need
   * @throws Exception when provisioning or signing in fails
   */
  private Session anAdministrator(String name) throws Exception {
    String email = "audit-coverage-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Auditing " + name, userId);
    return new Session(signIn(email, PASSWORD), userId, tenantId);
  }

  /**
   * A signed-in administrator.
   *
   * @param session the HTTP session
   * @param userId who they are
   * @param tenantId which tenant they own
   */
  private record Session(MockHttpSession session, UUID userId, UUID tenantId) {}
}
