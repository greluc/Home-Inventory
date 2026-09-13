/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.domain;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether an inheriting type's field is at least as strict as the one it overrides (REQ-CORE-024).
 *
 * <h2>What "tighten" means, member by member</h2>
 *
 * <p>A value the parent accepts may be refused by the child; a value the parent refuses may never be
 * accepted by it. That single rule decides every member:
 *
 * <ul>
 *   <li>the data type may not change at all — a different type is a different field wearing the same
 *       name, and the items written against the parent would stop validating;
 *   <li>{@code required} may go from false to true and not back;
 *   <li>{@code min} may rise, {@code max} may fall, {@code minLength} may rise, {@code maxLength}
 *       may fall — and a bound the parent declares may not be dropped;
 *   <li>a {@code pattern} may be added where the parent has none, and where the parent has one it
 *       must be repeated exactly. Deciding whether one regular expression accepts a subset of
 *       another is undecidable in general, so the honest options were "equal" or "anything goes";
 *   <li>a declared {@code unit} may be added and not removed or changed — a field that accepted only
 *       euros must not start accepting dollars;
 *   <li>the value list may not change: a different list is a different set of permitted values, and
 *       nothing here can tell whether it is a subset.
 * </ul>
 *
 * <p>No framework, no database: this is the rule itself, and it is checked at publish time by the
 * adapter that has both definitions in hand.
 */
public final class FieldTightening {

  private FieldTightening() {}

  /**
   * Checks a child's field against the one it inherits.
   *
   * @param child the overriding definition
   * @param parent the definition it overrides
   * @return the name of the first property the child widens, or empty when it widens nothing
   */
  public static Optional<String> widens(FieldDefinitionView child, FieldDefinitionView parent) {
    if (child.dataType() != parent.dataType()) {
      return Optional.of("the data type");
    }
    if (parent.required() && !child.required()) {
      return Optional.of("required");
    }
    if (!Objects.equals(child.valueListId(), parent.valueListId())) {
      return Optional.of("the value list");
    }

    FieldConstraints tighter = child.constraints() == null ? FieldConstraints.NONE : child.constraints();
    FieldConstraints looser = parent.constraints() == null ? FieldConstraints.NONE : parent.constraints();

    if (dropped(tighter.min(), looser.min()) || lower(tighter.min(), looser.min())) {
      return Optional.of("min");
    }
    if (dropped(tighter.max(), looser.max()) || higher(tighter.max(), looser.max())) {
      return Optional.of("max");
    }
    if (droppedLength(tighter.minLength(), looser.minLength())
        || lowerLength(tighter.minLength(), looser.minLength())) {
      return Optional.of("minLength");
    }
    if (droppedLength(tighter.maxLength(), looser.maxLength())
        || higherLength(tighter.maxLength(), looser.maxLength())) {
      return Optional.of("maxLength");
    }
    if (looser.pattern() != null && !looser.pattern().equals(tighter.pattern())) {
      return Optional.of("the pattern");
    }
    if (looser.unit() != null && !looser.unit().equals(tighter.unit())) {
      return Optional.of("the unit");
    }
    return Optional.empty();
  }

  /**
   * Whether a bound the parent declared is absent from the child.
   *
   * @param childBound the child's bound
   * @param parentBound the parent's bound
   * @return true when the parent had one and the child has none
   */
  private static boolean dropped(BigDecimal childBound, BigDecimal parentBound) {
    return parentBound != null && childBound == null;
  }

  /**
   * Whether the child's lower bound admits values the parent refuses.
   *
   * @param childBound the child's {@code min}
   * @param parentBound the parent's {@code min}
   * @return true when both exist and the child's is smaller
   */
  private static boolean lower(BigDecimal childBound, BigDecimal parentBound) {
    return childBound != null && parentBound != null && childBound.compareTo(parentBound) < 0;
  }

  /**
   * Whether the child's upper bound admits values the parent refuses.
   *
   * @param childBound the child's {@code max}
   * @param parentBound the parent's {@code max}
   * @return true when both exist and the child's is larger
   */
  private static boolean higher(BigDecimal childBound, BigDecimal parentBound) {
    return childBound != null && parentBound != null && childBound.compareTo(parentBound) > 0;
  }

  /**
   * Whether a length bound the parent declared is absent from the child.
   *
   * @param childBound the child's bound
   * @param parentBound the parent's bound
   * @return true when the parent had one and the child has none
   */
  private static boolean droppedLength(Integer childBound, Integer parentBound) {
    return parentBound != null && childBound == null;
  }

  /**
   * Whether the child's minimum length admits values the parent refuses.
   *
   * @param childBound the child's {@code minLength}
   * @param parentBound the parent's {@code minLength}
   * @return true when both exist and the child's is smaller
   */
  private static boolean lowerLength(Integer childBound, Integer parentBound) {
    return childBound != null && parentBound != null && childBound < parentBound;
  }

  /**
   * Whether the child's maximum length admits values the parent refuses.
   *
   * @param childBound the child's {@code maxLength}
   * @param parentBound the parent's {@code maxLength}
   * @return true when both exist and the child's is larger
   */
  private static boolean higherLength(Integer childBound, Integer parentBound) {
    return childBound != null && parentBound != null && childBound > parentBound;
  }
}
