/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves a sensitive attribute is ciphertext in the column (ADR-0019, REQ-SEC-046, REQ-CORE-004).
 *
 * <p>The end-to-end half of the envelope: {@code SensitiveFieldCryptoIT} checks the format and the
 * rotations against the specification, and this one checks that an ordinary write actually goes
 * through it. The assertion that matters is on the <b>raw column</b> — a test that only read the
 * value back through the API would pass just as happily against a system that stored it in the
 * clear.
 *
 * <p>It also covers the write-back hazard, which is the part of this that loses data rather than
 * leaking it: somebody who may not read a licence key reads the record without it and saves a
 * change to the name, and the key has to survive that.
 */
@DisplayName("A sensitive attribute")
class SealedAttributesIT extends AbstractIntegrationTest {

  @Autowired private TypeAdministration types;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("reaches the column as ciphertext and comes back only for a caller who may read it")
  void sealedInTheColumn() {
    Tenant tenant = newTenant("sealed-column@example.org");
    UUID type = licenceType(tenant);

    AtomicReference<UUID> created = new AtomicReference<>();
    asOwner(
        tenant,
        () ->
            created.set(
                items
                    .create(
                        new ItemService.CreateItemCommand(
                            null,
                            type,
                            "Design tool",
                            null,
                            ItemKind.DIGITAL,
                            null,
                            BigDecimal.ONE,
                            null,
                            "{\"carrier\":\"vendor account\",\"licenceKey\":\"ABCD-1234-EFGH\"}",
                            null,
                            null),
                        Optional.empty(),
                        tenant.userId())
                    .item()
                    .id()));
    UUID item = created.get();

    // The column. Not the API, which would be the same code answering its own
    // question: a system that stored the key in the clear would pass that.
    String stored = rawAttributes(tenant, item);
    assertThat(stored).doesNotContain("ABCD-1234-EFGH");
    assertThat(stored).contains("vendor account");

    // An owner with a fresh second factor reads the value itself.
    assertThat(attributesAsOwner(tenant, item)).contains("ABCD-1234-EFGH");

    // A caller who proved no second factor does not: REQ-AUTH-011 makes reading
    // one of these an operation that asks for the factor again, and the field is
    // removed rather than masked — a mask says how long the value is.
    String toStranger =
        TenantContext.callAs(
            tenant.tenantId(),
            () -> {
              AtomicReference<String> seen = new AtomicReference<>();
              CallerContext.runAs(
                  new CallerContext.Caller(
                      tenant.userId(), tenant.tenantId(), "VIEWER", null, null),
                  () -> seen.set(transactions.execute(status -> items.get(item).attributes())));
              return seen.get();
            });
    assertThat(toStranger).doesNotContain("ABCD-1234-EFGH").contains("vendor account");
  }

  @Test
  @DisplayName("survives an edit by somebody who was never shown it")
  void theWriteBackHazard() {
    Tenant tenant = newTenant("sealed-writeback@example.org");
    UUID type = licenceType(tenant);

    AtomicReference<UUID> created = new AtomicReference<>();
    asOwner(
        tenant,
        () ->
            created.set(
                items
                    .create(
                        new ItemService.CreateItemCommand(
                            null,
                            type,
                            "Design tool",
                            null,
                            ItemKind.DIGITAL,
                            null,
                            BigDecimal.ONE,
                            null,
                            "{\"carrier\":\"vendor account\",\"licenceKey\":\"ABCD-1234-EFGH\"}",
                            null,
                            null),
                        Optional.empty(),
                        tenant.userId())
                    .item()
                    .id()));
    UUID item = created.get();

    // Somebody who cannot read the key edits the carrier. They send back exactly
    // what they were shown, which does not mention the key at all.
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(tenant.userId(), tenant.tenantId(), "VIEWER", null, null),
                () ->
                    transactions.executeWithoutResult(
                        status ->
                            items.update(
                                item,
                                new ItemService.UpdateItemCommand(
                                    "Design tool",
                                    null,
                                    null,
                                    BigDecimal.ONE,
                                    null,
                                    "{\"carrier\":\"a different account\"}",
                                    null,
                                    null),
                                OptionalLong.empty(),
                                tenant.userId()))));

    // The edit landed, and the key is still there. Without the merge it would be
    // gone, and the person who deleted it would have no way of knowing.
    String afterwards = attributesAsOwner(tenant, item);
    assertThat(afterwards).contains("a different account").contains("ABCD-1234-EFGH");
  }

  @Test
  @DisplayName("cannot also be searchable, because an index over it would hold ciphertext")
  void sensitiveAndSearchableIsRefused() {
    Tenant tenant = newTenant("sealed-contradiction@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TypeAdministration.ItemTypeView type =
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(
                      "contradiction", TypeKind.DIGITAL, null, null),
                  tenant.userId());
          assertThatThrownBy(
                  () ->
                      types.addField(
                          type.draftVersionId(),
                          fieldCommand("licenceKey", true, true),
                          tenant.userId()))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("cannot also be searchable");
        });
  }

  // -------------------------------------------------------------------------

  private String rawAttributes(Tenant tenant, UUID item) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status ->
                    jdbc.sql("select attributes::text from inventory.item where id = ?")
                        .param(item)
                        .query(String.class)
                        .single()));
  }

  private String attributesAsOwner(Tenant tenant, UUID item) {
    AtomicReference<String> seen = new AtomicReference<>();
    asOwner(tenant, () -> seen.set(items.get(item).attributes()));
    return seen.get();
  }

  /**
   * Runs a body as the tenant's owner with a second factor proved just now.
   *
   * <p>Both halves are needed for a sensitive field: the permission comes with the role, and
   * REQ-AUTH-011 asks for the factor to have been proved recently on top of it.
   *
   * @param tenant the tenant
   * @param body the work
   */
  private void asOwner(Tenant tenant, Runnable body) {
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(
                    tenant.userId(), tenant.tenantId(), "OWNER", null, null, Instant.now()),
                () -> transactions.executeWithoutResult(status -> body.run())));
  }

  /**
   * A type with a carrier and a sealed licence key — REQ-CORE-004's shape.
   *
   * @param tenant whose type
   * @return the type id
   */
  private UUID licenceType(Tenant tenant) {
    AtomicReference<UUID> id = new AtomicReference<>();
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TypeAdministration.ItemTypeView type =
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(
                      "software-licence", TypeKind.DIGITAL, null, null),
                  tenant.userId());
          types.addField(
              type.draftVersionId(), fieldCommand("carrier", true, false), tenant.userId());
          types.addField(
              type.draftVersionId(), fieldCommand("licenceKey", false, true), tenant.userId());
          types.publish(type.draftVersionId(), tenant.userId());
          id.set(type.id());
        });
    return id.get();
  }

  private TypeAdministration.FieldCommand fieldCommand(
      String key, boolean searchable, boolean sensitive) {
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
        searchable,
        false,
        false,
        sensitive);
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Sealer",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
