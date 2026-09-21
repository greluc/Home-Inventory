/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which build this is, and where its source is (REQ-CON-009).
 *
 * <h2>Why this is not a nicety</h2>
 *
 * <p>The AGPL obliges whoever runs a modified instance to offer its users the source <b>of that
 * instance</b>. A version number alone does not do it: two builds of {@code 0.1.0} can differ, and
 * the thing that identifies one exactly is the commit. So the commit reaches the artefact at build
 * time and is served here, beside the link it belongs to.
 *
 * <h2>Public, and deliberately so</h2>
 *
 * <p>An obligation that only signed-in users could discharge would be an obligation to the people
 * who already have an account. What it exposes is the build's identity, which is also in every
 * image tag and every release page — and an instance that hid its version would not thereby be
 * harder to attack, only harder to audit.
 */
@Tag(name = "Version", description = "What this instance is running.")
@RestController
@RequestMapping("/api/v1/version")
public class VersionController {

  /** Where the source is, if the build did not say. */
  private static final String SOURCE = "https://github.com/greluc/Home-Inventory";

  private final BuildProperties build;

  /**
   * Takes the build information if the artefact carries any.
   *
   * @param build what {@code bootBuildInfo} wrote, or nothing when the application was started
   *     from a build that did not generate it
   */
  public VersionController(ObjectProvider<BuildProperties> build) {
    this.build = build.getIfAvailable();
  }

  /**
   * The build's identity.
   *
   * @return the version, the commit, when it was built, and where its source is
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "The AGPL's source offer is owed to whoever uses the instance, not only to whoever has "
              + "an account on it (REQ-CON-009). It discloses the build's identity and nothing "
              + "about the data or the people on it.")
  public BuildVersion version() {
    if (build == null) {
      // A build without `bootBuildInfo`, which is a development one. Saying so
      // beats inventing a version: "unknown" is checkable and a made-up number
      // is not.
      return new BuildVersion("unknown", "unknown", null, SOURCE, "AGPL-3.0-or-later");
    }
    return new BuildVersion(
        build.getVersion(),
        build.get("commit") == null ? "unknown" : build.get("commit"),
        build.getTime(),
        build.get("source") == null ? SOURCE : build.get("source"),
        "AGPL-3.0-or-later");
  }

  /**
   * What a client is told about this build.
   *
   * @param version the application's own version
   * @param commit the exact commit it was built from, or {@code unknown} where the build had no
   *     git directory and was given no override
   * @param builtAt when it was built, or {@code null} when the build carried no information
   * @param source where the source is
   * @param licence the licence the source is under, so a reader knows what the offer is about
   */
  public record BuildVersion(
      String version, String commit, Instant builtAt, String source, String licence) {}
}
