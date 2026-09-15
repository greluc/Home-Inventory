/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit log: what it records, what chains it, and what it refuses (REQ-SEC-068…071,
 * REQ-SEC-073, REQ-PLG-011, ADR-0031).
 *
 * <p>The property worth the most here is the last one. A log the application could edit would be
 * evidence of nothing, and that is a <b>privilege</b> rather than a rule a service keeps — so the
 * test asks PostgreSQL what {@code homeinv_app} may do, not what the code happens to call.
 */
@DisplayName("The audit log")
class AuditLogIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AuditLog audit;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("records who did what, when, on what, and from where")
  void recordsTheWholeEntry() throws Exception {
    UUID tenant = aTenant("whole");
    UUID actor = UUID.randomUUID();

    TenantContext.runAs(
        tenant,
        () ->
            transactions.executeWithoutResult(
                status ->
                    audit.record(
                        new AuditLog.NewEntry(
                            AuditLog.ActorKind.USER,
                            actor,
                            null,
                            "item.created",
                            "item",
                            actor,
                            Map.of("name", Map.of("after", "A drill")),
                            "203.0.113.7",
                            "homeinv-web/1.0",
                            "00-trace-0000000000000001-01"))));

    AuditLog.AuditView entry = onlyEntry(tenant);
    assertThat(entry.seq()).isEqualTo(1);
    assertThat(entry.actorKind()).isEqualTo(AuditLog.ActorKind.USER);
    assertThat(entry.actorId()).isEqualTo(actor);
    assertThat(entry.action()).isEqualTo("item.created");
    assertThat(entry.resourceType()).isEqualTo("item");
    assertThat(entry.ip()).isEqualTo("203.0.113.7");
    assertThat(entry.client()).isEqualTo("homeinv-web/1.0");
    assertThat(entry.correlationId()).isEqualTo("00-trace-0000000000000001-01");
    assertThat(entry.diff()).contains("A drill");
  }

  @Test
  @DisplayName("chains each entry to the one before it, from a genesis of the tenant's own")
  void chainsWithinTheTenant() throws Exception {
    UUID tenant = aTenant("chain");

    TenantContext.runAs(
        tenant,
        () ->
            transactions.executeWithoutResult(
                status -> {
                  audit.record(anEntry("item.created"));
                  audit.record(anEntry("item.updated"));
                }));

    List<Map<String, Object>> rows = chainOf(tenant);
    assertThat(rows).hasSize(2);

    // The first chains from a value derived from the tenant id, so an empty chain
    // and one whose first entry was removed are different things.
    assertThat(hex(rows.get(0).get("prev_hash")))
        .isEqualTo(hex(sha256("homeinv-audit-genesis:" + tenant)));
    // And the second chains from the first, which is what makes a removal in the
    // middle visible.
    assertThat(hex(rows.get(1).get("prev_hash"))).isEqualTo(hex(rows.get(0).get("entry_hash")));
    assertThat(rows.get(0).get("entry_hash")).isNotEqualTo(rows.get(1).get("entry_hash"));
  }

  @Test
  @DisplayName("gives each tenant a chain of its own, so one cannot read the other's positions")
  void oneChainPerTenant() throws Exception {
    UUID first = aTenant("first");
    UUID second = aTenant("second");

    TenantContext.runAs(
        first, () -> transactions.executeWithoutResult(s -> audit.record(anEntry("item.created"))));
    TenantContext.runAs(
        second, () -> transactions.executeWithoutResult(s -> audit.record(anEntry("item.created"))));
    TenantContext.runAs(
        first, () -> transactions.executeWithoutResult(s -> audit.record(anEntry("item.updated"))));

    // Both start at one. A global chain would have given the second tenant a
    // sequence number that discloses how much the first has been writing
    // (ADR-0031).
    assertThat(seqsOf(first)).containsExactly(1L, 2L);
    assertThat(seqsOf(second)).containsExactly(1L);
  }

  @Test
  @DisplayName("records a plugin's write with the plugin as the actor, and no user id at all")
  void aPluginIsDistinguishable() throws Exception {
    // REQ-PLG-011 and REQ-SEC-073. Distinguishable means no reader has to know
    // which ids belong to people: the kind says it and there is no actor id.
    UUID tenant = aTenant("plugin");

    TenantContext.runAs(
        tenant,
        () ->
            transactions.executeWithoutResult(
                status ->
                    audit.record(
                        new AuditLog.NewEntry(
                            AuditLog.ActorKind.PLUGIN,
                            null,
                            "de.greluc.homeinv.plugin.isbn",
                            "item.updated",
                            "item",
                            UUID.randomUUID(),
                            Map.of(),
                            null,
                            null,
                            null))));

    AuditLog.AuditView entry = onlyEntry(tenant);
    assertThat(entry.actorKind()).isEqualTo(AuditLog.ActorKind.PLUGIN);
    assertThat(entry.actorId()).isNull();
    assertThat(entry.actorLabel()).isEqualTo("de.greluc.homeinv.plugin.isbn");
  }

  @Test
  @DisplayName("refuses an actor that is neither identified nor named")
  void anActorIsAlwaysIdentified() throws Exception {
    UUID tenant = aTenant("anonymous");

    // In the database and not in a service: a rule about what an entry must
    // contain is worth as much as the place it is enforced.
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    tenant,
                    () ->
                        transactions.executeWithoutResult(
                            status ->
                                audit.record(
                                    new AuditLog.NewEntry(
                                        AuditLog.ActorKind.USER,
                                        null,
                                        null,
                                        "item.created",
                                        "item",
                                        null,
                                        Map.of(),
                                        null,
                                        null,
                                        null)))))
        .hasMessageContaining("actor_is_identified");
  }

  @Test
  @DisplayName("answers what one account did in a period, and nothing anybody else did")
  void whatDidThisAccountDo() throws Exception {
    // REQ-SEC-071, the question an incident asks first.
    UUID tenant = aTenant("account");
    UUID theirs = UUID.randomUUID();
    UUID somebodyElse = UUID.randomUUID();

    TenantContext.runAs(
        tenant,
        () ->
            transactions.executeWithoutResult(
                status -> {
                  audit.record(byUser(theirs, "item.created"));
                  audit.record(byUser(somebodyElse, "item.updated"));
                  audit.record(byUser(theirs, "item.deleted"));
                }));

    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    Page<AuditLog.AuditView> page =
        TenantContext.callAs(tenant, () -> audit.actions(theirs, from, to, null, 50));

    assertThat(page.data()).extracting(AuditLog.AuditView::action)
        .containsExactly("item.deleted", "item.created");
  }

  @Test
  @DisplayName("is append-only for the application, and PostgreSQL is what says so")
  void appendOnlyByPrivilege() {
    // REQ-SEC-069. Asked of the database rather than of the code: a log the
    // application could edit would be evidence of nothing, and the guarantee is
    // worth what the privilege is worth.
    assertThat(may("homeinv_app", "INSERT")).isTrue();
    assertThat(may("homeinv_app", "SELECT")).isTrue();
    assertThat(may("homeinv_app", "UPDATE")).isFalse();
    assertThat(may("homeinv_app", "DELETE")).isFalse();

    // And the role that may delete is a different one, which is what makes
    // REQ-PRIV-010's retention possible without giving it to the application
    // (ADR-0046).
    assertThat(may("homeinv_housekeeping", "DELETE")).isTrue();
    assertThat(may("homeinv_housekeeping", "INSERT")).isFalse();
    assertThat(may("homeinv_housekeeping", "UPDATE")).isFalse();
  }

  @Test
  @DisplayName("keeps the uniqueness of a tenant's sequence on every partition it writes")
  void everyPartitionCarriesTheUniqueness() {
    // The cost of partitioning monthly, made visible: the parent cannot carry
    // `UNIQUE (tenant_id, seq)` because a unique index on a partitioned table
    // must contain the partition key. Every partition carries it instead, and a
    // partition created later must carry it too — which is why the function that
    // creates one also creates the index.
    List<String> without =
        jdbc.sql(
                """
                select c.relname
                from pg_inherits i
                join pg_class c on c.oid = i.inhrelid
                join pg_class p on p.oid = i.inhparent
                join pg_namespace n on n.oid = p.relnamespace
                where n.nspname = 'audit' and p.relname = 'audit_entry'
                  and not exists (
                      select 1 from pg_index x
                      join pg_class ic on ic.oid = x.indexrelid
                      where x.indrelid = c.oid and x.indisunique
                        and pg_get_indexdef(x.indexrelid) like '%(tenant_id, seq)'
                  )
                order by c.relname
                """)
            .query(String.class)
            .list();

    assertThat(without).as("every partition of audit.audit_entry is unique on (tenant_id, seq)")
        .isEmpty();
  }

  // -------------------------------------------------------------------------

  private static AuditLog.NewEntry anEntry(String action) {
    return byUser(UUID.randomUUID(), action);
  }

  private static AuditLog.NewEntry byUser(UUID actor, String action) {
    return new AuditLog.NewEntry(
        AuditLog.ActorKind.USER, actor, null, action, "item", UUID.randomUUID(),
        Map.of(), null, null, null);
  }

  private AuditLog.AuditView onlyEntry(UUID tenant) {
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
    assertThat(page.data()).hasSize(1);
    return page.data().getFirst();
  }

  /**
   * The raw chain of one tenant.
   *
   * <p>Inside that tenant's context and inside a transaction, because the rows are behind
   * row-level security: a read without a context sees nothing, which is the policy doing its job.
   *
   * @param tenant whose chain
   * @return the sequence numbers and both hashes, oldest first
   */
  private List<Map<String, Object>> chainOf(UUID tenant) {
    return TenantContext.callAs(
        tenant,
        () ->
            transactions.execute(
                status ->
                    jdbc.sql(
                            "select seq, prev_hash, entry_hash from audit.audit_entry"
                                + " where tenant_id = ? order by seq")
                        .param(tenant)
                        .query()
                        .listOfRows()));
  }

  private List<Long> seqsOf(UUID tenant) {
    return TenantContext.callAs(
        tenant,
        () ->
            transactions.execute(
                status ->
                    jdbc.sql("select seq from audit.audit_entry where tenant_id = ? order by seq")
                        .param(tenant)
                        .query(Long.class)
                        .list()));
  }

  private boolean may(String role, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("select has_table_privilege(?, 'audit.audit_entry', ?)")
            .param(role)
            .param(privilege)
            .query(Boolean.class)
            .single());
  }

  private static String hex(Object bytes) {
    return HexFormat.of().formatHex((byte[]) bytes);
  }

  private static byte[] sha256(String input) throws Exception {
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * A fresh tenant.
   *
   * @param name distinguishes this test's from the others'
   * @return its id
   */
  private UUID aTenant(String name) throws Exception {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "audit-" + name + "@example.org",
                    "Owner",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return provisioning.provision("Auditing " + name, userId);
  }
}
