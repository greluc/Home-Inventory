/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code REQ-NFR-027}'s acceptance criterion, which is *"the Testcontainers configuration
 * verified"* — so it is verified rather than arranged.
 *
 * <h2>What it stops</h2>
 *
 * <p>The requirement says integration tests run against the same images as production. Between the
 * matrix and the test harness that was two lists kept in step by hand, and they had drifted:
 * production ran {@code rabbitmq:4-management-alpine} while the tests ran {@code rabbitmq:4-alpine}.
 * A management plugin is the smallest version of that difference; the next one need not be.
 *
 * <p>Reading the coordinates from {@code deploy/services.yaml} fixed it once. This keeps it fixed,
 * by refusing the thing that caused it: an image named literally in a test.
 *
 * <p>No container: this reads files.
 */
@DisplayName("The test images")
class ProductionImagesTest {

  private static final Path TEST_SOURCES = Path.of("src", "test", "java");

  /**
   * The call that names an image, assembled rather than written.
   *
   * <p>Written whole it would appear in this file and the rule would report itself — which it did,
   * on the first run. Assembling it keeps the rule applying to every file including this one,
   * instead of exempting the file by name and creating a hole the next test could sit in.
   */
  private static final String NAMES_AN_IMAGE = "DockerImageName" + ".parse(";

  @Test
  @DisplayName("come from the deployment matrix, never from a literal in a test (REQ-NFR-027)")
  void noTestNamesItsOwnImage() throws IOException {
    try (Stream<Path> sources = Files.walk(TEST_SOURCES)) {
      List<String> offenders =
          sources
              .filter(path -> path.toString().endsWith(".java"))
              // ProductionImages is the one place allowed to name an image, which
              // is what makes it the single list.
              .filter(path -> !path.getFileName().toString().equals("ProductionImages.java"))
              .filter(
                  path -> {
                    try {
                      return Files.readString(path, StandardCharsets.UTF_8)
                          .contains(NAMES_AN_IMAGE);
                    } catch (IOException unreadable) {
                      throw new java.io.UncheckedIOException(unreadable);
                    }
                  })
              .map(path -> path.getFileName().toString())
              .toList();

      assertThat(offenders)
          .as(
              "a test naming its own image is how the harness drifted from the deployment "
                  + "once already; take it from ProductionImages, which reads services.yaml")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("resolve to what the deployment runs")
  void coordinatesMatchTheMatrix() {
    // Read back through the same accessor the containers use, so this fails for
    // the same reason they would rather than for a reason of its own.
    //
    // Asserted as a DIGEST rather than against a literal digest string: pinning one
    // is a deliberate act and bumping it must not mean editing a test, but running
    // against a floating tag when the matrix names a digest is exactly the drift
    // REQ-NFR-027 is about.
    assertThat(ProductionImages.of("valkey").asCanonicalNameString())
        .as("valkey is pulled by digest, as the matrix pins it")
        .startsWith("docker.io/valkey/valkey@sha256:");
    assertThat(ProductionImages.of("rabbitmq").asCanonicalNameString())
        .as("the deployment runs the management image by digest, and so must the tests")
        .startsWith("docker.io/library/rabbitmq@sha256:");

    // PostgreSQL is the documented exception: the deployment's own image is built
    // from this repository and cannot be pulled, so the tests run the base it is
    // built FROM — which is pinned in that Dockerfile — and copy the same role
    // script in.
    assertThat(ProductionImages.postgresBase().asCanonicalNameString())
        .startsWith("docker.io/library/postgres@sha256:");
  }
}
