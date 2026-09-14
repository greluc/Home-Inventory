/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.infrastructure;

import de.greluc.homeinv.authorization.api.FieldVisibility;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleHolders;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.SecondFactorMissingException;
import de.greluc.homeinv.authorization.api.SecondFactorStatus;
import de.greluc.homeinv.platform.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules that decide which sensitive fields a role reads (REQ-TEN-008, REQ-SEC-027).
 *
 * <h2>Two answers, and the union of them</h2>
 *
 * <p>A caller holds a built-in role and possibly a tenant-owned one extending it, and a rule can be
 * made about either. What they may read is the union: a definition that is granted a field adds it
 * to whatever its base already had, which is the same direction every other thing a definition does
 * runs in — extending, never subtracting.
 *
 * <h2>The default is not stored</h2>
 *
 * <p>{@code OWNER} and {@code ADMIN} read every sensitive field unless a rule says otherwise, and
 * that default is code rather than rows seeded per field. Seeding it would mean a field marked
 * sensitive after the fact is readable by nobody until something notices, and "until something
 * notices" is not a security model.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FieldVisibilityAdapter implements FieldVisibility {

  /** The roles that read everything sensitive unless a rule narrows it (04 §4.3). */
  private static final Set<Role> ALWAYS = Set.of(Role.OWNER, Role.ADMIN);

  private static final String KEYS_FOR_ROLE =
      "select field_key from authz.field_visibility"
          + " where tenant_id = ? and deleted_at is null"
          + " and (built_in_role = ? or role_definition_id = ?)";

  private static final String ANY_FOR_ROLE =
      "select exists(select 1 from authz.field_visibility"
          + " where tenant_id = ? and deleted_at is null"
          + " and (built_in_role = ? or role_definition_id = ?))";

  private static final String ALL_RULES =
      "select field_key, built_in_role, role_definition_id from authz.field_visibility"
          + " where tenant_id = ? and deleted_at is null order by field_key";

  private static final String GRANT =
      "insert into authz.field_visibility"
          + " (tenant_id, field_key, built_in_role, role_definition_id, created_by, updated_by)"
          + " values (?, ?, ?, ?, ?, ?) on conflict do nothing";

  private static final String REVOKE =
      "delete from authz.field_visibility where tenant_id = ? and field_key = ?"
          + " and built_in_role is not distinct from ?"
          + " and role_definition_id is not distinct from ?";

  private final JdbcClient jdbc;
  private final RoleHolders roleHolders;
  private final SecondFactorStatus secondFactors;

  @Override
  @Transactional(readOnly = true)
  public boolean readsSensitiveFields(RoleRef role) {
    if (role == null) {
      return false;
    }
    if (Role.named(role.builtIn()).filter(ALWAYS::contains).isPresent()) {
      // Without a query: these two read every sensitive field by default, whether
      // or not this tenant has ever made a rule.
      return true;
    }
    return Boolean.TRUE.equals(
        jdbc.sql(ANY_FOR_ROLE)
            .params(TenantContext.require(), role.builtIn(), role.definitionId())
            .query(Boolean.class)
            .single());
  }

  @Override
  @Transactional(readOnly = true)
  public SensitiveAccess sensitiveAccessFor(RoleRef role) {
    if (role == null) {
      return key -> false;
    }
    if (Role.named(role.builtIn()).filter(ALWAYS::contains).isPresent()) {
      // Everything, and not "every key that has a rule": an owner who has granted
      // one field to somebody else must not thereby stop seeing the ones they
      // have not granted to themselves.
      return key -> true;
    }

    Set<String> keys = new LinkedHashSet<>();
    jdbc.sql(KEYS_FOR_ROLE)
        .params(TenantContext.require(), role.builtIn(), role.definitionId())
        .query((rs, rowNum) -> keys.add(rs.getString("field_key")))
        .list();
    // Read once and answered from memory: a redaction pass asks about every
    // attribute of every item on a page.
    return keys::contains;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Rule> rules() {
    List<Rule> rules = new ArrayList<>();
    jdbc.sql(ALL_RULES)
        .param(TenantContext.require())
        .query(
            (rs, rowNum) ->
                rules.add(
                    new Rule(
                        rs.getString("field_key"),
                        rs.getString("built_in_role"),
                        rs.getObject("role_definition_id", UUID.class))))
        .list();
    return rules;
  }

  @Override
  @Transactional
  public void allow(String fieldKey, RoleRef role, UUID actor) {
    UUID tenantId = TenantContext.require();

    // REQ-AUTH-003's second half, and it is checked here rather than in the
    // controller because it is part of what granting means: a role that reads a
    // sensitive field may not be held by somebody who signs in with a password
    // alone. The rule is enforced where the grant is written, so a second caller
    // — GraphQL, a future import — cannot reach the write without it.
    requireHoldersEnrolled(role);

    jdbc.sql(GRANT)
        .params(tenantId, fieldKey, builtInOf(role), role.definitionId(), actor, actor)
        .update();
    log.info("Field '{}' made readable for {} in tenant {} by {}", fieldKey, role, tenantId, actor);
  }

  @Override
  @Transactional
  public void revoke(String fieldKey, RoleRef role, UUID actor) {
    UUID tenantId = TenantContext.require();
    jdbc.sql(REVOKE)
        .params(tenantId, fieldKey, builtInOf(role), role.definitionId())
        .update();
    log.info("Field '{}' withdrawn from {} in tenant {} by {}", fieldKey, role, tenantId, actor);
  }

  /**
   * Refuses a grant that would let somebody read a sensitive field with a password alone.
   *
   * <p>The count and never the names: an administrator needs to know that somebody has to act, and
   * which colleague has an authenticator is not their business — the reasoning REQ-SEC-110 applies
   * to addresses, applied to credentials.
   *
   * @param role the role being granted the field
   * @throws SecondFactorMissingException when a live member holding it has no second factor
   */
  private void requireHoldersEnrolled(RoleRef role) {
    long without =
        roleHolders.holdersOf(role).stream()
            .filter(user -> !secondFactors.isEnrolled(user))
            .count();
    if (without > 0) {
      throw new SecondFactorMissingException(
          without
              + " member(s) holding this role have no second factor, and a sensitive field may not"
              + " be read with a password alone (REQ-AUTH-003). They set one up at"
              + " /api/v1/auth/mfa/totp.");
    }
  }

  /**
   * The built-in name a rule stores, which is null when the rule is about a tenant-owned role.
   *
   * <p>Exactly one of the two columns is set, and the table's {@code num_nonnulls} check enforces
   * it. A rule about a definition does not also name its base: the base has rules of its own, and
   * writing both would make withdrawing one look like it had done nothing.
   *
   * @param role the role the rule is about
   * @return the built-in name, or null
   */
  private static String builtInOf(RoleRef role) {
    return role.definitionId() == null ? role.builtIn() : null;
  }

}
