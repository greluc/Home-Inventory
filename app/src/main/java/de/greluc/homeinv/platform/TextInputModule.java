/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.text.Normalizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Canonicalises every string that arrives in a request body (REQ-SEC-032).
 *
 * <h2>Why at the edge and not at each use</h2>
 *
 * <p>Because "each use" is a list that grows, and the one that gets forgotten is the one that
 * matters. Every string entering through JSON passes through one deserialiser, so the property is
 * structural rather than a convention.
 *
 * <h2>What it does, and why each part</h2>
 *
 * <ul>
 *   <li><b>Unicode NFC.</b> "Bohrmaschine" typed on macOS and on Linux can be two different byte
 *       sequences — decomposed and composed — that look identical. Without normalisation they are
 *       different strings: a search does not find them, a unique index does not catch them, and two
 *       rows exist that a person cannot tell apart. Normalising at the edge makes the stored form
 *       the canonical one, once.
 *   <li><b>Control characters removed.</b> They serve no purpose in a name or a description and
 *       several serve a purpose elsewhere: a line separator or a bidirectional override changes how
 *       text renders in a terminal, a log viewer and a PDF label, without changing what a reviewer
 *       reading the source sees. Newline and tab are kept, because a description legitimately has
 *       both.
 *   <li><b>Trimmed.</b> A trailing space is invisible and makes two names different.
 * </ul>
 *
 * <p>Length is <em>not</em> bounded here. It is bounded by Bean Validation on the field, where the
 * limit can differ per field and appears in the OpenAPI document; a blanket cap here would be a
 * second, invisible limit that contradicts the documented one.
 */
@Configuration(proxyBeanMethods = false)
public class TextInputModule {

  /**
   * The module that replaces the default string deserialiser.
   *
   * @return the module Spring Boot adds to the object mapper
   */
  @Bean
  public SimpleModule canonicalTextModule() {
    SimpleModule module = new SimpleModule("home-inv-canonical-text");
    module.addDeserializer(String.class, new CanonicalStringDeserializer());
    return module;
  }

  /** Reads a JSON string and returns it canonicalised. */
  static final class CanonicalStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
      return canonicalise(parser.getString());
    }
  }

  /**
   * Normalises, strips control characters and trims.
   *
   * @param raw the string as it arrived
   * @return the canonical form, or {@code null} when the input was null
   */
  static String canonicalise(String raw) {
    if (raw == null) {
      return null;
    }
    String normalised = Normalizer.normalize(raw, Normalizer.Form.NFC);

    StringBuilder cleaned = new StringBuilder(normalised.length());
    normalised
        .codePoints()
        .forEach(
            codePoint -> {
              if (codePoint == '\n' || codePoint == '\t') {
                cleaned.appendCodePoint(codePoint);
                return;
              }
              // Cc: the C0 and C1 control blocks. Cf: format characters, which is
              // where the bidirectional overrides live - the ones that make a
              // filename render as something other than what it is.
              int type = Character.getType(codePoint);
              if (type == Character.CONTROL || type == Character.FORMAT) {
                return;
              }
              cleaned.appendCodePoint(codePoint);
            });

    return cleaned.toString().strip();
  }
}
