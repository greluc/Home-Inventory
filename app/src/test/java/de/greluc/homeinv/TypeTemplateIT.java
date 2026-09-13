/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The eight shipped templates are importable and what comes out is ordinary (REQ-CORE-030).
 *
 * <p>Both halves of the acceptance are here. "Importable": the eight are offered and one of them
 * becomes a usable type in a single call. "Fully editable": what it becomes is a type like any
 * other — a field can be added to its next draft, and nothing links it back to the file it came
 * from.
 *
 * <p>The software licence template carries REQ-CORE-004 as well, so it gets its own test: a digital
 * item with no location, a carrier, an expiry, a seat count, and a licence key that reaches the
 * column sealed.
 */
@DisplayName("A shipped type template")
class TypeTemplateIT extends AbstractIntegrationTest {

  @Autowired private TypeAdministration types;
  @Autowired private TypeRegistry registry;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("is one of the eight REQ-CORE-030 names, and each brings its fields")
  void theEightAreOffered() {
    Tenant tenant = newTenant("templates-offered@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TypeAdministration.TemplateList offered = types.templates();

          assertThat(offered.templates())
              .extracting(TypeAdministration.TemplateView::key)
              .containsExactlyInAnyOrder(
                  "book",
                  "tool",
                  "appliance",
                  "furniture",
                  "clothing",
                  "software-licence",
                  "document",
                  "food");

          // Detailed rather than minimal, decided 2026-09-13: a template is a
          // starting point a person prunes, and a field nobody has costs more
          // than a field nobody fills.
          assertThat(offered.templates())
              .allSatisfy(
                  template -> assertThat(template.fieldCount()).isBetween(8, 12));

          // Each names itself in both shipped languages.
          assertThat(offered.templates())
              .allSatisfy(
                  template ->
                      assertThat(template.labels()).containsKeys("en", "de"));
        });
  }

  @Test
  @DisplayName("becomes an ordinary published type that the tenant can go on editing")
  void importedAndThenEdited() {
    Tenant tenant = newTenant("templates-import@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TypeAdministration.ItemTypeView book = types.importTemplate("book", tenant.userId());

          assertThat(book.key()).isEqualTo("book");
          assertThat(book.kind()).isEqualTo(TypeKind.PHYSICAL);
          assertThat(book.builtin()).isFalse();
          // Published, not left as a draft: an imported template is usable at
          // once, and a type whose only version were a draft could hold nothing.
          assertThat(book.publishedVersionId()).isNotNull();
          assertThat(book.draftVersionId()).isNull();

          TypeAdministration.VersionView published = types.version(book.publishedVersionId());
          assertThat(published.fields())
              .extracting(FieldDefinitionView::key)
              .contains("author", "isbn", "publisher", "published", "pages");

          // "Fully editable": a next draft takes a field of the tenant's own, and
          // nothing about the type remembers the file it came from.
          TypeAdministration.VersionView draft =
              types.draftVersion(book.id(), tenant.userId());
          types.addField(
              draft.id(),
              new TypeAdministration.FieldCommand(
                  "readOn",
                  de.greluc.homeinv.catalog.api.FieldDataType.DATE,
                  java.util.Map.of("en", "Read on"),
                  java.util.Map.of(),
                  false,
                  null,
                  de.greluc.homeinv.catalog.api.FieldConstraints.NONE,
                  null,
                  null,
                  null,
                  99,
                  false,
                  true,
                  false,
                  false),
              tenant.userId());
          TypeAdministration.VersionView second = types.publish(draft.id(), tenant.userId());
          assertThat(second.fields()).extracting(FieldDefinitionView::key).contains("readOn");
        });
  }

  @Test
  @DisplayName("is refused the second time, rather than becoming a second type of the same name")
  void importingTwiceIsRefused() {
    Tenant tenant = newTenant("templates-twice@example.org");
    TenantContext.runAs(
        tenant.tenantId(), () -> types.importTemplate("tool", tenant.userId()));

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    tenant.tenantId(), () -> types.importTemplate("tool", tenant.userId())))
        .isInstanceOf(TypeAdministration.TypeKeyTakenException.class);

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    tenant.tenantId(), () -> types.importTemplate("spaceship", tenant.userId())))
        .isInstanceOf(TypeRegistry.UnknownTypeException.class);
  }

  @Test
  @DisplayName("for a software licence carries REQ-CORE-004, licence key sealed and all")
  void theSoftwareLicenceTemplate() {
    Tenant tenant = newTenant("templates-licence@example.org");
    AtomicReference<UUID> typeId = new AtomicReference<>();

    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TypeAdministration.ItemTypeView licence =
              types.importTemplate("software-licence", tenant.userId());
          typeId.set(licence.id());

          // A digital item has no physical location; the four fields REQ-CORE-004
          // names are the ones it has instead.
          assertThat(licence.kind()).isEqualTo(TypeKind.DIGITAL);
          assertThat(types.version(licence.publishedVersionId()).fields())
              .extracting(FieldDefinitionView::key)
              .contains("carrier", "licenceKey", "expiresOn", "seats");

          FieldDefinitionView key =
              types.version(licence.publishedVersionId()).fields().stream()
                  .filter(field -> "licenceKey".equals(field.key()))
                  .findFirst()
                  .orElseThrow();
          assertThat(key.sensitive()).isTrue();
          // Not required, decided 2026-09-13: a licence is often recorded before
          // the key is to hand, and a required field somebody may not read is one
          // they cannot supply.
          assertThat(key.required()).isFalse();
          // And not indexed, which the editor would refuse anyway: the value is
          // stored encrypted, so an index over it would hold ciphertext.
          assertThat(key.projected()).isFalse();
        });

    // The acceptance in as many words: creatable without a location, and the
    // licence field stored encrypted.
    UUID item =
        asOwner(
            tenant,
            () ->
                items
                    .create(
                        new ItemService.CreateItemCommand(
                            null,
                            typeId.get(),
                            "Design suite",
                            null,
                            ItemKind.DIGITAL,
                            null,
                            BigDecimal.ONE,
                            null,
                            "{\"carrier\":\"vendor portal\",\"licenceKey\":\"nothing real\","
                                + "\"seats\":5,\"expiresOn\":\"2027-01-31\"}",
                            null,
                            null, Valuation.NONE),
                        Optional.empty(),
                        tenant.userId())
                    .item()
                    .id());

    String stored =
        TenantContext.callAs(
            tenant.tenantId(),
            () ->
                transactions.execute(
                    status ->
                        jdbc.sql("select attributes::text from inventory.item where id = ?")
                            .param(item)
                            .query(String.class)
                            .single()));
    assertThat(stored).doesNotContain("nothing real");
    assertThat(stored).contains("vendor portal").contains("2027-01-31");
  }

  // -------------------------------------------------------------------------

  private UUID asOwner(Tenant tenant, java.util.function.Supplier<UUID> body) {
    AtomicReference<UUID> result = new AtomicReference<>();
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(
                    tenant.userId(), tenant.tenantId(), "OWNER", null, null, Instant.now()),
                () -> transactions.executeWithoutResult(status -> result.set(body.get()))));
    return result.get();
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
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
