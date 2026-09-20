/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ExpiryOverview;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything that runs out, in one list (REQ-LIFE-013).
 *
 * <p>The interesting part is <b>which</b> dates get in. A warranty end is a column; a licence
 * expiry and a best-before date are type attributes, and a tenant can define its own. The list is
 * therefore driven by a field flag rather than by a set of known keys — 07 §7.13's warning about
 * reports over attributes, answered by letting the field say what it is.
 */
@DisplayName("The expiry overview")
class ExpiryOverviewIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ExpiryOverview expiries;
  @Autowired private ItemService items;
  @Autowired private TypeAdministration types;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("collects a warranty and a marked attribute into one list, soonest first")
  void bothSourcesInOneList() {
    Tenant tenant = newTenant("expiry-both@example.org");
    UUID typeId = inOwn(tenant, () -> aTypeWithAnExpiryField(tenant, "validUntil", true));

    UUID licence = anItem(tenant, typeId, "A licence", Map.of("validUntil", "2026-11-01"), null);
    UUID kettle =
        anItem(tenant, builtinTypeVersion(tenant), "A kettle", null, LocalDate.parse("2026-10-01"));

    List<ExpiryOverview.Expiring> due = inOwn(tenant, () -> expiries.due(null, true, 50));

    // Sorted by due date, which is REQ-LIFE-013's own acceptance criterion: the
    // kettle's warranty in October comes before the licence in November, and the
    // two sources are one list rather than two.
    assertThat(due).hasSize(2);
    assertThat(due.get(0).itemId()).isEqualTo(kettle);
    assertThat(due.get(0).kind()).isEqualTo("warranty");
    assertThat(due.get(1).itemId()).isEqualTo(licence);
    assertThat(due.get(1).kind()).isEqualTo("validUntil");

    // A TENANT'S OWN NAME for the date, which is the whole reason this is a flag
    // and not a list of known keys: nothing in the code knows "validUntil".
    assertThat(due.get(1).label()).isEqualTo("Valid until");
  }

  @Test
  @DisplayName("leaves out a date whose field is not marked as an expiry")
  void anUnmarkedDateStaysOut() {
    Tenant tenant = newTenant("expiry-unmarked@example.org");
    UUID typeId = inOwn(tenant, () -> aTypeWithAnExpiryField(tenant, "lastCalibrated", false));

    anItem(tenant, typeId, "A scale", Map.of("lastCalibrated", "2026-10-01"), null);

    // When a thing was last calibrated is a fact about the past. Collecting every
    // date-typed attribute would put it in a list of what runs out, where it
    // means nothing -- which is why the field says what it is.
    assertThat(inOwn(tenant, () -> expiries.due(null, true, 50))).isEmpty();
  }

  @Test
  @DisplayName("never lists an item whose warranty lasts for life")
  void aLifetimeWarrantyNeverExpires() {
    Tenant tenant = newTenant("expiry-lifetime@example.org");
    UUID item = anItemWithLifetimeWarranty(tenant, "A cast-iron pan");

    // A lifetime warranty has no date, which is exactly why REQ-LIFE-002 made it
    // a flag rather than a date far in the future -- a date somebody would
    // eventually have to explain, and which would sit at the bottom of this list.
    assertThat(inOwn(tenant, () -> expiries.due(null, true, 50))).isEmpty();
    assertThat(item).isNotNull();
  }

  @Test
  @DisplayName("keeps what has already run out, unless asked not to")
  void whatIsAlreadyGone() {
    Tenant tenant = newTenant("expiry-past@example.org");
    anItem(tenant, builtinTypeVersion(tenant), "An old kettle", null, LocalDate.parse("2020-01-01"));

    // A warranty that ran out is the one somebody most wants to know about, so it
    // is in the list by default.
    assertThat(inOwn(tenant, () -> expiries.due(null, true, 50))).hasSize(1);
    // And can be excluded when a caller wants only what is still to come.
    assertThat(inOwn(tenant, () -> expiries.due(null, false, 50))).isEmpty();
  }

  @Test
  @DisplayName("stops at the date it was asked to stop at")
  void theHorizonNarrowsIt() {
    Tenant tenant = newTenant("expiry-horizon@example.org");
    anItem(tenant, builtinTypeVersion(tenant), "Soon", null, LocalDate.parse("2026-10-05"));
    anItem(tenant, builtinTypeVersion(tenant), "Later", null, LocalDate.parse("2027-10-05"));

    List<ExpiryOverview.Expiring> fortnight =
        inOwn(tenant, () -> expiries.due(LocalDate.parse("2026-10-31"), true, 50));
    assertThat(fortnight).hasSize(1);
    assertThat(fortnight.get(0).itemName()).isEqualTo("Soon");
  }

  @Test
  @DisplayName("refuses to mark anything but a date as an expiry")
  void onlyADateCanExpire() {
    Tenant tenant = newTenant("expiry-nondate@example.org");

    // An overview sorted by due date cannot sort a piece of text, so the refusal
    // is where the field is defined rather than where the list is read.
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () -> {
                      TypeAdministration.ItemTypeView type =
                          types.createItemType(
                              new TypeAdministration.CreateItemTypeCommand(
                                  "nondate", TypeKind.PHYSICAL, null, null),
                              tenant.userId());
                      types.addField(
                          type.draftVersionId(),
                          field("notADate", FieldDataType.TEXT, "Not a date", true),
                          tenant.userId());
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only a date field");
  }

  // -------------------------------------------------------------------------

  private static TypeAdministration.FieldCommand field(
      String key, FieldDataType dataType, String label, boolean expiry) {
    return new TypeAdministration.FieldCommand(
        key,
        dataType,
        Map.of("en", label),
        Map.of(),
        false,
        null,
        de.greluc.homeinv.catalog.api.FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        false,
        false,
        false,
        false,
        expiry);
  }

  private UUID aTypeWithAnExpiryField(Tenant tenant, String key, boolean expiry) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand(
                "kind-" + key.toLowerCase(java.util.Locale.ROOT), TypeKind.PHYSICAL, null, null),
            tenant.userId());
    types.addField(
        type.draftVersionId(),
        field(key, FieldDataType.DATE, expiry ? "Valid until" : "Last calibrated", expiry),
        tenant.userId());
    types.publish(type.draftVersionId(), tenant.userId());
    // The TYPE's id and not the version's: `CreateItemCommand` names a type and
    // the service resolves its published version (ADR-0004).
    return type.id();
  }

  private UUID anItem(
      Tenant tenant, UUID typeVersionId, String name, Map<String, String> attributes, LocalDate warranty) {
    String json =
        attributes == null
            ? "{}"
            : "{\"" + attributes.keySet().iterator().next() + "\":\""
                + attributes.values().iterator().next() + "\"}";
    return inOwn(
        tenant,
        () ->
            items
                .create(
                    new ItemService.CreateItemCommand(
                        null,
                        typeVersionId,
                        name,
                        null,
                        ItemKind.PHYSICAL,
                        aPlace(tenant),
                        BigDecimal.ONE,
                        null,
                        json,
                        null,
                        null,
                        warranty == null
                            ? Valuation.NONE
                            : new Valuation(
                                null, null, null, warranty, false, null, null, null, null, null)),
                    java.util.Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID anItemWithLifetimeWarranty(Tenant tenant, String name) {
    return inOwn(
        tenant,
        () ->
            items
                .create(
                    new ItemService.CreateItemCommand(
                        null,
                        builtinTypeVersion(tenant),
                        name,
                        null,
                        ItemKind.PHYSICAL,
                        aPlace(tenant),
                        BigDecimal.ONE,
                        null,
                        "{}",
                        null,
                        null,
                        new Valuation(
                            Money.of("30.00", "EUR"), null, null, null, true, null, null, null, null,
                            null)),
                    java.util.Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID builtinTypeVersion(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc
                .sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
                .param(tenant.tenantId())
                .query(UUID.class)
                .single());
  }

  private UUID aPlace(Tenant tenant) {
    return locations
        .create(
            new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                null, anyCategory(tenant.tenantId()), null, "A place " + UUID.randomUUID(), null),
            java.util.Optional.empty(),
            tenant.userId())
        .id();
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
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
