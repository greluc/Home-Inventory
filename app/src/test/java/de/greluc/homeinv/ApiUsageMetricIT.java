/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Usage per endpoint, version and client (REQ-API-009).
 *
 * <p>ADR-0011: without this measurement "a shutdown is a leap in the dark". 08 §8 turns that into a
 * rule — an API version is retired only when usage is zero or the period has elapsed — so the
 * number has to exist and has to be the right shape.
 *
 * <p>What the tests are really about is <b>cardinality</b>. A Prometheus series exists per
 * combination of tag values, so a tag that a caller can choose freely is an outage waiting for the
 * right request: the route template rather than the path, and the client product matched against a
 * known set with everything else collapsed to {@code other}.
 */
@DisplayName("The API usage metric")
class ApiUsageMetricIT extends AbstractIntegrationTest {

  @Autowired private MeterRegistry meters;

  @Test
  @DisplayName("counts the route template and the version, not the path")
  void countsTheTemplate() throws Exception {
    mockMvc.perform(get("/api/v1/version")).andReturn();

    assertThat(counted("/api/v1/version", "v1", "other")).isPositive();
  }

  @Test
  @DisplayName("names a client it knows, and collapses one it does not")
  void theClientTagIsBounded() throws Exception {
    mockMvc.perform(get("/api/v1/version").header("X-Home-Inv-Client", "web/1.2.3")).andReturn();
    mockMvc
        .perform(get("/api/v1/version").header("X-Home-Inv-Client", "definitely-not-ours/9"))
        .andReturn();

    assertThat(counted("/api/v1/version", "v1", "web")).isPositive();
    // Anything unrecognised is one series and not one per value: the header is
    // chosen by the caller, so a free tag would let any request invent a series.
    assertThat(counted("/api/v1/version", "v1", "other")).isPositive();
    assertThat(clientTags()).allMatch(tag -> tag.matches("web|android|ios|desktop|cli|plugin|other"));
  }

  @Test
  @DisplayName("does not put the client's version in the tag")
  void theVersionIsNotATag() throws Exception {
    mockMvc.perform(get("/api/v1/version").header("X-Home-Inv-Client", "web/9.9.9")).andReturn();

    // The version is what a deprecation notice is addressed to, not what a
    // shutdown decision needs -- and every release of every client would
    // otherwise be its own time series.
    assertThat(clientTags()).doesNotContain("web/9.9.9", "9.9.9");
  }

  private double counted(String endpoint, String version, String client) {
    return Search.in(meters)
        .name("homeinv.api.requests")
        .tag("endpoint", endpoint)
        .tag("version", version)
        .tag("client", client)
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  private java.util.List<String> clientTags() {
    return Search.in(meters).name("homeinv.api.requests").counters().stream()
        .map(counter -> counter.getId().getTag("client"))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }
}
