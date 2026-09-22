/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Whether a regular expression a tenant wrote may be used at all (REQ-SEC-035).
 *
 * <h2>What this is defending against</h2>
 *
 * <p>A field definition may carry a {@code pattern}, and that pattern is <b>data one tenant
 * supplies and every item of that tenant is then matched against</b>. Java's regular expressions
 * backtrack, so {@code (a+)+$} against thirty {@code a}s and a {@code b} takes longer than the heat
 * death of a request: one administrator, typing one field definition, can make every write of their
 * own tenant hang — and a shared thread pool means it does not stay their own problem.
 *
 * <p>This is the <b>first</b> of the two lines REQ-SEC-035 asks for, taken when the definition is
 * saved. The second is a time limit when the pattern actually runs, which is where a shape nobody
 * anticipated is still stopped.
 *
 * <h2>Why refusing is allowed to be blunt</h2>
 *
 * <p>Deciding whether an arbitrary regular expression backtracks catastrophically means building
 * its automaton, and a rule that got it right in every case would be a small library nobody here
 * would maintain. So this refuses <b>shapes</b>, conservatively:
 *
 * <ul>
 *   <li>an unbounded repetition of a group that itself repeats without bound — {@code (a+)+},
 *       {@code (a*)*}, {@code (\d{2,}){3,}} — which is the classic form and the one every published
 *       example is;
 *   <li>an unbounded repetition of an alternation whose branches can begin with the same character
 *       — {@code (a|ab)*}, {@code (\d|\w)+} — which is the same explosion written differently;
 *   <li>anything over {@value #MAX_LENGTH} characters, because a field validation pattern is not a
 *       parser and the length is the cheapest bound there is;
 *   <li>anything the JDK will not compile, which would otherwise fail later and somewhere else.
 * </ul>
 *
 * <p>It therefore refuses patterns that are in fact harmless: {@code ((ab)|c)+} is safe and is
 * refused, because the first branch begins with a group this does not look inside. That is the
 * trade taken deliberately — the cost is a tenant rewriting a rare pattern and being told why, and
 * the cost of the other mistake is an instance that stops answering.
 *
 * <p>No framework, no database: this is the rule itself.
 */
public final class PatternSafety {

  /** The longest pattern a field definition may carry. */
  public static final int MAX_LENGTH = 200;

  /** A rule, not a thing to instantiate. Empty, so JaCoCo filters it out as it does the others. */
  private PatternSafety() {}

  /**
   * Whether this pattern may be stored in a field definition.
   *
   * @param pattern what the tenant wrote, or {@code null} for a field that declares none
   * @return the reason it is refused, in a sentence an administrator can act on, or empty when it
   *     may be used
   */
  public static Optional<String> refuses(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return Optional.empty();
    }
    if (pattern.length() > MAX_LENGTH) {
      return Optional.of(
          "A pattern is at most "
              + MAX_LENGTH
              + " characters; this one is "
              + pattern.length()
              + ". A field validation pattern is a rule about one value, not a parser.");
    }
    try {
      // CodeQL flags this as regular expression injection (java/regex-injection),
      // and it is right about the fact and wrong about the conclusion: the
      // pattern IS user-provided, and compiling user-provided patterns is what
      // this class exists to do. A field definition may carry a `pattern`
      // (REQ-CORE-022), and the only way to find out whether what a tenant wrote
      // is a regular expression at all is to ask the engine that will run it.
      // There is no sanitiser: `Pattern.quote` would turn the rule into a
      // literal and make the feature meaningless.
      //
      // What the query is actually about -- a caller spending unbounded time in
      // a regular expression they chose -- is answered, and answered twice,
      // which is what REQ-SEC-035 asks for:
      //
      //   * the length is bounded above, before this line;
      //   * the shape is refused above for the forms that backtrack
      //     catastrophically, and this call's result is discarded rather than
      //     used, so nothing here runs the pattern against anything;
      //   * every actual MATCH runs under a 100 ms budget
      //     (`BoundedRegularExpressions`), which is the line that does not depend
      //     on having anticipated the pattern.
      //
      // Compilation itself is linear in the pattern's length, and that length is
      // at most 200 characters by the check above.
      Pattern.compile(pattern); // codeql[java/regex-injection]
    } catch (PatternSyntaxException invalid) {
      return Optional.of("Not a valid regular expression: " + invalid.getDescription());
    }
    return structure(pattern);
  }

  /**
   * The shape checks, over one left-to-right pass.
   *
   * @param pattern the compiled-clean pattern
   * @return the reason it is refused, or empty
   */
  private static Optional<String> structure(String pattern) {
    List<Group> open = new ArrayList<>();
    // The outermost level is a group too, so that a quantifier at the top has
    // somewhere to record itself; it is never quantified, so it never refuses.
    open.add(new Group(0));

    for (int index = 0; index < pattern.length(); index++) {
      char current = pattern.charAt(index);
      switch (current) {
        case '\\' -> {
          // An escape covers the next character, whatever it is. Skipping it is
          // what keeps `\*` and `\(` from being read as structure.
          index++;
        }
        case '[' -> {
          index = endOfClass(pattern, index);
        }
        case '(' -> open.add(new Group(index));
        case ')' -> {
          if (open.size() == 1) {
            // Unbalanced, which `Pattern.compile` already refused; reaching here
            // would mean the two disagree, so stop rather than guess.
            return Optional.empty();
          }
          Group closed = open.removeLast();
          Optional<String> refusal = quantified(pattern, index, closed);
          if (refusal.isPresent()) {
            return refusal;
          }
        }
        case '|' -> open.getLast().sawAlternation(index);
        case '*', '+' -> open.getLast().sawUnbounded();
        case '{' -> {
          int close = pattern.indexOf('}', index);
          if (close > index && unbounded(pattern.substring(index + 1, close))) {
            open.getLast().sawUnbounded();
          }
          if (close > index) {
            index = close;
          }
        }
        default -> {
          // A literal. Nothing about it changes the shape of the group.
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Judges a group that has just closed, by what follows it and what it contains.
   *
   * @param pattern the whole pattern
   * @param closingParen where the group ended
   * @param group what it contained
   * @return the reason it is refused, or empty
   */
  private static Optional<String> quantified(String pattern, int closingParen, Group group) {
    if (!repeatsWithoutBound(pattern, closingParen)) {
      return Optional.empty();
    }
    if (group.unbounded) {
      return Optional.of(
          "A repetition inside a repetition — like (a+)+ — can take exponentially long on input "
              + "that nearly matches. Repeat one of the two, not both.");
    }
    if (group.alternation && overlapping(pattern, group)) {
      return Optional.of(
          "A repeated alternation whose branches can match the same text — like (a|ab)* — can take "
              + "exponentially long on input that nearly matches. Make the branches begin "
              + "differently.");
    }
    return Optional.empty();
  }

  /**
   * Whether a quantifier without an upper bound follows this position.
   *
   * @param pattern the whole pattern
   * @param position the index of the closing parenthesis
   * @return true for {@code *}, {@code +} and {@code {n,\}}
   */
  private static boolean repeatsWithoutBound(String pattern, int position) {
    int next = position + 1;
    if (next >= pattern.length()) {
      return false;
    }
    char quantifier = pattern.charAt(next);
    if (quantifier == '*' || quantifier == '+') {
      return true;
    }
    if (quantifier != '{') {
      return false;
    }
    int close = pattern.indexOf('}', next);
    return close > next && unbounded(pattern.substring(next + 1, close));
  }

  /**
   * Whether a {@code {…}} body names no upper bound.
   *
   * @param body what stands between the braces
   * @return true for {@code 2,} and false for {@code 2} or {@code 2,5}
   */
  private static boolean unbounded(String body) {
    return body.endsWith(",");
  }

  /**
   * Whether two branches of this group can begin with the same character.
   *
   * <p>Only the first token of each branch is read, and only the kinds that can be read without
   * building an automaton: a literal, an escaped literal, a character class, and {@code .}, which
   * overlaps everything. Anything else — a nested group, most often — is treated as overlapping,
   * which is the conservative answer and the one that refuses rather than admits.
   *
   * @param pattern the whole pattern
   * @param group the group that closed
   * @return true when two branches share a possible first character
   */
  private static boolean overlapping(String pattern, Group group) {
    Set<String> seen = new LinkedHashSet<>();
    for (int start : group.branchStarts(pattern)) {
      String first = firstToken(pattern, start);
      if (first == null || !seen.add(first)) {
        return true;
      }
      if (".".equals(first)) {
        return true;
      }
    }
    // Two classes are compared by their text and not by their contents: `[a-z]`
    // twice is caught above, `[a-z]` against `[b]` is not. The time limit is
    // what covers what this does not.
    return false;
  }

  /**
   * The first token of a branch, as a comparable string.
   *
   * @param pattern the whole pattern
   * @param start where the branch begins
   * @return the token, or {@code null} when it is a kind this does not read
   */
  private static String firstToken(String pattern, int start) {
    if (start >= pattern.length()) {
      return null;
    }
    char first = pattern.charAt(start);
    return switch (first) {
      case '(' -> null;
      case '[' -> pattern.substring(start, Math.min(endOfClass(pattern, start) + 1, pattern.length()));
      case '\\' ->
          start + 1 < pattern.length() ? pattern.substring(start, start + 2) : null;
      case '^', '$' -> null;
      default -> String.valueOf(first);
    };
  }

  /**
   * Where a character class ends, honouring an escape and a leading {@code ]}.
   *
   * @param pattern the whole pattern
   * @param open the index of the opening bracket
   * @return the index of the closing bracket, or the last index when there is none
   */
  private static int endOfClass(String pattern, int open) {
    for (int index = open + 1; index < pattern.length(); index++) {
      char current = pattern.charAt(index);
      if (current == '\\') {
        index++;
      } else if (current == ']' && index > open + 1) {
        return index;
      }
    }
    return pattern.length() - 1;
  }

  /** One level of parentheses, and what has been seen inside it. */
  private static final class Group {

    /** Where the group opened, so a branch start can be computed from it. */
    private final int opensAt;

    /** Whether it contains a quantifier with no upper bound. */
    private boolean unbounded;

    /** Whether it contains a top-level {@code |}. */
    private boolean alternation;

    /** Where each top-level {@code |} stands, which is where the next branch begins. */
    private final List<Integer> bars = new ArrayList<>();

    private Group(int opensAt) {
      this.opensAt = opensAt;
    }

    private void sawUnbounded() {
      this.unbounded = true;
    }

    private void sawAlternation(int index) {
      this.alternation = true;
      this.bars.add(index);
    }

    /**
     * Where each branch of this group starts.
     *
     * @param pattern the whole pattern
     * @return the indices, the first of them just inside the opening parenthesis
     */
    private List<Integer> branchStarts(String pattern) {
      List<Integer> starts = new ArrayList<>();
      starts.add(bodyStart(pattern));
      bars.forEach(bar -> starts.add(bar + 1));
      return starts;
    }

    /**
     * Where the body of this group begins, past whatever prefix it opens with.
     *
     * <p>{@code (a…)} starts one character in. {@code (?:…)}, {@code (?=…)} and {@code (?!…)} start
     * two. {@code (?<=…)} and {@code (?<!…)} start three. {@code (?<name>…)} starts after the
     * {@code >}, and the name is as long as the tenant made it — which is what this exists for:
     * counting a fixed number of characters put the first branch of {@code (?<code>a|ab)*} in the
     * middle of the name.
     *
     * @param pattern the whole pattern
     * @return the index of the first character of the first branch
     */
    private int bodyStart(String pattern) {
      int first = opensAt + 1;
      if (first >= pattern.length() || pattern.charAt(first) != '?') {
        return first;
      }
      int afterQuestion = first + 1;
      if (afterQuestion >= pattern.length()) {
        return first;
      }
      if (pattern.charAt(afterQuestion) == '<') {
        int next = afterQuestion + 1;
        if (next < pattern.length() && (pattern.charAt(next) == '=' || pattern.charAt(next) == '!')) {
          return next + 1;
        }
        int close = pattern.indexOf('>', afterQuestion);
        return close < 0 ? first : close + 1;
      }
      return afterQuestion + 1;
    }

  }
}
