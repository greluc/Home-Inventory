/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.platform.EventType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The published list of event types and the code agree (REQ-API-010).
 *
 * <p>An event type leaves this deployment: a tenant administrator subscribes a webhook target to
 * one, it is stored in {@code notification.webhook_target.event_types}, and a receiver branches on
 * it in a system nobody here controls. That is the same kind of identifier as a {@code
 * problem.type} token or a plugin health state, and it gets the same treatment — a registry in
 * {@code docs/reference/} and a test that fails when the two sets differ <b>in either direction</b>.
 *
 * <p>One direction catches a new event nobody published; the other catches a name removed from the
 * code while somebody's subscription still holds it.
 */
@DisplayName("The event type registry")
class EventTypeRegistryTest {

  private static final Path REGISTRY =
      Path.of("..", "docs", "reference", "event-types.yaml");

  private static final Path SOURCES = Path.of("src", "main", "java");

  /** {@code   - type: item.created} — the list entries and nothing in the prose above them. */
  private static final Pattern REGISTRY_TYPE =
      Pattern.compile("^  - type: ([a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*)$", Pattern.MULTILINE);

  /** {@code     event: de.greluc.homeinv.inventory.api.ItemCreated} */
  private static final Pattern REGISTRY_EVENT = Pattern.compile("^    event: (\\S+)$", Pattern.MULTILINE);

  /** {@code @Externalized("homeinv.inventory::item-created.v1")} */
  private static final Pattern EXTERNALIZED = Pattern.compile("@Externalized\\(\"([^\"]+)\"\\)");

  /** {@code return EventType.ITEM_CREATED;} in an event's own {@code eventType()}. */
  private static final Pattern DECLARED = Pattern.compile("return EventType\\.([A-Z_]+);");

  @Test
  @DisplayName("holds exactly the types the code raises")
  void theRegistryAndTheEnumAgree() throws IOException {
    Set<String> declared = new LinkedHashSet<>();
    for (EventType type : EventType.values()) {
      declared.add(type.id());
    }

    Set<String> published = new LinkedHashSet<>();
    Matcher types = REGISTRY_TYPE.matcher(Files.readString(REGISTRY, StandardCharsets.UTF_8));
    while (types.find()) {
      published.add(types.group(1));
    }

    assertThat(published)
        .as(
            "docs/reference/event-types.yaml is the published list a receiver reads."
                + " A type in the enum and not in it is one nobody can find out about;"
                + " a type in it and not in the enum is one a subscription can name and"
                + " that will never fire.")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  @Test
  @DisplayName("names, for each type, the event class that raises it")
  void everyRegistryEntryNamesAnEventThatExists() throws IOException {
    String registry = Files.readString(REGISTRY, StandardCharsets.UTF_8);
    List<String> missing = new ArrayList<>();

    Matcher events = REGISTRY_EVENT.matcher(registry);
    while (events.find()) {
      String className = events.group(1);
      Path source = SOURCES.resolve(className.replace('.', '/') + ".java");
      if (!Files.exists(source)) {
        missing.add(className);
      }
    }

    assertThat(missing)
        .as("A registry entry naming a class that is not here is a renamed event nobody followed")
        .isEmpty();
  }

  @Test
  @DisplayName("agrees with the routing key of every event that also goes to the broker")
  void theTypeAndTheRoutingKeyAreOneName() throws IOException {
    Map<String, String> wrong = new LinkedHashMap<>();

    try (Stream<Path> sources = Files.walk(SOURCES)) {
      sources
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                String text = read(path);
                Matcher externalized = EXTERNALIZED.matcher(text);
                Matcher declared = DECLARED.matcher(text);
                if (!externalized.find() || !declared.find()) {
                  return;
                }
                String routingKey = externalized.group(1);
                String type = EventType.valueOf(declared.group(1)).id();
                // `item.type-changed` has to be findable inside
                // `homeinv.inventory::item-type-changed.v1`. Two names for one
                // event is how the two drift.
                if (!routingKey.contains(type.replace('.', '-'))) {
                  wrong.put(path.getFileName().toString(), routingKey + " vs " + type);
                }
              });
    }

    assertThat(wrong)
        .as(
            "An event that is both externalised and subscribable carries two names, and they are"
                + " the same name written twice. A routing key that does not contain its event"
                + " type means one of them was renamed alone.")
        .isEmpty();
  }

  @Test
  @DisplayName("gives every type a noun a live stream can send")
  void everyTypeHasANoun() {
    for (EventType type : EventType.values()) {
      assertThat(type.id()).matches("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*");
      assertThat(type.noun()).isNotEmpty().doesNotContain(".");
      assertThat(EventType.of(type.id())).contains(type);
    }
    assertThat(EventType.of("item.exploded")).isEmpty();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
