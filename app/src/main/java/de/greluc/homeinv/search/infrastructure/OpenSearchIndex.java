/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.platform.Facet;
import de.greluc.homeinv.search.api.SearchDocument;
import de.greluc.homeinv.search.api.SearchIndex;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * OpenSearch behind the {@link SearchIndex} port (ADR-0008, REQ-SRCH-005, REQ-SRCH-011).
 *
 * <h2>It matches text; the database still answers everything else</h2>
 *
 * <p>07 §7.3 divides the work and this class keeps to it: OpenSearch serves full text, and
 * {@code item_attr_index} serves the filters, the sorting and the paging, because
 * {@code REQ-CORE-013} calls those <b>transactionally exact</b> and an index that lags cannot be.
 * So a query is answered in two steps — the text becomes a set of item ids here, and
 * {@link PostgresSearchIndex} narrows, orders and pages within that set. Decided with the owner on
 * 2026-09-14; the alternative, mirroring typed attributes into the document, would have made the
 * filters eventually right instead of exact.
 *
 * <p>What OpenSearch buys in exchange is what it is good at: stemming and ranking across six
 * fields at once — name, description, notes, attribute values, tags and the location path
 * (REQ-SRCH-011) — where the PostgreSQL fallback has two generated vectors over two of them.
 *
 * <p>A query with no text does not touch OpenSearch at all. There is nothing to match, and asking
 * would be a round trip whose answer is "everything".
 *
 * <h2>Derived, and therefore allowed to be wrong</h2>
 *
 * <p>The index answers with ids and the rows are re-loaded from PostgreSQL (REQ-SRCH-007), so a
 * stale document costs one hit too many or too few and never wrong content. {@link #available()}
 * is what the service asks before using this engine; when it says no, the answer comes from
 * PostgreSQL with {@code meta.degraded} and {@code search-fallback} (REQ-SRCH-006, ADR-0039).
 */
@Slf4j
@Component
@ConditionalOnBean(OpenSearchClient.class)
@RequiredArgsConstructor
public class OpenSearchIndex implements SearchIndex {

  /** The alias every query and every write goes through, so a reindex can swap what is behind it. */
  static final String ALIAS = "homeinv-items";

  /** How many ids one round of {@code search_after} collects. */
  private static final int PAGE = 1000;

  /** How long a failure keeps this engine out of the rotation before it is tried again. */
  private static final long CIRCUIT_MILLIS = 30_000;

  private final OpenSearchClient client;

  /** Where the narrowing, ordering and paging happen once the text has become ids. */
  private final PostgresSearchIndex relational;

  /**
   * Only here to be built first.
   *
   * <p>Spring creates a bean's dependencies before the bean, so naming the bootstrap makes the
   * index and its alias exist before this engine can write to either. Without that ordering the
   * first write creates an index under the alias's name and the alias can never be created.
   */
  @SuppressWarnings("unused")
  private final OpenSearchIndexBootstrap bootstrap;

  /** When the last failure was, or 0. Read and written from request threads, hence volatile. */
  private volatile long brokenSince;

  /**
   * What this engine is called.
   *
   * @return {@code opensearch}
   */
  @Override
  public String name() {
    return "opensearch";
  }

  /**
   * Whether this index may be used right now.
   *
   * <p>A failure opens the circuit for half a minute rather than being retried on the next request:
   * a search that waits for a timeout on every call is slower than the fallback it is refusing to
   * use, and the fallback is correct (03 §3.7).
   *
   * @return true when nothing has failed recently
   */
  @Override
  public boolean available() {
    long broken = brokenSince;
    return broken == 0 || System.currentTimeMillis() - broken > CIRCUIT_MILLIS;
  }

  /**
   * Writes one item's document, replacing what was there.
   *
   * @param document what to hold
   */
  @Override
  public void index(SearchDocument document) {
    try {
      client.index(
          request ->
              request.index(ALIAS).id(document.itemId().toString()).document(bodyOf(document)));
      brokenSince = 0;
    } catch (IOException | RuntimeException unreachable) {
      // Recorded and swallowed. An index is a derived store: failing the write
      // that caused this would make the inventory depend on it (CLAUDE.md rule
      // 10), and the outbox redelivers (04 §4.4).
      trip("indexing item " + document.itemId(), unreachable);
    }
  }

  /**
   * Takes one item's document out.
   *
   * @param tenantId whose item
   * @param itemId the item
   */
  @Override
  public void remove(UUID tenantId, UUID itemId) {
    try {
      client.delete(request -> request.index(ALIAS).id(itemId.toString()));
      brokenSince = 0;
    } catch (IOException | RuntimeException unreachable) {
      trip("removing item " + itemId, unreachable);
    }
  }

  /**
   * Finds the matching items: the text here, everything else in the database.
   *
   * @param query what to look for
   * @return the ids and where the next page resumes
   */
  @Override
  public Hits find(Query query) {
    Query narrowed = narrowed(query);
    return narrowed == null
        ? new Hits(List.of(), java.util.Optional.empty())
        : relational.find(narrowed);
  }

  /**
   * Counts one dimension over the same two-step query.
   *
   * @param query what to count over
   * @param dimension what to count
   * @param locationRoot the place whose children are counted, for {@code location} only
   * @return the counts
   */
  @Override
  public Facet facet(Query query, String dimension, UUID locationRoot) {
    Query narrowed = narrowed(query);
    return narrowed == null
        ? new Facet(dimension, List.of())
        : relational.facet(narrowed, dimension, locationRoot);
  }

  /**
   * Turns the query's text into an id restriction, leaving the rest alone.
   *
   * @param query the query as it arrived
   * @return the same query with the text matched and removed, or {@code null} when the text matched
   *     nothing at all — which is not the same as "no restriction" and must not be read as one
   */
  private Query narrowed(Query query) {
    if (query.text() == null || query.text().isBlank()) {
      return query;
    }
    List<UUID> hits = matches(query);
    if (hits.isEmpty()) {
      return null;
    }
    List<UUID> restricted;
    if (query.itemIds() == null || query.itemIds().isEmpty()) {
      restricted = hits;
    } else {
      Set<UUID> already = new LinkedHashSet<>(query.itemIds());
      restricted = hits.stream().filter(already::contains).toList();
      if (restricted.isEmpty()) {
        return null;
      }
    }
    // The text is spent: it has become this id list, and asking the database to
    // match it again would apply the weaker matcher on top of the better one.
    return new Query(
        "",
        query.language(),
        query.locationIds(),
        query.typeVersionIds(),
        restricted,
        query.after(),
        query.sort(),
        query.filters(),
        query.limit());
  }

  /**
   * Every item whose document matches the text, within the tenant.
   *
   * <p>All of them and not one page: the caller pages by keyset over the database afterwards, and a
   * page taken here would be a page of a different ordering. Collected with {@code search_after}
   * over the document id, which is stable and needs no scroll context.
   *
   * <p>The tenant term is a <b>filter</b> on every query and not a convention. ADR-0008 requires it
   * and {@code OpenSearchIsolationIT} checks that a query without it is not possible from here.
   *
   * @param query what to match
   * @return the matching ids, most relevant first
   */
  private List<UUID> matches(Query query) {
    UUID tenantId = de.greluc.homeinv.platform.TenantContext.require();
    List<String> fields = fieldsFor(query.language());
    List<UUID> found = new ArrayList<>();
    String after = null;
    try {
      while (true) {
        String resumeAt = after;
        SearchResponse<Void> response =
            client.search(
                request -> {
                  request
                      .index(ALIAS)
                      .size(PAGE)
                      .source(source -> source.fetch(false))
                      .query(
                          q ->
                              q.bool(
                                  b ->
                                      b.filter(
                                              f ->
                                                  f.term(
                                                      t ->
                                                          t.field("tenantId")
                                                              .value(
                                                                  FieldValue.of(
                                                                      tenantId.toString()))))
                                          .must(
                                              m ->
                                                  m.multiMatch(
                                                      mm ->
                                                          mm.query(query.text()).fields(fields)))))
                      .sort(s -> s.field(f -> f.field("itemId").order(SortOrder.Asc)));
                  if (resumeAt != null) {
                    request.searchAfter(FieldValue.of(resumeAt));
                  }
                  return request;
                },
                Void.class);

        var hits = response.hits().hits();
        if (hits.isEmpty()) {
          break;
        }
        hits.forEach(hit -> found.add(UUID.fromString(hit.id())));
        if (hits.size() < PAGE) {
          break;
        }
        after = found.getLast().toString();
      }
      brokenSince = 0;
      return found;
    } catch (IOException | RuntimeException unreachable) {
      trip("matching '" + query.text() + "'", unreachable);
      // Nothing is returned as "no hits" here: the caller asked whether this
      // engine is available before calling, and an empty answer would be a
      // silent wrong result. The service catches this and falls back.
      throw new IllegalStateException("OpenSearch did not answer", unreachable);
    }
  }

  /**
   * Which analysed fields a language searches.
   *
   * <p>Six fields, which is what REQ-SRCH-011 asks for, each in the language's own analyser plus
   * the unanalysed one — a part number is not German and not English, and the standard field is
   * where it still matches.
   *
   * @param language {@code de} or {@code en}, already folded by the service that took it from the
   *     request — compared exactly here, because a fold next to a comparison is a habit worth not
   *     having even where it is harmless
   * @return the field names, with the name weighted above the rest
   */
  private static List<String> fieldsFor(String language) {
    String analysed = "en".equals(language) ? "en" : "de";
    return List.of(
        "name." + analysed + "^3",
        "name^2",
        "description." + analysed,
        "notes." + analysed,
        "attributeValues",
        "tags");
  }

  /**
   * The document as OpenSearch stores it.
   *
   * <p>A map rather than the record, so the wire format is written here and read here: the record
   * is this application's shape and the mapping is the index's. It also keeps the client's Jackson
   * 2 away from {@code Instant}, which it would need a module to render.
   *
   * @param document what to store
   * @return the body
   */
  private static Map<String, Object> bodyOf(SearchDocument document) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", document.tenantId().toString());
    body.put("itemId", document.itemId().toString());
    body.put("itemTypeVersionId", document.itemTypeVersionId().toString());
    body.put("name", document.name());
    body.put("description", document.description());
    body.put("notes", document.notes());
    body.put("attributeValues", document.attributeValues());
    body.put("tags", document.tags());
    body.put("locationId", document.locationId() == null ? null : document.locationId().toString());
    body.put("locationPath", document.locationPath().stream().map(UUID::toString).toList());
    body.put("createdAt", document.createdAt().toString());
    body.put("updatedAt", document.updatedAt().toString());
    return body;
  }

  /**
   * Records a failure and opens the circuit.
   *
   * @param what was being attempted, for the log line
   * @param cause what went wrong
   */
  private void trip(String what, Exception cause) {
    brokenSince = System.currentTimeMillis();
    log.warn("OpenSearch failed while {}; falling back for {} ms", what, CIRCUIT_MILLIS, cause);
  }

  /**
   * When the last failure was, for a test that has to know the circuit is open.
   *
   * @return the timestamp, or {@code Instant#EPOCH} when nothing has failed
   */
  Instant brokenSince() {
    long broken = brokenSince;
    return broken == 0 ? Instant.EPOCH : Instant.ofEpochMilli(broken);
  }
}
