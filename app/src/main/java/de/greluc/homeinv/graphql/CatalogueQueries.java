/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.platform.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Places and types — the two things an item is described by (REQ-API-006).
 *
 * <p>Together in one adapter because they answer one question between them: what an item's
 * attributes mean and where the item is. Splitting them would be two classes of four methods with
 * the same two dependencies.
 */
@Controller
@RequiredArgsConstructor
public class CatalogueQueries {

  private final LocationService locations;
  private final TypeAdministration catalogue;
  private final TypeRegistry types;

  /**
   * One place.
   *
   * @param id which one
   * @return it, or {@code null} when this tenant has no such place
   */
  @QueryMapping
  @RequiresPermission(Permission.LOCATION_READ)
  public LocationView location(@Argument UUID id) {
    try {
      return locations.get(id);
    } catch (NotFoundException absent) {
      return null;
    }
  }

  /**
   * A place and what is under it, to a depth.
   *
   * <p>Without a root it is the tenant's whole tree, bounded by the same depth. The depth is
   * counted <b>from the root asked for</b>, so {@code depth: 1} is a place and its children.
   *
   * <p>The result is a flat list and not a nested one, deliberately: a nested shape would need a
   * {@code children} field on {@code Location}, and a client could then walk it to the depth limit
   * of 10 in a way this argument could not bound. Every entry carries its {@code parentId}, which
   * is what a client assembles the tree from.
   *
   * @param rootId where to start, or {@code null} for the whole tree
   * @param depth how many levels below the root, bounded at 12, which is the tree's own ceiling
   * @return the places, the root first
   */
  @QueryMapping
  @RequiresPermission(Permission.LOCATION_READ)
  public List<LocationView> locationTree(@Argument UUID rootId, @Argument Integer depth) {
    int levels = depth == null || depth < 0 ? 3 : Math.min(depth, 12);

    if (rootId == null) {
      // The whole tenant, to the depth asked for. `list` is paged and the cap is
      // the same 200 every listing here has: a tenant with more places than that
      // asks for a subtree, which is what the argument is for.
      return locations.list(null, 200).data().stream()
          .filter(place -> place.depth() <= levels)
          .toList();
    }

    LocationView root;
    try {
      root = locations.get(rootId);
    } catch (NotFoundException absent) {
      return List.of();
    }

    List<LocationView> tree = new ArrayList<>();
    tree.add(root);
    for (UUID id : locations.subtreeIds(rootId)) {
      if (id.equals(rootId)) {
        continue;
      }
      LocationView place = locations.get(id);
      if (place.depth() - root.depth() <= levels) {
        tree.add(place);
      }
    }
    return tree;
  }

  /**
   * The tenant's item types.
   *
   * @param first how many at most
   * @param after an opaque cursor from a previous page
   * @return the page
   */
  @QueryMapping
  @RequiresPermission(Permission.TYPE_READ)
  public ItemQueries.Connection<TypeAdministration.ItemTypeView> itemTypes(
      @Argument Integer first, @Argument String after) {
    return ItemQueries.Connection.of(catalogue.itemTypes(after, ItemQueries.bounded(first)));
  }

  /**
   * The fields a type's published version declares.
   *
   * @param type the type being resolved
   * @return its fields, or none when it has no published version yet
   */
  @SchemaMapping(typeName = "ItemType", field = "fields")
  @RequiresPermission(Permission.TYPE_READ)
  public List<FieldDefinitionView> fields(TypeAdministration.ItemTypeView type) {
    return type.publishedVersionId() == null
        ? List.of()
        : types.fields(type.publishedVersionId());
  }

  /**
   * What a field's data type is called on the wire.
   *
   * <p>The enum's name rather than the enum, so that a client needs no enum of ours — the same
   * choice {@code ItemView.kind} makes.
   *
   * @param field the field being resolved
   * @return {@code TEXT}, {@code NUMBER}, {@code DATE} and the rest
   */
  @SchemaMapping(typeName = "FieldDefinition", field = "dataType")
  @RequiresPermission(Permission.TYPE_READ)
  public String dataType(FieldDefinitionView field) {
    return field.dataType().name();
  }

  /**
   * What kind of type this is.
   *
   * @param type the type being resolved
   * @return {@code ITEM} or the location-category kind
   */
  @SchemaMapping(typeName = "ItemType", field = "kind")
  @RequiresPermission(Permission.TYPE_READ)
  public String kind(TypeAdministration.ItemTypeView type) {
    return type.kind().name();
  }
}
