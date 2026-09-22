/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportService;
import de.greluc.homeinv.portability.api.MappingProfile;
import de.greluc.homeinv.portability.application.ImportRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
 * Coming from somewhere else (REQ-PORT-001, REQ-PORT-002, REQ-PORT-008).
 *
 * <p>The file here is shaped like a real Homebox export, header for header: {@code HB.name}, {@code
 * HB.location} with its {@code /} path, {@code HB.tags}, {@code HB.import_ref}. Those names are not
 * invented — they are the {@code csv} tags on Homebox's own {@code io_row.go}, which is what makes
 * the shipped profile worth shipping.
 */
@DisplayName("A CSV from another system")
class CsvImportIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** Three rows, a nested place, two tags, a price and a purchase date. */
  private static final String HOMEBOX_EXPORT =
      """
      HB.import_ref,HB.name,HB.description,HB.location,HB.tags,HB.quantity,HB.purchase_price,\
      HB.purchase_date,HB.purchase_from,HB.serial_number
      ref-1,A cordless drill,Makes holes,Garage / Shelf,tools;valuable,1,149.99,2024-03-01,\
      The hardware shop,SN-12345
      ref-2,A box of screws,Assorted,Garage / Shelf,tools,3,4.50,2024-03-02,The hardware shop,
      ref-3,A bicycle,,Garage,valuable,1,,,,
      """;

  @Autowired private ImportService imports;
  @Autowired private ImportRunner runner;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("arrives with its places, its tags and where each row came from")
  void aHomeboxExportBecomesAnInventory() throws Exception {
    Tenant tenant = newTenant("csv-homebox@example.org");

    ImportService.ImportJobView job = upload(tenant, HOMEBOX_EXPORT, "homebox", false);
    assertThat(runner.runAsTenant(tenant.tenantId())).isEqualTo(1);

    ImportService.ImportJobView done = inOwn(tenant, () -> imports.job(job.id()));
    assertThat(done.state()).as("the import finished: %s", done.failure()).isEqualTo("DONE");

    assertThat(names(tenant))
        .containsExactlyInAnyOrder("A cordless drill", "A box of screws", "A bicycle");

    // The place path became a tree rather than three places called "Garage /
    // Shelf": a path is nested, which is what `HB.location` means over there.
    assertThat(placeNames(tenant)).containsExactlyInAnyOrder("Garage", "Shelf");
    assertThat(childCount(tenant, "Garage")).isEqualTo(1);

    // Tags that did not exist were made, and the ones shared by two rows are one
    // tag rather than two.
    assertThat(tagNames(tenant)).containsExactlyInAnyOrder("tools", "valuable");
    assertThat(assignmentCount(tenant)).isEqualTo(4);

    // REQ-PORT-008: each item knows where it came from and what it was called
    // there, which is what makes a second import an update.
    assertThat(provenance(tenant, "A cordless drill")).isEqualTo("ref-1");

    JsonNode report = json.readTree(done.report());
    assertThat(report.get("created").asInt()).isEqualTo(3);
    assertThat(report.get("updated").asInt()).isZero();
    // A Homebox file carries a serial number; whether an item here has a field
    // for one is the tenant's decision, so the column is reported rather than
    // refused (ADR-0020).
    assertThat(report.get("fieldsTheTypeDoesNotDeclare").valueStream().map(JsonNode::asString))
        .contains("serialNumber");
    assertThat(report.get("preview")).isNotEmpty();
  }

  @Test
  @DisplayName("updates what it imported before rather than importing it twice")
  void theSameFileTwiceIsNotTwoInventories() throws Exception {
    Tenant tenant = newTenant("csv-twice@example.org");

    upload(tenant, HOMEBOX_EXPORT, "homebox", false);
    runner.runAsTenant(tenant.tenantId());
    assertThat(names(tenant)).hasSize(3);

    String renamed = HOMEBOX_EXPORT.replace("A cordless drill", "A rather good drill");
    ImportService.ImportJobView second = upload(tenant, renamed, "homebox", false);
    runner.runAsTenant(tenant.tenantId());

    ImportService.ImportJobView done = inOwn(tenant, () -> imports.job(second.id()));
    assertThat(done.state()).as("the second import finished: %s", done.failure()).isEqualTo("DONE");

    // Three items, not six: `HB.import_ref` is the key the other system used and
    // the provenance row is what remembers it (REQ-PORT-008).
    assertThat(names(tenant)).hasSize(3).contains("A rather good drill");
    JsonNode report = json.readTree(done.report());
    assertThat(report.get("updated").asInt()).isEqualTo(3);
    assertThat(report.get("created").asInt()).isZero();
  }

  @Test
  @DisplayName("shows what it would do and writes none of it, when it is a dry run")
  void aDryRunIsAPreview() throws Exception {
    Tenant tenant = newTenant("csv-dry@example.org");

    ImportService.ImportJobView job = upload(tenant, HOMEBOX_EXPORT, "homebox", true);
    runner.runAsTenant(tenant.tenantId());

    ImportService.ImportJobView done = inOwn(tenant, () -> imports.job(job.id()));
    assertThat(done.state()).as("the dry run finished: %s", done.failure()).isEqualTo("DONE");

    JsonNode report = json.readTree(done.report());
    assertThat(report.get("dryRun").asBoolean()).isTrue();
    assertThat(report.get("created").asInt()).isEqualTo(3);
    // The preview is what REQ-PORT-001 asks for beside the dry run: the mapped
    // rows, so somebody can see that `HB.location` became a place before they
    // commit to five hundred of them.
    assertThat(report.get("preview").valueStream().map(row -> row.get("name").asString()).toList())
        .contains("A cordless drill");

    assertThat(names(tenant)).isEmpty();
    assertThat(placeNames(tenant)).isEmpty();
    assertThat(tagNames(tenant)).isEmpty();
  }

  @Test
  @DisplayName("writes nothing at all when one row cannot be read, and says which")
  void oneBadRowStopsAllOfIt() throws Exception {
    Tenant tenant = newTenant("csv-bad@example.org");
    String broken = HOMEBOX_EXPORT.replace("2024-03-02", "the second of March");

    ImportService.ImportJobView job = upload(tenant, broken, "homebox", false);
    runner.runAsTenant(tenant.tenantId());

    ImportService.ImportJobView done = inOwn(tenant, () -> imports.job(job.id()));
    assertThat(done.state()).isEqualTo("FAILED");
    assertThat(done.failure())
        .contains("line 3")
        .contains("purchasedOn")
        .contains("nothing was written");

    // REQ-PORT-007, and the reason the whole import is one transaction: the two
    // rows that were fine did not arrive either.
    assertThat(names(tenant)).isEmpty();
  }

  @Test
  @DisplayName("offers the two profiles that ship, by the names the other systems use")
  void bothProfilesShip() {
    Tenant tenant = newTenant("csv-profiles@example.org");

    List<MappingProfile> profiles = inOwn(tenant, () -> imports.profiles());

    assertThat(profiles).extracting(MappingProfile::key).contains("homebox", "inventree");
    MappingProfile homebox =
        profiles.stream().filter(p -> p.key().equals("homebox")).findFirst().orElseThrow();
    // Verbatim from Homebox's own `io_row.go`, which is the point of a shipped
    // profile: a header that is nearly right maps nothing at all.
    assertThat(homebox.columns())
        .containsEntry("HB.name", "name")
        .containsEntry("HB.location", "location")
        .containsEntry("HB.import_ref", "sourceKey");
  }

  // -------------------------------------------------------------------------

  private ImportService.ImportJobView upload(
      Tenant tenant, String csv, String profile, boolean dryRun) {
    return inOwn(
        tenant,
        () -> {
          try (var bytes = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))) {
            return imports.accept(tenant.userId(), bytes, dryRun, profile);
          } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
          }
        });
  }

  private List<String> names(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select name from inventory.item where deleted_at is null")
                .query(String.class)
                .list());
  }

  private List<String> placeNames(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select name from locations.location where deleted_at is null")
                .query(String.class)
                .list());
  }

  private long childCount(Tenant tenant, String parentName) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql(
                    """
                    select count(*) from locations.location child
                    join locations.location parent on parent.id = child.parent_id
                    where parent.name = ?
                    """)
                .param(parentName)
                .query(Long.class)
                .single());
  }

  private List<String> tagNames(Tenant tenant) {
    return inOwn(tenant, () -> jdbc.sql("select name from tagging.tag").query(String.class).list());
  }

  private long assignmentCount(Tenant tenant) {
    return inOwn(
        tenant,
        () -> jdbc.sql("select count(*) from tagging.tag_assignment").query(Long.class).single());
  }

  private String provenance(Tenant tenant, String itemName) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql(
                    """
                    select p.source_key from portability.import_provenance p
                    join inventory.item i on i.id = p.item_id
                    where i.name = ?
                    """)
                .param(itemName)
                .query(String.class)
                .single());
  }

  private <T> T inOwn(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Importer",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
