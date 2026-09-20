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
import java.util.List;
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
          Map.entry("data/catalog/location-category-children.jsonl",
              "catalog.location_category_child"),
          Map.entry("data/inventory/maintenance-entries.jsonl", "inventory.maintenance_entry"),
          Map.entry("data/inventory/loans.jsonl", "inventory.loan"),
          Map.entry("data/inventory/relations.jsonl", "inventory.item_relation"),
          Map.entry("data/inventory/bundles.jsonl", "inventory.item_bundle"),
          Map.entry("data/tagging/tags.jsonl", "tagging.tag"),
          Map.entry("data/tagging/tag-groups.jsonl", "tagging.tag_group"),
          Map.entry("data/tagging/tag-assignments.jsonl", "tagging.tag_assignment"),
          Map.entry("data/media/media-objects.jsonl", "media.media_object"),
          Map.entry("data/media/attachments.jsonl", "media.attachment"),
          Map.entry("data/media/variants.jsonl", "media.media_variant"),
          Map.entry("data/identity/users.jsonl", "identity.app_user"),
          Map.entry("data/tenancy/tenant.jsonl", "tenancy.tenant"),
          Map.entry("data/tenancy/memberships.jsonl", "tenancy.membership"),
          Map.entry("data/authorization/role-definitions.jsonl", "authz.role_definition"),
          Map.entry("data/authorization/role-permissions.jsonl", "authz.role_permission"),
          Map.entry("data/authorization/field-visibility.jsonl", "authz.field_visibility"),
          Map.entry("data/notification/reminder-rules.jsonl", "notification.notification_rule"),
          Map.entry("data/notification/subscriptions.jsonl", "notification.subscription"),
          Map.entry("data/search/saved-searches.jsonl", "search.saved_search"),
          Map.entry("data/portability/mapping-profiles.jsonl",
              "portability.mapping_profile"));

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
          Set.of("tenant_id", "search_vector_de", "search_vector_en"),
          "identity.app_user",
          // Two different reasons in one row.
          //
          // The credential path first: an archive is handed to a person and
          // then copied -- onto a laptop, into a cloud drive, through a support
          // ticket -- and a password hash in it is one somebody can grind
          // offline at their leisure. The receiving instance issues its own.
          //
          // Then the instance's own administration. `instance_operator`,
          // `may_create_tenants` and `tenant_limit` are what THIS deployment's
          // operator decided about an account, not anything the account owns.
          // An import that carried them would let an archive hand its bearer
          // operator rights on the instance receiving it, which is a
          // privilege escalation with a friendly name on it.
          Set.of(
              "password_hash",
              "password_changed_at",
              "instance_operator",
              "may_create_tenants",
              "tenant_limit"),
          "tenancy.tenant",
          // A pending deletion belongs to the instance being left: importing
          // "deletion requested" would schedule the copy somebody just made for
          // removal. And `revocation_token_hash` is key material -- the thing
          // that calls a deletion off -- which no archive carries.
          Set.of("deletion_requested_at", "deletion_requested_by", "revocation_token_hash"));

  /**
   * Tenant-scoped tables that are deliberately <b>not</b> in the archive, and why.
   *
   * <p>Filled in by reading the failure of {@link #everyTenantTableIsExportedOrDeclared()}, which
   * is the point: a table holding a tenant's rows joins this list by decision or it joins the
   * archive, and a new one does neither until somebody says which.
   */
  private static final Map<String, String> NOT_IN_THE_ARCHIVE =
      Map.ofEntries(
          Map.entry(
              "audit.audit_entry",
              "A tamper-evident security record with a hash chain. Its worth is that it sits "
                  + "beside the instance that wrote it; a copy away from there proves nothing "
                  + "while still naming who did what when"),
          Map.entry("audit.chain_truncation", "Part of the audit chain, and it goes with it"),
          Map.entry("audit.revision_record", "The version history, which is audit's other half"),
          Map.entry(
              "crypto.tenant_data_key",
              "Key material. A wrapped DEK is useless without the KEK, which never leaves the "
                  + "deployment, and an archive is the last place to put either"),
          Map.entry(
              "idempotency.processed_request",
              "Which request ids this deployment has already answered. Operational, and it means "
                  + "nothing on an instance that never received them"),
          Map.entry(
              "identity.service_account",
              "A credential. The token hash belongs to this deployment; the receiving one issues "
                  + "its own"),
          Map.entry(
              "inventory.item_attr_index",
              "Derived from the JSONB that IS in the archive (ADR-0004), and rebuilt on import. "
                  + "Carrying it would hold one fact twice, and the copy that was wrong would be "
                  + "the one somebody trusted"),
          Map.entry(
              "notification.notification",
              "The log of messages this deployment sent. Re-importing it would either duplicate "
                  + "what was already delivered or record deliveries that never happened"),
          Map.entry("notification.delivery_attempt", "The delivery log of the above"),
          Map.entry(
              "notification.reminder",
              "What the rules produced here. The rules travel; what they fired is this "
                  + "deployment's history"),
          Map.entry(
              "outbox.event_publication",
              "The transactional outbox. It is machinery, not data, and it is empty by the time "
                  + "anything is consistent enough to export"),
          Map.entry(
              "plugins.capability_grant",
              "A grant to a plugin installed here, by id and version. The receiving instance has "
                  + "its own plugins, and a grant to one it does not have is a permission nobody "
                  + "can read"),
          Map.entry(
              "portability.export_job",
              "The archive would contain the record of its own making, and of every earlier one"),
          Map.entry(
              "portability.import_provenance",
              "Every row of it points at an import job, and on the receiving instance there is no "
                  + "such job. Where an item came from is a fact about the instance it was "
                  + "imported into, made afresh there by the import that brings it"),
          Map.entry(
              "portability.import_job",
              "The other direction of the same thing: an archive that carried the record of being "
                  + "imported would, on the next export, carry the record of having carried it"),
          Map.entry(
              "tenancy.invitation",
              "An open invitation holds a token and the address of somebody who is not a member. "
                  + "It would put a third party's address in a file handed to somebody else, and "
                  + "the token would still open a door on the instance being left"),
          Map.entry(
              "tenancy.quota_usage", "A counter derived from the rows the archive already carries"),
          Map.entry(
              "tenancy.tenant_quota",
              "Limits the operator set. The receiving instance has its own, and importing these "
                  + "would import a policy rather than data"));

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
    UUID second = anItem(tenant, "A drill bit", place);
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
    theConfigurationATenantAccumulates(tenant);
    theRecordsAnItemAccumulates(tenant, item, second);

    ExportService.ExportJobView queued = inOwn(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, String> archive = unzip(inOwn(tenant, () -> openQuietly(queued.id())));

    Map<String, Set<String>> missing = new LinkedHashMap<>();
    Set<String> unpopulated = new TreeSet<>();
    for (Map.Entry<String, String> dataset : DATASETS.entrySet()) {
      String file = archive.get(dataset.getKey());
      assertThat(file).as("the archive holds %s", dataset.getKey()).isNotNull();
      if (file.isBlank()) {
        // No row means no keys to read, so the columns of that table would go
        // unexamined -- silently, which is the failure this test exists to
        // prevent one level up. The fixture above puts a row in every table
        // named in DATASETS, so a blank file is a fixture that stopped working
        // and not a tenant that happens to own nothing.
        unpopulated.add(dataset.getKey());
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

    assertThat(unpopulated)
        .as(
            "every dataset under test has at least one row, or its columns are never looked at "
                + "and this test passes by examining nothing")
        .isEmpty();
    assertThat(missing)
        .as(
            "every column is exported or declared in NOT_EXPORTED with a reason. A column added "
                + "later is neither until somebody decides, and this is where that decision is "
                + "made rather than discovered by a tenant whose data went missing (REQ-PORT-003)")
        .isEmpty();
  }

  @Test
  @DisplayName("leaves out no tenant-scoped table without saying so")
  void everyTenantTableIsExportedOrDeclared() {
    // Partitions are excluded: `audit.audit_entry` is partitioned by month, and
    // counting its partitions would turn one decision into one row per month
    // that nobody makes.
    Set<String> tenantScoped =
        new TreeSet<>(
            jdbc
                .sql(
                    """
                    select n.nspname || '.' || c.relname
                    from pg_class c
                    join pg_namespace n on n.oid = c.relnamespace
                    join pg_attribute a on a.attrelid = c.oid
                         and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped
                    where c.relkind in ('r', 'p')
                      and not c.relispartition
                      and n.nspname not in ('pg_catalog', 'information_schema')
                    order by 1
                    """)
                .query(String.class)
                .list());

    Set<String> undeclared = new TreeSet<>(tenantScoped);
    undeclared.removeAll(Set.copyOf(DATASETS.values()));
    undeclared.removeAll(NOT_IN_THE_ARCHIVE.keySet());

    assertThat(undeclared)
        .as(
            "a table holding tenant rows is either in the archive or in NOT_IN_THE_ARCHIVE with a "
                + "reason. A block added later owes an `ExportSource` or an entry, and this is "
                + "where that is noticed rather than by somebody whose data did not travel "
                + "(REQ-PORT-006)")
        .isEmpty();

    // And the other direction, so the list cannot outlive the tables it
    // describes: a reason for a table nobody has any more is a reason nobody
    // will read, and it hides the next one.
    Set<String> stale = new TreeSet<>(NOT_IN_THE_ARCHIVE.keySet());
    stale.removeAll(tenantScoped);
    assertThat(stale)
        .as("every declared omission names a table that exists")
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

  /**
   * One row in each of the configuration tables a tenant fills as it is used.
   *
   * <p>A fresh tenant has none of these: a custom role, a field visibility rule, a reminder rule, a
   * notification channel and a saved search are all things somebody makes. The column check skips a
   * dataset with no row, so without them these tables would be listed and never examined — which is
   * the failure mode this test exists to prevent, one level up.
   *
   * @param tenant whose configuration it is
   */
  private void theConfigurationATenantAccumulates(Tenant tenant) {
    inOwn(
        tenant,
        () -> {
          UUID role =
              jdbc
                  .sql(
                      """
                      insert into authz.role_definition
                          (tenant_id, name, description, base_role, created_by, updated_by)
                      values (?, 'Lodger', 'May look, may not move anything', 'VIEWER', ?, ?)
                      returning id
                      """)
                  .param(tenant.tenantId())
                  .param(tenant.userId())
                  .param(tenant.userId())
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into authz.role_permission
                      (tenant_id, role_definition_id, permission, created_by, updated_by)
                  values (?, ?, 'item.read', ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(role)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into authz.field_visibility
                      (tenant_id, field_key, role_definition_id, created_by, updated_by)
                  values (?, 'purchasePrice', ?, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(role)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          UUID search =
              jdbc
                  .sql(
                      """
                      insert into search.saved_search
                          (tenant_id, name, query_text, filters, sort, created_by, updated_by)
                      values (?, 'Everything valuable', 'drill', '{"tag:valuable"}', 'name', ?, ?)
                      returning id
                      """)
                  .param(tenant.tenantId())
                  .param(tenant.userId())
                  .param(tenant.userId())
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into notification.notification_rule
                      (tenant_id, name, trigger_kind, saved_search_id, offset_days, channel_key,
                       created_by, updated_by)
                  values (?, 'Warranties running out', 'WARRANTY_EXPIRY', ?, 30, 'email', ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(search)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into portability.mapping_profile
                      (tenant_id, key, name, source, column_map, default_currency, created_by,
                       updated_by)
                  values (?, 'the-old-spreadsheet', 'The old spreadsheet', 'spreadsheet',
                          '{"Thing": "name"}'::jsonb, 'EUR', ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into notification.subscription
                      (tenant_id, user_id, kind, channel_key, address, created_by, updated_by)
                  values (?, ?, 'REMINDER', 'email', 'export-coverage@example.org', ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(tenant.userId())
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          UUID list =
              jdbc
                  .sql(
                      """
                      insert into catalog.value_list (tenant_id, key, labels, created_by, updated_by)
                      values (?, 'condition', '{"en": "Condition"}'::jsonb, ?, ?)
                      returning id
                      """)
                  .param(tenant.tenantId())
                  .param(tenant.userId())
                  .param(tenant.userId())
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into catalog.value_list_entry
                      (tenant_id, value_list_id, value, labels, display_order, created_by, updated_by)
                  values (?, ?, 'used', '{"en": "Used"}'::jsonb, 1, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(list)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          UUID typeVersion =
              jdbc
                  .sql(
                      """
                      select v.id from catalog.item_type_version v
                      join catalog.item_type t on t.id = v.item_type_id
                      where t.key = 'general'
                      order by v.version_number desc
                      limit 1
                      """)
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into catalog.field_definition
                      (tenant_id, item_type_version_id, key, data_type, labels, value_list_id,
                       display_order, searchable, created_by, updated_by)
                  values (?, ?, 'conditionOfIt', 'enum', '{"en": "Condition"}'::jsonb, ?, 1, true,
                          ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(typeVersion)
              .param(list)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          return role;
        });
  }

  /**
   * One row in each of the tables that hang off an item or a place.
   *
   * <p>A loan, a maintenance entry, a relation, a bundle, a tag on something and a rule about which
   * category may sit inside which. None of them exists in a tenant that has only been provisioned,
   * and each is a table whose columns would otherwise never be compared against the archive.
   *
   * @param tenant whose records they are
   * @param item the item they hang off
   * @param second a second item, because a relation and a bundle need two
   */
  private void theRecordsAnItemAccumulates(Tenant tenant, UUID item, UUID second) {
    inOwn(
        tenant,
        () -> {
          jdbc.sql(
                  """
                  insert into inventory.loan
                      (tenant_id, item_id, borrower_name, handed_out_on, due_on, note,
                       created_by, updated_by)
                  values (?, ?, 'The neighbour', current_date - 7, current_date + 7,
                          'Said they would bring it back', ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(item)
              .param(tenant.userId())
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into inventory.maintenance_entry
                      (tenant_id, item_id, performed_on, kind, cost_amount, cost_currency, note,
                       created_by)
                  values (?, ?, current_date - 30, 'Serviced', 19.9900, 'EUR', 'New brushes', ?)
                  """)
              .param(tenant.tenantId())
              .param(item)
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into inventory.item_relation
                      (tenant_id, source_id, target_id, relation_type, created_by)
                  values (?, ?, ?, 'ACCESSORY_OF', ?)
                  """)
              .param(tenant.tenantId())
              .param(second)
              .param(item)
              .param(tenant.userId())
              .update();
          jdbc.sql(
                  """
                  insert into inventory.item_bundle
                      (tenant_id, bundle_item_id, member_item_id, created_by)
                  values (?, ?, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(item)
              .param(second)
              .param(tenant.userId())
              .update();
          UUID group =
              jdbc
                  .sql(
                      """
                      insert into tagging.tag_group
                          (tenant_id, key, labels, exclusive, display_order, created_by, updated_by)
                      values (?, 'condition', '{"en": "Condition"}'::jsonb, true, 1, ?, ?)
                      returning id
                      """)
                  .param(tenant.tenantId())
                  .param(tenant.userId())
                  .param(tenant.userId())
                  .query(UUID.class)
                  .single();
          UUID tag =
              jdbc
                  .sql("select id from tagging.tag order by created_at limit 1")
                  .query(UUID.class)
                  .single();
          jdbc.sql(
                  """
                  insert into tagging.tag_assignment (tenant_id, tag_id, item_id, created_by)
                  values (?, ?, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(tag)
              .param(item)
              .param(tenant.userId())
              .update();
          List<UUID> categories =
              jdbc
                  .sql("select id from catalog.location_category order by key limit 2")
                  .query(UUID.class)
                  .list();
          jdbc.sql(
                  """
                  insert into catalog.location_category_child
                      (tenant_id, parent_category_id, child_category_id, created_by)
                  values (?, ?, ?, ?)
                  """)
              .param(tenant.tenantId())
              .param(categories.get(0))
              .param(categories.get(1))
              .param(tenant.userId())
              .update();
          return group;
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
