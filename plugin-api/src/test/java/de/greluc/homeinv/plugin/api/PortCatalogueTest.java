/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ports REQ-PLG-001 names are the ports that exist.
 *
 * <p>The requirement lists fifteen extension points by name. That list is a fact stated twice —
 * once in the catalogue and once in this source tree — and this test is what keeps the second copy
 * from drifting. A port renamed in code and not in the requirement fails here, which is the
 * cheapest moment to notice.
 *
 * <p>Six of the fifteen belong to features that ship at stage 2 or 3. They exist now because the
 * contract is written once (ADR-0028), and because a port added after the runtime was built is a
 * port the runtime was not designed for.
 */
@DisplayName("The port catalogue")
class PortCatalogueTest {

  private static final Path REQUIREMENTS = Path.of("..", "docs", "requirements", "01-functional.md");
  private static final String PORT_PACKAGE = "de.greluc.homeinv.plugin.api.port.";

  @Test
  @DisplayName("holds an interface for every port REQ-PLG-001 names")
  void everyNamedPortExists() {
    List<String> named = portsNamedByTheRequirement();

    assertThat(named)
        .as("REQ-PLG-001 names the ports in its description cell; the row was not found or is empty")
        .hasSize(15);

    List<String> missing = new ArrayList<>();
    List<String> notAnInterface = new ArrayList<>();
    for (String port : named) {
      Class<?> type;
      try {
        type = Class.forName(PORT_PACKAGE + port);
      } catch (ClassNotFoundException absent) {
        missing.add(port);
        continue;
      }
      if (!type.isInterface() || !Modifier.isPublic(type.getModifiers())) {
        notAnInterface.add(port);
      }
    }

    assertThat(missing)
        .as("REQ-PLG-001 names these ports and %s has no interface for them", PORT_PACKAGE)
        .isEmpty();
    assertThat(notAnInterface)
        .as("a port is a public interface: a plugin implements it from another codebase")
        .isEmpty();
  }

  /**
   * Reads the port names out of the requirement's own description.
   *
   * @return the names, in the order the requirement lists them
   */
  private static List<String> portsNamedByTheRequirement() {
    String row =
        read().lines()
            .filter(line -> line.startsWith("| REQ-PLG-001 "))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "REQ-PLG-001 is not in docs/requirements/01-functional.md. It is the "
                            + "requirement this whole package implements."));

    // The description cell only. The verification cell after it names test
    // classes and chapters in backticks too, and those are not ports.
    String[] cells = row.split("\\|");
    String description = cells.length > 2 ? cells[2] : "";

    List<String> ports = new ArrayList<>();
    Matcher names = Pattern.compile("`([A-Z][A-Za-z]+)`").matcher(description);
    while (names.find()) {
      ports.add(names.group(1));
    }
    return ports;
  }

  private static String read() {
    try {
      return Files.readString(REQUIREMENTS);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("The requirements catalogue could not be read", unreadable);
    }
  }
}
