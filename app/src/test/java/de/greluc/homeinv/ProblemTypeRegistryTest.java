/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.rest.ProblemType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The code and the registry name the same problem types, with the same status codes (REQ-API-003).
 *
 * <h2>Why this is a build failure rather than a review item</h2>
 *
 * <p>{@code docs/reference/problem-types.yaml} says of itself that it is the source of truth for the
 * set, and that "changing the status code of an existing case" is a breaking change. Both claims are
 * only worth something if something checks them: a constant in {@link ProblemType} without a row
 * there is a type no client knows, and a row whose status has drifted from the code is a contract
 * that documents one answer and gives another.
 *
 * <p>The third test is REQ-API-003's own verification, in so many words: "every registry entry with
 * a {@code since} at or below the current stage is reachable by a test". The OpenAPI document is
 * where that is observable — an endpoint that can produce a condition says so there — and it is
 * generated from the annotations, so a type nothing can answer with cannot appear in it.
 */
@DisplayName("The problem type registry")
class ProblemTypeRegistryTest {

  /** The stage this build is for. Entries above it describe conditions that cannot happen yet. */
  private static final int STAGE = 0;

  private static final Path REGISTRY =
      Path.of("..", "docs", "reference", "problem-types.yaml");

  private static final Path DOCUMENT = Path.of("..", "api", "openapi.yaml");

  @Test
  @DisplayName("has a row for every type the code can emit, with the same status")
  void everyConstantIsRegistered() throws Exception {
    Map<String, Integer> registered = statusByToken();

    Map<String, Integer> mismatched = new TreeMap<>();
    Set<String> unregistered = new TreeSet<>();
    for (ProblemType type : ProblemType.values()) {
      Integer status = registered.get(type.token());
      if (status == null) {
        unregistered.add(type.token());
      } else if (status != type.status().value()) {
        mismatched.put(type.token(), status);
      }
    }

    assertThat(unregistered)
        .as("every ProblemType has a row in docs/reference/problem-types.yaml")
        .isEmpty();
    assertThat(mismatched)
        .as("the registry's status and the code's status agree, per token")
        .isEmpty();
  }

  @Test
  @DisplayName("names no condition of this stage that the code cannot produce")
  void everyStageZeroEntryHasAConstant() throws Exception {
    Set<String> known = new TreeSet<>();
    for (ProblemType type : ProblemType.values()) {
      known.add(type.token());
    }

    Set<String> missing = new TreeSet<>();
    for (Map<String, Object> entry : entries()) {
      if (((Number) entry.get("since")).intValue() <= STAGE
          && !known.contains(String.valueOf(entry.get("token")))) {
        missing.add(String.valueOf(entry.get("token")));
      }
    }

    assertThat(missing)
        .as(
            "a registry entry for this stage that no code path can produce is a promise to clients "
                + "that nothing keeps. Either implement it or move its `since` to the stage that "
                + "does.")
        .isEmpty();
  }

  @Test
  @DisplayName("is reachable: every type of this stage appears in the API document (REQ-API-003)")
  void everyStageZeroEntryIsInTheContract() throws Exception {
    String document = Files.readString(DOCUMENT, StandardCharsets.UTF_8);

    Set<String> undocumented = new TreeSet<>();
    for (Map<String, Object> entry : entries()) {
      String token = String.valueOf(entry.get("token"));
      if (((Number) entry.get("since")).intValue() <= STAGE && !document.contains("`" + token + "`")) {
        undocumented.add(token);
      }
    }

    assertThat(undocumented)
        .as(
            "every problem type this stage can answer with is named by at least one endpoint in "
                + "api/openapi.yaml. Run `./gradlew updateOpenApi` after adding one.")
        .isEmpty();
  }

  // -------------------------------------------------------------------------

  private static Map<String, Integer> statusByToken() throws Exception {
    Map<String, Integer> registered = new TreeMap<>();
    for (Map<String, Object> entry : entries()) {
      registered.put(String.valueOf(entry.get("token")), ((Number) entry.get("status")).intValue());
    }
    return registered;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> entries() throws Exception {
    Map<String, Object> registry =
        new Yaml().loadAs(Files.readString(REGISTRY, StandardCharsets.UTF_8), Map.class);

    // `pending` is deliberately excluded: those are proposed and not yet emitted,
    // which the registry's own preamble says is what the list means.
    List<Map<String, Object>> entries = new java.util.ArrayList<>();
    for (String list : List.of("registered", "assigned")) {
      List<Map<String, Object>> rows = (List<Map<String, Object>>) registry.get(list);
      if (rows != null) {
        entries.addAll(rows);
      }
    }
    assertThat(entries).as("the registry is not empty").isNotEmpty();
    return entries;
  }
}
