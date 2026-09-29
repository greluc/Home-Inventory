/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.inventory.application.AttributeIndexReconciliation;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.infrastructure.PathConsistencyQueries;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The derived stores are compared with the truth, and rebuilt from it (REQ-NFR-073, ADR-0004).
 *
 * <p>Two projections carry the same risk and are checked the same way. {@code item_attr_index} is
 * written by the application in the transaction that writes the item, and {@code location.path} is
 * written by the move that changes a parent — both are duplicated facts, both are maintained by
 * code, and both fail <b>silently</b>: filters stop finding things, a location scope answers from a
 * stale tree, and nothing raises an error.
 *
 * <p>Each case here corrupts a row deliberately, because that is the only way to know the check can
 * see one. A reconciliation nobody has watched fail is a reconciliation that reports zero because it
 * looked at nothing.
 */
@DisplayName("The nightly reconciliation")
class ReconciliationIT extends AbstractIntegrationTest {

  @Autowired private AttributeIndexReconciliation attributes;
  @Autowired private PathConsistencyQueries paths;
  @Autowired private TypeAdministration types;
  @Autowired private ItemService items;
  @Autowired private LocationService locations;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("sees a row somebody removed from the attribute index, and the rebuild restores it")
  void aMissingProjectionIsFoundAndRebuilt() {
    Tenant tenant = newTenant("reconcile-missing@example.org");
    UUID item = anItemWithAttributes(tenant);

    inTenant(tenant, () -> assertThat(attributes.deviations()).isZero());

    inTenant(
        tenant,
        () ->
            jdbc.sql(
                    """
                    delete from inventory.item_attr_index
                    where tenant_id = ? and item_id = ? and field_key = 'isbn'
                    """)
                .params(tenant.tenantId(), item)
                .update());

    inTenant(tenant, () -> assertThat(attributes.deviations()).isEqualTo(1));

    inTenant(tenant, () -> assertThat(attributes.rebuild()).isEqualTo(1));
    inTenant(tenant, () -> assertThat(attributes.deviations()).isZero());
  }

  @Test
  @DisplayName("sees a row somebody added to the attribute index that no attribute justifies")
  void anInventedProjectionIsFound() {
    Tenant tenant = newTenant("reconcile-invented@example.org");
    UUID item = anItemWithAttributes(tenant);

    inTenant(
        tenant,
        () ->
            jdbc.sql(
                    """
                    insert into inventory.item_attr_index
                        (tenant_id, item_id, field_key, text_value)
                    values (?, ?, 'invented', 'nothing says this')
                    """)
                .params(tenant.tenantId(), item)
                .update());

    inTenant(tenant, () -> assertThat(attributes.deviations()).isEqualTo(1));
    inTenant(tenant, () -> assertThat(attributes.rebuild()).isEqualTo(1));
    inTenant(tenant, () -> assertThat(attributes.deviations()).isZero());
  }

  @Test
  @DisplayName("sees a location whose path no longer follows from its parent")
  void aBentPathIsFound() {
    Tenant tenant = newTenant("reconcile-path@example.org");
    UUID house = aPlace(tenant, "A house", null);
    UUID room = aPlace(tenant, "A room", house);

    inTenant(tenant, () -> assertThat(paths.deviations()).isZero());

    inTenant(
        tenant,
        () ->
            jdbc.sql(
                    """
                    update locations.location
                    set path = text2ltree(
                        'ffffffffffffffffffffffffffffffff.' || replace(?::text, '-', ''))
                    where tenant_id = ? and id = ?
                    """)
                .params(room.toString(), tenant.tenantId(), room)
                .update());

    inTenant(
        tenant,
        () -> {
          assertThat(paths.deviations()).isEqualTo(1);
          assertThat(paths.deviating(10)).containsExactly(room);
        });
  }

  @Test
  @DisplayName("counts nothing in a tenant whose tree and projections are sound")
  void aHealthyTenantReportsZero() {
    Tenant tenant = newTenant("reconcile-healthy@example.org");
    UUID house = aPlace(tenant, "A house", null);
    aPlace(tenant, "A cellar", house);
    anItemWithAttributes(tenant);

    inTenant(
        tenant,
        () -> {
          assertThat(attributes.deviations()).isZero();
          assertThat(paths.deviations()).isZero();
        });
  }

  private void inTenant(Tenant tenant, Runnable work) {
    TenantContext.runAs(tenant.tenantId(), () -> transactions.executeWithoutResult(status -> work.run()));
  }

  private UUID anItemWithAttributes(Tenant tenant) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status -> {
                  TypeAdministration.ItemTypeView type =
                      types.createItemType(
                          new TypeAdministration.CreateItemTypeCommand(
                              "book-" + UUID.randomUUID(), TypeKind.DIGITAL, null, null),
                          tenant.userId());
                  types.addField(
                      type.draftVersionId(),
                      field("isbn", FieldDataType.TEXT),
                      tenant.userId());
                  types.addField(
                      type.draftVersionId(),
                      field("published", FieldDataType.INTEGER),
                      tenant.userId());
                  types.publish(type.draftVersionId(), tenant.userId());

                  return items
                      .create(
                          new ItemService.CreateItemCommand(
                              null,
                              type.id(),
                              "Refactoring",
                              null,
                              ItemKind.DIGITAL,
                              null,
                              BigDecimal.ONE,
                              null,
                              "{\"isbn\":\"9780134757599\",\"published\":2018}",
                              null,
                              null,
                              Valuation.NONE),
                          Optional.empty(),
                          tenant.userId())
                      .item()
                      .id();
                }));
  }

  private TypeAdministration.FieldCommand field(String key, FieldDataType dataType) {
    return new TypeAdministration.FieldCommand(
        key,
        dataType,
        Map.of("en", key),
        Map.of(),
        false,
        null,
        FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        true,
        false,
        false,
        false);
  }

  private UUID aPlace(Tenant tenant, String name, UUID parent) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status ->
                    locations
                        .create(
                            new LocationService.CreateLocationCommand(
                                null,
                                anyCategory(tenant.tenantId()),
                                parent,
                                name + " " + UUID.randomUUID(),
                                null),
                            Optional.empty(),
                            tenant.userId())
                        .id()));
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Keeper",
                    "en",
                    passwordEncoder.encode("correct-horse-battery-staple-42"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
