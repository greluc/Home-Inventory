/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The permission catalogue in code and the one in {@code docs/} are the same catalogue.
 *
 * <p>A permission id outlives the Java constant that produced it: it ends up in a tenant-owned role
 * definition, in an audit entry and in the log line of every denial. So it is written down where
 * data lives — and two hand-maintained copies of one fact diverge, which is what
 * {@code docs/reference/tracked-facts.yaml} exists because of. This is the same mechanism
 * {@code problem-types.yaml} gets for the RFC 9457 tokens.
 */
@DisplayName("The permission registry")
class AuthorizationRegistryTest {

  private static final Path REGISTRY = Path.of("..", "docs", "reference", "permissions.yaml");

  @Test
  @DisplayName("names exactly the permissions the code defines")
  void permissionsMatchTheCode() {
    Set<String> documented =
        permissionEntries().stream().map(entry -> (String) entry.get("id")).collect(Collectors.toSet());
    Set<String> implemented =
        EnumSet.allOf(Permission.class).stream().map(Permission::id).collect(Collectors.toSet());

    assertThat(documented)
        .as("every permission the code defines is in docs/reference/permissions.yaml and vice versa")
        .isEqualTo(implemented);
  }

  @Test
  @DisplayName("gives every permission a description, because an id is not an explanation")
  void everyPermissionIsDescribed() {
    List<String> undescribed =
        permissionEntries().stream()
            .filter(entry -> String.valueOf(entry.get("description")).isBlank())
            .map(entry -> (String) entry.get("id"))
            .toList();

    assertThat(undescribed).isEmpty();
  }

  @Test
  @DisplayName("names exactly the roles the code defines, and the same six the database allows")
  void rolesMatchTheCode() {
    Set<String> documented =
        roleEntries().stream().map(entry -> (String) entry.get("name")).collect(Collectors.toSet());
    Set<String> implemented =
        EnumSet.allOf(Role.class).stream().map(Enum::name).collect(Collectors.toSet());

    assertThat(documented).isEqualTo(implemented);

    // And the database's own check constraint, which is the third copy of this
    // list and the one that fails at insert time rather than at review time.
    String migration = read(Path.of("src", "main", "resources", "db", "migration", "tenancy",
        "V3__tenant_and_membership.sql"));
    for (String role : implemented) {
      assertThat(migration)
          .as("tenancy.membership accepts the role %s", role)
          .contains("'" + role + "'");
    }
  }

  @Test
  @DisplayName("grants exactly what each role holds in code")
  void grantsMatchTheCode() {
    Map<String, Set<String>> mismatches = new LinkedHashMap<>();

    for (Map<String, Object> entry : roleEntries()) {
      String name = (String) entry.get("name");
      Role role = Role.valueOf(name);
      Object grants = entry.get("grants");

      Set<String> documented;
      if ("all".equals(grants)) {
        documented =
            EnumSet.allOf(Permission.class).stream().map(Permission::id).collect(Collectors.toSet());
      } else {
        documented = new java.util.LinkedHashSet<>(castToStrings(grants));
      }

      Set<String> held = role.permissions().stream().map(Permission::id).collect(Collectors.toSet());
      if (!documented.equals(held)) {
        mismatches.put(name, symmetricDifference(documented, held));
      }
    }

    assertThat(mismatches)
        .as("each role's grants in docs/reference/permissions.yaml equal what Role holds")
        .isEmpty();
  }

  @Test
  @DisplayName("keeps the ladder monotonic: a higher role never loses a lower one's permission")
  void theLadderOnlyGrows() {
    // Not a style rule. A ladder with a gap - CONTRIBUTOR able to do something
    // MEMBER cannot - is one where "promote this person" can take a capability
    // away, and nobody reviewing a role change would expect that.
    List<Role> ascending = List.of(Role.GUEST, Role.VIEWER, Role.CONTRIBUTOR, Role.MEMBER,
        Role.ADMIN, Role.OWNER);
    List<String> regressions = new ArrayList<>();

    for (int i = 1; i < ascending.size(); i++) {
      Role lower = ascending.get(i - 1);
      Role higher = ascending.get(i);
      for (Permission permission : lower.permissions()) {
        if (!higher.holds(permission)) {
          regressions.add(higher + " lacks " + permission.id() + ", which " + lower + " holds");
        }
      }
    }

    assertThat(regressions).isEmpty();
  }

  // -------------------------------------------------------------------------

  @Test
  @DisplayName("names exactly the entitlements the code defines, with the same ids (ADR-0057)")
  void entitlementsMatchTheCode() {
    // The second mechanism, written down in the same file and checked the same
    // way. An entitlement id reaches an audit entry and a denial log line exactly
    // as a permission id does, so the same drift is possible and the same check
    // closes it.
    Map<String, String> documented = new LinkedHashMap<>();
    for (Map<String, Object> entry : entitlementEntries()) {
      documented.put((String) entry.get("name"), (String) entry.get("id"));
    }

    Map<String, String> implemented = new LinkedHashMap<>();
    for (Entitlement entitlement : EnumSet.allOf(Entitlement.class)) {
      implemented.put(entitlement.name(), entitlement.id());
    }

    assertThat(documented)
        .as("every Entitlement is in docs/reference/permissions.yaml under the same id")
        .isEqualTo(implemented);
  }

  @Test
  @DisplayName("keeps entitlements out of the permission catalogue, because they are not roles")
  void anEntitlementIsNotAPermission() {
    Set<String> permissionIds =
        permissionEntries().stream().map(entry -> (String) entry.get("id")).collect(Collectors.toSet());

    for (Entitlement entitlement : EnumSet.allOf(Entitlement.class)) {
      assertThat(permissionIds)
          .as(
              "%s is an instance-level entitlement; listing it as a permission would make it "
                  + "grantable by a tenant-owned role (ADR-0057)",
              entitlement.id())
          .doesNotContain(entitlement.id());
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> entitlementEntries() {
    return (List<Map<String, Object>>) registry().get("entitlements");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> permissionEntries() {
    return (List<Map<String, Object>>) registry().get("permissions");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> roleEntries() {
    return (List<Map<String, Object>>) registry().get("roles");
  }

  @SuppressWarnings("unchecked")
  private static List<String> castToStrings(Object value) {
    return (List<String>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> registry() {
    return new Yaml().loadAs(read(REGISTRY), Map.class);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "The permission registry at " + path.toAbsolutePath() + " could not be read", unreadable);
    }
  }

  private static Set<String> symmetricDifference(Set<String> left, Set<String> right) {
    Set<String> difference = new java.util.LinkedHashSet<>(left);
    difference.removeAll(right);
    Set<String> other = new java.util.LinkedHashSet<>(right);
    other.removeAll(left);
    difference.addAll(other);
    return difference;
  }
}
