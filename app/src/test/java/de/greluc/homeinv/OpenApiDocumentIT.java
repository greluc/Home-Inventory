/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The OpenAPI document is generated from the implementation, and this is the drift check.
 *
 * <h2>What this replaces</h2>
 *
 * <p>ADR-0049 changed the direction: the document used to be written first, with server stubs and
 * clients generated from it. After a full stage what existed was six hand-written endpoints, a
 * hand-written client and no document at all — which is where a specification goes when it is not
 * on the path to a running program.
 *
 * <p>What was actually promised was never that a person types the document. It was that the
 * document and the implementation agree, and generation plus this check reaches that with a red
 * build instead of a discipline. It is the third time this repository uses the mechanism:
 * {@code deploy/generate.py --check} and {@code npm run csp:check} are the other two.
 *
 * <h2>Why the document is still committed</h2>
 *
 * <p>Because that is what makes a contract change reviewable. A reviewer sees it as a diff, next to
 * the code that caused it, and a change nobody intended shows up as a diff nobody expected.
 *
 * <p>Run {@code ./gradlew updateOpenApi} to regenerate after an intended change.
 */
@DisplayName("The OpenAPI document")
class OpenApiDocumentIT extends AbstractIntegrationTest {

  /** Where the committed document lives, relative to the {@code app} module. */
  private static final Path COMMITTED = Path.of("..", "api", "openapi.yaml");

  /** Set by {@code ./gradlew updateOpenApi} to write instead of compare. */
  private static final boolean REGENERATE = Boolean.getBoolean("homeinv.openapi.regenerate");

  @Test
  @DisplayName("matches the implementation it was generated from (ADR-0049)")
  void theDocumentMatchesTheCode() throws Exception {
    String generated = normalise(fetch());

    if (REGENERATE) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
      // Not an assertion. This branch exists to write the file, and a test that
      // asserted after writing would assert that writing works.
      return;
    }

    assertThat(Files.exists(COMMITTED))
        .as("api/openapi.yaml is missing. Run `./gradlew updateOpenApi`.")
        .isTrue();

    assertThat(generated)
        .as(
            "The committed OpenAPI document no longer matches the implementation. It is generated, "
                + "not hand-written (ADR-0049): run `./gradlew updateOpenApi` and commit the "
                + "result, so the contract change is reviewable as a diff next to the code that "
                + "caused it.")
        .isEqualTo(Files.readString(COMMITTED, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("describes every endpoint under /api/v1 and nothing else (REQ-API-001)")
  void everyEndpointIsDescribed() throws Exception {
    Map<String, Object> document = parse(fetch());
    @SuppressWarnings("unchecked")
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");

    assertThat(paths).isNotEmpty();

    // The media path is the one endpoint outside /api/v1: it answers on the media
    // hostname and is authorised by a signature rather than by a session
    // (REQ-MED-010). Everything else is versioned, because an unversioned path
    // is one that cannot be changed without breaking a client.
    Set<String> unversioned = new TreeSet<>();
    for (String path : paths.keySet()) {
      if (!path.startsWith("/api/v1/") && !path.startsWith("/media/")) {
        unversioned.add(path);
      }
    }
    assertThat(unversioned)
        .as("every endpoint is under /api/v1, except the signed media path")
        .isEmpty();
  }

  @Test
  @DisplayName("names the problem types it can return, and only registered ones (REQ-API-003)")
  void problemTypesComeFromTheRegistry() throws Exception {
    // The registry is the source: `docs/reference/problem-types.yaml` lists every
    // `type` URI a client may branch on. A response shape that invented one would
    // be a contract nobody could write a client against.
    Map<String, Object> registry = parse(Files.readString(
        Path.of("..", "docs", "reference", "problem-types.yaml"), StandardCharsets.UTF_8));
    // Three lists, and the distinction is the registry's own: `registered` are
    // the tokens published at an IANA-style URI, `assigned` are ours and in use,
    // `pending` are proposed and not yet emitted. A response may carry any of the
    // first two; a `pending` one would be a client branching on a token that may
    // still change.
    Set<String> registered = new TreeSet<>();
    for (String list : List.of("registered", "assigned")) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> entries = (List<Map<String, Object>>) registry.get(list);
      if (entries == null) {
        continue;
      }
      for (Map<String, Object> entry : entries) {
        registered.add(String.valueOf(entry.get("token")));
      }
    }

    String document = fetch();
    Set<String> used = new TreeSet<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("https://home-inv\\.example/problems/([a-z0-9-]+)")
            .matcher(document);
    while (matcher.find()) {
      used.add(matcher.group(1));
    }

    assertThat(registered).as("the registry is not empty").isNotEmpty();
    assertThat(used)
        .as("every problem type the document names is in docs/reference/problem-types.yaml")
        .isSubsetOf(registered);
  }

  // -------------------------------------------------------------------------

  /**
   * The document as the running application serves it.
   *
   * <p>Decoded as UTF-8 explicitly. {@code getContentAsString()} without a charset falls back to
   * ISO-8859-1, and the descriptions come from Javadoc that is full of em dashes — every one of them
   * would arrive as three characters the YAML parser then refuses.
   *
   * @return the raw document
   * @throws Exception when the request fails
   */
  private String fetch() throws Exception {
    return mockMvc
        .perform(get("/v3/api-docs.yaml"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * Re-serialises the document so that two runs produce byte-identical output.
   *
   * <p>springdoc emits map entries in whatever order the reflection happened to produce, which
   * differs between runs on the same code. Without this the drift check would fail on a reordering
   * and teach everybody to regenerate rather than to read the diff — which is the one habit that
   * would make the whole mechanism useless.
   *
   * @param raw the document as springdoc produced it
   * @return the same document, deterministically ordered
   */
  private static String normalise(String raw) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setIndent(2);
    options.setWidth(100);
    Yaml yaml = new Yaml(options);
    return """
        # GENERATED FROM THE IMPLEMENTATION — DO NOT EDIT.
        #
        # `./gradlew updateOpenApi` regenerates it; `./gradlew :app:test` fails while this file and
        # the code disagree. Editing it by hand is lost and noticed rather than lost and not
        # (ADR-0049).
        """
        + yaml.dump(sorted(yaml.load(raw)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String yaml) {
    return new Yaml().loadAs(yaml, Map.class);
  }

  /**
   * Sorts every map in a parsed document, recursively.
   *
   * @param value any node of the parsed document
   * @return the same node with deterministic key order
   */
  @SuppressWarnings("unchecked")
  private static Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      java.util.TreeMap<String, Object> ordered = new java.util.TreeMap<>();
      map.forEach((key, nested) -> ordered.put(String.valueOf(key), sorted(nested)));
      return ordered;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(OpenApiDocumentIT::sorted).toList();
    }
    return value;
  }
}
