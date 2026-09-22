/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.portability.api.PlacePath;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns {@code Garage / Shelf / Box} into a place, creating what is missing (REQ-PORT-001).
 *
 * <p>Walked one level at a time, matching by name among siblings exactly as the tenant's own
 * uniqueness rule does — {@code location_sibling_name} is on {@code lower(name)} under the same
 * parent, so matching any other way would find nothing and then fail to insert what is already
 * there.
 *
 * <p>Anything it creates is a real place made through {@link LocationService}, not a row written
 * behind its back: the depth ceiling, the category rules and the audit entry all apply. An import
 * is not a way around the rules the tree has.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportedPlaces implements PlacePath {

  private final LocationService locations;
  private final LocationCategories categories;
  private final JdbcClient jdbc;

  @Override
  public UUID resolveOrCreate(String path, UUID actor) {
    if (path == null || path.isBlank()) {
      return null;
    }
    UUID parent = null;
    for (String segment : path.split("/")) {
      String name = segment.strip();
      if (name.isEmpty()) {
        continue;
      }
      UUID here = childNamed(parent, name).orElse(null);
      parent = here != null ? here : create(parent, name, actor);
    }
    return parent;
  }

  /**
   * The child of this parent with this name, if there is one.
   *
   * @param parent the parent, or null for a root
   * @param name the name, matched case-insensitively
   * @return the place, or empty
   */
  private Optional<UUID> childNamed(UUID parent, String name) {
    return jdbc
        .sql(
            """
            select id from locations.location
            where lower(name) = lower(?)
              and parent_id is not distinct from ?
              and deleted_at is null
            limit 1
            """)
        .param(name)
        .param(parent)
        .query(UUID.class)
        .optional();
  }

  /**
   * A new place under this parent.
   *
   * @param parent where it goes, or null for a root
   * @param name what it is called
   * @param actor who is importing
   * @return its id
   */
  private UUID create(UUID parent, String name, UUID actor) {
    log.debug("An import created the place '{}'", name);
    return locations
        .create(
            new LocationService.CreateLocationCommand(null, categoryFor(parent), parent, name,
                null),
            Optional.empty(),
            actor)
        .id();
  }

  /**
   * Which category a created place gets.
   *
   * <p>The parent's, when there is one: a shelf inside a room is more likely to be the same kind of
   * thing than a category picked at random. Otherwise the tenant's first, which is deterministic
   * and therefore reproducible — a place must have a category ({@code category_version_id} is
   * {@code NOT NULL}) and a CSV never says which.
   *
   * @param parent the parent, or null
   * @return a category id
   */
  private UUID categoryFor(UUID parent) {
    if (parent != null) {
      return locations.get(parent).categoryId();
    }
    return categories.list(null, 1).data().stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The tenant has no location category, so an imported place cannot be given "
                        + "one. A provisioned tenant has thirteen."))
        .id();
  }
}
