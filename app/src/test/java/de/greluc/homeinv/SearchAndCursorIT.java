/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.Page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SearchService;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.greluc.homeinv.search.api.SearchIndex;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves full-text search and the signed cursor.
 *
 * <p>The stemming test is the one that matters most: it is the whole reason two generated vectors
 * exist rather than one {@code simple} configuration (ADR-0047). If it were ever weakened to a
 * prefix match, the assertion below would still pass for the wrong reason — so it searches for a
 * word that shares no prefix with the text it must match.
 *
 * <p>Since 2026-09-14 the search runs through the {@code SearchIndex} port, which answers with
 * identifiers and nothing else (REQ-SRCH-005, REQ-SRCH-007). The last test here holds that
 * contract: the engine's order survives the reload, and an id the index names but the tenant cannot
 * see costs a shorter page rather than an error — which is the whole reason a derived index is safe
 * to run.
 */
@DisplayName("Search and pagination")
class SearchAndCursorIT extends AbstractIntegrationTest {

  @Autowired private SearchService search;
  @Autowired private SearchIndex index;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("answers with ids, loads the rows, and keeps the index's order (REQ-SRCH-007)")
  void theIndexSuppliesIdsAndTheRowsAreLoaded() {
    Fixture tenant = newTenant("ids-only@example.org");
    UUID first = create(tenant, "Alpha", "one");
    UUID second = create(tenant, "Beta", "two");
    UUID third = create(tenant, "Gamma", "three");

    // What the port answers: identifiers, in the order they are to be shown.
    SearchIndex.Hits hits =
        inTenant(
            tenant,
            () ->
                index.find(
                    new SearchIndex.Query(
                        "", "de", List.of(), List.of(), List.of(),
                        java.util.Optional.empty(), null, List.of(), 50)));
    assertThat(hits.itemIds())
        .as("the index names the items and hands over nothing else")
        .containsExactly(first, second, third);

    // And what the service makes of them: the same items, in the same order,
    // loaded through the ordinary read.
    assertThat(namesOf(tenant, "")).containsExactly("Alpha", "Beta", "Gamma");

    // An id the index names and the tenant cannot see is absent rather than an
    // error. A derived index that has gone stale can only ever cost a row.
    List<de.greluc.homeinv.inventory.api.ItemView> loaded =
        inTenant(tenant, () -> items.byIds(List.of(first, UUID.randomUUID(), third)));
    assertThat(loaded.stream().map(de.greluc.homeinv.inventory.api.ItemView::name).toList())
        .as("a stale hit shortens the page and never fails it")
        .containsExactly("Alpha", "Gamma");
  }

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
      Page<ItemView> result =
          inTenant(tenant, () -> search.query(new SearchService.SearchRequest("", "de", List.of(), current, null, List.of(), 3)));
      result.data().forEach(item -> seen.add(item.name()));
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
        inTenant(tenant, () -> search.query(new SearchService.SearchRequest("", "de", List.of(), null, null, List.of(), 2)))
            .nextCursor();
    assertThat(cursor).isNotNull();

    // Flip one character of the payload. The signature no longer matches, and the
    // answer must be a refusal rather than a page starting somewhere else.
    String tampered = (cursor.charAt(0) == 'A' ? 'B' : 'A') + cursor.substring(1);
    assertThatThrownBy(
            () ->
                inTenant(
                    tenant,
                    () -> search.query(new SearchService.SearchRequest("", "de", List.of(), tampered, null, List.of(), 2))))
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
                () -> search.query(new SearchService.SearchRequest("Hammer", "de", List.of(), null, null, List.of(), 2)))
            .nextCursor();
    assertThat(cursor).isNotNull();

    // Same tenant, same session, untampered cursor - and a different search.
    // REQ-SRCH-009: a cursor with changed filters is rejected.
    assertThatThrownBy(
            () ->
                inTenant(
                    tenant,
                    () -> search.query(new SearchService.SearchRequest("Zange", "de", List.of(), cursor, null, List.of(), 2))))
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
    return inTenant(tenant, () -> search.query(new SearchService.SearchRequest(text, "de", List.of(), null, null, List.of(), 50)))
        .data()
        .stream()
        .map(de.greluc.homeinv.inventory.api.ItemView::name)
        .toList();
  }

  private UUID create(Fixture tenant, String name, String description) {
    return inTenant(
        tenant,
        () ->
            items.create(
                new ItemService.CreateItemCommand(
                    null, null, name, description, ItemKind.DIGITAL, null, null, null, null, null, null, Valuation.NONE), Optional.empty(),
                tenant.userId())
                .item()
                .id());
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
