/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.AccessDeniedException;
import de.greluc.homeinv.authorization.api.Permission;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.Field;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.parser.Parser;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Which queries may be sent at all, and who may send something new (REQ-API-006, 08 §8.4).
 *
 * <h2>Persisted queries</h2>
 *
 * <p>The allowlist is <b>generated from the client at build time</b> and shipped in the image:
 * {@code tools/persisted_queries.py} reads every {@code .graphql} document under {@code web/src}
 * and writes its SHA-256 here. "Only registered queries in production" therefore means "the queries
 * this release ships with", and there is no registration surface at run time to be abused
 * (ADR-0079).
 *
 * <p>That also makes drift impossible in the direction that matters: a client that sends a query
 * the build did not see is refused <b>here</b>, in an environment where somebody is watching,
 * rather than in a browser six weeks later.
 *
 * <h2>Free-form queries, and introspection with them</h2>
 *
 * <p>Both are the same permission: whoever may write a query by hand may read the schema that
 * describes it, and whoever may not, gains nothing from either. {@code administrators} is the
 * default — {@code tenancy:tenant:update} is held by {@code ADMIN} and {@code OWNER} and by no
 * scoped membership — and {@code anyone} exists for development, where a schema explorer is the
 * point.
 *
 * <p>Introspection is refused by <b>request</b> rather than by hiding the fields from the schema,
 * which is what graphql-java's field visibility would do. Field visibility is schema-wide, and this
 * has to be able to say yes to one caller and no to the next.
 */
@Slf4j
@Component
public class QueryGuard implements WebGraphQlInterceptor {

  /** The introspection roots. Naming them is enough: neither can be reached any other way. */
  private static final Set<String> INTROSPECTION = Set.of("__schema", "__type");

  private final AccessControl accessControl;
  private final int maxRepeatedFields;
  private final Set<String> registered;
  private final FreeForm freeForm;

