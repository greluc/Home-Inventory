/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What a query may cost before it is allowed to run (REQ-API-006, 08 §8.4, 12 §12.3).
 *
 * <p>Two of the three limits, both applied <b>before execution</b>, because a query that is refused after
 * touching the database has already cost what the limit exists to prevent.
 *
 * <ol>
 *   <li><b>Depth</b>, at 10. Below the maximum location depth of 12 on purpose: a deep tree cannot
 *       be walked to its leaves in one query, and {@code locationTree(rootId:, depth:)} is the
 *       intended path. Raising it to 12 would raise the ceiling of every other query with it.
 *   <li><b>Cost</b>, against a budget. The weight of a field is declared in the schema with {@code
 *       @cost}, and a list multiplies its weight by the {@code first} it was asked for — so
 *       {@code items(first: 200) { history(first: 20) }} is expensive on paper before it is
 *       expensive on the database.
 * </ol>
 *
 * <p>The <b>third</b> limit 08 §8.4 names — a bound on repeated aliases — is not here but in
 * {@link QueryGuard}, which already parses the document to hash it. An instrumentation refusing at
 * {@code beginValidation} was written first and is not what graphql-java calls for a document that
 * is merely large, so it never fired; the guard refuses it where every other pre-execution refusal
 * is made, in one shape a client can recognise.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class GraphQlSafeguards {

  /**
   * How deep a query may nest.
   *
   * <p>Ten, and the number is load-bearing rather than round — see the class documentation.
   */
  @Value("${homeinv.graphql.max-depth:10}")
  private int maxDepth;

  /**
   * The budget one query may spend.
   *
   * <p>Chosen so that the ordinary screens fit comfortably and a query that walks lists inside
   * lists does not: {@code items(first: 50)} with tags and photos costs about 1 000.
   */
  @Value("${homeinv.graphql.max-cost:5000}")
  private int maxCost;

  /**
   * Refuses a query that nests deeper than the limit.
   *
   * @return the instrumentation
   */
  @Bean
  Instrumentation depthLimit() {
    return new MaxQueryDepthInstrumentation(maxDepth);
  }

  /**
   * Refuses a query whose declared cost exceeds the budget.
   *
   * <p>The calculator reads {@code @cost(weight:)} from the field's own definition — the schema is
   * the declaration, and a field that declares none costs one. A field taking {@code first}
   * multiplies by it, which is what makes the budget about rows rather than about field names.
   *
   * @return the instrumentation
   */
  @Bean
  Instrumentation costLimit() {
    return new MaxQueryComplexityInstrumentation(
        maxCost,
        (environment, childComplexity) -> {
          int weight =
              environment.getFieldDefinition().getAppliedDirectives().stream()
                  .filter(directive -> "cost".equals(directive.getName()))
                  .findFirst()
                  .map(directive -> directive.getArgument("weight").getValue())
                  .filter(Integer.class::isInstance)
                  .map(Integer.class::cast)
                  .orElse(1);
          Object first = environment.getArguments().get("first");
          int rows = first instanceof Integer asked && asked > 0 ? asked : 1;
          // The field's own weight once, and its children once per row it
          // returns. `weight * rows + child * rows` would charge the weight per
          // row as well, which made an ordinary two-level query cost 13 100
          // against a budget of 5 000 -- a limit that refuses the normal case is
          // a limit somebody raises until it refuses nothing.
          return weight + childComplexity * rows;
        });
  }

}
