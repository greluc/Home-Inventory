/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.validation.Constraint;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Shapes the generated OpenAPI document (ADR-0049).
 *
 * <p>The document is generated from the implementation rather than written, so the parts that are
 * not derivable from a controller — the title, the licence, who to contact — have to come from
 * somewhere, and this is that somewhere. {@code OpenApiDocumentIT} then compares what this produces
 * with {@code api/openapi.yaml}, so a change here shows up as a diff in the contract.
 *
 * <p>Both beans exist regardless of the profile; the document is only ever built where
 * {@code springdoc.api-docs.enabled} is true, which is the test profile and nowhere else.
 */
@Configuration
public class OpenApiConfiguration {

  /** The URI of the licence the core is published under, as SPDX names it. */
  private static final String LICENCE_URL = "https://www.gnu.org/licenses/agpl-3.0.html";

  /**
   * Supplies the parts of the document that no controller can describe.
   *
   * <p>The version is {@code v1} rather than a build number on purpose: it is the version of the
   * <em>contract</em>, which is what {@code /api/v1} promises and what REQ-API-008 governs. A
   * release number here would change on every release and mean nothing to a client.
   *
   * @return the document's static header
   */
  @Bean
  public OpenAPI homeInventoryOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Home Inventory API")
                .version("v1")
                .description(
                    """
                    The HTTP surface of a self-hosted Home Inventory instance.

                    This document is generated from the implementation and committed (ADR-0049); \
                    it describes what the code does rather than what it was meant to do. Errors \
                    follow RFC 9457 on every endpoint, and a client branches on the `type` URI, \
                    which is registered in `docs/reference/problem-types.yaml` and documented \
                    under `api/problems/` (REQ-API-003).

                    Paths are versioned. Within a major version a field may be added and an \
                    optional one may appear, and nothing a client already reads changes meaning \
                    (REQ-API-008).""")
                .license(new License().name("AGPL-3.0-or-later").url(LICENCE_URL))
                .contact(
                    new Contact()
                        .name("Lucas Greuloch (greluc)")
                        .email("lucas.greuloch@gmail.com")
                        .url("https://github.com/greluc/Home-Inventory")));
  }

  /** On every endpoint there is, whatever it does. */
  private static final Set<ProblemType> UNIVERSAL =
      EnumSet.of(
          ProblemType.METHOD_NOT_ALLOWED, ProblemType.NOT_ACCEPTABLE, ProblemType.INTERNAL_ERROR);

  /** On every endpoint that reads a request body. */
  private static final Set<ProblemType> WITH_BODY =
      EnumSet.of(
          ProblemType.MALFORMED_REQUEST,
          ProblemType.PAYLOAD_TOO_LARGE,
          ProblemType.UNSUPPORTED_MEDIA_TYPE,
          ProblemType.VALIDATION_FAILED);

  /** The name the problem schema is registered under, and what every error response refers to. */
  private static final String PROBLEM_SCHEMA = "Problem";

  /**
   * Describes what each endpoint can fail with (REQ-API-001, REQ-API-003).
   *
   * <p>The document used to name a {@code 200} per operation and nothing else, which told a client
   * what to do when everything worked. Nothing here is written by hand: what an endpoint can fail
   * with is read off its annotations, and {@link CanFail} carries only the part no annotation
   * already says.
   *
   * <p>Several conditions share a status — {@code 422} is both {@code validation-failed} and
   * {@code malware-detected} — so the responses are keyed by status and the description names every
   * type that status can carry. The client branches on the {@code type} in the body, which is what
   * REQ-API-003 makes the stable part.
   *
   * @return the customiser
   */
  @Bean
  public GlobalOperationCustomizer declaredFailures() {
    return (operation, handlerMethod) -> {
      Set<ProblemType> failures = EnumSet.copyOf(UNIVERSAL);

      if (handlerMethod.getMethodAnnotation(PublicEndpoint.class) == null) {
        failures.add(ProblemType.UNAUTHENTICATED);
      }
      if (handlerMethod.getMethodAnnotation(RequiresPermission.class) != null) {
        failures.add(ProblemType.FORBIDDEN);
      }
      if (readsABody(handlerMethod.getMethodParameters())) {
        failures.addAll(WITH_BODY);
      }
      if (hasConstrainedParameter(handlerMethod.getMethodParameters())) {
        failures.add(ProblemType.VALIDATION_FAILED);
      }
      CanFail declared = handlerMethod.getMethodAnnotation(CanFail.class);
      if (declared != null) {
        failures.addAll(List.of(declared.value()));
      }

      TreeMap<Integer, List<ProblemType>> byStatus = new TreeMap<>();
      for (ProblemType failure : failures) {
        byStatus.computeIfAbsent(failure.status().value(), status -> new ArrayList<>()).add(failure);
      }

      ApiResponses responses = operation.getResponses();
      byStatus.forEach(
          (status, types) ->
              responses.addApiResponse(String.valueOf(status), problemResponse(types)));
      return operation;
    };
  }

  /**
   * The response for one status, naming every condition that can produce it.
   *
   * @param types the conditions sharing that status
   * @return the response, whose body is the shared problem schema
   */
  private static ApiResponse problemResponse(List<ProblemType> types) {
    String description =
        types.stream()
            .map(type -> "%s (`%s`)".formatted(type.title(), type.token()))
            .reduce((first, second) -> first + ", or " + second)
            .orElseThrow();

    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/problem+json",
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
  }

  /**
   * Whether the handler reads a request body.
   *
   * @param parameters the handler's parameters
   * @return {@code true} when one of them is the deserialised body
   */
  private static boolean readsABody(MethodParameter[] parameters) {
    for (MethodParameter parameter : parameters) {
      if (parameter.hasParameterAnnotation(RequestBody.class)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether any parameter carries a validation constraint.
   *
   * <p>Looks for the meta-annotation rather than for a list of known constraints, so a constraint
   * this project has not used yet counts too.
   *
   * @param parameters the handler's parameters
   * @return {@code true} when a {@code 422} is reachable through parameter validation
   */
  private static boolean hasConstrainedParameter(MethodParameter[] parameters) {
    for (MethodParameter parameter : parameters) {
      for (Annotation annotation : parameter.getParameterAnnotations()) {
        if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Rewrites every description from Javadoc HTML into CommonMark.
   *
   * <p>The descriptions come from the Javadoc, which is where this project keeps the sentence and
   * where it is mandatory. What therapi hands over is Javadoc markup — {@code <p>}, {@code <code>},
   * {@code <ul>} — and an OpenAPI description is CommonMark. Renderers mostly cope with inline HTML,
   * "mostly" being the problem: a paragraph break that renders as a literal tag in one tool and as a
   * break in another is a contract that reads differently depending on who opens it.
   *
   * @param openApi the finished document
   */
  private static void toMarkdown(OpenAPI openApi) {
    openApi.getInfo().setDescription(markdown(openApi.getInfo().getDescription()));

    if (openApi.getPaths() != null) {
      openApi
          .getPaths()
          .values()
          .forEach(
              path ->
                  path.readOperations()
                      .forEach(
                          operation -> {
                            operation.setSummary(markdown(operation.getSummary()));
                            operation.setDescription(markdown(operation.getDescription()));
                            if (operation.getParameters() != null) {
                              operation
                                  .getParameters()
                                  .forEach(
                                      parameter ->
                                          parameter.setDescription(
                                              markdown(parameter.getDescription())));
                            }
                            if (operation.getRequestBody() != null) {
                              operation
                                  .getRequestBody()
                                  .setDescription(
                                      markdown(operation.getRequestBody().getDescription()));
                            }
                            if (operation.getResponses() != null) {
                              operation
                                  .getResponses()
                                  .values()
                                  .forEach(
                                      response ->
                                          response.setDescription(
                                              markdown(response.getDescription())));
                            }
                          }));
    }

    if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
      openApi.getComponents().getSchemas().values().forEach(OpenApiConfiguration::toMarkdown);
    }
  }

  /**
   * Rewrites a schema's descriptions, and those of everything inside it.
   *
   * @param schema the schema
   */
  private static void toMarkdown(Schema<?> schema) {
    schema.setDescription(markdown(schema.getDescription()));
    if (schema.getProperties() != null) {
      schema.getProperties().values().forEach(OpenApiConfiguration::toMarkdown);
    }
    if (schema.getItems() != null) {
      toMarkdown(schema.getItems());
    }
  }

  /**
   * Turns the Javadoc markup this project writes into CommonMark.
   *
   * <p>Deliberately small: the tags below are the ones the corpus uses, and anything else stays as
   * it is rather than being half-converted. Javadoc's own inline tags never reach here — therapi has
   * already turned {@code @code} and {@code @link} into HTML.
   *
   * @param javadoc the description as therapi produced it, or {@code null}
   * @return the same text as CommonMark, or {@code null}
   */
  private static String markdown(String javadoc) {
    if (javadoc == null) {
      return null;
    }

    String text =
        javadoc
            .replaceAll("(?i)<h[1-6]>", "\n\n## ")
            .replaceAll("(?i)</h[1-6]>", "\n")
            .replaceAll("(?i)<p>", "\n\n")
            .replaceAll("(?i)</p>", "")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)<ul>|</ul>|<ol>|</ol>", "\n")
            .replaceAll("(?i)<li>", "\n- ")
            .replaceAll("(?i)</li>", "")
            .replaceAll("(?i)</?code>", "`")
            .replaceAll("(?i)</?(em|i)>", "*")
            .replaceAll("(?i)</?(strong|b)>", "**")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&");

    // Javadoc indents its continuation lines by one space, which CommonMark keeps
    // and which turns a wrapped sentence into a differently indented one.
    text = text.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));
    return text.replaceAll("\n{3,}", "\n\n").strip();
  }

  /**
   * The RFC 9457 document every error in this API carries.
   *
   * <p>Written out rather than derived from {@code ProblemDetail}: Spring's class serialises its
   * {@code properties} map as members at the top level, through a serialiser swagger-core cannot see
   * through, so a generated schema would describe a nested {@code properties} object that no
   * response contains.
   *
   * @return the schema
   */
  private static Schema<?> problemSchema() {
    Schema<Object> schema = new Schema<>();
    schema.setType("object");
    schema.setDescription(
        "An error, as RFC 9457 describes one. A client branches on `type` and never on `detail`: "
            + "the URI is stable and documented under `api/problems/`, the prose is not and is "
            + "translated (REQ-API-003).");
    schema.addProperty(
        "type",
        new StringSchema()
            .format("uri")
            .description(
                "What went wrong, as an identifier. One of the tokens in "
                    + "`docs/reference/problem-types.yaml`, under `"
                    + ProblemType.NAMESPACE
                    + "`. Fixed for the product rather than derived from a deployment hostname, so "
                    + "a client recognises it from every instance."));
    schema.addProperty(
        "title", new StringSchema().description("The condition stable, human-readable name."));
    schema.addProperty(
        "status",
        new Schema<Integer>()
            .type("integer")
            .format("int32")
            .description("The HTTP status, repeated in the body."));
    schema.addProperty(
        "detail",
        new StringSchema()
            .description("What happened this time, in a sentence. Not stable; do not branch on it."));
    schema.addProperty(
        "instance",
        new StringSchema().format("uri").description("The path of the request that failed."));
    schema.addProperty(
        "traceId",
        new StringSchema()
            .description(
                "The identifier of this request in the server log. Quoting it in a report leads "
                    + "straight to the operation (REQ-NFR-042)."));
    schema.setRequired(List.of("type", "title", "status"));
    // Some conditions carry more: the field paths of a validation failure, the id
    // of a conflict, the limit that was exceeded. Each is documented with its type.
    schema.setAdditionalProperties(true);
    return schema;
  }

  /**
   * Removes the two things springdoc infers that this contract must not carry.
   *
   * <p><strong>Servers.</strong> springdoc fills in the URL the document was fetched from, which
   * here is a test container's {@code http://localhost}. A base URL belongs to a deployment, not to
   * the contract — {@code HOMEINV_PUBLIC_BASE_URL} is per instance and is printed onto physical
   * labels — so a hostname baked in here would be one a client copies from the wrong instance. The
   * {@code homeinv-no-server-urls} rule in {@code api/spectral.yaml} fails the build if one returns.
   *
   * <p><strong>Tags.</strong> springdoc tags every operation with a slug of the class that handles
   * it, so the contract would name {@code item-controller} and {@code media-controller} — internal
   * class names, published, and renaming a class would be a contract change. Twelve endpoints need
   * no taxonomy; an invented one would be a second thing to maintain.
   *
   * <p>Both run after springdoc has finished building, which is why the test profile disables
   * springdoc's document cache: a cached document is re-served through the server-filling step
   * without passing here again.
   *
   * @return the customiser
   */
  @Bean
  public OpenApiCustomizer homeInventoryDocumentShape() {
    return openApi -> {
      openApi.setServers(null);
      openApi.setTags(null);
      if (openApi.getPaths() != null) {
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(
            operation -> operation.setTags(null)));
      }
      if (openApi.getComponents() == null) {
        openApi.setComponents(new Components());
      }
      openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
      toMarkdown(openApi);
    };
  }
}
