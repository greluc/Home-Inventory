/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third-party notices, reachable from the running installation (REQ-CON-013).
 *
 * <p>Two properties, and both are the requirement rather than decoration. It answers <b>without a
 * session</b>, because a licence notice is owed to whoever receives a copy of the software and not
 * to whoever has an account on it; and it carries the <b>text</b>, because a list of names
 * discharges nothing — what MIT and BSD ask for is the copyright line and the permission notice,
 * and those are words rather than identifiers.
 *
 * <p>[ADR-0034](../../../../../docs/adr/0034-icon-set-and-no-third-party-hosts.md) dated this
 * obligation by trigger rather than by calendar: *with the first distributed build*.
 */
@DisplayName("The third-party notices endpoint")
class NoticesEndpointIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("answers anybody, with the components and their licence texts")
  void anybodyMayRead() throws Exception {
    mockMvc
        .perform(get("/api/v1/version/notices"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.artifact").value("app"))
        .andExpect(jsonPath("$.components.length()").value(Matchers.greaterThan(100)))
        .andExpect(jsonPath("$.components[0].name").isNotEmpty())
        .andExpect(jsonPath("$.components[0].licences").isArray())
        // The appendix carries the licences whose wording is the same for every
        // component under them; Apache-2.0 is the one every build here has.
        .andExpect(jsonPath("$.licences[?(@.id == 'Apache-2.0')].text").isNotEmpty());
  }

  @Test
  @DisplayName("reproduces a licence rather than naming it")
  void theWordsAreThere() throws Exception {
    mockMvc
        .perform(get("/api/v1/version/notices"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.licences[?(@.id == 'Apache-2.0')].text")
                .value(
                    Matchers.hasItem(
                        Matchers.containsString("TERMS AND CONDITIONS FOR USE, REPRODUCTION"))));
  }
}
