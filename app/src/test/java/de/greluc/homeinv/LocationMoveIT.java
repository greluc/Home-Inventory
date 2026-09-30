/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.locations.api.InvalidMoveException;
import de.greluc.homeinv.locations.api.LocationMoved;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.NameTakenException;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.locations.domain.Location;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves a location can be re-parented, and what that costs (REQ-CORE-043, 045, 047).
 *
 * <p>Driven through the service, like {@link LocationTreeIT} and for the same reason: what is under
 * test is the tree — the one statement that rewrites a subtree, the prefix comparison that refuses a
 * cycle, the ceiling measured on the deepest descendant. The REST adapter over it gets one test of
 * its own at the bottom, because a `409` with the right {@code type} is a contract and not a detail.
 *
 * <p>Events are recorded rather than mocked. REQ-CORE-043's acceptance is a <em>count</em> — "moving
 * a box with 200 items produces one event, not 200" — so counting them is the test, and anything
 * that stands in for the publisher would be counting itself.
 */
@DisplayName("Moving a location")
@RecordApplicationEvents
class LocationMoveIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private LocationService locations;
  @Autowired private TypeAdministration types;
  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ApplicationEvents events;

  @Test
  @DisplayName("takes the whole subtree along and publishes one event, not one per item")
  void aSubtreeMovesInOneEvent() {
    Tenant tenant = newTenant("move-subtree@example.org");
    UUID room = category(tenant, "room");
    UUID boxes = category(tenant, "box");
    UUID type = builtinItemType(tenant);

    UUID house = inOwnTransaction(tenant, () -> create(room, null, "House").id());
    UUID cellar = inOwnTransaction(tenant, () -> create(room, house, "Cellar").id());
    UUID attic = inOwnTransaction(tenant, () -> create(room, house, "Attic").id());
    UUID box = inOwnTransaction(tenant, () -> create(boxes, cellar, "Box 7").id());
    UUID tray = inOwnTransaction(tenant, () -> create(boxes, box, "Tray").id());

    for (int index = 0; index < 8; index++) {
      String name = "Thing " + index;
      UUID where = index % 2 == 0 ? box : tray;
      inOwnTransaction(tenant, () -> item(type, where, name));
    }

    String boxPathBefore = inOwnTransaction(tenant, () -> rawPath(box));
    long ownEventsBefore = ownEvents();

    LocationView moved = inOwnTransaction(tenant, () -> locations.move(box, attic, OptionalLong.empty(), tenant.userId()));

    List<LocationMoved> published = events.stream(LocationMoved.class).toList();
    assertThat(published).hasSize(1);
    assertThat(published.getFirst().locationId()).isEqualTo(box);
    assertThat(published.getFirst().fromParentId()).isEqualTo(cellar);
    assertThat(published.getFirst().toParentId()).isEqualTo(attic);
    assertThat(published.getFirst().subtreeSize())
        .as("the box and the tray moved; the eight items did not move, they were carried")
        .isEqualTo(2);
    assertThat(ownEvents() - ownEventsBefore)
        .as("the move published exactly one event of this application's own")
        .isEqualTo(1);

    assertThat(moved.parentId()).isEqualTo(attic);
    assertThat(moved.ancestors()).containsExactly("House", "Attic", "Box 7");
    assertThat(inOwnTransaction(tenant, () -> rawPath(box))).isNotEqualTo(boxPathBefore);
    assertThat(inOwnTransaction(tenant, () -> rawPath(tray)))
        .startsWith(inOwnTransaction(tenant, () -> rawPath(box)) + ".");
    assertThat(inOwnTransaction(tenant, () -> locations.get(tray)).depth()).isEqualTo(3);
    assertThat(inOwnTransaction(tenant, () -> locations.get(tray)).ancestors())
        .containsExactly("House", "Attic", "Box 7", "Tray");

    assertThat(inOwnTransaction(tenant, () -> itemsIn(box))).isEqualTo(4);
    assertThat(inOwnTransaction(tenant, () -> itemsIn(tray))).isEqualTo(4);
  }

  @Test
  @DisplayName("to the place it already sits changes nothing and publishes nothing")
  void movingWhereItAlreadyIsIsNotAnError() {
    Tenant tenant = newTenant("move-noop@example.org");
    UUID room = category(tenant, "room");

    UUID house = inOwnTransaction(tenant, () -> create(room, null, "House").id());
    UUID cellar = inOwnTransaction(tenant, () -> create(room, house, "Cellar").id());
    String pathBefore = inOwnTransaction(tenant, () -> rawPath(cellar));

    LocationView same =
        inOwnTransaction(tenant, () -> locations.move(cellar, house, OptionalLong.empty(), tenant.userId()));

    assertThat(same.parentId()).isEqualTo(house);
    assertThat(inOwnTransaction(tenant, () -> rawPath(cellar))).isEqualTo(pathBefore);
    assertThat(events.stream(LocationMoved.class)).isEmpty();
  }

  @Test
  @DisplayName("into its own subtree is refused, which is what keeps the tree a tree")
  void aCycleIsRefused() {
    Tenant tenant = newTenant("move-cycle@example.org");
    UUID room = category(tenant, "room");

    UUID house = inOwnTransaction(tenant, () -> create(room, null, "House").id());
    UUID cellar = inOwnTransaction(tenant, () -> create(room, house, "Cellar").id());
    UUID shelf = inOwnTransaction(tenant, () -> create(room, cellar, "Shelf").id());

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(house, shelf, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(InvalidMoveException.class)
        .hasMessageContaining("itself");

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(house, cellar, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(InvalidMoveException.class);

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(house, house, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(InvalidMoveException.class);

    assertThat(inOwnTransaction(tenant, () -> locations.get(house)).parentId()).isNull();
  }

  @Test
  @DisplayName("is refused when the deepest thing inside it would end up past the ceiling")
  void theCeilingIsMeasuredOnTheDeepestDescendant() {
    Tenant tenant = newTenant("move-depth@example.org");
    UUID room = category(tenant, "room");

    UUID deepest =
        inOwnTransaction(
            tenant,
            () -> {
              LocationView current = create(room, null, "Level 0");
              for (int depth = 1; depth <= Location.MAX_DEPTH; depth++) {
                current = create(room, current.id(), "Level " + depth);
              }
              return current.id();
            });
    UUID oneAbove = inOwnTransaction(tenant, () -> locations.get(deepest).parentId());

    UUID crate = inOwnTransaction(tenant, () -> create(room, null, "Crate").id());
    UUID inside = inOwnTransaction(tenant, () -> create(room, crate, "Inside").id());

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(crate, oneAbove, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(TooDeepException.class);

    assertThat(inOwnTransaction(tenant, () -> locations.get(crate)).parentId()).isNull();
    assertThat(inOwnTransaction(tenant, () -> locations.get(inside)).depth()).isEqualTo(1);

    UUID twoAbove = inOwnTransaction(tenant, () -> locations.get(oneAbove).parentId());
    inOwnTransaction(tenant, () -> locations.move(crate, twoAbove, OptionalLong.empty(), tenant.userId()));
    assertThat(inOwnTransaction(tenant, () -> locations.get(inside)).depth())
        .isEqualTo(Location.MAX_DEPTH);
  }

  @Test
  @DisplayName("is refused when a sibling at the target already carries the name")
  void aNameCollisionAtTheTargetIsRefused() {
    Tenant tenant = newTenant("move-names@example.org");
    UUID room = category(tenant, "room");

    UUID house = inOwnTransaction(tenant, () -> create(room, null, "House").id());
    UUID cellar = inOwnTransaction(tenant, () -> create(room, house, "Cellar").id());
    inOwnTransaction(tenant, () -> create(room, house, "Store"));
    UUID store = inOwnTransaction(tenant, () -> create(room, cellar, "Store").id());

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(store, house, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(NameTakenException.class);
  }

  @Test
  @DisplayName("obeys a category's rule about what it takes underneath it (REQ-CORE-047)")
  void aChildCategoryRuleIsSettableAndEffective() {
    Tenant tenant = newTenant("move-rules@example.org");
    UUID room = category(tenant, "room");
    UUID boxes = category(tenant, "box");
    UUID shelves = category(tenant, "shelf");

    UUID hall = inOwnTransaction(tenant, () -> create(room, null, "Hall").id());
    UUID box = inOwnTransaction(tenant, () -> create(boxes, null, "Box 1").id());

    assertThat(inOwnTransaction(tenant, () -> types.childCategories(room)).permitted()).isEmpty();
    inOwnTransaction(tenant, () -> locations.move(box, hall, OptionalLong.empty(), tenant.userId()));
    inOwnTransaction(tenant, () -> locations.move(box, null, OptionalLong.empty(), tenant.userId()));

    TypeAdministration.ChildCategoryRuleView rule =
        inOwnTransaction(
            tenant, () -> types.setChildCategories(room, List.of(shelves), tenant.userId()));
    assertThat(rule.permitted()).containsExactly(shelves);

    assertThatThrownBy(
            () -> inOwnTransaction(tenant, () -> locations.move(box, hall, OptionalLong.empty(), tenant.userId())))
        .isInstanceOf(InvalidMoveException.class)
        .hasMessageContaining("does not take");

    inOwnTransaction(
        tenant, () -> types.setChildCategories(room, List.of(shelves, boxes), tenant.userId()));
    LocationView moved =
        inOwnTransaction(tenant, () -> locations.move(box, hall, OptionalLong.empty(), tenant.userId()));
    assertThat(moved.parentId()).isEqualTo(hall);

    inOwnTransaction(tenant, () -> types.setChildCategories(room, List.of(), tenant.userId()));
    assertThat(inOwnTransaction(tenant, () -> types.childCategories(room)).permitted()).isEmpty();
    inOwnTransaction(tenant, () -> locations.move(box, null, OptionalLong.empty(), tenant.userId()));
    inOwnTransaction(tenant, () -> locations.move(box, hall, OptionalLong.empty(), tenant.userId()));
  }

  @Test
  @DisplayName("answers a refused move with 409 invalid-move over HTTP")
  void theRefusalIsAProblemDocument() throws Exception {
    String email = "move-rest@example.org";
    UUID userId = createUser(email);
    UUID tenantId = provisioning.provision("Move over HTTP", userId);
    Tenant tenant = new Tenant(userId, tenantId);

    UUID room = category(tenant, "room");
    UUID house = inOwnTransaction(tenant, () -> create(room, null, "House").id());
    UUID cellar = inOwnTransaction(tenant, () -> create(room, house, "Cellar").id());

    var session = signIn(email, PASSWORD);

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/locations/{id}/move", house)
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .header("If-Match", eTagOf(session, "/api/v1/locations/" + house))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + cellar + "\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(
                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type")
                .value("https://home-inv.example/problems/invalid-move"));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/locations/{id}/move", cellar)
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .header("If-Match", eTagOf(session, "/api/v1/locations/" + cellar))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"parentId\":null}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.depth")
                .value(0));
  }

  /**
   * How many events this application published so far in this test.
   *
   * <p>Filtered to our own packages, because the framework publishes its own — a context refresh, a
   * request handled — and counting those would make the assertion about Spring rather than about
   * the move.
   *
   * @return the count
   */
  private long ownEvents() {
    return events
        .stream()
        .map(event -> event instanceof PayloadApplicationEvent<?> wrapper
                ? wrapper.getPayload()
                : (Object) event)
        .filter(payload -> payload.getClass().getName().startsWith("de.greluc.homeinv"))
        .count();
  }

  private LocationView create(UUID categoryId, UUID parentId, String name) {
    return locations.create(
        new LocationService.CreateLocationCommand(null, categoryId, parentId, name, null), Optional.empty(),
        currentActor);
  }

  private UUID item(UUID typeId, UUID locationId, String name) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null,
                typeId,
                name,
                null,
                ItemKind.PHYSICAL,
                locationId,
                BigDecimal.ONE,
                null,
                "{}",
                null,
                null, Valuation.NONE), Optional.empty(),
            currentActor)
        .item()
        .id();
  }

  private int itemsIn(UUID locationId) {
    return jdbc
        .sql("select count(*) from inventory.item where location_id = ? and deleted_at is null")
        .param(locationId)
        .query(Integer.class)
        .single();
  }

  private String rawPath(UUID locationId) {
    return jdbc.sql("select path::text from locations.location where id = ?")
        .param(locationId)
        .query(String.class)
        .single();
  }

  private UUID category(Tenant tenant, String key) {
    return inOwnTransaction(
        tenant,
        () ->
            jdbc.sql("select id from catalog.location_category where tenant_id = ? and key = ?")
                .params(tenant.tenantId(), key)
                .query(UUID.class)
                .single());
  }

  private UUID builtinItemType(Tenant tenant) {
    return inOwnTransaction(
        tenant,
        () ->
            jdbc.sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
                .param(tenant.tenantId())
                .query(UUID.class)
                .single());
  }

  /** Who the helpers act as; set for the duration of one {@link #inOwnTransaction} call. */
  private UUID currentActor;

  /**
   * Runs a body in its own committed transaction, inside the tenant's context.
   *
   * <p>A transaction each, for the reason {@link LocationTreeIT} writes out: a service that throws
   * inside an enclosing transaction marks it rollback-only, and the assertion about the exception is
   * then followed by a failure at commit that belongs to no test.
   *
   * @param tenant whose context to act in
   * @param body the work
   * @param <T> what it produces
   * @return the result
   */
  private <T> T inOwnTransaction(Tenant tenant, Supplier<T> body) {
    currentActor = tenant.userId();
    return TenantContext.callAs(
        tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = createUser(email);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private UUID createUser(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Mover",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
