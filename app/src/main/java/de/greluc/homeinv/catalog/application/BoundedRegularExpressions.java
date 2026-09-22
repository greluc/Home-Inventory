/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.application;

import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;
import com.networknt.schema.regex.RegularExpressions;
import de.greluc.homeinv.platform.LogSafe;
import java.time.Duration;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a tenant's {@code pattern} with a time limit (REQ-SEC-035).
 *
 * <h2>Why a limit is needed at all</h2>
 *
 * <p>{@link de.greluc.homeinv.catalog.domain.PatternSafety} refuses the shapes that are known to
 * explode, when the field definition is saved. It refuses <b>shapes</b>, though, and the set of
 * regular expressions that backtrack catastrophically is larger than the set of shapes anybody has
 * written down. This is the line that does not depend on having anticipated the pattern: however
 * long a match would take, it stops.
 *
 * <h2>How, given that {@code java.util.regex} has no timeout</h2>
 *
 * <p>It has no timeout and it has something better: the matcher reads its input through
 * {@link CharSequence#charAt(int)}, once per step of the backtracking. A sequence that refuses to
 * answer after a deadline therefore stops the engine <b>inside</b> its loop, without a second
 * thread, without an interrupt and without anything to cancel. The check is made every {@value
 * #STRIDE} reads rather than every read, because {@code nanoTime} on every character would cost
 * more than the matching.
 *
 * <p>A pattern that runs out of budget is reported as <b>not matching</b>. That makes the value a
 * {@code 422} naming the field, which is what a tenant can act on — where letting the exception
 * escape would be a {@code 500} about a field definition they wrote themselves. The operator gets a
 * warning line naming the pattern, because a budget exceeded in production is either an attack or a
 * definition worth fixing, and both are worth knowing about.
 *
 * <h2>The two rewrites</h2>
 *
 * <p>The library's own JDK implementation rewrites {@code $} anchors and long-form character
 * properties before compiling, because JSON Schema's dialect of regular expressions is ECMAScript's
 * and not Java's. Both rewrites are applied here too — a guarded matcher that quietly accepted
 * different input from the unguarded one would be a difference nobody would find.
 */
@Slf4j
public final class BoundedRegularExpressions implements RegularExpressionFactory {

  /** How many characters are read between two clock readings. */
  private static final int STRIDE = 2048;

  /** How long one match may take. */
  private final Duration budget;

  /**
   * Builds the factory.
   *
   * @param budget how long a single match may run before it is abandoned
   */
  public BoundedRegularExpressions(Duration budget) {
    this.budget = budget;
  }

  @Override
  public RegularExpression getRegularExpression(String pattern) {
    Pattern compiled =
        Pattern.compile(
            RegularExpressions.replaceLongformCharacterProperties(
                RegularExpressions.replaceDollarAnchors(pattern)));
    return value -> matchesWithinBudget(compiled, value, pattern);
  }

  /**
   * Whether the value matches, giving up rather than running long.
   *
   * @param compiled the pattern
   * @param value the instance value being validated
   * @param source the pattern as the tenant wrote it, for the warning
   * @return true when it matches, false when it does not and false when it took too long
   */
  private boolean matchesWithinBudget(Pattern compiled, String value, String source) {
    Deadline input = new Deadline(value, System.nanoTime() + budget.toNanos());
    try {
      // `find` and not `matches`: JSON Schema's `pattern` is an unanchored
      // search, which is what the library's own implementation does and what
      // every generated schema here is written against.
      return compiled.matcher(input).find();
    } catch (BudgetExceeded exceeded) {
      // `LogSafe` because the pattern is a tenant's own text, and a pattern is
      // the one value here that somebody would write a newline into on purpose.
      log.warn(
          "A field pattern was abandoned after {}: it is either an attack or a definition worth "
              + "rewriting. Pattern: {}",
          budget,
          LogSafe.value(source));
      return false;
    }
  }

  /** Thrown from inside the matcher, which is the only place that can stop it. */
  private static final class BudgetExceeded extends RuntimeException {

    private BudgetExceeded() {
      // No message, no stack trace: it is control flow caught two frames up and
      // never shown to anybody.
      super(null, null, false, false);
    }
  }

  /**
   * The value being matched, which stops answering once the budget is gone.
   *
   * <p>Deliberately not a record: it counts reads, so it has state that a record could not hold.
   */
  private static final class Deadline implements CharSequence {

    private final CharSequence delegate;
    private final long deadlineNanos;
    private int reads;

    private Deadline(CharSequence delegate, long deadlineNanos) {
      this.delegate = delegate;
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    public int length() {
      return delegate.length();
    }

    @Override
    public char charAt(int index) {
      if (++reads % STRIDE == 0 && System.nanoTime() > deadlineNanos) {
        throw new BudgetExceeded();
      }
      return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      // Shares the counter and the deadline: a subsequence the engine takes is
      // part of the same match and has the same budget.
      Deadline part = new Deadline(delegate.subSequence(start, end), deadlineNanos);
      part.reads = reads;
      return part;
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
