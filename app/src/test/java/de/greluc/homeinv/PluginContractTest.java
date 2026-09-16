/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ServiceDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every port has a service in the wire contract, and the two are named the same.
 *
 * <p>This closes the last link of a chain that is otherwise three separate lists. {@code
 * REQ-PLG-001} names the ports; {@code PortCatalogueTest} in {@code plugin-api} proves a Java
 * interface exists for each; this proves the protobuf service does too, and that it is spelled the
 * way the requirement spells the port.
 *
 * <p>The spelling matters more than it looks. {@code home_inv.plugin.v1.BlobStore} is the path a
 * gRPC call is routed by and the name a plugin author looks up. A service renamed to satisfy a
 * lint rule would put a second spelling of a port into the system, which is why {@code buf.yaml}
 * excepts {@code SERVICE_SUFFIX} and why this test exists to hold the exception to its promise.
 *
 * <p>It does <b>not</b> compare methods. The Java interface and the service differ on purpose —
 * {@code ImageProcessor.derive} takes a stream over the wire and a stream in Java, {@code
 * ScanSource} ends a delivery with a gRPC status where Java has a callback — and a test that
 * demanded they match would be a test against the transport rather than against the contract.
 */
@DisplayName("The plugin wire contract")
class PluginContractTest {

  private static final Path REQUIREMENTS = Path.of("..", "docs", "requirements", "01-functional.md");
  private static final String GENERATED = "de.greluc.homeinv.plugin.v1.";
  private static final String PROTO_PACKAGE = "home_inv.plugin.v1.";

  @Test
  @DisplayName("has a service for every port REQ-PLG-001 names, under the port's own name")
  void everyPortHasAService() throws Exception {
    List<String> ports = portsNamedByTheRequirement();
    assertThat(ports).as("REQ-PLG-001 names fifteen ports").hasSize(15);

    List<String> missing = new ArrayList<>();
    List<String> misnamed = new ArrayList<>();
    for (String port : ports) {
      Class<?> stub;
      try {
        stub = Class.forName(GENERATED + port + "Grpc");
      } catch (ClassNotFoundException absent) {
        missing.add(port);
        continue;
      }
      String fullName = descriptorOf(stub).getName();
      if (!(PROTO_PACKAGE + port).equals(fullName)) {
        misnamed.add(port + " is served as " + fullName);
      }
    }

    assertThat(missing)
        .as("REQ-PLG-001 names these ports and proto/home_inv/plugin/v1 has no service for them")
        .isEmpty();
    assertThat(misnamed)
        .as("a service is named after the port it serves, because that name is the gRPC path")
        .isEmpty();
  }

  @Test
  @DisplayName("has the health service every plugin serves, whatever ports it implements")
  void healthIsPartOfTheContract() throws Exception {
    // Not a port and not optional. The core asks once a minute (13 §13.7), and
    // an outbound plugin reports PROVISIONING_INCOMPLETE through this rather
    // than by failing calls one at a time (REQ-PLG-015).
    Class<?> stub = Class.forName(GENERATED + "PluginHealthGrpc");
    assertThat(descriptorOf(stub).getName()).isEqualTo(PROTO_PACKAGE + "PluginHealth");
  }

  /**
   * Reads a generated stub's service descriptor.
   *
   * @param stub the {@code *Grpc} class protoc produced
   * @return the descriptor, which carries the fully qualified service name
   * @throws Exception when the generated class does not look the way grpc-java generates it, which
   *     would mean the generator changed and this test is reading the wrong thing
   */
  private static ServiceDescriptor descriptorOf(Class<?> stub) throws Exception {
    Method descriptor = stub.getMethod("getServiceDescriptor");
    return (ServiceDescriptor) descriptor.invoke(null);
  }

  /**
   * Reads the port names out of the requirement's own description.
   *
   * @return the names, in the order the requirement lists them
   */
  private static List<String> portsNamedByTheRequirement() {
    String row =
        read().lines()
            .filter(line -> line.startsWith("| REQ-PLG-001 "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("REQ-PLG-001 is not in the requirements"));

    // The description cell only; the verification cell names test classes in
    // backticks too, and those are not ports.
    String[] cells = row.split("\\|");
    String description = cells.length > 2 ? cells[2] : "";

    List<String> ports = new ArrayList<>();
    Matcher names = Pattern.compile("`([A-Z][A-Za-z]+)`").matcher(description);
    while (names.find()) {
      ports.add(names.group(1));
    }
    return ports;
  }

  private static String read() {
    try {
      return Files.readString(REQUIREMENTS);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("The requirements catalogue could not be read", unreadable);
    }
  }
}
