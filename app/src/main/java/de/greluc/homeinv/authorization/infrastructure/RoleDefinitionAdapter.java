/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.infrastructure;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleAdministration;
import de.greluc.homeinv.authorization.api.RoleNameTakenException;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-owned roles, in the {@code authz} schema (REQ-TEN-006, ADR-0058).
 *
 * <p>An adapter and not an application service, because it is SQL: the rule that keeps queries out
 * of the application layer is the reason this class is here rather than beside
 * {@code DefaultAccessControl}.
 *
 * <h2>A permission this build does not know grants nothing</h2>
 *
 * <p>{@code authz.role_permission.permission} is text and carries no foreign key: the set of
 * permissions lives in code, and a table of them would be a second copy of a list the build already
 * compares against {@code docs/reference/permissions.yaml}. The cost is that a row can name a
 * permission this build has never heard of — a downgrade, a hand-edited row — and the treatment is
 * the one an unknown role gets: it is dropped, with a line in the log, rather than being an error
 * somebody works around.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RoleDefinitionAdapter implements RoleAdministration {

  private static final String DEFINITIONS =
      "select id, name, description, base_role, created_at from authz.role_definition"
          + " where tenant_id = ? and deleted_at is null"
          + " order by created_at asc, id asc limit ?";

  /**
   * The same listing after a keyset position.
   *
   * <p>By creation and not by name, because a keyset cursor needs a key that does not move and a
   * name can be edited between two pages.
   */
  private static final String DEFINITIONS_AFTER =
      "select id, name, description, base_role, created_at from authz.role_definition"
          + " where tenant_id = ? and deleted_at is null"
          + " and (created_at > ? or (created_at = ? and id > ?))"
          + " order by created_at asc, id asc limit ?";

  private static final String DEFINITION =
      "select id, name, description, base_role from authz.role_definition"
          + " where tenant_id = ? and id = ? and deleted_at is null";

  private static final String GRANTS =
      "select role_definition_id, permission from authz.role_permission"
          + " where tenant_id = ? and deleted_at is null";

  private static final String GRANTS_OF =
      "select permission from authz.role_permission"
          + " where tenant_id = ? and role_definition_id = ? and deleted_at is null";

  private static final String INSERT_DEFINITION =
      "insert into authz.role_definition"
          + " (id, tenant_id, name, description, base_role, created_by, updated_by)"
          + " values (?, ?, ?, ?, ?, ?, ?)";

  // `now()` rather than a bound instant, as every other adapter here does. The
  // driver has no type for `java.time.Instant` and sends it as text, which
  // PostgreSQL refuses against a `timestamptz` column with a 42-class error that
  // Spring reports as "bad SQL grammar" — a message that sends a reader looking
  // for a typo that is not there.
  private static final String UPDATE_DEFINITION =
      "update authz.role_definition set name = ?, description = ?, updated_by = ?,"
          + " updated_at = now(), version = version + 1"
          + " where tenant_id = ? and id = ? and deleted_at is null";

  private static final String TOMBSTONE_DEFINITION =
      "update authz.role_definition set deleted_at = now(), updated_by = ?, updated_at = now(),"
          + " version = version + 1 where tenant_id = ? and id = ? and deleted_at is null";

  private static final String CLEAR_GRANTS =
      "delete from authz.role_permission where tenant_id = ? and role_definition_id = ?";

  private static final String INSERT_GRANT =
      "insert into authz.role_permission"
          + " (tenant_id, role_definition_id, permission, created_by, updated_by)"
          + " values (?, ?, ?, ?, ?)";

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a role cursor is bound to, so it cannot be replayed against another listing. */
  private static final String CURSOR = "tenant-roles";

  private final JdbcClient jdbc;
  private final CursorCodec cursors;

  @Override
  @Transactional(readOnly = true)
  public Page<RoleDefinitionView> roles(String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);
    Map<UUID, Set<Permission>> grants = grantsByRole(tenantId);

    List<RoleDefinitionView> views = new ArrayList<>();
    List<CursorCodec.Position> positions = new ArrayList<>();
    RowCallbackHandler collect =
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          views.add(
              viewOf(
                  id,
                  rs.getString("name"),
                  rs.getString("description"),
                  rs.getString("base_role"),
                  grants.getOrDefault(id, Set.of())));
          positions.add(
              CursorCodec.Position.of(rs.getTimestamp("created_at").toInstant(), id));
        };

    if (cursor == null || cursor.isBlank()) {
      jdbc.sql(DEFINITIONS).params(tenantId, size).query(collect);
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      // Wrapped, not an Instant: the driver cannot infer a SQL type for one.
      java.sql.Timestamp at = java.sql.Timestamp.from(from.createdAt());
      jdbc.sql(DEFINITIONS_AFTER).params(tenantId, at, at, from.id(), size).query(collect);
    }

    String next =
        views.size() == size ? cursors.encode(positions.getLast(), CURSOR) : null;
    return Page.of(views, next);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RoleDefinitionView> byId(UUID id) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(DEFINITION)
        .params(tenantId, id)
        .query(
            (rs, rowNum) ->
                viewOf(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("base_role"),
                    grantsOf(tenantId, id)))
        .optional();
  }

  @Override
  @Transactional
  public RoleDefinitionView create(
      String name, String description, Role baseRole, Set<Permission> added, UUID actor) {

    UUID tenantId = TenantContext.require();
    UUID id = UUID.randomUUID();
    try {
      jdbc.sql(INSERT_DEFINITION)
          .params(id, tenantId, name.trim(), description, baseRole.name(), actor, actor)
          .update();
    } catch (DuplicateKeyException taken) {
      throw new RoleNameTakenException(name);
    }
    writeGrants(tenantId, id, added, actor);

    log.info("Role '{}' defined in tenant {} on {} by {}", name, tenantId, baseRole, actor);
    return viewOf(id, name.trim(), description, baseRole.name(), added);
  }

  @Override
  @Transactional
  public RoleDefinitionView update(
      UUID id, String name, String description, Set<Permission> added, UUID actor) {

    UUID tenantId = TenantContext.require();
    RoleDefinitionView existing = byId(id).orElseThrow(() -> new NotFoundException("role", id));

    int changed;
    try {
      changed =
          jdbc.sql(UPDATE_DEFINITION)
              .params(name.trim(), description, actor, tenantId, id)
              .update();
    } catch (DuplicateKeyException taken) {
      throw new RoleNameTakenException(name);
    }
    if (changed == 0) {
      throw new NotFoundException("role", id);
    }

    // Replaced wholesale rather than diffed. The caller sent what the role should
    // add, and computing the difference here would make "what does it add" depend
    // on what it added before — which is the state the caller is replacing.
    jdbc.sql(CLEAR_GRANTS).params(tenantId, id).update();
    writeGrants(tenantId, id, added, actor);

    log.info("Role {} of tenant {} changed by {}", id, tenantId, actor);
    return viewOf(id, name.trim(), description, existing.baseRole().name(), added);
  }

  @Override
  @Transactional
  public void remove(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    int changed = jdbc.sql(TOMBSTONE_DEFINITION).params(actor, tenantId, id).update();
    if (changed > 0) {
      // Members keep their membership and its built-in role, so removing a role
      // demotes its holders to the base it extended rather than stranding them.
      log.info("Role {} of tenant {} removed by {}", id, tenantId, actor);
    }
  }

  /**
   * The permissions one definition adds, for {@code DefaultAccessControl}.
   *
   * <p>Read per request rather than carried in the session, which is what makes a permission change
   * take effect for somebody who is signed in while it happens. It is a single indexed read and
   * {@code require} is called once or twice per request.
   *
   * @param tenantId the tenant
   * @param definitionId the definition
   * @return the permissions it adds, ignoring any this build does not know
   */
  @Transactional(readOnly = true)
  public Set<Permission> grantsOf(UUID tenantId, UUID definitionId) {
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    jdbc.sql(GRANTS_OF)
        .params(tenantId, definitionId)
        .query((rs, rowNum) -> permissions.addAll(parse(rs.getString("permission"))))
        .list();
    return permissions;
  }

  /**
   * The built-in role a definition extends.
   *
   * @param tenantId the tenant
   * @param definitionId the definition
   * @return the base, or empty when there is no such live definition
   */
  @Transactional(readOnly = true)
  public Optional<Role> baseOf(UUID tenantId, UUID definitionId) {
    return jdbc
        .sql("select base_role from authz.role_definition"
            + " where tenant_id = ? and id = ? and deleted_at is null")
        .params(tenantId, definitionId)
        .query(String.class)
        .optional()
        .flatMap(Role::named);
  }

  /**
   * Every definition's grants, in one read.
   *
   * @param tenantId the tenant
   * @return the permissions each definition adds
   */
  private Map<UUID, Set<Permission>> grantsByRole(UUID tenantId) {
    Map<UUID, Set<Permission>> grants = new LinkedHashMap<>();
    jdbc.sql(GRANTS)
        .param(tenantId)
        .query(
            (rs, rowNum) ->
                grants
                    .computeIfAbsent(
                        rs.getObject("role_definition_id", UUID.class),
                        ignored -> EnumSet.noneOf(Permission.class))
                    .addAll(parse(rs.getString("permission"))))
        .list();
    return grants;
  }

  /**
   * Writes a definition's grants.
   *
   * @param tenantId the tenant
   * @param definitionId the definition
   * @param added what it adds
   * @param actor who is writing them
   */
  private void writeGrants(UUID tenantId, UUID definitionId, Set<Permission> added, UUID actor) {
    for (Permission permission : added) {
      jdbc.sql(INSERT_GRANT)
          .params(tenantId, definitionId, permission.id(), actor, actor)
          .update();
    }
  }

  /**
   * A stored permission id, if this build knows it.
   *
   * @param id the stored id
   * @return the permission as a singleton, or an empty set
   */
  private static Set<Permission> parse(String id) {
    for (Permission permission : Permission.values()) {
      if (permission.id().equals(id)) {
        return Set.of(permission);
      }
    }
    log.warn("A role grants '{}', which this build does not know; it grants nothing.", id);
    return Set.of();
  }

  /**
   * One definition with its effective set worked out.
   *
   * @param id the definition
   * @param name its name
   * @param description its description
   * @param baseRole the built-in role it extends, as stored
   * @param added what it adds
   * @return the view
   */
  private static RoleDefinitionView viewOf(
      UUID id, String name, String description, String baseRole, Set<Permission> added) {

    Role base = Role.named(baseRole).orElse(Role.GUEST);
    Set<Permission> effective = EnumSet.noneOf(Permission.class);
    effective.addAll(base.permissions());
    effective.addAll(added);
    return new RoleDefinitionView(id, name, description, base, Set.copyOf(added), effective);
  }

}
