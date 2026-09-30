/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The AGPL's source offer, discharged by the running instance (REQ-CON-009).
 *
 * <p>Two properties, and both are the requirement rather than decoration. It answers <b>without a
 * session</b>, because an offer only signed-in people could take up would be owed only to them; and
 * it names the <b>commit</b>, because a version number does not identify a build — two builds of
 * {@code 0.1.0} can differ, and the source somebody is owed is the source of the instance they are
 * using.
 */
@DisplayName("The version endpoint")
class VersionEndpointIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("answers anybody, and says which build this is and where its source is")
  void anybodyMayAsk() throws Exception {
    mockMvc
        .perform(get("/api/v1/version"))
        .andExpect(status().isOk())
        .andExpect(content -> MediaType.APPLICATION_JSON.isCompatibleWith(
            MediaType.parseMediaType(content.getResponse().getContentType())))
        .andExpect(jsonPath("$.version").isNotEmpty())
        .andExpect(jsonPath("$.commit").isNotEmpty())
        .andExpect(jsonPath("$.source").value("https://github.com/greluc/Home-Inventory"))
        .andExpect(jsonPath("$.licence").value("AGPL-3.0-or-later"));
  }
}
