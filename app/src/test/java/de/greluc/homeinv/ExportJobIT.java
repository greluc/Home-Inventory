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
import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportNotReadyException;
import de.greluc.homeinv.portability.api.ExportService;
import de.greluc.homeinv.portability.application.ExportRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayInputStream;
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
 * <p>Ten blocks write themselves, {@code media} among them, so the archive carries the photographs
 * and not only the rows about them. What an archive must <b>not</b> lose is {@code
 * ExportCoverageIT}<!-- -->'s subject; what it must not carry is here, because a derivative and an
 * infected file are both things the receiving instance is better off without.
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
  @Autowired private BlobStore blobs;
  @Autowired private TypeAdministration types;

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
  @DisplayName("carries the photographs themselves, but neither a derivative nor an infected file")
  void theBlobsTravelToo() throws Exception {
    Tenant tenant = newTenant("export-blobs@example.org");
    UUID item = anItem(tenant, "A camera", aPlace(tenant, "A drawer"));

    byte[] photograph = "the bytes of a photograph".getBytes(StandardCharsets.UTF_8);
    String clean = "a".repeat(64);
    String infected = "b".repeat(64);
    String derivative = "c".repeat(64);
    store(tenant, clean, photograph);
    store(tenant, infected, "something the scanner did not like".getBytes(StandardCharsets.UTF_8));
    store(tenant, derivative, "a thumbnail".getBytes(StandardCharsets.UTF_8));
    UUID object = aMediaObject(tenant, clean, "CLEAN");
    aMediaObject(tenant, infected, "INFECTED");
    aVariantOf(tenant, object, derivative);
    anAttachment(tenant, object, item);

    ExportService.ExportJobView queued = inOwn(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, byte[]> entries = unzipBytes(inOwn(tenant, () -> openQuietly(queued.id())));

    // The bytes, under their content address -- which is how a row finds its file
    // without a second index that could disagree with the first.
    assertThat(entries).containsKey("media/blobs/" + clean);
    assertThat(entries.get("media/blobs/" + clean)).isEqualTo(photograph);

    // Not the infected one: this deployment refuses to serve it (REQ-MED-013), and
    // putting it in an archive would hand it over inside a container that hides it
    // from the scanner on the other side. Its row still travels, so the archive
    // says the thing existed and what happened to it.
    assertThat(entries).doesNotContainKey("media/blobs/" + infected);
    assertThat(new String(entries.get("data/media/media-objects.jsonl"), StandardCharsets.UTF_8))
        .contains(infected);

    // And not the derivative: a thumbnail is recomputed from the original, so
    // shipping it doubles the largest part of the archive to save work the
    // receiving instance does anyway.
    assertThat(entries).doesNotContainKey("media/blobs/" + derivative);
    assertThat(new String(entries.get("data/media/variants.jsonl"), StandardCharsets.UTF_8))
        .contains(derivative);

    // The manifest counts the files as well as the rows, so a reader can tell a
    // truncated archive from a small one without unpacking all of it.
    JsonNode manifest =
        json.readTree(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
    JsonNode listed =
        manifest.get("files").valueStream()
            .filter(file -> ("media/blobs/" + clean).equals(file.get("file").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(listed.get("block").asString()).isEqualTo("media");
    assertThat(listed.get("bytes").asLong()).isEqualTo(photograph.length);
  }

  @Test
  @DisplayName("opens a sealed value for the person who may read it, and names the ones it did not")
  void sealedValuesTravelAsFarAsTheRequesterMayReadThem() throws Exception {
    Tenant tenant = newTenant("export-sealed@example.org");
    UUID type = aLicenceType(tenant);
    UUID licence =
        asOwner(
            tenant,
            () ->
                items
                    .create(
                        new ItemService.CreateItemCommand(
                            null,
                            type,
                            "A copy of something",
                            null,
                            ItemKind.DIGITAL,
                            null,
                            BigDecimal.ONE,
                            null,
                            "{\"carrier\":\"vendor account\",\"licenceKey\":\"not a real key\"}",
                            null,
                            null,
                            Valuation.NONE),
                        java.util.Optional.empty(),
                        tenant.userId())
                    .item()
                    .id());

    // It is ciphertext in the column: that is ADR-0019, and it is why an archive
    // that copied the column would be an archive of something unreadable.
    String stored =
        inOwn(
            tenant,
            () ->
                jdbc
                    .sql("select attributes::text from inventory.item where id = ?")
                    .param(licence)
                    .query(String.class)
                    .single());
    assertThat(stored).doesNotContain("not a real key");

    // Asked for by the owner, with a second factor proved just now: the value is
    // opened into the archive, because it is theirs and an archive they cannot
    // read is not portability (REQ-PORT-003).
    ExportService.ExportJobView mine =
        asOwner(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, String> opened = unzip(inOwn(tenant, () -> openQuietly(mine.id())));
    assertThat(opened.get("data/inventory/items.jsonl")).contains("not a real key");

    // Asked for with no second factor proved, which is REQ-AUTH-011's other
    // side. The field is withheld -- and the manifest says so by name, because
    // an archive silent about it is one whose reader believes the field was
    // empty.
    ExportService.ExportJobView withoutProof = inOwn(tenant, () -> exports.request(tenant.userId()));
    runner.runAsTenant(tenant.tenantId());
    Map<String, String> withheld = unzip(inOwn(tenant, () -> openQuietly(withoutProof.id())));
    assertThat(withheld.get("data/inventory/items.jsonl"))
        .doesNotContain("not a real key")
        .contains("vendor account");

    JsonNode manifest = json.readTree(withheld.get("manifest.json"));
    assertThat(
            manifest.get("withheld").valueStream()
                .map(entry -> entry.get("what").asString())
                .toList())
        .contains("licenceKey");
    assertThat(json.readTree(opened.get("manifest.json")).get("withheld")).isEmpty();
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

  /**
   * A type with a carrier and a sealed licence key — REQ-CORE-004's shape.
   *
   * @param tenant whose type
   * @return the type id
   */
  private UUID aLicenceType(Tenant tenant) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status -> {
                  TypeAdministration.ItemTypeView type =
                      types.createItemType(
                          new TypeAdministration.CreateItemTypeCommand(
                              "software-licence", TypeKind.DIGITAL, null, null),
                          tenant.userId());
                  types.addField(
                      type.draftVersionId(), aField("carrier", false), tenant.userId());
                  types.addField(
                      type.draftVersionId(), aField("licenceKey", true), tenant.userId());
                  types.publish(type.draftVersionId(), tenant.userId());
                  return type.id();
                }));
  }

  /**
   * One text field, sensitive or not.
   *
   * @param key its key
   * @param sensitive whether its value is sealed
   * @return the command
   */
  private TypeAdministration.FieldCommand aField(String key, boolean sensitive) {
    return new TypeAdministration.FieldCommand(
        key,
        FieldDataType.TEXT,
        Map.of("en", key),
        Map.of(),
        false,
        null,
        FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        false,
        false,
        false,
        sensitive);
  }

  /**
   * Runs a body as the tenant's owner, with a second factor proved just now.
   *
   * <p>Both halves matter here: the role decides whether a sensitive field may be read at all, and
   * REQ-AUTH-011 asks for the factor to have been proved recently on top of it. An export asked for
   * without the second half is the negative case in the test above, not a mistake.
   *
   * @param tenant the tenant
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
                () -> transactions.executeWithoutResult(status -> produced.set(body.get()))));
    return produced.get();
  }

  private InputStream openQuietly(UUID id) {
    try {
      return exports.open(id);
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  /**
   * Puts bytes in the blob store under an address of this test<!-- -->'s choosing.
   *
   * <p>The digest is not verified on the way in, which is what lets a test name its blobs {@code
   * aaa…} and {@code bbb…} and stay readable. What the export does with them is the subject here.
   *
   * @param tenant whose store
   * @param sha256 the address
   * @param content the bytes
   */
  private void store(Tenant tenant, String sha256, byte[] content) {
    inOwn(
        tenant,
        () -> {
          try {
            return blobs.store(tenant.tenantId(), sha256, new ByteArrayInputStream(content));
          } catch (java.io.IOException failed) {
            throw new IllegalStateException(failed);
          }
        });
  }

  /**
   * A media row in a given scan state.
   *
   * @param tenant whose it is
   * @param sha256 the blob it points at
   * @param scanState what the scanner said
   * @return the row<!-- -->'s id
   */
  private UUID aMediaObject(Tenant tenant, String sha256, String scanState) {
    return inOwn(
        tenant,
        () ->
            jdbc
                .sql(
                    """
                    insert into media.media_object
                        (tenant_id, sha256, media_type, byte_size, scan_state, ref_count,
                         created_by, updated_by)
                    values (?, ?, 'image/png', 25, ?, 1, ?, ?)
                    returning id
                    """)
                .param(tenant.tenantId())
                .param(sha256)
                .param(scanState)
                .param(tenant.userId())
                .param(tenant.userId())
                .query(UUID.class)
                .single());
  }

  /**
   * A derivative of a media object.
   *
   * @param tenant whose it is
   * @param object what it was derived from
   * @param sha256 the derivative<!-- -->'s own address
   */
  private void aVariantOf(Tenant tenant, UUID object, String sha256) {
    inOwn(
        tenant,
        () ->
            jdbc
                .sql(
                    """
                    insert into media.media_variant
                        (tenant_id, media_object_id, kind, sha256, media_type, byte_size,
                         width_px, height_px, created_by, updated_by)
                    values (?, ?, 'thumb', ?, 'image/webp', 11, 2, 2, ?, ?)
                    """)
                .param(tenant.tenantId())
                .param(object)
                .param(sha256)
                .param(tenant.userId())
                .param(tenant.userId())
                .update());
  }

  /**
   * Hangs a media object on an item.
   *
   * @param tenant whose it is
   * @param object the media
   * @param item what it is a photograph of
   */
  private void anAttachment(Tenant tenant, UUID object, UUID item) {
    inOwn(
        tenant,
        () ->
            jdbc
                .sql(
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
                .update());
  }

  private static Map<String, String> unzip(InputStream archive) throws Exception {
    Map<String, String> entries = new HashMap<>();
    unzipBytes(archive)
        .forEach((name, bytes) -> entries.put(name, new String(bytes, StandardCharsets.UTF_8)));
    return entries;
  }

  /**
   * Unpacks an archive without decoding it.
   *
   * <p>A photograph is not text, and comparing it as a string would either pass on bytes that were
   * mangled or fail on bytes that were not.
   *
   * @param archive the ZIP
   * @return every entry, by name
   * @throws Exception when it cannot be read
   */
  private static Map<String, byte[]> unzipBytes(InputStream archive) throws Exception {
    Map<String, byte[]> entries = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(archive)) {
      for (java.util.zip.ZipEntry entry = zip.getNextEntry();
          entry != null;
          entry = zip.getNextEntry()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        zip.transferTo(out);
        entries.put(entry.getName(), out.toByteArray());
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
