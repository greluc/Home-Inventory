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
import java.util.Locale;
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

  @Test
  @DisplayName("bounds every collection it returns, at 200 a page (REQ-NFR-010)")
  @SuppressWarnings("unchecked")
  void everyCollectionIsBounded() throws Exception {
    // "Verified across all endpoints" is the requirement's own gate, and the
    // document is where every endpoint is visible at once. An endpoint that grows
    // a collection response later fails here rather than in production, which is
    // what an unbounded query does: nothing, until the row count changes.
    Map<String, Object> document = parse(fetch());
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    Map<String, Object> schemas =
        (Map<String, Object>)
            ((Map<String, Object>) document.get("components")).get("schemas");

    Set<String> unbounded = new TreeSet<>();
    for (Map.Entry<String, Object> path : paths.entrySet()) {
      Map<String, Object> operations = (Map<String, Object>) path.getValue();
      for (Map.Entry<String, Object> operation : operations.entrySet()) {
        Map<String, Object> details = (Map<String, Object>) operation.getValue();
        if (!returnsACollection(details, schemas) || hasABoundedLimit(details)) {
          continue;
        }
        unbounded.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
      }
    }

    assertThat(unbounded)
        .as(
            "every endpoint returning a collection takes a `limit` capped at 200. Add the "
                + "parameter with @Positive @Max(200) and page the query (REQ-NFR-010).")
        .isEmpty();
  }

  /**
   * Whether an operation answers with a collection.
   *
   * @param operation the operation
   * @param schemas the document's component schemas, for resolving a {@code $ref}
   * @return {@code true} when a success response is an array, or an object carrying one
   */
  @SuppressWarnings("unchecked")
  private static boolean returnsACollection(
      Map<String, Object> operation, Map<String, Object> schemas) {

    Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
    if (responses == null) {
      return false;
    }
    for (Map.Entry<String, Object> response : responses.entrySet()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) response.getValue()).get("content");
      if (content == null) {
        continue;
      }
      for (Object body : content.values()) {
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) body).get("schema");
        if (isACollection(resolve(schema, schemas), schemas)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Resolves one level of {@code $ref} into the component schemas.
   *
   * @param schema the schema, possibly a reference
   * @param schemas the component schemas
   * @return the schema itself, or the one it points at
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolve(
      Map<String, Object> schema, Map<String, Object> schemas) {
    if (schema == null) {
      return Map.of();
    }
    Object reference = schema.get("$ref");
    if (reference == null) {
      return schema;
    }
    String name = String.valueOf(reference).substring("#/components/schemas/".length());
    return (Map<String, Object>) schemas.getOrDefault(name, Map.of());
  }

  /**
   * Whether a resolved schema is a collection, or a page wrapping one.
   *
   * <p>An array <em>inside</em> an object does not count unless it is the object's {@code items}:
   * {@code LocationView.ancestors} is a path from the root, bounded by the tree's depth ceiling, and
   * demanding a page cursor for it would be nonsense.
   *
   * @param schema the resolved schema
   * @param schemas the component schemas
   * @return {@code true} when the response is a collection
   */
  @SuppressWarnings("unchecked")
  private static boolean isACollection(Map<String, Object> schema, Map<String, Object> schemas) {
    if ("array".equals(schema.get("type"))) {
      return true;
    }
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    if (properties == null) {
      return false;
    }
    Map<String, Object> items = (Map<String, Object>) properties.get("items");
    return items != null && "array".equals(resolve(items, schemas).get("type"));
  }

  /**
   * Whether an operation takes a {@code limit} that cannot exceed 200.
   *
   * @param operation the operation
   * @return {@code true} when the parameter is there and capped
   */
  @SuppressWarnings("unchecked")
  private static boolean hasABoundedLimit(Map<String, Object> operation) {
    List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
    if (parameters == null) {
      return false;
    }
    for (Map<String, Object> parameter : parameters) {
      if (!"limit".equals(parameter.get("name"))) {
        continue;
      }
      Map<String, Object> schema = (Map<String, Object>) parameter.get("schema");
      Object maximum = schema == null ? null : schema.get("maximum");
      return maximum instanceof Number cap && cap.intValue() <= 200;
    }
    return false;
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
