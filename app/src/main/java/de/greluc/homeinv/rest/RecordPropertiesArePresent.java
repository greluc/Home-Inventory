/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Says which properties a <b>response</b> always carries (REQ-API-002).
 *
 * <h2>Why the document needed this</h2>
 *
 * <p>springdoc marks a property {@code required} when a bean-validation annotation says so —
 * {@code @NotBlank} on a request body, and little else. Every response view in this application is
 * a Java record with no such annotation, so the document described its schemas without once saying
 * which fields are always there.
 *
 * <p>That is invisible until somebody generates a client from it, which is what {@code REQ-API-002}
 * asks for: every field becomes optional, and every call site has to cope with an {@code id} that
 * might not be there. It is not an inconvenience — it is a contract that does not say the one thing
 * a consumer most needs to know.
 *
 * <h2>Why every property, and why only on a response</h2>
 *
 * <p>{@code required} in JSON Schema means the property is <b>present</b>, and a Java record
 * serialises every component it has. There is no configuration under which one is omitted: Jackson
 * writes {@code "description": null} rather than leaving it out, because this application does not
 * set {@code NON_NULL} globally — {@code Page.facets} carries the annotation itself precisely
 * because the default does not. So for a response, "present" is true of every property.
 *
 * <p>On a <b>request</b> it is the opposite: {@code required} there means the client <i>must send
 * it</i>, and a body with an optional field would become one a client cannot omit. So a schema that
 * any request body reaches is left exactly as bean validation left it, and that is why this works
 * on the finished document rather than on the Java types — the document is where the difference
 * between the two is visible at all.
 *
 * <p>Reachability is <b>transitive</b>: a schema nested inside a request body is a request schema
 * too, however deep. {@code Money} appears in both a request and a response and is therefore left
 * alone, which is the safe direction.
 *
 * <p><b>What this deliberately does not claim</b> is that a value is not null. Nullability is a
 * separate assertion, the document does not make it today, and inventing one from a Java type would
 * say "may be null" of an id that never is. A generated client learns which fields arrive, not
 * which may be empty — which is exactly what the hand-written client asserted before there was a
 * generated one.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RecordPropertiesArePresent {

  /**
   * Marks the properties of every response-only schema as present.
   *
   * @return the customiser springdoc applies after the document is built
   */
  @Bean
  public OpenApiCustomizer responsePropertiesArePresent() {
    return openApi -> {
      Map<String, Schema> schemas =
          openApi.getComponents() == null ? Map.of() : openApi.getComponents().getSchemas();
      if (schemas == null || schemas.isEmpty()) {
        return;
      }

      // RFC 9457 lets a problem carry extension members, and Spring's
      // `ProblemDetail` holds them in a map -- which springdoc publishes as
      // `additionalProperties: true`. That is true and it is unusable: a schema
      // with properties AND a free map makes the OpenAPI generator emit a Kotlin
      // class extending `HashMap<String, Any>()()`, which does not compile
      // (REQ-API-002). It is dropped here, so the schema describes the six
      // members every problem carries; the extras are per problem type and are
      // documented with it under `api/problems/`, which is where a client reads
      // them anyway -- no generated type could have made them type-safe.
      Schema<?> problem = schemas.get("Problem");
      if (problem != null) {
        problem.setAdditionalProperties(null);
        // And `status` arrives with a format and no type, because springdoc reads
        // `ProblemDetail.getStatus()` through a getter it cannot type. A property
        // with `format: int32` and nothing else is an `Any?` in a generated
        // client -- and one that kotlinx.serialization then refuses to serialise.
        Object status = problem.getProperties() == null ? null : problem.getProperties().get("status");
        if (status instanceof Schema<?> field && field.getTypes() == null) {
          // `setTypes` and not `setType`: this document is OpenAPI 3.1, where a
          // type is a SET, and the 3.0 field is ignored when it is written out.
          field.setTypes(new LinkedHashSet<>(List.of("integer")));
        }
      }

      Set<String> reachedByARequest = reachedByARequest(openApi, schemas);
      int marked = 0;
      for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
        if (reachedByARequest.contains(entry.getKey())) {
          continue;
        }
        if (markRequired(entry.getValue())) {
          marked++;
        }
      }
      log.debug("{} response schema(s) now say which properties they always carry", marked);
    };
  }

  /**
   * Every schema a request body can reach, directly or through another schema.
   *
   * @param openApi the finished document
   * @param schemas the components, by name
   * @return the names to leave alone
   */
  private static Set<String> reachedByARequest(OpenAPI openApi, Map<String, Schema> schemas) {
    Deque<String> pending = new ArrayDeque<>();
    if (openApi.getPaths() != null) {
      openApi.getPaths().values().stream()
          .flatMap(path -> path.readOperations().stream())
          .map(Operation::getRequestBody)
          .filter(body -> body != null && body.getContent() != null)
          .flatMap(body -> body.getContent().values().stream())
          .map(MediaType::getSchema)
          .forEach(schema -> namesIn(schema, pending));
    }

    Set<String> reached = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      String name = pending.pop();
      if (!reached.add(name)) {
        continue;
      }
      Schema<?> schema = schemas.get(name);
      if (schema == null || schema.getProperties() == null) {
        continue;
      }
      schema.getProperties().values().forEach(property -> namesIn((Schema<?>) property, pending));
    }
    return reached;
  }

  /**
   * Collects the component names a schema refers to, one level down.
   *
   * <p>A reference, an array's items and a map's additional properties are all places a name hides;
   * a composed schema's members are another. Anything unrecognised contributes nothing, which errs
   * towards marking a schema required — so the one case that must not be wrong, a request body, is
   * covered by the reference forms this document actually uses.
   *
   * @param schema the schema to look into, or {@code null}
   * @param into where to put what is found
   */
  private static void namesIn(Schema<?> schema, Deque<String> into) {
    if (schema == null) {
      return;
    }
    if (schema.get$ref() != null) {
      into.push(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
    }
    namesIn(schema.getItems(), into);
    if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
      namesIn(additional, into);
    }
    List<Schema> composed = new ArrayList<>();
    if (schema.getAllOf() != null) {
      composed.addAll(schema.getAllOf());
    }
    if (schema.getOneOf() != null) {
      composed.addAll(schema.getOneOf());
    }
    if (schema.getAnyOf() != null) {
      composed.addAll(schema.getAnyOf());
    }
    composed.forEach(member -> namesIn(member, into));
  }

  /**
   * Marks every declared property of a schema as present.
   *
   * @param schema the schema
   * @return whether anything was marked
   */
  private static boolean markRequired(Schema<?> schema) {
    if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
      return false;
    }
    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
    return true;
  }
}
