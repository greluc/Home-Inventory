/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Creates the item index and its alias if they are not there (ADR-0008).
 *
 * <h2>Why the application and not a migration</h2>
 *
 * <p>OpenSearch is not backed up and is rebuildable from PostgreSQL (ADR-0008), so an empty one is
 * a normal state: a fresh volume, a restored host, a profile switched on. Making the index a
 * deployment step would mean a stack that starts and then answers nothing until somebody runs
 * something. Creating it on the way up costs one existence check per start.
 *
 * <h2>Before anything writes, by construction</h2>
 *
 * <p>On {@code @PostConstruct}, and {@link OpenSearchIndex} takes this bean as a dependency so that
 * Spring builds it first. Not on {@code ApplicationReadyEvent}, which is what it was until
 * 2026-09-14: the broker's queues are durable, so a consumer can start draining messages that
 * arrived while the application was down <i>before</i> the ready event fires. The first such write
 * goes to the alias, OpenSearch auto-creates an <b>index</b> under that name, and the alias can
 * then never be created — {@code invalid_alias_name_exception}, on an index whose dynamic mapping
 * has none of the analysed fields a query names. The whole engine fails from then on.
 *
 * <h2>Through an alias, always</h2>
 *
 * <p>Every read and write goes through {@code homeinv-items}, never through the concrete
 * {@code homeinv-items-v1}. A mapping change is then a new index filled in the background and an
 * atomic alias switch, which is what ADR-0008 asks for: "index schema changes go through a new
 * index with an alias switch, without a search outage".
 *
 * <h2>One index for every tenant</h2>
 *
 * <p>Not one per tenant. ADR-0008 makes the boundary a <b>mandatory {@code tenantId} filter on
 * every query</b>, which {@link OpenSearchIndex} applies as a filter clause and a test checks. An
 * index per tenant would put a per-tenant shard count into a system whose tenants are households.
 */
@Slf4j
@Component
@ConditionalOnBean(OpenSearchClient.class)
@RequiredArgsConstructor
public class OpenSearchIndexBootstrap {

  /** The concrete index behind {@link OpenSearchIndex#ALIAS}. */
  static final String INDEX = "homeinv-items-v1";

  /**
   * The two analysers the text fields carry, beside the unanalysed one.
   *
   * <p>The same two the generated PostgreSQL vectors use (ADR-0047), so a word stems the same way
   * in either engine and a search does not change meaning when the fallback answers.
   */
  private static final Map<String, String> ANALYSERS = Map.of("de", "german", "en", "english");

  private final OpenSearchClient client;

  /**
   * A text field with a sub-field per shipped language.
   *
   * @param text the field being built
   * @return the same builder, with {@code .de} and {@code .en} beside the standard analysis
   */
  private static org.opensearch.client.util.ObjectBuilder<
          org.opensearch.client.opensearch._types.mapping.TextProperty>
      analysed(org.opensearch.client.opensearch._types.mapping.TextProperty.Builder text) {
    ANALYSERS.forEach(
        (language, analyser) ->
            text.fields(language, f -> f.text(sub -> sub.analyzer(analyser))));
    return text;
  }

  /**
   * Creates the index unless it is already there.
   *
   * <p>A failure is logged and not thrown: a search that fails because the index is missing is a
   * degraded search, and refusing to start over a derived store would be the dependency CLAUDE.md
   * rule 10 forbids. The engine then reports itself unavailable, which routes queries to
   * PostgreSQL with {@code meta.degraded} (REQ-SRCH-006).
   */
  @jakarta.annotation.PostConstruct
  public void createIfAbsent() {
    try {
      boolean exists = client.indices().exists(request -> request.index(INDEX)).value();
      if (exists) {
        log.info("The search index {} is already there.", INDEX);
        return;
      }
      client
          .indices()
          .create(
              request ->
                  request
                      .index(INDEX)
                      // One shard and no replica: a household's inventory is not
                      // a cluster's problem, and `ha` replicates the deployment
                      // rather than the index, which is rebuildable anyway.
                      .settings(s -> s.numberOfShards(1).numberOfReplicas(0))
                      .aliases(OpenSearchIndex.ALIAS, alias -> alias)
                      .mappings(
                          m -> {
                            // Strict: a field nobody mapped is a bug in the
                            // document, and letting OpenSearch guess its type is
                            // how two tenants' documents come to disagree about
                            // what `notes` is.
                            m.dynamic(DynamicMapping.Strict);
                            m.properties("tenantId", p -> p.keyword(k -> k));
                            m.properties("itemId", p -> p.keyword(k -> k));
                            m.properties("itemTypeVersionId", p -> p.keyword(k -> k));
                            m.properties("name", p -> p.text(OpenSearchIndexBootstrap::analysed));
                            m.properties(
                                "description", p -> p.text(OpenSearchIndexBootstrap::analysed));
                            m.properties("notes", p -> p.text(OpenSearchIndexBootstrap::analysed));
                            m.properties("attributeValues", p -> p.text(x -> x));
                            m.properties("tags", p -> p.text(x -> x));
                            m.properties("locationId", p -> p.keyword(k -> k));
                            m.properties("locationPath", p -> p.keyword(k -> k));
                            m.properties("createdAt", p -> p.date(d -> d));
                            m.properties("updatedAt", p -> p.date(d -> d));
                            return m;
                          }));
      log.info("Created the search index {} and pointed {} at it.", INDEX, OpenSearchIndex.ALIAS);
    } catch (IOException | RuntimeException unreachable) {
      // Not fatal. The engine will report itself unavailable and every search is
      // answered by PostgreSQL with `meta.degraded` (REQ-SRCH-006), which is the
      // documented behaviour for an index that is not there.
      log.warn(
          "The search index {} could not be created; searches will be answered by PostgreSQL",
          INDEX,
          unreachable);
    }
  }
}
