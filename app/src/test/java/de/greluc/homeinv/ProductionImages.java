/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/**
 * The images the deployment runs, read from {@code deploy/services.yaml}.
 *
 * <h2>Why the tests do not name their own</h2>
 *
 * <p>{@code REQ-NFR-027} asks that integration tests run against <b>the same images as
 * production</b>, and until 2026-09-12 that was two lists in two files kept in step by hand. They
 * were not in step: production ran {@code rabbitmq:4-management-alpine} and the tests ran
 * {@code rabbitmq:4-alpine} — a different image, so every AMQP test proved something about a broker
 * the deployment does not use. Nothing caught it, because nothing compared the two.
 *
 * <p>So there is one list now, and it is the matrix. {@code REQ-CON-015}'s rule is that a fact
 * stated twice is checked rather than trusted; the cheaper form of the same rule is to state it
 * once, which is what this does.
 *
 * <h2>Digests</h2>
 *
 * <p>When the matrix carries a digest, that is what is pulled — which is the whole of
 * {@code REQ-NFR-027}, and every image an integration test starts carries one. The images built
 * from this repository carry none, because nothing has been published; they are the deployment's
 * own, no test starts one, and pinning and signing them is {@code REQ-SEC-077} at stage 1. The day
 * one is published, the tests pin it without a line of test code changing, because they were never
 * the place the coordinates lived.
 *
 * <h2>PostgreSQL is the one exception, and it is not a substitute</h2>
 *
 * <p>The deployment runs {@code ghcr.io/greluc/home-inv-postgres}, which is upstream
 * {@code postgres:18-alpine} with this deployment's {@code initdb} scripts baked into a layer.
 * The tests run that same upstream base and copy the same {@code 00-roles.sql} in — Gradle puts the
 * production file on the test classpath, so there is one role script and not a copy of one. What
 * differs is how the file gets into the container, not what runs.
 */
final class ProductionImages {

  private static final Map<String, Map<String, Object>> SERVICES = load();

  private ProductionImages() {}

  /**
   * The image one service of the deployment runs.
   *
   * @param service the service's key in {@code deploy/services.yaml}
   * @return its image, pinned to the digest when the matrix carries one
   */
  static DockerImageName of(String service) {
    Map<String, Object> entry = SERVICES.get(service);
    if (entry == null) {
      throw new IllegalArgumentException(
          "deploy/services.yaml has no service '" + service + "'; it has " + SERVICES.keySet());
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> image = (Map<String, Object>) entry.get("image");
    String name = String.valueOf(image.get("name"));
    Object digest = image.get("digest");
    Object tag = image.get("tag");

    if (digest != null) {
      // The digest wins over the tag, because that is what the requirement asks
      // for: the same bytes, not the same label pointing at whatever was pushed
      // last.
      return DockerImageName.parse(name + "@" + digest);
    }
    return DockerImageName.parse(tag == null ? name : name + ":" + tag);
  }

  /**
   * The image {@code deploy/images/postgres/Dockerfile} is built {@code FROM}.
   *
   * <p>The deployment runs {@code ghcr.io/greluc/home-inv-postgres}, which is that base with this
   * deployment's {@code initdb} scripts baked in — and which no test can pull, because it is built
   * from this repository rather than published. So the tests run the base and copy the same role
   * script in, and the base is read from the Dockerfile that defines it rather than written out a
   * second time here.
   *
   * @return the base image, from the one file that names it
   */
  static DockerImageName postgresBase() {
    try (InputStream dockerfile =
        ProductionImages.class.getResourceAsStream("/deploy/postgres-image.Dockerfile")) {
      if (dockerfile == null) {
        throw new IllegalStateException(
            "deploy/images/postgres/Dockerfile is not on the test classpath");
      }
      for (String line : new String(dockerfile.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          .split("\\R")) {
        if (line.startsWith("FROM ")) {
          return DockerImageName.parse(line.substring("FROM ".length()).trim().split("\\s+")[0]);
        }
      }
      throw new IllegalStateException("deploy/images/postgres/Dockerfile carries no FROM");
    } catch (IOException unreadable) {
      throw new IllegalStateException("the postgres Dockerfile could not be read", unreadable);
    }
  }

  /**
   * Reads the matrix from the test classpath.
   *
   * <p>Gradle copies {@code deploy/services.yaml} into the test resources, the same way it copies
   * the production role script: a test that read it from a relative path would depend on the
   * working directory, and a second copy of it in this directory would be the drift this class
   * exists to remove.
   *
   * @return the {@code services} block, by service name
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> load() {
    try (InputStream matrix =
        ProductionImages.class.getResourceAsStream("/deploy/services.yaml")) {
      if (matrix == null) {
        throw new IllegalStateException(
            "deploy/services.yaml is not on the test classpath; processTestResources copies it");
      }
      Map<String, Object> root = new Yaml().load(matrix);
      return (Map<String, Map<String, Object>>) root.get("services");
    } catch (IOException unreadable) {
      throw new IllegalStateException("deploy/services.yaml could not be read", unreadable);
    }
  }
}
