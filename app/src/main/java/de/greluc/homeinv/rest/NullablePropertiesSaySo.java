/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Says which properties may be <b>null</b> (REQ-API-002, ADR-0081).
 *
 * <h2>The half that {@code required} does not cover</h2>
 *
 * <p>{@link RecordPropertiesArePresent} states that a response carries every property, because a
 * record serialises every component. That is one of the two things a consumer needs and not both:
 * {@code required} says the key is <b>there</b>, and says nothing about whether its value is
 * {@code null}. A generated client reading only that believes an item's {@code description} is a
 * string, and finds out otherwise at run time.
 *
 * <h2>Where the answer comes from</h2>
 *
 * <p>From {@code @Nullable} on the record component. Not from the Javadoc, although the Javadoc has
 * always said it in prose beside every one of them: <b>prose reaches a reader and no generator</b>,
 * and a document generated from a sentence is a document that changes when somebody rewords a
 * sentence.
 *
 * <p>The annotation went on the 83 components whose Javadoc already said so, and the prose stayed —
 * a sentence explaining <i>when</i> a value is absent is not a duplicate of an annotation saying
 * <i>that</i> it can be.
 *
 * <p><b>Jakarta's {@code @Nullable} and not JSpecify's</b>, although Spring Framework 7 brings the
 * latter and it is the more modern choice: JSpecify's is {@code TYPE_USE} only, so on a record
 * component it annotates the <i>type</i> and not the field, the accessor or the constructor
 * parameter — and those are where the schema resolver looks. It was written with JSpecify first
 * and the document came back unchanged, twice, which is what the difference costs.
 *
 * <h2>How it is written</h2>
 *
 * <p>OpenAPI 3.1 is JSON Schema, so a nullable string is {@code type: [string, "null"]} rather than
 * 3.0's {@code nullable: true}. A property that is a {@code $ref} is left alone: expressing "this
 * object or null" needs a {@code oneOf} wrapper, which loses the property's description and which
 * the code generators handle differently — the four such properties say what they are in prose, as
 * everything did before this.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class NullablePropertiesSaySo {

  /** What the annotation is called. Matched by simple name, so JSpecify's and Jakarta's both do. */
  private static final String NULLABLE = "Nullable";

  /** JSON Schema's own spelling of "and it may be null". */
  private static final String NULL_TYPE = "null";

  /**
   * Adds {@code "null"} to the type of every property whose component is annotated.
   *
   * <p>A {@code PropertyCustomizer} and not a {@code ModelConverter}: this one receives the
   * <b>annotated type of the property</b>, which is where the annotation is, and springdoc applies
   * it as a bean. A converter was tried first and never ran — springdoc resolves models through its
   * own {@code ModelConverters} instance rather than the global one.
   *
   * @return the customiser
   */
  @Bean
  public PropertyCustomizer nullablePropertyCustomizer() {
    return (property, type) -> {
      if (property == null || !isNullable(type)) {
        return property;
      }
      if (property.get$ref() != null) {
        // "This object, or null" is a `oneOf` in JSON Schema, which would replace
        // the reference and take the description with it. Left as it was.
        return property;
      }
      // A schema built for a scalar carries `type`, the 3.0 field, and leaves
      // `types` null; the 3.1 set is filled at write time. Seeding from one and
      // writing the other is what actually reaches the document -- reading only
      // `types` finds nothing and silently does nothing, which is where this sat
      // for a while.
      Set<String> types =
          property.getTypes() != null
              ? new LinkedHashSet<>(property.getTypes())
              : new LinkedHashSet<>();
      if (types.isEmpty() && property.getType() != null) {
        types.add(property.getType());
      }
      if (types.isEmpty()) {
        return property;
      }
      types.add(NULL_TYPE);
      property.setTypes(types);
      return property;
    };
  }

  /**
   * Whether the property's own declaration carries a nullability annotation.
   *
   * @param type the annotated type springdoc is resolving
   * @return whether anything called {@code Nullable} is on it
   */
  private static boolean isNullable(AnnotatedType type) {
    if (type == null || type.getCtxAnnotations() == null) {
      return false;
    }
    for (Annotation annotation : type.getCtxAnnotations()) {
      if (NULLABLE.equals(annotation.annotationType().getSimpleName())) {
        return true;
      }
    }
    return false;
  }
}
