/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * What this installation is built from, and under which licences (REQ-CON-013).
 *
 * <h2>Why an endpoint rather than a file in the repository</h2>
 *
 * <p>A permissive licence — MIT, BSD, ISC, Apache-2.0 — permits the copying on one condition: that
 * its notice appears <b>in all copies</b>. A container image is a copy, and the {@code LICENSE}
 * file sitting in a public repository is not part of it. So the notice travels inside the artifact,
 * as a resource in this jar, and is reachable from the running installation beside the version and
 * the source link that discharge the AGPL's own obligation
 * ([ADR-0034](docs/adr/0034-icon-set-and-no-third-party-hosts.md), REQ-CON-009).
 *
 * <h2>Public, for the same reason the version is</h2>
 *
 * <p>An attribution only signed-in people could read would be an attribution owed to whoever has an
 * account. What it discloses is the list of open-source libraries in a public repository's build,
 * which is also in the SBOM published with every release.
 *
 * <h2>Generated, and checked</h2>
 *
 * <p>The document is produced by {@code tools/notices.py} from what the boot jar actually carries
 * rather than from what the build files ask for, and CI regenerates it and fails on any difference.
 * Nothing here interprets it: an endpoint that summarised a licence notice would be an endpoint
 * that could get one wrong.
 */
@Tag(name = "Version", description = "What this instance is running.")
@RestController
@RequestMapping("/api/v1/version/notices")
public class NoticesController {

  /** Where {@code tools/notices.py} writes the document this serves. */
  private static final String RESOURCE = "third-party-notices.json";

  private final ThirdPartyNoticesView notices;

  /**
   * Reads the notice out of the jar, once.
   *
   * <p>It refuses to start without it. An installation that cannot show its third-party notices is
   * one distributing other people's work without the attribution they asked for, and that is a
   * defect of the build rather than a runtime condition to degrade around — the same reasoning
   * that makes a missing secret abort startup.
   *
   * @param json the application's configured mapper
   * @throws IllegalStateException when the artifact carries no notice, or an unreadable one
   */
  public NoticesController(ObjectMapper json) {
    try (InputStream source = new ClassPathResource(RESOURCE).getInputStream()) {
      this.notices = json.readValue(source, ThirdPartyNoticesView.class);
    } catch (IOException | RuntimeException unreadable) {
      throw new IllegalStateException(
          "This artifact carries no third-party licence notice at classpath:"
              + RESOURCE
              + ", which REQ-CON-013 requires of every distributed artifact. Run "
              + "`python tools/notices.py app` and rebuild.",
          unreadable);
    }
  }

  /**
   * Every third-party component in this artifact, with the licence text it is under.
   *
   * <p>Cached for an hour, which is the one thing this endpoint does beyond serving a file. The
   * document is a few hundred kilobytes of licence text and does not change while a build is
   * running, so re-sending it on every open of the dialog would be the only expensive thing an
   * unauthenticated caller can ask this application for. An hour and not a year: the URL is the
   * same across builds, so a long lifetime would show somebody the notice of the version they
   * were running yesterday.
   *
   * @return the notice, as generated for this build
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "A licence notice is owed to whoever receives a copy of the software, not to whoever has "
              + "an account on it (REQ-CON-013). It discloses the build's dependency list, which "
              + "is also in the SBOM published with the release.")
  public ResponseEntity<ThirdPartyNoticesView> notices() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(notices);
  }

  /**
   * The whole notice for one artifact.
   *
   * @param artifact which artifact this describes, as {@code tools/notices.py} names it
   * @param title what that artifact is called in prose
   * @param components everything third-party it carries
   * @param licences the full text of every licence whose wording is the same for all of them,
   *     reproduced once; a licence whose text carries a copyright line is with its component
   *     instead
   */
  public record ThirdPartyNoticesView(
      String artifact,
      String title,
      List<ThirdPartyComponentView> components,
      List<ThirdPartyLicenceView> licences) {}

  /**
   * One third-party component the artifact carries.
   *
   * @param name the component's name as the artifact spells it
   * @param version its version
   * @param licences the SPDX identifiers it is declared under, or the names where what was
   *     declared is not an identifier
   * @param notices the licence and notice files the component itself ships, verbatim; empty where
   *     the licence's text is the same for every component and appears in {@code licences}
   */
  public record ThirdPartyComponentView(
      String name, String version, List<String> licences, List<ThirdPartyNoticeView> notices) {}

  /**
   * One file a component ships, reproduced as it is.
   *
   * @param path where the file sat inside the component, so a reader can check it
   * @param text the file, verbatim
   */
  public record ThirdPartyNoticeView(String path, String text) {}

  /**
   * The full text of one licence.
   *
   * @param id the SPDX identifier
   * @param source where this copy of the text came from, named so the provenance is answerable
   * @param text the licence, verbatim
   */
  public record ThirdPartyLicenceView(String id, String source, String text) {}
}
