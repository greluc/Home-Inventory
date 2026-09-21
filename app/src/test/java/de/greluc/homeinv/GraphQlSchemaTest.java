/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GraphQL schema is read-only, and every list in it declares what it costs (REQ-API-006).
 *
 * <p>Read from the SDL rather than from the built schema, deliberately: the file is the contract
 * that ships with the documentation and is what a reader reviews. A test over the built schema
 * would pass on a schema assembled at run time from something else.
 *
 * <h2>Read-only is structural</h2>
 *
 * <p>ADR-0010: two writing surfaces mean duplicated authorisation, validation and idempotency
 * logic, and the second one is always the one with the hole. So there is no {@code Mutation} type
 * and no {@code Subscription} type — not "no mutations are implemented", but nothing to implement
 * one on. This is the test that keeps it that way when somebody adds "just one".
 */
@DisplayName("The GraphQL schema")
class GraphQlSchemaTest {

  private static final Path SCHEMA =
      Path.of("src", "main", "resources", "graphql", "schema.graphqls");

  /** A type declaration: {@code type Item {}, {@code type Query {} and any other. */
  private static final Pattern TYPE = Pattern.compile("^type\\s+(\\w+)\\s*\\{", Pattern.MULTILINE);

  @Test
  @DisplayName("declares no way to write anything")
  void readOnlyIsStructural() throws IOException {
    String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);

    List<String> writingTypes = new ArrayList<>();
    Matcher types = TYPE.matcher(schema);
    while (types.find()) {
      String name = types.group(1);
      if ("Mutation".equals(name) || "Subscription".equals(name)) {
        writingTypes.add(name);
      }
    }

    assertThat(writingTypes)
        .as(
            "ADR-0010 makes GraphQL read-only, and the schema is where that is true or not. A"
                + " Mutation type would be a second writing surface with its own authorisation,"
                + " validation and idempotency to get right")
        .isEmpty();
    assertThat(schema).contains("type Query {");
  }

  @Test
  @DisplayName("declares a cost weight on every list a client can widen")
  void everyExpensiveFieldSaysSo() throws IOException {
    String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);

    // A field declaration may span lines — an argument list long enough to wrap
    // puts `first` on one line and `@cost` on another, and a line-by-line check
    // reports it as undeclared. Collapsed to one line per declaration first.
    String flattened = schema.replaceAll("\\(\\s*\\R\\s*", "(").replaceAll(",\\s*\\R\\s*", ", ")
        .replaceAll("\\R\\s*\\)", ")").replaceAll("\\)\\s*:", "): ");

    List<String> undeclared = new ArrayList<>();
    for (String line : flattened.split("\\R")) {
      String trimmed = line.strip();
      // A field taking `first` returns as many rows as the client asks for, and
      // the budget is computed from the weight beside it. One without a weight
      // costs 1 per row, which is the wrong answer for anything that reads.
      if (trimmed.contains("first: Int") && !trimmed.contains("@cost")) {
        undeclared.add(trimmed);
      }
    }

    assertThat(undeclared)
        .as(
            "a field that takes `first` multiplies whatever it costs by the rows asked for, so it"
                + " declares a weight with @cost. Without one it costs 1, and the budget stops"
                + " bounding the queries it exists to bound (08 §8.4)")
        .isEmpty();
  }

  @Test
  @DisplayName("declares the two scalars its resolvers return")
  void theScalarsAreDeclared() throws IOException {
    String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);
    assertThat(schema).contains("scalar JSON");
    assertThat(schema).contains("scalar DateTime");
    // And no Float anywhere: GraphQL's Float is a double, and a quantity or an
    // amount never travels as one (ADR-0025).
    assertThat(schema).doesNotContain(": Float");
  }
}
