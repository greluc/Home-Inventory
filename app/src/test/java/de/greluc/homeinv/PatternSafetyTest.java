/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.regex.RegularExpression;
import de.greluc.homeinv.catalog.application.BoundedRegularExpressions;
import de.greluc.homeinv.catalog.domain.PatternSafety;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Both lines of REQ-SEC-035, against the patterns that are published as examples of the attack.
 *
 * <h2>The two halves, and why neither alone would do</h2>
 *
 * <p>{@link PatternSafety} refuses the known shapes when a field definition is saved, so a tenant
 * is told what is wrong with their pattern while they are writing it. {@link
 * BoundedRegularExpressions} puts a clock on every match, so a shape nobody anticipated costs a
 * hundred milliseconds instead of a thread.
 *
 * <p>The second is the one that matters under attack and the first is the one that matters in
 * daily use, which is why the requirement asks for both and why this class tests both.
 *
 * <p>No container, no Spring.
 */
@DisplayName("A tenant's regular expression")
class PatternSafetyTest {

  @ParameterizedTest(name = "{0}")
  @DisplayName("is refused when it repeats something that already repeats")
  @ValueSource(strings = {"(a+)+", "(a*)*", "(a+)*", "([a-z]+)+", "(\\d{2,}){3,}", "^(x+x+)+y$"})
  void nestedQuantifiersAreRefused(String pattern) {
    assertThat(PatternSafety.refuses(pattern))
        .as("the published shape of the attack")
        .isPresent()
        .get()
        .asString()
        .contains("repetition inside a repetition");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("is refused when a repeated alternation can match the same text two ways")
  @ValueSource(strings = {"(a|a)*", "(a|ab)+", "(\\d|\\d)*", "(.|a)+"})
  void overlappingAlternationsAreRefused(String pattern) {
    assertThat(PatternSafety.refuses(pattern)).isPresent();
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("is allowed when it is an ordinary field rule")
  @ValueSource(
      strings = {
        "^[A-Z]{3}$",
        "\\d{4}-\\d{2}-\\d{2}",
        "^(cat|dog)+$",
        "[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+",
        "^ISBN-\\d{13}$",
        "a*b*",
        "(abc)+"
      })
  void ordinaryPatternsPass(String pattern) {
    // The point of the refusals above is that these still work. A guard that
    // refused half of what a tenant legitimately writes would be switched off.
    assertThat(PatternSafety.refuses(pattern)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("is read correctly through the constructs that hide structure")
  @ValueSource(
      strings = {
        // A quote block: everything between \Q and \E is literal, so this `)`
        // is not a closing parenthesis however much it looks like one.
        "\\Q)\\E",
        // A group that is not repeated at all cannot explode, whatever is in it.
        "(a+)d",
        // A `]` escaped inside a character class, which ends the class only if
        // the escape is missed.
        "[\\]]+",
        // Branches that begin with different things: a literal and a class.
        "(a|[xy])*"
      })
  void theseAreReadCorrectlyAndAllowed(String pattern) {
    assertThat(PatternSafety.refuses(pattern)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("is refused conservatively when a branch begins with something unreadable")
  @ValueSource(strings = {"((ab)|c)+", "(^a|b)+", "(?:a|ab)*", "(?<code>a|ab)*"})
  void whatCannotBeReadIsRefusedRatherThanAdmitted(String pattern) {
    // `((ab)|c)+` is in fact harmless and is refused anyway: the first branch
    // begins with a group, and reading inside it would mean building the
    // automaton this rule exists to avoid. The cost of refusing is a rewritten
    // pattern and a sentence saying why; the cost of admitting is an instance
    // that stops answering.
    //
    // The last two are the same question through a non-capturing and a named
    // group, whose `?` prefix is not where the branch starts.
    assertThat(PatternSafety.refuses(pattern)).isPresent();
  }

  @Test
  @DisplayName("is refused when it does not compile")
  void anInvalidPatternIsRefusedHereRatherThanLater() {
    assertThat(PatternSafety.refuses("[a-")).isPresent().get().asString().contains("Not a valid");
  }

  @Test
  @DisplayName("is refused when it is longer than a field rule has any reason to be")
  void aVeryLongPatternIsRefused() {
    assertThat(PatternSafety.refuses("a".repeat(PatternSafety.MAX_LENGTH + 1)))
        .isPresent()
        .get()
        .asString()
        .contains("at most " + PatternSafety.MAX_LENGTH);
  }

  @Test
  @DisplayName("gives up rather than running long, whatever shape it has")
  void theBudgetStopsAMatchTheRuleDidNotForesee() {
    // `(a|aa)+$` is catastrophic AND is refused at definition time -- both
    // branches begin with `a`. It is compiled directly here, past that guard, to
    // stand in for the pattern nobody anticipated: the budget must stop a match
    // it was never warned about, which is the whole reason there are two lines.
    RegularExpression bounded =
        new BoundedRegularExpressions(Duration.ofMillis(50)).getRegularExpression("(a|aa)+$");
    String nearlyMatching = "a".repeat(40) + "!";

    long start = System.nanoTime();
    boolean matched = bounded.matches(nearlyMatching);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(matched)
        .as("abandoned, and abandoned reads as not matching: the value gets a 422 naming the "
            + "field rather than a 500 about a definition the tenant wrote")
        .isFalse();
    assertThat(took)
        .as("without the budget this does not finish in the lifetime of the process")
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("still matches what it should, under the budget")
  void theBudgetDoesNotChangeAnOrdinaryAnswer() {
    RegularExpression bounded =
        new BoundedRegularExpressions(Duration.ofMillis(100)).getRegularExpression("^[A-Z]{3}$");

    assertThat(bounded.matches("EUR")).isTrue();
    assertThat(bounded.matches("eur")).isFalse();
  }
}
