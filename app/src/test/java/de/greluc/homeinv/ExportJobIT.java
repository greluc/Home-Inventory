/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportNotReadyException;
import de.greluc.homeinv.portability.api.ExportService;
import de.greluc.homeinv.portability.application.ExportRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Taking everything with you (REQ-PORT-005, and the beginning of REQ-PORT-003).
 *
 * <p>An export is a <b>job</b>: asked for, built in the worker, downloaded afterwards. The tests
 * here are about that contract — the archive is not there until it is, and the thing that arrives
 * is a ZIP whose manifest says what is in it.
 *
 * <p>They are deliberately <b>not</b> a claim that REQ-PORT-003 is met. Two blocks write themselves
 * so far; media and the configuration blocks follow, and until they do an archive is not something
 * a tenant could move on.
 */
@DisplayName("An export job")
class ExportJobIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ExportService exports;
  @Autowired private ExportRunner runner;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("is queued at once and says so, rather than making the caller wait")
  void itIsQueuedImmediately() {
    Tenant tenant = newTenant("export-queued@example.org");

    ExportService.ExportJobView job = inOwn(tenant, () -> exports.request(tenant.userId()));

    // REQ-PORT-005: the answer is a job, not an archive. A tenant with ten
    // thousand items is minutes of work, and this is what stops that being a
    // request that times out.
    assertThat(job.state()).isEqualTo("QUEUED");
    assertThat(job.progress()).isZero();
    assertThat(job.byteSize()).isNull();
    assertThat(job.isReady()).isFalse();
  }

  @Test
  @DisplayName("refuses to hand over an archive that does not exist yet")
  void theArchiveIsNotThereUntilItIs() {
    Tenant tenant = newTenant("export-early@example.org");
    ExportService.ExportJobView job = inOwn(tenant, () -> exports.request(tenant.userId()));

    // Not a 404: the job is there and the caller is looking at the right thing.
    // Sending them to a "no such job" would send them looking for something in
    // their own list.
    assertThatThrownBy(() -> inOwn(tenant, () -> openQuietly(job.id())))
        .isInstanceOf(ExportNotReadyException.class)
        .hasMessageContaining("queued");
  }

  @Test
  @DisplayName("builds a ZIP whose manifest counts what it holds")
  void theArchiveIsAZipWithAManifest() throws Exception {
    Tenant tenant = newTenant("export-built@example.org");
    UUID place = aPlace(tenant, "A shed");
    anItem(tenant, "A drill", place);
    anItem(tenant, "A saw", place);

    ExportService.ExportJobView queued = inOwn(tenant, () -> exports.request(tenant.userId()));
    assertThat(runner.runAsTenant(tenant.tenantId())).isEqualTo(1);

    ExportService.ExportJobView done = inOwn(tenant, () -> exports.job(queued.id()));
    assertThat(done.state()).isEqualTo("READY");
    assertThat(done.progress()).isEqualTo(100);
    assertThat(done.byteSize()).isPositive();
    assertThat(done.finishedAt()).isNotNull();

    Map<String, String> entries = unzip(inOwn(tenant, () -> openQuietly(queued.id())));

    // The manifest says what the archive is and what is in it, so a reader knows
    // what they have without unpacking all of it.
    assertThat(entries).containsKey("manifest.json");
    JsonNode manifest = json.readTree(entries.get("manifest.json"));
    assertThat(manifest.get("format").asInt()).isEqualTo(1);
    assertThat(manifest.get("tenantId").asString()).isEqualTo(tenant.tenantId().toString());
    assertThat(manifest.get("producedBy").get("commit")).isNotNull();

    // Two items and one place, as JSON Lines -- one object per line, so an
    // import can report "row 4,812" instead of "the file is wrong".
    assertThat(entries).containsKeys("data/inventory/items.jsonl", "data/locations/locations.jsonl");
    assertThat(entries.get("data/inventory/items.jsonl").strip().lines()).hasSize(2);
    assertThat(entries.get("data/inventory/items.jsonl")).contains("A drill").contains("A saw");

    // And the counts in the manifest agree with the files, which is the property
    // that makes the manifest worth reading at all.
    long itemRows =
        manifest.get("datasets").valueStream()
            .filter(dataset -> "items".equals(dataset.get("dataset").asString()))
            .map(dataset -> dataset.get("rows").asLong())
            .findFirst()
            .orElseThrow();
    assertThat(itemRows).isEqualTo(2);
  }

  @Test
  @DisplayName("never carries the derived columns, because they are one fact twice")
  void derivedColumnsStayOut() throws Exception {
    Tenant tenant = newTenant("export-derived@example.org");
    anItem(tenant, "A lamp", aPlace(tenant, "A room"));

    ExportService.ExportJobView queued = inOwn(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, String> entries = unzip(inOwn(tenant, () -> openQuietly(queued.id())));

    String items = entries.get("data/inventory/items.jsonl");
    // `search_vector_*` are generated and `item_attr_index` is derived from the
    // JSONB that IS carried (ADR-0004). An archive holding both would hold one
    // fact twice, and the copy that was wrong would be the one somebody trusted.
    assertThat(items).doesNotContain("search_vector_de").doesNotContain("search_vector_en");
    assertThat(entries).doesNotContainKey("data/inventory/item-attr-index.jsonl");
    assertThat(items).contains("attributes");
  }

  @Test
  @DisplayName("never contains another tenant's rows")
  void oneTenantsArchiveHoldsOnlyItsOwn() throws Exception {
    Tenant mine = newTenant("export-mine@example.org");
    Tenant theirs = newTenant("export-theirs@example.org");
    anItem(mine, "Zzz mine", aPlace(mine, "My shed"));
    anItem(theirs, "Zzz theirs", aPlace(theirs, "Their shed"));

    ExportService.ExportJobView queued = inOwn(mine, () -> exports.request(mine.userId()));
    runner.runAsTenant(mine.tenantId());
    Map<String, String> entries = unzip(inOwn(mine, () -> openQuietly(queued.id())));

    assertThat(entries.get("data/inventory/items.jsonl"))
        .contains("Zzz mine")
        .doesNotContain("Zzz theirs");
  }

  // -------------------------------------------------------------------------

  private InputStream openQuietly(UUID id) {
    try {
      return exports.open(id);
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  private static Map<String, String> unzip(InputStream archive) throws Exception {
    Map<String, String> entries = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(archive)) {
      for (java.util.zip.ZipEntry entry = zip.getNextEntry();
          entry != null;
          entry = zip.getNextEntry()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        zip.transferTo(out);
        entries.put(entry.getName(), out.toString(StandardCharsets.UTF_8));
      }
    }
    return entries;
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
                    java.util.Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID aPlace(Tenant tenant, String name) {
    return inOwn(
        tenant,
        () ->
            locations
                .create(
                    new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                        null, anyCategory(tenant.tenantId()), null, name + " " + UUID.randomUUID(), null),
                    java.util.Optional.empty(),
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

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
