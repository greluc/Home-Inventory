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
 * Every Gradle project exists inside every image that runs Gradle.
 *
 * <h2>Why this is a test and not a comment in two Dockerfiles</h2>
 *
 * <p>Gradle <b>configures every project in {@code settings.gradle.kts}</b> whatever task is asked
 * for, and refuses one whose directory is not there. An image builds from a partial context — the
 * application image copies what the application needs and nothing else, which is the point of it —
 * so a project added to the build is a project that must also be made to exist in each of those
 * contexts, or the image stops building.
 *
 * <p>Nothing about that is discoverable from the Dockerfile, the settings file or the error, which
 * names a task the image does not run. It has cost two CI rounds:
 *
 * <ul>
 *   <li>{@code :plugins:oidc} on 2026-09-21, which broke the application image;
 *   <li>{@code :api-client-kotlin} the same day, which broke the application image, the plugin
 *       image and both smoke runtimes with them.
 * </ul>
 *
 * <p>The second time is what makes it a rule rather than a mistake.
 *
 * <h2>Two ways to satisfy it, and they are not the same</h2>
 *
 * <p>A {@code COPY} of the project's build file, for a project the image actually builds; or a
 * {@code mkdir}, for one it does not. The second is deliberate and cheaper: a project needs a
 * directory to be <b>configured</b> and a build file only to <b>do</b> anything, so an image that
 * builds a Java artefact does not have to resolve the Kotlin toolchain to ignore a Kotlin project.
 *
 * <p>No container: this reads files.
 */
@DisplayName("The Gradle projects")
class GradleProjectsInImagesTest {

  private static final Path ROOT = Path.of("..");

  /** {@code include(":app")} and {@code include(":plugins:oidc")}. */
  private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\(\"(:[^\"]+)\"\\)", Pattern.MULTILINE);

  /** {@code project(":x").projectDir = file("some/where")} — a project that is not at its path. */
  private static final Pattern RELOCATED =
      Pattern.compile("project\\(\"(:[^\"]+)\"\\)\\.projectDir\\s*=\\s*file\\(\"([^\"]+)\"\\)");

  @Test
  @DisplayName("each exist in every image that runs Gradle")
  void everyProjectExistsInEveryGradleImage() throws IOException {
    String settings = Files.readString(ROOT.resolve("settings.gradle.kts"), StandardCharsets.UTF_8);

    Map<String, String> directories = new LinkedHashMap<>();
    Matcher includes = INCLUDE.matcher(settings);
    while (includes.find()) {
      String project = includes.group(1);
      directories.put(project, project.substring(1).replace(':', '/'));
    }
    Matcher relocated = RELOCATED.matcher(settings);
    while (relocated.find()) {
      directories.put(relocated.group(1), relocated.group(2));
    }
    assertThat(directories).as("the settings file declares projects").isNotEmpty();

    List<String> missing = new ArrayList<>();
    for (Path dockerfile : gradleImages()) {
      String text = Files.readString(dockerfile, StandardCharsets.UTF_8);
      for (Map.Entry<String, String> project : directories.entrySet()) {
        String directory = project.getValue();
        boolean present =
            text.contains("COPY " + directory + "/build.gradle.kts")
                || text.contains("COPY " + directory + "/")
                || text.contains("mkdir -p " + directory);
        if (!present) {
          missing.add(
              ROOT.relativize(dockerfile).toString().replace('\\', '/')
                  + " has nothing for "
                  + project.getKey()
                  + " (" + directory + ")");
        }
      }
    }

    assertThat(missing)
        .as(
            "Gradle configures every project whatever task is asked for, so a project the image "
                + "does not build still has to EXIST in its context. Copy its build file, or "
                + "`mkdir -p` its directory when the image has no use for it")
        .isEmpty();
  }

  /**
   * Every Dockerfile that runs Gradle.
   *
   * <p>Found by what it does rather than by a list: an image that runs {@code ./gradlew} is subject
   * to this whether or not anybody remembered to add it here.
   *
   * @return the Dockerfiles
   * @throws IOException when the tree cannot be walked
   */
  private static Set<Path> gradleImages() throws IOException {
    Set<Path> images = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(ROOT, 4)) {
      files
          .filter(path -> path.getFileName().toString().equals("Dockerfile"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/build/"))
          .forEach(
              path -> {
                if (read(path).contains("./gradlew")) {
                  images.add(path);
                }
              });
    }
    assertThat(images).as("some image builds with Gradle").isNotEmpty();
    return images;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
