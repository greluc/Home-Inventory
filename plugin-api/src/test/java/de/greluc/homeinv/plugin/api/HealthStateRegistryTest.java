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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The health states in code and the ones in {@code docs/} are the same set.
 *
 * <p>A state ends up in an operator UI, in a metric label and in an alert rule, which is why it is
 * written down where data lives rather than only in an enum. Two hand-maintained copies of one fact
 * diverge — the same reason {@code permissions.yaml} and {@code problem-types.yaml} are checked
 * against their enums.
 */
@DisplayName("The plugin health states")
class HealthStateRegistryTest {

  private static final Path REGISTRY =
      Path.of("..", "docs", "reference", "plugin-health-states.yaml");

  @Test
  @DisplayName("are exactly the states the reference names")
  void statesMatchTheReference() {
    Set<String> documented =
        entries().stream().map(entry -> (String) entry.get("state")).collect(Collectors.toSet());
    Set<String> implemented =
        EnumSet.allOf(HealthState.class).stream().map(Enum::name).collect(Collectors.toSet());

    assertThat(documented)
        .as("every state in docs/reference/plugin-health-states.yaml is in HealthState, and back")
        .isEqualTo(implemented);
  }

  @Test
  @DisplayName("agree with the reference about which of them is a fault")
  void faultsMatchTheReference() {
    // Not decoration: `fault` decides whether the operator UI raises the state
    // and whether an alert fires. NOT_CONFIGURED is the one that costs if it is
    // wrong here - a `minimal` installation runs without plugin-smtp by design
    // (ADR-0028), and an alert for that is an alert an operator learns to ignore.
    Map<String, Boolean> documented =
        entries().stream()
            .collect(
                Collectors.toMap(
                    entry -> (String) entry.get("state"),
                    entry -> Boolean.TRUE.equals(entry.get("fault"))));

    Map<String, Boolean> implemented =
        EnumSet.allOf(HealthState.class).stream()
            .collect(Collectors.toMap(Enum::name, HealthState::fault));

    assertThat(implemented).isEqualTo(documented);
  }

  @Test
  @DisplayName("refuse to serve where the reference says the plugin accepts nothing")
  void provisioningIncompleteIsFailClosed() {
    // REQ-PLG-015 in one assertion. A half-provisioned target that kept serving
    // would accept writes the other half cannot read back, which is the failure
    // this state exists to prevent.
    assertThat(HealthState.PROVISIONING_INCOMPLETE.servable()).isFalse();
    assertThat(HealthState.CONTRACT_MISMATCH.servable()).isFalse();

    assertThat(HealthState.OK.servable()).isTrue();
    assertThat(HealthState.DESTINATION_UNREACHABLE.servable())
        .as("an unreachable host is worth retrying; the circuit breaker decides when to stop")
        .isTrue();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> entries() {
    try {
      Map<String, Object> document = new Yaml().load(Files.readString(REGISTRY));
      return (List<Map<String, Object>>) document.get("states");
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "docs/reference/plugin-health-states.yaml is the source of truth for the health states "
              + "and could not be read",
          unreadable);
    }
  }
}
