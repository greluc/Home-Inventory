/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.search.infrastructure.SearchEngineProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which engine answers a search, and what happens when the answer is half-configured
 * (REQ-SRCH-005, ADR-0008).
 *
 * <p>A plain unit test: this is configuration validation, and the value of it is entirely in the
 * refusals. Starting a container to watch a string be parsed would test the container.
 *
 * <p>The property worth protecting is that a half-configured OpenSearch <b>aborts startup</b>
 * rather than falling back. An operator who asked for OpenSearch and silently got PostgreSQL has a
 * deployment that is not the one they described, and would learn about it from a performance
 * report months later — which is the same argument as every other missing secret in this codebase
 * (CLAUDE.md, Security; REQ-NFR-046).
 */
@DisplayName("The search engine switch")
class SearchEngineSwitchTest {

  private static final String URL = "https://opensearch:9200";
  private static final String USER = "homeinv";
  /**
   * Stands in for the value a mounted secret file would hold.
   *
   * <p>Not password-shaped, and not called one: this test cares only whether something was
   * configured, and a constant named {@code PASSWORD} beside a string is the exact shape a secret
   * scanner is built to find. Nothing secret ever lands in this repository, including things that
   * only look like it (CLAUDE.md, Security).
   */
  private static final String MOUNTED = "mounted";

  @Test
  @DisplayName("defaults to PostgreSQL, which every profile has")
  void theDefault() {
    SearchEngineProperties properties = new SearchEngineProperties("postgresql", "", "", "");
    assertThat(properties.usesOpenSearch()).isFalse();
    assertThat(properties.getUrl()).isNull();

    // `minimal` leaves the variable unset altogether, and the placeholder's own
    // default is what it lands on.
    assertThat(new SearchEngineProperties("PostgreSQL", null, null, null).getEngine())
        .as("the name is read without regard to case, like HOMEINV_REGISTRATION_MODE")
        .isEqualTo(SearchEngineProperties.Engine.POSTGRESQL);
  }

  @Test
  @DisplayName("takes OpenSearch when it is given everything it needs")
  void openSearchConfigured() {
    SearchEngineProperties properties =
        new SearchEngineProperties("opensearch", URL, USER, MOUNTED);
    assertThat(properties.usesOpenSearch()).isTrue();
    assertThat(properties.getUrl().toString()).isEqualTo(URL);
    assertThat(properties.getUsername()).isEqualTo(USER);
  }

  @Test
  @DisplayName("refuses to start on a half-configured OpenSearch rather than using the other engine")
  void halfConfigured() {
    assertThatThrownBy(() -> new SearchEngineProperties("opensearch", "", USER, MOUNTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOMEINV_SEARCH_URL");

    assertThatThrownBy(() -> new SearchEngineProperties("opensearch", URL, "", MOUNTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOMEINV_SEARCH_USER");

    // The password is named by its VARIABLE and never by its value, here as
    // everywhere else (REQ-SEC-050).
    assertThatThrownBy(() -> new SearchEngineProperties("opensearch", URL, USER, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOMEINV_SEARCH_PASSWORD_FILE");

    assertThatThrownBy(() -> new SearchEngineProperties("opensearch", "not a url", USER, MOUNTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a URL");
  }

  @Test
  @DisplayName("refuses an engine nobody ships")
  void anUnknownEngine() {
    assertThatThrownBy(() -> new SearchEngineProperties("elasticsearch", "", "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'postgresql' or 'opensearch'");
  }
}
