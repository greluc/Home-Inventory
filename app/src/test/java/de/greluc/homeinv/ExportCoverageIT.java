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
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportService;
import de.greluc.homeinv.portability.application.ExportRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * An export loses no column (REQ-PORT-003).
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Each {@code ExportSource} names its columns one by one rather than writing {@code select *},
 * so that a column joins the archive by decision. The cost of that is the opposite mistake: a
 * column added later is silently <b>left out</b>, and an export that quietly drops a field is the
 * one failure REQ-PORT-003 cannot survive — a tenant moves instance and finds the data gone, with
 * nothing having failed anywhere.
 *
 * <p>It happened twice while the block was being written: {@code locations.location.updated_by} was
 * simply forgotten, and {@code category_id} was named although V14 dropped it fifty migrations ago.
 * This compares the archive against {@code information_schema} and fails with the column's name.
 *
 * <h2>Every omission is declared</h2>
 *
 * <p>{@link #NOT_EXPORTED} is the list, and each entry has a reason. That is the point: the test
 * does not ask "did you export everything", it asks "is every column either exported or
 * deliberately not". A new column is neither until somebody decides, which is what turns a silent
 * loss into a failing build.
 */
@DisplayName("An export")
class ExportCoverageIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** Which archive file holds which table. */
  private static final Map<String, String> DATASETS =
      Map.ofEntries(
          Map.entry("data/locations/locations.jsonl", "locations.location"),
          Map.entry("data/inventory/items.jsonl", "inventory.item"),
          Map.entry("data/catalog/item-types.jsonl", "catalog.item_type"),
          Map.entry("data/catalog/item-type-versions.jsonl", "catalog.item_type_version"),
          Map.entry("data/catalog/field-definitions.jsonl", "catalog.field_definition"),
          Map.entry("data/catalog/location-categories.jsonl", "catalog.location_category"),
          Map.entry(
              "data/catalog/location-category-versions.jsonl", "catalog.location_category_version"),
          Map.entry("data/catalog/value-lists.jsonl", "catalog.value_list"),
          Map.entry("data/catalog/value-list-entries.jsonl", "catalog.value_list_entry"),
          Map.entry("data/tagging/tags.jsonl", "tagging.tag"),
          Map.entry("data/media/media-objects.jsonl", "media.media_object"),
          Map.entry("data/media/attachments.jsonl", "media.attachment"),
          Map.entry("data/media/variants.jsonl", "media.media_variant"));

  /**
   * Columns deliberately left out, and why.
   *
   * <p>{@code tenant_id} is on every one of them: an archive <b>is</b> one tenant's, the manifest
   * says whose, and repeating it on every row would be a field an importer has to decide whether to
   * trust. The rest are derived — recomputed on the other side from what the archive does carry,
   * and a copy that disagreed with its source would be the one somebody trusted.
   */
  private static final Map<String, Set<String>> NOT_EXPORTED =
      Map.of(
          "inventory.item",
          // Generated columns: the stored form of what `name`, `description` and
          // `notes` already say, through a text analyser the receiving instance
          // chooses for itself.
          Set.of("tenant_id", "search_vector_de", "search_vector_en"));

  @Autowired private ExportService exports;
  @Autowired private ExportRunner runner;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.tagging.api.TagService tags;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("carries every column of every table it claims to hold, or declares the omission")
  void noColumnIsSilentlyLost() throws Exception {
    Tenant tenant = newTenant("export-coverage@example.org");
    // A row in each of the tables under test. The catalogue ones are already
    // populated: provisioning gives a tenant the built-in types, categories and
    // value lists, which is what makes this test cheap enough to be worth having.
    UUID place = aPlace(tenant, "A shed");
    UUID item = anItem(tenant, "A drill", place);
    inOwn(
        tenant,
        () ->
            tags.create(
                new de.greluc.homeinv.tagging.api.TagService.CreateTagCommand(
                    "valuable", null, null, null),
                tenant.userId()));
    // Written directly: reaching a media object through the API means uploading
    // through a scanner, and this test is about columns rather than about
    // uploads.
    aMediaObject(tenant, item);

    ExportService.ExportJobView queued = inOwn(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, String> archive = unzip(inOwn(tenant, () -> openQuietly(queued.id())));

    Map<String, Set<String>> missing = new LinkedHashMap<>();
    for (Map.Entry<String, String> dataset : DATASETS.entrySet()) {
      String file = archive.get(dataset.getKey());
      assertThat(file).as("the archive holds %s", dataset.getKey()).isNotNull();
      if (file.isBlank()) {
        // Nothing in that table for this tenant, so there is no row to read the
        // keys from. Not a pass and not a failure: the dataset is present and
        // this test simply cannot speak about its columns.
        continue;
      }

      JsonNode first = json.readTree(file.lines().findFirst().orElseThrow());
      Set<String> exported = new TreeSet<>();
      first.propertyNames().forEach(exported::add);

      Set<String> absent = new TreeSet<>(columnsOf(dataset.getValue()));
      absent.removeAll(exported);
      absent.removeAll(NOT_EXPORTED.getOrDefault(dataset.getValue(), Set.of()));
      absent.remove("tenant_id");
      if (!absent.isEmpty()) {
        missing.put(dataset.getValue(), absent);
      }
    }

    assertThat(missing)
        .as(
            "every column is exported or declared in NOT_EXPORTED with a reason. A column added "
                + "later is neither until somebody decides, and this is where that decision is "
                + "made rather than discovered by a tenant whose data went missing (REQ-PORT-003)")
        .isEmpty();
  }

  // -------------------------------------------------------------------------

  /**
   * A clean media object, an attachment hanging off an item, and a derivative.
   *
   * <p>Written with SQL rather than through {@code MediaService}, because reaching a media object
   * through the API means uploading bytes past a virus scanner, and this test is about columns
   * rather than about uploads. The three rows exist so the three media datasets are non-empty —
   * the check skips a dataset with no row, which would make the columns unexamined.
   *
   * @param tenant whose media it is
   * @param item what the attachment hangs on
   */
  private void aMediaObject(Tenant tenant, UUID item) {
    inOwn(
        tenant,
        () -> {
          UUID object =
              jdbc
                  .sql(
                      """
                      insert into media.media_object
                          (tenant_id, sha256, media_type, byte_size, width_px, height_px,
                           scan_state, ref_count, created_by, updated_by)
                      values (?, ?, 'image/png', 7, 4, 4, 'CLEAN', 1, ?, ?)
                      returning id
                      """)
                  .param(tenant.tenantId())
                  .param("f".repeat(64))
                  .param(tenant.userId())
                  .param(tenant.userId())
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into media.media_variant
                      (tenant_id, media_object_id, kind, sha256, media_type, byte_size,
                       width_px, height_px, created_by, updated_by)
                  values (?, ?, 'thumb', ?, 'image/webp', 3, 2, 2, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(object)
              .param("e".repeat(64))
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into media.attachment
                      (tenant_id, media_object_id, target_kind, target_id, primary_image,
                       created_by, updated_by)
                  values (?, ?, 'ITEM', ?, true, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(object)
              .param(item)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          return object;
        });
  }

  private Set<String> columnsOf(String qualified) {
    String[] parts = qualified.split("\\.");
    return new TreeSet<>(
        jdbc
            .sql(
                """
                select column_name from information_schema.columns
                where table_schema = ? and table_name = ?
                """)
            .param(parts[0])
            .param(parts[1])
            .query(String.class)
            .list());
  }

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
