/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportService;
import de.greluc.homeinv.portability.api.ImportService;
import de.greluc.homeinv.portability.application.ExportRunner;
import de.greluc.homeinv.portability.application.ImportRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The move REQ-PORT-004 asks for, end to end.
 *
 * <p>Not "the archive contains the type definitions" — that is a property of one file and it was
 * already true. This is the sentence the requirement actually makes: <b>a tenant can move
 * entirely</b>. One tenant exports, a second, freshly provisioned tenant imports, and what arrives
 * is the inventory rather than a set of rows pointing at things that are not there.
 *
 * <h2>Why the receiving tenant is a fresh one</h2>
 *
 * <p>Because that is the case a move is. It is also the hard case: provisioning has already given
 * the second tenant the same built-in types, categories and value lists under <b>different ids</b>,
 * so every imported item references a type version that does not exist there. The remapping in
 * {@code CatalogImport} is what makes this test pass, and without it the import fails on a foreign
 * key rather than quietly producing something wrong — which is the second thing this proves.
 */
@DisplayName("A move to another tenant")
class ArchiveMoveIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ExportService exports;
  @Autowired private ExportRunner exportRunner;
  @Autowired private ImportService imports;
  @Autowired private ImportRunner importRunner;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private de.greluc.homeinv.tagging.api.TagService tags;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("carries the inventory into a tenant that has never seen it")
  void everythingArrives() throws Exception {
    Tenant from = newTenant("move-from@example.org");
    UUID shed = aPlace(from, "A shed", null);
    UUID shelf = aPlace(from, "A shelf", shed);
    anItem(from, "A drill", shelf);
    anItem(from, "A saw", shed);
    inOwn(
        from,
        () ->
            tags.create(
                new de.greluc.homeinv.tagging.api.TagService.CreateTagCommand(
                    "valuable", null, null, null),
                from.userId()));

    // Out of one tenant...
    ExportService.ExportJobView export = asOwner(from, () -> exports.request(from.userId()));
    assertThat(exportRunner.runAsTenant(from.tenantId())).isEqualTo(1);

    // The things then LEAVE. A move is not a copy: a row keeps the id it was
    // printed on a label with (10 §10.2.1), and an id exists once in a database
    // -- so the old tenant letting go of them is part of what a move is, and
    // this test would otherwise be proving something an instance cannot do.
    emptied(from);

    // ...and into another, which has its own built-in catalogue under its own
    // ids. That is the whole difficulty: every item in the archive is written
    // against a type version this tenant has never heard of.
    Tenant to = newTenant("move-to@example.org");
    assertThat(itemCount(to)).isZero();

    ImportService.ImportJobView job =
        asOwner(
            to,
            () -> {
              try (InputStream archive = inOwn(from, () -> openQuietly(export.id()))) {
                return imports.accept(to.userId(), archive, false, null);
              } catch (java.io.IOException unreadable) {
                throw new IllegalStateException(unreadable);
              }
            });
    assertThat(job.state()).isEqualTo("QUEUED");
    assertThat(importRunner.runAsTenant(to.tenantId())).isEqualTo(1);

    ImportService.ImportJobView done = inOwn(to, () -> imports.job(job.id()));
    assertThat(done.state()).as("the import finished: %s", done.failure()).isEqualTo("DONE");
    assertThat(done.progress()).isEqualTo(100);

    // The things arrived.
    assertThat(itemCount(to)).isEqualTo(2);
    assertThat(names(to)).containsExactlyInAnyOrder("A drill", "A saw");

    // And they point at things that are there. An item whose type version is
    // missing is the failure REQ-PORT-004 exists to prevent, and a foreign key
    // is not enough to notice it: the row would simply not have been written.
    assertThat(danglingTypeVersions(to)).isZero();
    assertThat(placeCount(to)).as("the whole tree arrived: %s", done.report()).isEqualTo(2);

    // The report says what it did, and what it deliberately did not.
    JsonNode report = json.readTree(done.report());
    assertThat(report.get("blocks").get("inventory").get("inserted").asInt()).isEqualTo(2);
    assertThat(report.get("notImported").valueStream().map(JsonNode::asString).toList())
        .anySatisfy(sentence -> assertThat(sentence).contains("Accounts and memberships"));
  }

  @Test
  @DisplayName("writes nothing at all when it is a dry run")
  void aDryRunLeavesNoTrace() throws Exception {
    Tenant from = newTenant("dry-from@example.org");
    anItem(from, "A lamp", aPlace(from, "A room", null));
    ExportService.ExportJobView export = asOwner(from, () -> exports.request(from.userId()));
    exportRunner.runAsTenant(from.tenantId());

    emptied(from);
    Tenant to = newTenant("dry-to@example.org");
    ImportService.ImportJobView job =
        asOwner(
            to,
            () -> {
              try (InputStream archive = inOwn(from, () -> openQuietly(export.id()))) {
                return imports.accept(to.userId(), archive, true, null);
              } catch (java.io.IOException unreadable) {
                throw new IllegalStateException(unreadable);
              }
            });
    importRunner.runAsTenant(to.tenantId());

    ImportService.ImportJobView done = inOwn(to, () -> imports.job(job.id()));
    assertThat(done.state()).as("the dry run finished: %s", done.failure()).isEqualTo("DONE");

    // It reports what it would have done...
    JsonNode report = json.readTree(done.report());
    assertThat(report.get("dryRun").asBoolean()).isTrue();
    assertThat(report.get("blocks").get("inventory").get("inserted").asInt()).isEqualTo(1);

    // ...and left nothing behind, which is the half REQ-PORT-001 is about. The
    // job row survives because it is written in its own transaction; everything
    // the import did was rolled back with the one it ran in.
    assertThat(itemCount(to)).isZero();
    assertThat(placeCount(to)).isZero();
  }

  // -------------------------------------------------------------------------

  /**
   * The source tenant letting go of what it exported.
   *
   * <p>What makes this a move rather than a copy. Two tenants of one instance cannot hold the same
   * rows: an id is unique in the database and not merely within a tenant, on purpose — it is the id
   * printed on a label, and a label that resolved to two different things would be worse than one
   * that resolved to none.
   *
   * @param tenant whose things leave
   */
  private void emptied(Tenant tenant) {
    inOwn(
        tenant,
        () -> {
          jdbc.sql("delete from tagging.tag_assignment").update();
          jdbc.sql("delete from tagging.tag").update();
          jdbc.sql("delete from inventory.item").update();
          jdbc.sql("delete from locations.location").update();
          return null;
        });
  }

  private long itemCount(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select count(*) from inventory.item where deleted_at is null")
                .query(Long.class)
                .single());
  }

  private long placeCount(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select count(*) from locations.location where deleted_at is null")
                .query(Long.class)
                .single());
  }

  private List<String> names(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select name from inventory.item where deleted_at is null")
                .query(String.class)
                .list());
  }

  /**
   * How many items point at a type version this tenant does not have.
   *
   * <p>Always zero, and it has to be asked rather than assumed: the foreign key is composite and
   * tenant-qualified, so a row pointing at another tenant's version could not have been written at
   * all — which means a failure would show up as a missing row, not as a bad one.
   *
   * @param tenant whose inventory
   * @return the count
   */
  private long danglingTypeVersions(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql(
                    """
                    select count(*) from inventory.item i
                    where not exists (
                      select 1 from catalog.item_type_version v where v.id = i.item_type_version_id)
                    """)
                .query(Long.class)
                .single());
  }

  private InputStream openQuietly(UUID id) {
    try {
      return exports.open(id);
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  private UUID anItem(Tenant tenant, String name, UUID where) {
    return inOwn(
        tenant,
        () ->
            items
                .create(
                    new ItemService.CreateItemCommand(
                        null,
                        builtinType(tenant.tenantId()),
                        name,
                        null,
                        ItemKind.PHYSICAL,
                        where,
                        BigDecimal.ONE,
                        null,
                        "{}",
                        null,
                        null,
                        Valuation.NONE),
                    Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID aPlace(Tenant tenant, String name, UUID parent) {
    return inOwn(
        tenant,
        () ->
            locations
                .create(
                    new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                        null, anyCategory(tenant.tenantId()), parent,
                        name + " " + UUID.randomUUID(), null),
                    Optional.empty(),
                    tenant.userId())
                .id());
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private UUID builtinType(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private <T> T inOwn(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  /**
   * Runs a body as the tenant's owner, with a second factor proved just now.
   *
   * <p>Needed on both sides: the export opens what its requester may read (ADR-0068), so an archive
   * asked for without a caller would be missing every sensitive value and this test would prove
   * less than it looks like it does.
   *
   * @param tenant whose
   * @param body the work
   * @param <T> what it produces
   * @return what the body produced
   */
  private <T> T asOwner(Tenant tenant, Supplier<T> body) {
    java.util.concurrent.atomic.AtomicReference<T> produced =
        new java.util.concurrent.atomic.AtomicReference<>();
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(
                    tenant.userId(), tenant.tenantId(), "OWNER", null, null, Instant.now()),
                () -> produced.set(body.get())));
    return produced.get();
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Mover", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