  /**
   * Reads the allowlist once, at startup.
   *
   * @param accessControl who decides whether a caller is an administrator
   * @param registry the generated file, absent in a build that has never run the generator.
   *     A property rather than a fixed path so that a test can point at a list of its own
   *     without shadowing the shipped one for every other test
   * @param freeForm who may send a query that is not in the list
   * @param maxRepeatedFields how often one field name may appear in one document
   */
  public QueryGuard(
      AccessControl accessControl,
      @Value("${homeinv.graphql.persisted-queries:classpath:graphql/persisted-queries.txt}")
          Resource registry,
      @Value("${homeinv.graphql.free-form:ADMINISTRATORS}") FreeForm freeForm,
      @Value("${homeinv.graphql.max-repeated-fields:20}") int maxRepeatedFields) {
    this.accessControl = accessControl;
    this.freeForm = freeForm;
    this.maxRepeatedFields = maxRepeatedFields;
    this.registered = read(registry);
    log.info(
        "GraphQL: {} persisted quer(ies) registered; free-form queries are for {}",
        registered.size(),
        freeForm.name().toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Lets a registered query through, and asks who is sending anything else.
   *
   * @param request the incoming request
   * @param chain the rest of the handling
   * @return the response, or a refusal that never reached a resolver
   */
  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    String document = request.getDocument();

    if (INTROSPECTION.stream().anyMatch(document::contains) && !mayWriteQueries()) {
      return refuse(
          request,
          "Introspection is disabled here. The schema ships with the documentation, and a caller"
              + " who may not write a query by hand gains nothing from reading it.");
    }

    String repeated = fieldRepeatedTooOften(document);
    if (repeated != null) {
      return refuse(
          request,
          "This query asks for "
              + repeated
              + ", and no field may appear more than "
              + maxRepeatedFields
              + " times. Aliasing one field many times executes it many times, which is what the"
              + " depth and cost limits both miss: each counts it once.");
    }

    if (registered.contains(hashOf(document)) || mayWriteQueries()) {
      return chain.next(request);
    }

    return refuse(
        request,
        registered.isEmpty()
            ? "This deployment has no persisted queries registered, so the GraphQL surface is open"
                + " to administrators only. A client's queries are registered by the build that"
                + " ships it."
            : "This query is not one this release registered. Persisted queries are generated from"
                + " the client at build time (REQ-API-006), so a query the build did not see is"
                + " refused here rather than in somebody's browser.");
  }

  /**
   * The field a query repeats past the bound, or {@code null} when none does.
   *
   * <p>Counted on the field's <b>name</b> and not on its alias: aliasing is what makes the
   * repetition possible and renaming is what hides it. A page legitimately asks for {@code items}
   * twice — a list and a counter — and never twenty times.
   *
   * <p>A document that does not parse is let through: the pipeline's own syntax error says where
   * the problem is, and a refusal from here would replace it with a worse message.
   *
   * @param document the query text
   * @return a description of the offending field, or {@code null}
   */
  private String fieldRepeatedTooOften(String document) {
    Map<String, Integer> occurrences = new HashMap<>();
    try {
      NodeTraverser traverser = new NodeTraverser();
      traverser.depthFirst(
          new NodeVisitorStub() {
            @Override
            public TraversalControl visitField(Field node, TraverserContext<Node> context) {
              // Entering only: a depth-first traversal visits a node twice, and
              // counting both would halve the effective limit without saying so.
              if (context.isVisited()) {
                return TraversalControl.CONTINUE;
              }
              occurrences.merge(node.getName(), 1, Integer::sum);
              return TraversalControl.CONTINUE;
            }
          },
          Parser.parse(document));
    } catch (RuntimeException notParseable) {
      return null;
    }
    return occurrences.entrySet().stream()
        .filter(entry -> entry.getValue() > maxRepeatedFields)
        .findFirst()
        .map(entry -> "`" + entry.getKey() + "` " + entry.getValue() + " times")
        .orElse(null);
  }

  /**
   * Whether this caller may send a query nobody registered.
   *
   * @return whether free-form queries and introspection are open to them
   */
  private boolean mayWriteQueries() {
    if (freeForm == FreeForm.ANYONE) {
      return true;
    }
    if (freeForm == FreeForm.NEVER) {
      return false;
    }
    try {
      // The same permission that configures the tenant. Held by ADMIN and OWNER
      // and -- because it is not whole-tenant -- also by a scoped administrator,
      // which is right: what they may ask for is still bounded by every other
      // permission, one field at a time (ADR-0079).
      accessControl.require(Permission.TENANT_UPDATE);
      return true;
    } catch (AccessDeniedException notAnAdministrator) {
      return false;
    } catch (RuntimeException noCaller) {
      // No session at all. The security chain answers that before this runs, so
      // reaching here means the surface was opened to anonymous callers by
      // configuration rather than by design.
      log.debug("A GraphQL request arrived with no caller to ask about", noCaller);
      return false;
    }
  }

  /**
   * The SHA-256 of a document, as the generator computes it.
   *
   * <p>Whitespace is normalised first — the leading and trailing kind only. A client that pretty-
   * prints the same query it registered is sending the same query; one that renames a field is not,
   * and no normalisation here would hide that.
   *
   * @param document the query text
   * @return lower-case hex
   */
  static String hashOf(String document) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha256.digest(document.strip().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("A JVM without SHA-256", impossible);
    }
  }

  private static Mono<WebGraphQlResponse> refuse(WebGraphQlRequest request, String why) {
    GraphQLError error =
        GraphqlErrorBuilder.newError()
            .errorType(ErrorType.FORBIDDEN)
            .message(why)
            .build();
    return Mono.just(
        new WebGraphQlResponse(
            new DefaultExecutionGraphQlResponse(
                request.toExecutionInput(),
                graphql.ExecutionResult.newExecutionResult().addError(error).build())));
  }

  /**
   * Reads the generated allowlist.
   *
   * <p>A missing file is not a failure: a build that has never run the generator has no client with
   * queries in it, and the surface is then administrator-only, which the constructor says out loud.
   *
   * @param registry the file
   * @return the hashes, lower-case hex
   */
  private static Set<String> read(Resource registry) {
    if (!registry.exists()) {
      return Set.of();
    }
    // The READER in the resource block, not only the stream it produces: closing a
    // `Stream` returned by `lines()` does not close the reader it reads from, which
    // leaves a file handle open for every startup of every context.
    try (java.io.BufferedReader reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(registry.getInputStream(), StandardCharsets.UTF_8));
        var lines = reader.lines()) {
      return lines
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(line -> line.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "The persisted-query allowlist is in the image and could not be read", unreadable);
    }
  }

  /** Who may send a query that is not in the allowlist. */
  public enum FreeForm {
    /** Nobody. The strictest setting, for a deployment that serves only its own client. */
    NEVER,
    /** Whoever may configure the tenant. The default, and what 08 §8.4 describes. */
    ADMINISTRATORS,
    /** Anybody who may query at all. For development, where a schema explorer is the point. */
    ANYONE
  }
}
