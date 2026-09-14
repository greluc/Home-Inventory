/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Map;

/**
 * Indexes documents and answers queries over them (09 §9.2).
 *
 * <p>OpenSearch and PostgreSQL are both in the core image — both are part of the deployment and
 * neither leaves it. Meilisearch and Typesense are the plugins this port exists for.
 *
 * <p><b>An index may fail but must never lie.</b> What comes back here is a list of document ids
 * and nothing else: the core re-loads every hit from PostgreSQL under row-level security before
 * showing it, so a stale or poisoned index can cost a result but cannot leak one across a tenant
 * boundary. That is why this port returns no content, and it is not an oversight to be optimised
 * away.
 *
 * <p>Stage 1 (REQ-SRCH-005).
 */
public interface SearchIndex {

  /**
   * The key this engine is known by, lowercase and stable — {@code opensearch}, {@code postgres}.
   *
   * @return the key
   */
  String engineKey();

  /**
   * Whether the engine is answering right now.
   *
   * <p>Asked before a query, and cheap by contract: a call that takes a second to say "yes" has
   * already cost more than the query it was protecting. An implementation caches its own answer.
   *
   * @param context who is asking
   * @return {@code true} when a query would be served. {@code false} sends the core to its
   *     fallback, which is a degraded answer and not an error (REQ-SRCH-007)
   */
  boolean available(CallContext context);

  /**
   * Adds a document or replaces the one with the same id.
   *
   * <p>Idempotent, because delivery is at-least-once: indexing the same document twice must leave
   * the index as one write would.
   *
   * @param context who it is for
   * @param document what to index
   * @throws de.greluc.homeinv.plugin.api.PluginException when the write failed
   */
  void index(CallContext context, Document document);

  /**
   * Removes a document.
   *
   * <p>Removing one that is not there succeeds, for the same reason {@link BlobStore#delete} does.
   *
   * @param context who it is for
   * @param documentId which document
   * @throws de.greluc.homeinv.plugin.api.PluginException when the write failed
   */
  void remove(CallContext context, String documentId);

  /**
   * Answers a query.
   *
   * @param context who is asking. An implementation partitions by the tenant and must never answer
   *     across two, even though the core checks again afterwards
   * @param query what to find
   * @return the matching document ids, in the order asked for
   * @throws de.greluc.homeinv.plugin.api.PluginException when the engine could not answer
   */
  Hits search(CallContext context, Query query);

  /**
   * Counts how the matches of a query distribute over one dimension.
   *
   * @param context who is asking
   * @param query the same query the hits came from, so that the counts belong to the list on screen
   * @param dimension what to count over, as the core names it
   * @param maxBuckets how many buckets at most. The rest are not returned, and their absence is why
   *     a facet is a hint rather than a census
   * @return the buckets, largest first
   * @throws de.greluc.homeinv.plugin.api.PluginException when the engine could not answer
   */
  Facet facet(CallContext context, Query query, String dimension, int maxBuckets);

  /**
   * One indexable document.
   *
   * @param documentId the id the core knows it by, and the id hits come back as
   * @param language the document's language as an IETF tag, which decides the analyser. Empty lets
   *     the engine choose, which is worse than saying
   * @param text the free-text fields, keyed by field name — the name, the notes, the attribute
   *     values as text
   * @param keywords the exact-match fields, keyed by field name: tag keys, type keys, states. Not
   *     analysed, so {@code Bosch} and {@code bosch} are two keywords unless the caller folded them
   * @param paths hierarchical values, one string per level chain, for subtree queries — a location
   *     path is one of these
   * @param numbers numeric fields for range queries and sorting, keyed by field name, as decimal
   *     text. Not {@code Double}, for the reason {@link de.greluc.homeinv.plugin.api.MoneyValue}
   *     gives: one of these fields is a price, and a binary fraction has lost the cents before the
   *     engine sees it. An engine maps the text to its own numeric type at its own boundary
   */
  record Document(
      String documentId,
      String language,
      Map<String, String> text,
      Map<String, List<String>> keywords,
      List<String> paths,
      Map<String, String> numbers) {}

  /**
   * One query.
   *
   * @param text the free text, or empty for a query that filters without searching
   * @param language the searcher's language, for the analyser
   * @param filters the narrowings, all of which must hold
   * @param cursor an opaque position from a previous {@link Hits}, or empty for the first page.
   *     Opaque to the core too: it is the engine's to mint and to read
   * @param limit how many at most
   * @param sortField what to sort by, as the core names it, or empty for relevance
   * @param ascending the direction, ignored when sorting by relevance
   */
  record Query(
      String text,
      String language,
      List<Filter> filters,
      String cursor,
      int limit,
      String sortField,
      boolean ascending) {}

  /**
   * One narrowing.
   *
   * @param field which field, as {@link Document} names it
   * @param operator what to do with the values: {@code eq}, {@code ne}, {@code lt}, {@code lte},
   *     {@code gt}, {@code gte}, {@code contains}, {@code subtree}. A closed set — an engine that
   *     meets an operator it does not know refuses the query rather than ignoring the filter, which
   *     would answer with more than was asked for
   * @param values the values, one for most operators and two for a range
   */
  record Filter(String field, String operator, List<String> values) {}

  /**
   * What a query matched.
   *
   * @param documentIds the ids, in order
   * @param total how many matched altogether, or {@code -1} when the engine does not count cheaply.
   *     Never an estimate presented as a count
   * @param cursor the position to continue from, or empty when this was the last page
   */
  record Hits(List<String> documentIds, long total, String cursor) {}

  /**
   * How matches distribute over one dimension.
   *
   * @param dimension what was counted over
   * @param buckets the counts, largest first
   * @param complete whether every value fitted within {@code maxBuckets}. {@code false} means the
   *     tail was cut, and the core says so rather than showing a partial list as a whole one
   */
  record Facet(String dimension, List<Bucket> buckets, boolean complete) {}

  /**
   * One value and how often it occurs.
   *
   * @param key the value, exactly as it is indexed
   * @param count how many of the query's matches carry it
   */
  record Bucket(String key, long count) {}
}
