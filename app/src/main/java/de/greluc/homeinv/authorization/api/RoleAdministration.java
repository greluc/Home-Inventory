/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import de.greluc.homeinv.platform.Page;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roles a tenant defines for itself (REQ-TEN-006).
 *
 * <p>04 §4.3: tenant-owned roles <b>extend</b> the built-in ones, they do not replace them. So a
 * definition names one of the six as its base and adds permissions to it; nothing subtracts,
 * because a role that took a permission away from its base would be a role whose name lies about
 * what it is.
 *
 * <p>A tenant never defines a new <b>permission</b>. That would be a tenant deciding what the
 * application does, which is the line ADR-0020 draws for the type system and which holds here for
 * the same reason: the set of permissions is code and the combinations are data.
 *
 * <p>Every method acts on the tenant the session is acting for (REQ-SEC-004), and none takes a
 * tenant.
 */
public interface RoleAdministration {

  /**
   * A tenant-owned role.
   *
   * @param id the definition
   * @param name what the tenant calls it
   * @param description what it is for, or null
   * @param baseRole the built-in role it extends
   * @param added the permissions it adds to that base
   * @param effective everything it holds: the base's permissions and the added ones
   */
  record RoleDefinitionView(
      UUID id,
      String name,
      String description,
      Role baseRole,
      Set<Permission> added,
      Set<Permission> effective) {}


  /**
   * One page of this tenant's roles, oldest first.
   *
   * <p>Paged like every other collection this application answers with (REQ-NFR-010). A tenant is
   * unlikely to define two pages of roles, and "unlikely" is not a bound. Ordered by creation
   * rather than by name, because a keyset cursor needs a stable key and a name can be edited.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page
   */
  Page<RoleDefinitionView> roles(String cursor, int limit);

  /**
   * One definition.
   *
   * @param id the definition
   * @return it, or empty when this tenant has no such role
   */
  Optional<RoleDefinitionView> byId(UUID id);

  /**
   * Defines a role.
   *
   * @param name what to call it, unique within the tenant case-insensitively
   * @param description what it is for, or null
   * @param baseRole the built-in role it extends
   * @param added the permissions to add beyond that base
   * @param actor who is defining it
   * @return the new definition
   * @throws RoleNameTakenException when a live role of that name exists here
   */
  RoleDefinitionView create(
      String name, String description, Role baseRole, Set<Permission> added, UUID actor);

  /**
   * Replaces a definition's name, description and added permissions.
   *
   * <p>The base is not changeable. Changing it would silently move everybody holding the role
   * to a different floor, and the honest way to do that is a new role and a reassignment somebody
   * can see.
   *
   * @param id the definition
   * @param name the new name
   * @param description the new description, or null
   * @param added the permissions it should add, replacing whatever it added before
   * @param actor who is changing it
   * @return the definition as it now stands
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such role
   * @throws RoleNameTakenException when another live role already has the new name
   */
  RoleDefinitionView update(
      UUID id, String name, String description, Set<Permission> added, UUID actor);

  /**
   * Removes a definition.
   *
   * <p>A tombstone rather than a deletion (07 §7.1, rule 5), and members holding it are <b>not</b>
   * blocked or rewritten: their membership keeps naming the built-in role the definition extended,
   * so removing a role demotes its holders to its base. That is a defined outcome rather than a
   * broken one, and it is why the membership keeps a built-in name at all.
   *
   * @param id the definition
   * @param actor who is removing it
   */
  void remove(UUID id, UUID actor);
}
