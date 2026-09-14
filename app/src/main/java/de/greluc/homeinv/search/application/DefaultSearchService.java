/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.search.api.SearchIndex;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.search.api.SearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search, answered at stage 0 by PostgreSQL full text.
 *
 * <p>The block owns the question and not the table: the rows come from {@code inventory} through its
 * published {@link ItemSearchQuery} port, because no block reads another block's schema
 * (REQ-NFR-021). When OpenSearch arrives at stage 1 it becomes a second implementation behind the
 * same service, and callers do not change.
 */
@Service
@RequiredArgsConstructor
public class DefaultSearchService implements SearchService {

  /** More than this per page and the response stops being a page (REQ-SEC-065's spirit). */
  private static final int MAX_LIMIT = 200;

  private static final int DEFAULT_LIMIT = 50;

  /**
   * Where the search is actually run (REQ-SRCH-005).
   *
   * <p>One today. When OpenSearch joins it, this becomes the ordered list of engines to try and the
   * fallback becomes a decision made here rather than in an adapter — which is why the service asks
   * a port and not {@code ItemSearchQuery} directly, even while there is only one of them.
   */
  private final SearchIndex index;

  /** Turns the ids the index answered with back into rows (REQ-SRCH-007). */
  private final de.greluc.homeinv.inventory.api.ItemService items;

  private final CursorCodec cursors;

  @Override
  @Transactional(readOnly = true)
  public Page<de.greluc.homeinv.inventory.api.ItemView> query(SearchRequest request) {
    int limit = request.limit() <= 0 ? DEFAULT_LIMIT : Math.min(request.limit(), MAX_LIMIT);
    String language = request.language() == null ? "de" : request.language();
    String text = request.text() == null ? "" : request.text();

    List<UUID> locationIds =
        request.locationIds() == null ? List.of() : List.copyOf(request.locationIds());

    String fingerprint = fingerprintOf(text, language, locationIds);
    Optional<CursorCodec.Position> after =
        request.cursor() == null || request.cursor().isBlank()
            ? Optional.empty()
            // Throws when the cursor was tampered with or belongs to another
            // query. Rejecting is the point: an unverified cursor silently
            // resumes somewhere else (REQ-SEC-106, REQ-SRCH-009).
            : Optional.of(cursors.decode(request.cursor(), fingerprint));

    SearchIndex.Hits hits =
        index.find(new SearchIndex.Query(text, language, locationIds, after, limit));

    // The ids become rows here, through the ordinary read: row-level security and
    // the field visibility rules apply on the way out, which is what makes a
    // derived index safe to run at all (REQ-SRCH-007). An id that named a row
    // this tenant cannot see is simply absent, so a stale index costs a shorter
    // page and never somebody else's item.
    List<de.greluc.homeinv.inventory.api.ItemView> rows = items.byIds(hits.itemIds());

    String nextCursor =
        hits.last().map(position -> cursors.encode(position, fingerprint)).orElse(null);
    return Page.of(rows, nextCursor);
  }

  /**
   * A stable fingerprint of the query a cursor belongs to.
   *
   * <p>Hashed rather than carried in clear, so the cursor does not grow with the query and does not
   * echo the search text back through a URL that may end up in a log or a referrer header.
   *
   * @param text the query text
   * @param language the language
   * @param locationIds the location filter, sorted into the digest so that the same set in a
   *     different order is recognised as the same query
   * @return a hex digest identifying this query
   */
  private static String fingerprintOf(String text, String language, List<UUID> locationIds) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // The separator matters: without it, ("ab", "c") and ("a", "bc") would
      // hash alike, and a cursor from one search would be accepted by the other.
      // The location filter is part of the query, so it is part of what the
      // cursor is bound to. Without it a cursor from "everything in the cellar"
      // would resume a search of the whole tenant at the same row.
      String filter =
          locationIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
      byte[] hash =
          digest.digest(
              (text + " " + language + " " + filter).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
