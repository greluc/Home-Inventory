/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every event that leaves this deployment says which version of itself it is (REQ-CON-004).
 *
 * <h2>Why the version is in the routing key</h2>
 *
 * <p>Four contracts are versioned separately because they change at different speeds
 * ({@code 08 §8.3}), and an event schema is one of them. The version sits in the routing key rather
 * than inside the payload so that a consumer can <b>route</b> on it: two versions of one event can
 * run side by side, each with its own binding, and a consumer that does not understand the second
 * simply never receives it. A version inside the message would force every consumer to open every
 * message to find out whether it can read it.
 *
 * <p>The naming is {@code homeinv.<block>::<event>.v<n>} — Spring Modulith's {@code target::key}
 * form, which is what the broker binds on. {@code 08 §8.3} described it as
 * {@code de.greluc.homeinv.item.created.v1} until 2026-09-16; the code was right about the shape
 * and wrong about nothing, so the chapter was corrected rather than the annotations.
 */
@DisplayName("Event schemas")
class EventSchemaVersionTest {

  private static final Path SOURCES = Path.of("src", "main", "java");

  /** {@code @Externalized("homeinv.inventory::item-created.v1")} and nothing shaped otherwise. */
  private static final Pattern EXTERNALIZED =
      Pattern.compile("@Externalized\\(\"([^\"]+)\"\\)");

  /** The key half of a {@code @RabbitListener} binding. */
  private static final Pattern BINDING_KEY = Pattern.compile("key = \"([^\"]+)\"");

  /** What a routing key has to look like for a consumer to be able to bind a version of it. */
  private static final Pattern VERSIONED =
      Pattern.compile("^homeinv\\.[a-z]+::[a-z][a-z-]*\\.v[1-9][0-9]*$");

  @Test
  @DisplayName("carry a version in the routing key, so two of them can run side by side")
  void everyExternalisedEventIsVersioned() throws IOException {
    List<String> unversioned = new ArrayList<>();
    List<String> keys = new ArrayList<>();

    try (Stream<Path> sources = Files.walk(SOURCES)) {
      sources
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                Matcher events = EXTERNALIZED.matcher(read(path));
                while (events.find()) {
                  String key = events.group(1);
                  keys.add(key);
                  if (!VERSIONED.matcher(key).matches()) {
                    unversioned.add(path.getFileName() + ": " + key);
                  }
                }
              });
    }

    assertThat(keys)
        .as("externalised events found at all — a pattern that matches nothing proves nothing")
        .isNotEmpty();
    assertThat(unversioned)
        .as(
            "REQ-CON-004: an event schema is one of the four separately versioned contracts, and "
                + "the version belongs in the routing key so a consumer can bind on it. Expected "
                + "`homeinv.<block>::<event>.v<n>`")
        .isEmpty();
  }


  @Test
  @DisplayName("are consumed under the key they are published as, version included")
  void everyBindingMatchesAnEvent() throws IOException {
    List<String> published = new ArrayList<>();
    List<String> bound = new ArrayList<>();

    try (Stream<Path> sources = Files.walk(SOURCES)) {
      sources
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                String source = read(path);
                Matcher events = EXTERNALIZED.matcher(source);
                while (events.find()) {
                  // The key half of `target::key`, which is what a binding binds.
                  String key = events.group(1);
                  published.add(key.substring(key.indexOf("::") + 2));
                }
                Matcher bindings = BINDING_KEY.matcher(source);
                while (bindings.find()) {
                  bound.add(bindings.group(1));
                }
              });
    }

    // The failure this catches is silent in production and loud here: renaming a
    // published event without its consumers leaves a queue bound to a key
    // nothing publishes any more, so derivatives stop being generated and the
    // search index stops being updated while everything reports success. Adding
    // `.v1` to ten events did exactly that on 2026-09-16, and this is what says
    // so next time.
    assertThat(bound)
        .as("every @RabbitListener binds a key some event is actually published under")
        .isSubsetOf(published);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("Could not read " + path, unreadable);
    }
  }
}
