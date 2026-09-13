/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.InvalidAttributesException;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An item carries the fields its type declares, and the side table mirrors them (REQ-CORE-005,
 * REQ-CORE-013, REQ-CORE-032).
 *
 * <p>The two halves are tested together because they are one property: JSONB is the source of truth
 * and {@code item_attr_index} is written in the same transaction, so an attribute filter is exact
 * rather than eventually right. A test that only checked the JSON would pass over the half that the
 * filters actually read.
 */
@DisplayName("An item's attributes")
class ItemAttributesIT extends AbstractIntegrationTest {

  @Autowired private TypeAdministration types;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("are validated against the type version, and refused with the path (REQ-CORE-005)")
  void validatedAgainstTheVersion() {
    Tenant tenant = newTenant("attributes-validated@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID type = bookType(tenant);

          // Valid: every declared field, in the shape the schema states.
          ItemView stored =
              items.create(
                      new ItemService.CreateItemCommand(
                          null,
                          type,
                          "Refactoring",
                          null,
                          ItemKind.DIGITAL,
                          null,
                          BigDecimal.ONE,
                          null,
                          """
                          {"isbn":"9780134757599",
                           "published":2018,
                           "purchasePrice":{"amount":"49.90","currency":"EUR"}}
                          """, null, null), Optional.empty(),
                      tenant.userId())
                  .item();
          assertThat(stored.attributes()).contains("9780134757599");

          // A value of the wrong shape is refused, and the violation names it.
          assertThatThrownBy(
                  () ->
                      items.create(
                          new ItemService.CreateItemCommand(
                              null,
                              type,
                              "Wrong",
                              null,
                              ItemKind.DIGITAL,
                              null,
                              BigDecimal.ONE,
                              null,
                              "{\"published\":\"not a year\"}", null, null), Optional.empty(),
                          tenant.userId()))
              .isInstanceOfSatisfying(
                  InvalidAttributesException.class,
                  refusal ->
                      assertThat(refusal.getViolations())
                          .anySatisfy(
                              violation -> assertThat(violation.path()).isEqualTo("/published")));

          // A key the type does not declare is refused too (REQ-SEC-029).
          assertThatThrownBy(
                  () ->
                      items.create(
                          new ItemService.CreateItemCommand(
                              null,
                              type,
                              "Smuggled",
                              null,
                              ItemKind.DIGITAL,
                              null,
                              BigDecimal.ONE,
                              null,
                              "{\"invented\":\"x\"}", null, null), Optional.empty(),
                          tenant.userId()))
              .isInstanceOf(InvalidAttributesException.class);
        });
  }

  @Test
  @DisplayName("are projected into the side table, with their units (REQ-CORE-013, REQ-CORE-032)")
  void projectedWithUnits() {
    Tenant tenant = newTenant("attributes-projected@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID type = bookType(tenant);
          ItemView item =
              items.create(
                      new ItemService.CreateItemCommand(
                          null,
                          type,
                          "Domain-Driven Design",
                          null,
                          ItemKind.DIGITAL,
                          null,
                          BigDecimal.ONE,
                          null,
                          """
                          {"isbn":"9780321125217",
                           "published":2003,
                           "purchasePrice":{"amount":"59.95","currency":"EUR"},
                           "licenceKey":"not-projected"}
                          """, null, null), Optional.empty(),
                      tenant.userId())
                  .item();

          Map<String, Projection> rows = projections(item.id());

          // Text, number and money each in their own column.
          assertThat(rows.get("isbn").text()).isEqualTo("9780321125217");
          assertThat(rows.get("published").number()).isEqualByComparingTo("2003");
          assertThat(rows.get("purchasePrice").number()).isEqualByComparingTo("59.95");
          // The currency travels with the amount, so no total ever adds euros to
          // dollars (REQ-CORE-032).
          assertThat(rows.get("purchasePrice").unit()).isEqualTo("EUR");

          // A secret is never projected, whatever its flags say: this table answers
          // filters, and a value gated by a permission must not be filterable.
          assertThat(rows).doesNotContainKey("licenceKey");
        });
  }

  @Test
  @DisplayName("are rewritten on an edit and removed when the item is trashed (07 §7.3)")
  void rewrittenAndCleared() {
    Tenant tenant = newTenant("attributes-lifecycle@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID type = bookType(tenant);
          ItemView item =
              items.create(
                      new ItemService.CreateItemCommand(
                          null,
                          type,
                          "Working Effectively with Legacy Code",
                          null,
                          ItemKind.DIGITAL,
                          null,
                          BigDecimal.ONE,
                          null,
                          "{\"isbn\":\"9780131177055\",\"published\":2004}", null, null), Optional.empty(),
                      tenant.userId())
                  .item();
          assertThat(projections(item.id()).get("published").number()).isEqualByComparingTo("2004");

          items.update(
              item.id(),
              new ItemService.UpdateItemCommand(
                  "Working Effectively with Legacy Code",
                  null,
                  null,
                  BigDecimal.ONE,
                  null,
                  "{\"isbn\":\"9780131177055\",\"published\":2005}", null, null), OptionalLong.empty(),
              tenant.userId());
          Map<String, Projection> after = projections(item.id());
          assertThat(after.get("published").number()).isEqualByComparingTo("2005");
          assertThat(after).hasSize(2);

          // Trashing takes the projection with it, in the same transaction: a
          // trashed item that kept answering filters would be findable for the
          // whole retention period.
          items.delete(item.id(), OptionalLong.empty(), tenant.userId());
          assertThat(projections(item.id())).isEmpty();
        });
  }

  /**
   * A type with one field of each shape the projection has to handle.
   *
   * @param tenant whose type system
   * @return the published type's id
   */
  private UUID bookType(Tenant tenant) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("book", TypeKind.DIGITAL, null, null),
            tenant.userId());
    types.addField(
        type.draftVersionId(),
        field("isbn", FieldDataType.TEXT, true, false),
        tenant.userId());
    types.addField(
        type.draftVersionId(),
        field("published", FieldDataType.INTEGER, true, false),
        tenant.userId());
    types.addField(
        type.draftVersionId(),
        field("purchasePrice", FieldDataType.MONEY, true, false),
        tenant.userId());
    // Every flag set and never projected, because the kind has the veto.
    types.addField(
        type.draftVersionId(),
        field("licenceKey", FieldDataType.SECRET, true, true),
        tenant.userId());
    types.publish(type.draftVersionId(), tenant.userId());
    return type.id();
  }

  /**
   * A field command with the flags this test cares about.
   *
   * @param key the attribute key
   * @param dataType the kind of value
   * @param searchable whether it is mirrored
   * @param sensitive whether reading it needs its own permission
   * @return the command
   */
  private TypeAdministration.FieldCommand field(
      String key, FieldDataType dataType, boolean searchable, boolean sensitive) {
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
        searchable,
        false,
        false,
        sensitive);
  }

  /**
   * What the side table holds for one item, by field key.
   *
   * @param itemId the item
   * @return the projected rows
   */
  private Map<String, Projection> projections(UUID itemId) {
    // In a transaction, because row-level security reads `app.tenant_id` and the
    // transaction manager is what sets it. Outside one the policy sees an empty
    // setting and answers nothing — which looks exactly like a projection that was
    // never written, and cost this test a wrong diagnosis once.
    List<Projection> rows =
        transactions.execute(status -> jdbc.sql(
                """
                select field_key, num_value, text_value, unit_value
                from inventory.item_attr_index
                where tenant_id = ? and item_id = ?
                """)
            .params(TenantContext.require(), itemId)
            .query(
                (rs, rowNum) ->
                    new Projection(
                        rs.getString("field_key"),
                        rs.getBigDecimal("num_value"),
                        rs.getString("text_value"),
                        rs.getString("unit_value")))
            .list());
    return rows.stream().collect(java.util.stream.Collectors.toMap(Projection::key, row -> row));
  }

  /**
   * One row of the side table, in the columns this test reads.
   *
   * @param key the field key
   * @param number the numeric projection
   * @param text the textual projection
   * @param unit the currency or unit that travels with a number
   */
  private record Projection(String key, BigDecimal number, String text, String unit) {}

  /**
   * A provisioned tenant with an owner.
   *
   * @param email the owner's address
   * @return the tenant and its owner
   */
  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  /**
   * The identifiers a test needs.
   *
   * @param userId the owner
   * @param tenantId the tenant
   */
  private record Tenant(UUID userId, UUID tenantId) {}
}
