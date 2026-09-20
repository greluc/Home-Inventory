/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.infrastructure.AttributeIndexQueries;
import de.greluc.homeinv.inventory.infrastructure.AttributeProjector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares the derived side table with the truth, and rebuilds it (REQ-NFR-073, ADR-0004).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code item_attr_index} is maintained by the application in the same transaction as the write
 * (07 §7.3), which is what makes an attribute filter transactionally exact. A projection maintained
 * by code is a projection a code change can get wrong — and the failure is silent: filters simply
 * stop finding things, or find things that no longer match. <b>Derived stores may fail and may never
 * lie</b>, and that is a property only if something checks.
 *
 * <h2>The expectation is computed by the same code that writes it</h2>
 *
 * <p>{@link FieldDefinitionView#projected()} decides what belongs in the table, and this asks it
 * rather than re-deriving the rule in SQL. A second copy of "what gets projected" would drift from
 * the first, and then the reconciliation would report deviations that are its own.
 *
 * <p>That is also why the rebuild goes through {@link AttributeProjector}: 07 §7.3 says a rebuild
 * runs "through the same code path as a normal write, so there is no second truth", and an
 * {@code INSERT … SELECT} would be exactly that second truth.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttributeIndexReconciliation {

  /** How many items are compared at a time. Large enough to be few queries, small enough to hold. */
  private static final int PAGE = 500;

  /**
   * How many items one run visits, per tenant.
   *
   * <p>A bound rather than "everything", because a run that cannot finish is a run that reports
   * nothing: it would be killed on its next startup and the count would never be published. A tenant
   * with more items than this has the rest compared by the following night, which is the shape
   * `REQ-NFR-073`'s "nightly" allows and a stall is not.
   */
  private static final int MOST_PER_RUN = 100_000;

  private final AttributeIndexQueries items;
  private final AttributeProjector projector;
  private final TypeRegistry types;
  private final ObjectMapper mapper;

  /**
   * Counts the items whose projection does not match their attributes.
   *
   * <p>Read-only: a reconciliation that repaired what it found would report zero deviations for ever
   * and hide the defect that produced them. Finding and fixing are separate acts, and
   * {@link #rebuild()} is the other one.
   *
   * @return how many items disagree, for the tenant the context names
   */
  @Transactional(readOnly = true)
  public long deviations() {
    long deviating = 0;
    UUID after = null;
    int visited = 0;

    while (visited < MOST_PER_RUN) {
      List<AttributeIndexQueries.Projectable> page = items.page(after, PAGE);
      if (page.isEmpty()) {
        break;
      }
      Map<UUID, Set<String>> held =
          items.projectedKeys(page.stream().map(AttributeIndexQueries.Projectable::id).toList());

      for (AttributeIndexQueries.Projectable item : page) {
        Set<String> expected = expectedKeys(item);
        Set<String> actual = held.getOrDefault(item.id(), Set.of());
        if (!expected.equals(actual)) {
          // The item, not the rows: an item whose projection is wrong is one
          // thing wrong, and counting rows would make one bad item look like
          // eight.
          deviating++;
          log.warn(
              "The attribute index of item {} holds {} and should hold {}",
              item.id(),
              actual,
              expected);
        }
      }
      after = page.get(page.size() - 1).id();
      visited += page.size();
    }
    return deviating;
  }

  /**
   * Re-projects every item, through the write path.
   *
   * <p>The whole table for this tenant, not the deviating rows: a rebuild exists for the case where
   * nobody knows what is wrong, and a selective one would carry the same assumption that produced
   * the damage.
   *
   * @return how many items were re-projected
   */
  @Transactional
  public long rebuild() {
    long written = 0;
    UUID after = null;

    while (written < MOST_PER_RUN) {
      List<AttributeIndexQueries.Projectable> page = items.page(after, PAGE);
      if (page.isEmpty()) {
        break;
      }
      for (AttributeIndexQueries.Projectable item : page) {
        // The same call an ordinary write makes, in a transaction, which is what
        // `Propagation.MANDATORY` on the projector insists on.
        projector.project(item.id(), item.typeVersionId(), item.attributes());
        written++;
      }
      after = page.get(page.size() - 1).id();
    }
    log.info("REINDEX_ATTRIBUTES re-projected {} item(s)", written);
    return written;
  }

  /**
   * What the side table should hold for one item.
   *
   * @param item the item
   * @return the field keys, sorted
   */
  private Set<String> expectedKeys(AttributeIndexQueries.Projectable item) {
    Set<String> keys = new TreeSet<>();
    if (item.attributes() == null || item.attributes().isBlank()) {
      return keys;
    }
    JsonNode attributes = mapper.readTree(item.attributes());
    for (FieldDefinitionView field : types.fields(item.typeVersionId())) {
      if (!field.projected()) {
        continue;
      }
      JsonNode value = attributes.get(field.key());
      if (value == null || value.isNull()) {
        continue;
      }
      keys.add(field.key());
    }
    return keys;
  }
}
