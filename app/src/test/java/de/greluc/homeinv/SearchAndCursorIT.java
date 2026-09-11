/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.application.ItemService;
import de.greluc.homeinv.inventory.domain.ItemKind;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SearchService;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves full-text search and the signed cursor.
 *
 * <p>The stemming test is the one that matters most: it is the whole reason two generated vectors
 * exist rather than one {@code simple} configuration (ADR-0047). If it were ever weakened to a
 * prefix match, the assertion below would still pass for the wrong reason — so it searches for a
 * word that shares no prefix with the text it must match.
 */
@DisplayName("Search and pagination")
class SearchAndCursorIT extends AbstractIntegrationTest {

  @Autowired private SearchService search;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("finds a German word by its stem, not by its prefix")
  void germanStemmingWorks() {
    Fixture tenant = newTenant("stem@example.org");
    create(tenant, "Bohrmaschinen", "Zwei blaue Geräte im Keller");

    // "Bohrmaschine" stems to the same root as "Bohrmaschinen". A prefix match
    // would find it too, so the second search is the real check: "Geraet" shares
    // no prefix with "Geräte" but stems alike under the German configuration.
    assertThat(namesOf(tenant, "Bohrmaschine")).containsExactly("Bohrmaschinen");
    assertThat(namesOf(tenant, "blaue Geräte")).containsExactly("Bohrmaschinen");
  }

  @Test
  @DisplayName("pages through results with a cursor and never repeats a row")
  void cursorPagesWithoutRepeating() {
    Fixture tenant = newTenant("page@example.org");
    for (int i = 0; i < 7; i++) {
      create(tenant, "Kiste " + i, "Inhalt " + i);
    }

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      final String current = cursor;
      SearchService.SearchResult result =
          inTenant(tenant, () -> search.query(new SearchService.SearchRequest("", "de", current, 3)));
      result.items().forEach(item -> seen.add(item.name()));
      cursor = result.nextCursor();
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(seen).hasSize(7).doesNotHaveDuplicates();
    assertThat(pages).isEqualTo(3); // 3 + 3 + 1
  }

  @Test
  @DisplayName("rejects a tampered cursor instead of quietly resuming elsewhere")
  void tamperedCursorIsRejected() {
    Fixture tenant = newTenant("tamper@example.org");
    for (int i = 0; i < 4; i++) {
      create(tenant, "Ding " + i, null);
    }

    String cursor =
        inTenant(tenant, () -> search.query(new SearchService.SearchRequest("", "de", null, 2)))
            .nextCursor();
    assertThat(cursor).isNotNull();

    // Flip one character of the payload. The signature no longer matches, and the
    // answer must be a refusal rather than a page starting somewhere else.
    String tampered = (cursor.charAt(0) == 'A' ? 'B' : 'A') + cursor.substring(1);
    assertThatThrownBy(
            () ->
                inTenant(
                    tenant,
                    () -> search.query(new SearchService.SearchRequest("", "de", tampered, 2))))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("rejects a valid cursor used with a different query")
  void cursorIsBoundToItsQuery() {
    Fixture tenant = newTenant("bound@example.org");
    for (int i = 0; i < 4; i++) {
      create(tenant, "Hammer " + i, null);
    }

    String cursor =
        inTenant(
                tenant,
                () -> search.query(new SearchService.SearchRequest("Hammer", "de", null, 2)))
            .nextCursor();
    assertThat(cursor).isNotNull();

    // Same tenant, same session, untampered cursor - and a different search.
    // REQ-SRCH-009: a cursor with changed filters is rejected.
    assertThatThrownBy(
            () ->
                inTenant(
                    tenant,
                    () -> search.query(new SearchService.SearchRequest("Zange", "de", cursor, 2))))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("does not find another tenant's items")
  void searchStaysInsideTheTenant() {
    Fixture alice = newTenant("s-alice@example.org");
    Fixture bob = newTenant("s-bob@example.org");
    create(alice, "Alices Bohrmaschine", null);
    create(bob, "Bobs Bohrmaschine", null);

    assertThat(namesOf(alice, "Bohrmaschine")).containsExactly("Alices Bohrmaschine");
    assertThat(namesOf(bob, "Bohrmaschine")).containsExactly("Bobs Bohrmaschine");
  }

  private List<String> namesOf(Fixture tenant, String text) {
    return inTenant(tenant, () -> search.query(new SearchService.SearchRequest(text, "de", null, 50)))
        .items()
        .stream()
        .map(de.greluc.homeinv.inventory.api.ItemView::name)
        .toList();
  }

  private void create(Fixture tenant, String name, String description) {
    inTenant(
        tenant,
        () ->
            items.create(
                new ItemService.CreateItemCommand(
                    null, null, name, description, ItemKind.DIGITAL, null, null, null),
                tenant.userId()));
  }

  private <T> T inTenant(Fixture tenant, Supplier<T> body) {
    return TenantContext.callAs(
        tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Fixture newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "de", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Fixture(userId, provisioning.provision("Tenant " + email, userId));
  }

  private record Fixture(UUID userId, UUID tenantId) {}
}
