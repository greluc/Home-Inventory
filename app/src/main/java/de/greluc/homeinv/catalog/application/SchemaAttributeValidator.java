/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.application;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import de.greluc.homeinv.catalog.api.AttributeValidator;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates an attribute set against the schema document its type version ships (ADR-0056).
 *
 * <h2>What is configured, and why each setting is a decision</h2>
 *
 * <ul>
 *   <li><b>Remote resources are not fetched.</b> The library can resolve a {@code $ref} over HTTP,
 *       and {@code api} and {@code worker} have no outbound route to anything (ADR-0026, rule 5). A
 *       type definition arrives as tenant data; a {@code $ref} in one must not become a socket. Our
 *       generator emits no {@code $ref} at all, which is a reason to keep this off rather than a
 *       reason not to set it.
 *   <li><b>Format assertions are on.</b> In draft 2020-12 {@code format} is an annotation by
 *       default — {@code "format":"email"} would be carried to the client and asserted by nobody.
 *       REQ-CORE-022 lists {@code email}, {@code url}, {@code date} and {@code datetime} as data
 *       types, so here they assert.
 *   <li><b>Paths are JSON pointers.</b> {@code /purchasePrice/currency} is what the {@code 422}
 *       carries and what a client uses to highlight a field (REQ-CORE-005).
 *   <li><b>English messages.</b> A violation travels into a problem document and into logs, and the
 *       language of the interface is the client's business (REQ-NFR-032).
 *   <li><b>Every {@code pattern} runs under a time limit.</b> The patterns in a generated schema
 *       come from field definitions, which are tenant data, and Java's regular expressions
 *       backtrack — so one administrator's {@code (a+)+} would otherwise hang a request thread for
 *       as long as the input is long (REQ-SEC-035, {@link BoundedRegularExpressions}). The other
 *       half of that requirement refuses the known shapes when the definition is saved; this half
 *       is what covers the shapes nobody anticipated.
 * </ul>
 */
@Component
@Slf4j
public class SchemaAttributeValidator implements AttributeValidator {

  /**
   * How many compiled schemas are held before the cache is emptied.
   *
   * <p>Keyed by the document text rather than by a version id, so nothing has to be evicted when a
   * draft changes: a different document is a different key. The bound exists because a tenant may
   * edit a draft as often as it likes, and each edit would otherwise leave a compiled schema behind
   * for the life of the process.
   */
  private static final int CACHE_LIMIT = 256;

  private final TypeRegistry types;
  private final SchemaRegistry registry;
  private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

  /**
   * Builds the registry this validator uses for the life of the process.
   *
   * @param types where the schema document of a version comes from
   */
  public SchemaAttributeValidator(TypeRegistry types) {
    this.types = types;
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder()
            .formatAssertionsEnabled(Boolean.TRUE)
            .pathType(PathType.JSON_POINTER)
            .locale(Locale.ENGLISH)
            // 100 ms per pattern, which is two orders of magnitude above what a
            // field validation pattern needs on a value of a few hundred
            // characters and far below the 150 ms a detail view is allowed in
            // total (13 §13.6). A budget nobody legitimate reaches.
            .regularExpressionFactory(new BoundedRegularExpressions(Duration.ofMillis(100)))
            .build();
    this.registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder
                    .schemaRegistryConfig(config)
                    .schemaLoader(loader -> loader.fetchRemoteResources(false)));
  }

  @Override
  public ValidationResult validate(UUID typeVersionId, String attributesJson) {
    String document = types.jsonSchema(typeVersionId);
    Schema schema =
        compiled.computeIfAbsent(
            document,
            text -> {
              if (compiled.size() >= CACHE_LIMIT) {
                compiled.clear();
              }
              return registry.getSchema(text);
            });

    List<Error> errors = schema.validate(attributesJson, InputFormat.JSON);
    if (errors.isEmpty()) {
      return ValidationResult.VALID;
    }
    List<Violation> violations =
        errors.stream()
            .map(error -> new Violation(pointer(error), error.getMessage()))
            .toList();
    // At debug, and without a value: a rejected attribute set may hold a licence
    // key or an account number, and a log line is the one place a `secret` must
    // never reach (REQ-SEC-041, LogHygieneIT).
    log.debug("Attribute set rejected against version {}: {} violation(s)", typeVersionId, violations.size());
    return new ValidationResult(violations);
  }

  /**
   * The JSON pointer of the value an error is about.
   *
   * <p>The library renders the instance location as {@code $.purchasePrice.currency} or as a
   * pointer depending on the configured path type; this reads it back as the pointer the API
   * contract promises, and answers {@code ""} for an error about the object as a whole — a missing
   * required field is reported at the root, and the field it names is in the message.
   *
   * @param error one finding
   * @return the pointer, for example {@code /purchasePrice/currency}
   */
  private String pointer(Error error) {
    String location = error.getInstanceLocation().toString();
    return location == null || location.equals("$") ? "" : location;
  }
}
