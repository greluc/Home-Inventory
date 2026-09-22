/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.search.api.SearchService;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Items, and everything hanging off one (REQ-API-006).
 *
 * <p>An adapter and nothing else, exactly like a REST controller: it reads arguments, calls a
 * published port and returns what it got. Three things about it are worth stating.
 *
 * <p><b>The views are the blocks' own.</b> {@link ItemView}, {@link LocationView}, {@link TagView}
 * and the rest are returned unchanged — the schema's field names were chosen to match them. A
 * second set of GraphQL-shaped records would be a second definition of the same thing, drifting
 * from the first the day a field is added (REQ-NFR-022 already says entities never leave a block;
 * these are the views that do).
 *
 * <p><b>Every method declares what it needs.</b> {@link GraphQlPermissions} enforces it and {@code
 * ArchitectureRulesTest} refuses a resolver without one. A field whose permission the caller lacks
 * comes back {@code null} with an entry in {@code errors}, and the rest of the query still answers
 * (ADR-0079).
 *
 * <p><b>The nested fields are batched.</b> {@code Item.type} resolves through {@link TypeRegistry}
 * in one call per batch rather than one per item — see {@link #type}. The lists that are not
 * batched are bounded by their own {@code first} and by the cost budget.
 */
@Controller
@RequiredArgsConstructor
public class ItemQueries {

  private final ItemService items;
  private final SearchService search;
  private final LocationService locations;
  private final TypeRegistry types;
  private final TypeAdministration catalogue;
  private final TagService tags;
  private final MediaService media;
  private final ItemRelations relations;
  private final RevisionLog revisions;

  /**
   * One item.
   *
   * @param id which one
   * @return it, or {@code null} when this tenant has no such item — which is also the answer for
   *     another tenant's, because a query that said "forbidden" would confirm it exists
   *     (REQ-SEC-025)
   */
  @QueryMapping
  @RequiresPermission(Permission.ITEM_READ)
  public ItemView item(@Argument UUID id) {
    try {
      return items.get(id);
    } catch (NotFoundException absent) {
      // GraphQL's nullable field IS the "not found" of this surface: there is no
      // status code to carry one, and an error entry would say the same thing
      // with more words.
      return null;
    }
  }

  /**
   * A page of items.
   *
   * @param q free text, or {@code null}
   * @param language which analyser the text is searched with
   * @param filter the filter grammar of 08 §8.2, the same strings the REST surface takes
   * @param sort one sort key, {@code -} for descending
   * @param first how many at most
   * @param after an opaque cursor from a previous page, never an offset
   * @return the page
   */
  @QueryMapping
  @RequiresPermission(Permission.SEARCH_QUERY)
  public Connection<ItemView> items(
      @Argument String q,
      @Argument String language,
      @Argument List<String> filter,
      @Argument String sort,
      @Argument Integer first,
      @Argument String after) {
    return Connection.of(
        search.query(
            new SearchService.SearchRequest(
                q,
                language == null ? "de" : language,
                List.of(),
                after,
                SortOrder.parse(sort),
                filters(filter),
                List.of(),
                bounded(first))));
  }

  /**
   * The type version each item's attributes were written against, for a whole page at once.
   *
   * <p>A {@code @BatchMapping}, which is Spring for GraphQL's {@code DataLoader}: fifty items
   * resolve their type in <b>two</b> calls — one that maps versions to types, one that reads the
   * types — rather than in a hundred. That is the N+1 08 §8.4 asks to be guarded against, and
   * {@code GraphQlBatchingTest} counts the calls rather than trusting the annotation.
   *
   * @param items the page being resolved
   * @return the type of each, absent for an item whose type version names a type that is gone
   */
  @BatchMapping(typeName = "Item", field = "type")
  @RequiresPermission(Permission.TYPE_READ)
  public Map<ItemView, TypeAdministration.ItemTypeView> type(List<ItemView> items) {
    Map<UUID, UUID> typeOfVersion =
        types.itemTypesOfVersions(
            items.stream().map(ItemView::itemTypeVersionId).filter(Objects::nonNull).toList());
    Map<UUID, TypeAdministration.ItemTypeView> byId =
        catalogue.itemTypes(null, 200).data().stream()
            .collect(Collectors.toMap(TypeAdministration.ItemTypeView::id, Function.identity()));

    Map<ItemView, TypeAdministration.ItemTypeView> resolved = new LinkedHashMap<>();
    for (ItemView item : items) {
      TypeAdministration.ItemTypeView type = byId.get(typeOfVersion.get(item.itemTypeVersionId()));
      if (type != null) {
        resolved.put(item, type);
      }
    }
    return resolved;
  }

  /**
   * Where each item is, for a whole page at once.
   *
   * <p>Batched by <b>de-duplicating the ids</b>, which is where the win is: a page of fifty items
   * in one room is one read, not fifty. {@code LocationService} has no batch read and does not need
   * one for this — what it has is a per-place visibility check (REQ-TEN-007), and a batch read that
   * skipped it would move a visibility decision into this adapter, which ADR-0010 forbids.
   *
   * @param items the page being resolved
   * @return the place of each, absent for a digital item, which is nowhere
   */
  @BatchMapping(typeName = "Item", field = "location")
  @RequiresPermission(Permission.LOCATION_READ)
  public Map<ItemView, LocationView> location(List<ItemView> items) {
    Map<UUID, LocationView> byId = new LinkedHashMap<>();
    for (ItemView item : items) {
      if (item.locationId() != null) {
        byId.computeIfAbsent(item.locationId(), locations::get);
      }
    }
    Map<ItemView, LocationView> resolved = new LinkedHashMap<>();
    for (ItemView item : items) {
      LocationView place = item.locationId() == null ? null : byId.get(item.locationId());
      if (place != null) {
        resolved.put(item, place);
      }
    }
    return resolved;
  }

  /**
   * What an item is tagged with.
   *
   * @param item the item being resolved
   * @return its tags
   */
  @SchemaMapping(typeName = "Item", field = "tags")
  @RequiresPermission(Permission.TAG_READ)
  public List<TagView> tags(ItemView item) {
    return tags.tagsOf(TagService.TagTarget.ITEM, item.id(), null, 200).data();
  }

  /**
   * An item's photographs and documents.
   *
   * @param item the item being resolved
   * @param first how many at most
   * @return the attachments, without their bytes
   */
  @SchemaMapping(typeName = "Item", field = "photos")
  @RequiresPermission(Permission.MEDIA_READ)
  public List<MediaView> photos(ItemView item, @Argument Integer first) {
    return media.attachmentsOf("ITEM", item.id(), null, bounded(first)).data();
  }

  /**
   * What an item is related to.
   *
   * @param item the item being resolved
   * @param first how many at most
   * @return the relations, each naming the other end
   */
  @SchemaMapping(typeName = "Item", field = "relations")
  @RequiresPermission(Permission.ITEM_READ)
  public List<Relation> relations(ItemView item, @Argument Integer first) {
    return relations.relationsOf(item.id(), null, bounded(first)).data().stream()
        .map(view -> Relation.of(view, item.id()))
        .toList();
  }

  /**
   * An item's revision history.
   *
   * @param item the item being resolved
   * @param first how many at most
   * @return the revisions, newest first
   */
  @SchemaMapping(typeName = "Item", field = "history")
  @RequiresPermission(Permission.TENANT_READ)
  public Connection<RevisionLog.RevisionView> history(ItemView item, @Argument Integer first) {
    return Connection.of(
        revisions.history(RevisionLog.EntityType.ITEM, item.id(), null, bounded(first)));
  }

  /**
   * One relation, from the asking item's point of view.
   *
   * <p>The stored row names a source and a target; a client asking an item about its relations
   * wants the <b>other</b> end, whichever column it sits in. Resolving that here rather than in the
   * schema keeps the client from having to compare ids to find out which end it already had.
   *
   * @param id the relation
   * @param otherItemId the item at the other end
   * @param type what kind of relation it is
   * @param inbound whether the other item declared it
   */
  public record Relation(UUID id, UUID otherItemId, String type, boolean inbound) {

    /**
     * Turns a stored relation into one end of it.
     *
     * @param view the stored relation
     * @param asking the item that asked
     * @return the relation as that item sees it
     */
    static Relation of(ItemRelations.RelationView view, UUID asking) {
      UUID other = asking.equals(view.sourceId()) ? view.targetId() : view.sourceId();
      return new Relation(view.id(), other, view.type().name(), view.inbound());
    }
  }

  /**
   * The filter grammar, parsed by the one parser that knows it.
   *
   * @param raw what the client sent, or {@code null}
   * @return the parsed filters
   */
  private static List<QueryFilter> filters(List<String> raw) {
    return raw == null ? List.of() : raw.stream().map(QueryFilter::parse).toList();
  }

  /**
   * A page size that is bounded whatever the client asked for.
   *
   * <p>REQ-NFR-010 admits no exception, and the cost budget is a second line rather than the first:
   * a client that sends {@code first: 100000} gets 200 rather than a refusal, exactly as the REST
   * surface caps its {@code limit}.
   *
   * @param first what was asked for, or {@code null}
   * @return between 1 and 200
   */
  static int bounded(Integer first) {
    if (first == null || first < 1) {
      return 50;
    }
    return Math.min(first, 200);
  }

  /**
   * A page, as the schema's connections describe one.
   *
   * @param nodes the rows
   * @param pageInfo where the next ones are
   * @param <T> what the rows are
   */
  public record Connection<T>(List<T> nodes, PageCursor pageInfo) {

    /**
     * Wraps a block's page.
     *
     * @param page what the port returned
     * @param <T> what the rows are
     * @return the connection
     */
    static <T> Connection<T> of(Page<T> page) {
      return new Connection<>(
          page.data(), new PageCursor(page.page().nextCursor(), page.page().hasMore()));
    }
  }

  /**
   * Where the next page is.
   *
   * @param endCursor the opaque cursor, or {@code null} when this was the last page
   * @param hasNextPage whether asking again would return anything
   */
  public record PageCursor(String endCursor, boolean hasNextPage) {}
}
