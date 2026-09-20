/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capabilities 09 §9.4 publishes are the capabilities a manifest may name.
 *
 * <p>The same fact stated twice as the port list beside it: once in the chapter a plugin author
 * reads, once in {@link PluginManifestReader}. {@code PortCatalogueTest} exists because that other
 * pair had already drifted — a port was added to the requirement and not to the reader, and every
 * manifest declaring it was rejected at registration with a message listing the ports it was not
 * among. Nothing was comparing the two. This is the same comparison for the other list.
 *
 * <p>A capability in the chapter and not in the reader is a permission a plugin cannot ask for; one
 * in the reader and not in the chapter is a permission nobody documented and a tenant administrator
 * is asked to consent to blind.
 */
@DisplayName("The capability catalogue")
class CapabilityCatalogueTest {

  private static final Path CHAPTER =
      Path.of("..", "docs", "architecture", "09-extensibility-and-plugins.md");

  @Test
  @DisplayName("is exactly the table in 09 §9.4")
  void theChapterAndTheReaderAgree() {
    Set<String> published = capabilitiesInTheChapter();

    assertThat(published)
        .as("09 §9.4's table was not found, or holds no capability at all")
        .isNotEmpty();
    assertThat(PluginManifestReader.CAPABILITIES)
        .as(
            "a manifest may name exactly the capabilities 09 §9.4 publishes. One missing here is a "
                + "permission no plugin can ask for; one here that the chapter does not name is a "
                + "permission a tenant administrator is asked to consent to without a description")
        .containsExactlyInAnyOrderElementsOf(published);
  }

  /**
   * Reads the capability ids out of §9.4's table.
   *
   * <p>The first cell of each row, which carries them in backticks. Two rows use the shorthand
   * {@code `core:location:read` / `:write`} — a suffix continuing the id before it — and that is
   * expanded here rather than being rewritten in the chapter, because the shorthand is how a person
   * reads a pair and the expansion is what a machine needs.
   *
   * @return the ids the chapter publishes
   */
  private static Set<String> capabilitiesInTheChapter() {
    List<String> rows = tableRowsOfTheCapabilitySection();
    Set<String> capabilities = new LinkedHashSet<>();
    for (String row : rows) {
      String first = row.split("\\|")[1];
      String previous = null;
      Matcher codes = Pattern.compile("`([a-z:-]+)`").matcher(first);
      while (codes.find()) {
        String code = codes.group(1);
        if (code.startsWith(":") && previous != null) {
          // `:write` after `core:location:read` means core:location:write.
          capabilities.add(previous.substring(0, previous.lastIndexOf(':')) + code);
        } else {
          capabilities.add(code);
          previous = code;
        }
      }
    }
    return capabilities;
  }

  /**
   * The rows of the one table in §9.4 that lists capabilities.
   *
   * @return each row, as the chapter writes it
   */
  private static List<String> tableRowsOfTheCapabilitySection() {
    List<String> rows = new ArrayList<>();
    boolean inTheTable = false;
    for (String line : read().lines().toList()) {
      if (line.startsWith("| Capability | Permits |")) {
        inTheTable = true;
        continue;
      }
      if (inTheTable) {
        if (!line.startsWith("|")) {
          break;
        }
        if (!line.startsWith("|---")) {
          rows.add(line);
        }
      }
    }
    return rows;
  }

  private static String read() {
    try {
      return Files.readString(CHAPTER);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "09 Extensibility could not be read. This test compares the capability set with the "
              + "chapter that publishes it, and cannot run without it.",
          unreadable);
    }
  }
}
