/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.application;

import de.greluc.homeinv.inventory.api.ItemSearchQuery;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SearchDocument;
import de.greluc.homeinv.search.api.SearchIndex;
import de.greluc.homeinv.tagging.api.TagQueries;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Keeps the search index in step with the inventory (REQ-SRCH-005, REQ-SRCH-011).
 *
 * <h2>It reads rather than trusts</h2>
 *
 * <p>An event says <i>that</i> something happened and carries the scalars a consumer branches on;
 * this one then reads the item again. Not redundancy: a search document needs the tag names and the
 * location path as well, which live in two other blocks, so the row has to be fetched regardless —
 * and reading it fresh makes two deliveries of the same event produce the same document, which is
 * what an at-least-once outbox requires (04 §4.4).
 *
 * <p>Four published ports and no join across a schema: {@code inventory} answers what may be
 * indexed, {@code tagging} the tag names, {@code locations} the path (ADR-0002).
 *
 * <h2>An item that is gone is removed rather than skipped</h2>
 *
 * <p>{@link ItemSearchQuery#searchable} answers empty for an item that has been trashed or purged,
 * and that is the signal to take the document out. It also covers the race an at-least-once
 * delivery creates: an {@code ItemUpdated} that arrives after the item was trashed would otherwise
 * put a deleted thing back into the index, where it would stay findable for the whole retention
 * period (REQ-CORE-013).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexer {

  /**
   * Where the document goes: every engine this installation has.
   *
   * <p>A list rather than the chosen one, and the PostgreSQL engine's write does nothing — its
   * index is the database. Writing to all of them means an installation that switches to OpenSearch
   * does not need a different indexer, and an engine that is added later gets its documents without
   * this class changing.
   */
  private final List<SearchIndex> engines;

  /** Answers what of an item may be indexed, sealed values already excluded. */
  private final ItemSearchQuery items;

  /** Answers the tag names, which {@code inventory} may not read. */
  private final TagQueries tags;

  /** Answers the location path, which is {@code locations}' tree. */
  private final LocationService locations;

  /**
   * Brings one item's document up to date, or removes it when the item is gone.
   *
   * <p>Idempotent by construction: the document's id is the item's, so writing it twice replaces
   * rather than duplicates, and removing something that is not there is not an error.
   *
   * <p><b>No transaction of its own</b>, and that order is load-bearing: {@code app.tenant_id} is
   * set when a transaction begins, from the tenant context that is current then. A transaction
   * opened <i>around</i> {@code runAs} runs with none set, row-level security answers nothing, and
   * every item reads as gone — so the indexer would dutifully remove the document of every item
   * that was just created. It did, until this was noticed on 2026-09-14, and it failed silently:
   * removing something that is not there is not an error.
   *
   * <p>Each port below opens its own read transaction inside the context instead, which is the same
   * shape a request takes.
   *
   * @param tenantId whose item — the event carries it, because a listener acts for no one and has
   *     no context of its own
   * @param itemId the item
   */
  public void reindex(UUID tenantId, UUID itemId) {
    TenantContext.runAs(
        tenantId,
        () -> {
          Optional<ItemSearchQuery.SearchableItem> row = items.searchable(itemId);
          if (row.isEmpty()) {
            engines.forEach(engine -> engine.remove(tenantId, itemId));
            log.debug("Item {} removed from the index in tenant {}", itemId, tenantId);
            return;
          }
          SearchDocument document = documentOf(tenantId, row.get());
          engines.forEach(engine -> engine.index(document));
          log.debug("Item {} indexed in tenant {}", itemId, tenantId);
        });
  }

  /**
   * Takes one item out of the index without reading it first.
   *
   * <p>For a purge, where the row is gone and there is nothing to read. A trashing goes through
   * {@link #reindex} instead, because the item is still there and only the answer to "is it live"
   * has changed.
   *
   * @param tenantId whose item
   * @param itemId the item
   */
  public void forget(UUID tenantId, UUID itemId) {
    engines.forEach(engine -> engine.remove(tenantId, itemId));
    log.debug("Item {} forgotten in tenant {}", itemId, tenantId);
  }

  /**
   * Assembles the document from the three blocks that hold its parts.
   *
   * @param tenantId whose item
   * @param row what {@code inventory} answered
   * @return the document
   */
  private SearchDocument documentOf(UUID tenantId, ItemSearchQuery.SearchableItem row) {
    List<UUID> path =
        row.locationId() == null ? List.of() : locations.ancestorIds(row.locationId());
    return new SearchDocument(
        tenantId,
        row.itemId(),
        row.itemTypeVersionId(),
        row.name(),
        row.description(),
        row.notes(),
        row.attributeValues(),
        tags.tagNamesOf(row.itemId()),
        row.locationId(),
        path,
        row.createdAt(),
        row.updatedAt());
  }
}
