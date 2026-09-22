/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.graphql.ItemQueries;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.platform.Page;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A page of items resolves its nested fields in a few calls rather than in two per item
 * (REQ-API-006, 08 §8.4's "N+1: DataLoader for every relation, guarded by a test").
 *
 * <p>This is that guard, and it counts calls rather than trusting an annotation: {@code
 * @BatchMapping} batches only if the method it is on takes the whole list, and a method that takes
 * the list and then loops has the annotation and none of the benefit.
 *
 * <p>A unit test and not an integration one, on purpose. What has to be proved is that the resolver
 * asks its ports once; that Spring for GraphQL calls a batch method once per batch is the
 * framework's contract and is exercised by {@code GraphQlIT} answering correctly.
 */
@DisplayName("A batched GraphQL field")
class GraphQlBatchingTest {

  private static final int PAGE = 50;

  @Test
  @DisplayName("asks the catalogue twice for fifty items, not a hundred times")
  void typesResolveInTwoCalls() {
    TypeRegistry types = mock(TypeRegistry.class);
    TypeAdministration catalogue = mock(TypeAdministration.class);

    UUID versionId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    Map<UUID, UUID> typeOfVersion = new HashMap<>();
    typeOfVersion.put(versionId, typeId);
    when(types.itemTypesOfVersions(any())).thenReturn(typeOfVersion);
    when(catalogue.itemTypes(isNull(), anyInt()))
        .thenReturn(
            Page.of(
                List.of(
                    new TypeAdministration.ItemTypeView(
                        typeId, "power-tool", TypeKind.PHYSICAL, null, null, false, false,
                        versionId, null, null)),
                null));

    ItemQueries queries =
        new ItemQueries(null, null, null, types, catalogue, null, null, null, null);

    List<ItemView> page = new ArrayList<>();
    for (int row = 0; row < PAGE; row++) {
      page.add(anItem(versionId, null));
    }

    Map<ItemView, TypeAdministration.ItemTypeView> resolved = queries.type(page);

    assertThat(resolved).hasSize(PAGE);
    assertThat(resolved.values()).allSatisfy(type -> assertThat(type.key()).isEqualTo("power-tool"));
    verify(types, times(1)).itemTypesOfVersions(any());
    verify(catalogue, times(1)).itemTypes(isNull(), anyInt());
  }

  @Test
  @DisplayName("reads a place once however many items are in it")
  void locationsAreDeduplicated() {
    LocationService locations = mock(LocationService.class);

    UUID kitchen = UUID.randomUUID();
    UUID shed = UUID.randomUUID();
    when(locations.get(kitchen)).thenReturn(aLocation(kitchen, "Kitchen"));
    when(locations.get(shed)).thenReturn(aLocation(shed, "Shed"));

    ItemQueries queries =
        new ItemQueries(null, null, locations, null, null, null, null, null, null);

    List<ItemView> page = new ArrayList<>();
    for (int row = 0; row < PAGE; row++) {
      page.add(anItem(UUID.randomUUID(), row % 2 == 0 ? kitchen : shed));
    }

    Map<ItemView, LocationView> resolved = queries.location(page);

    assertThat(resolved).hasSize(PAGE);
    // Fifty items, two places, two reads. Without the de-duplication this is
    // fifty reads of two rows — which is the shape of an N+1 that looks fine in
    // a household of ten items and does not in one of a thousand.
    verify(locations, times(1)).get(kitchen);
    verify(locations, times(1)).get(shed);
  }

  @Test
  @DisplayName("leaves a digital item's place out rather than answering null for it")
  void nowhereIsAbsentAndNotNull() {
    ItemQueries queries = new ItemQueries(null, null, null, null, null, null, null, null, null);

    Map<ItemView, LocationView> resolved =
        queries.location(List.of(anItem(UUID.randomUUID(), null)));

    // A GraphQL batch mapping answers an absent key with null for that field,
    // which is what the schema says: `location: Location`, nullable, because a
    // digital item is nowhere.
    assertThat(resolved).isEmpty();
  }

  private static ItemView anItem(UUID versionId, UUID locationId) {
    return new ItemView(
        UUID.randomUUID(),
        "A thing",
        null,
        locationId == null ? "DIGITAL" : "PHYSICAL",
        versionId,
        locationId,
        BigDecimal.ONE,
        null,
        "{}",
        null,
        null,
        "ACTIVE",
        Instant.now(),
        Instant.now(),
        null,
        1L,
        null);
  }

  private static LocationView aLocation(UUID id, String name) {
    return new LocationView(id, name, UUID.randomUUID(), null, 0, "{}", List.of(), 1L);
  }
}
