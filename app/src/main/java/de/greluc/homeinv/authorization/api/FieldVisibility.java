/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.List;
import java.util.UUID;

/**
 * Which roles may read which sensitive fields (REQ-TEN-008, REQ-SEC-027).
 *
 * <p>Layer four of the five in 12 §12.5, and the word there is <b>removed</b>: a field the caller
 * may not read is absent from the answer, not replaced by asterisks. A mask reveals that the field
 * exists and how long its value is, which for a purchase price is most of what somebody wanted.
 *
 * <h2>The default, and why it is not "nobody"</h2>
 *
 * <p>A field nobody has made a rule about is readable by {@code OWNER} and {@code ADMIN}. That is
 * 04 §4.3's own example — "purchase price only for {@code ADMIN}" — taken as the default rather
 * than as an illustration. "Nobody" would mean a tenant that marks a field sensitive can no longer
 * read it at all, including the person who marked it; "everybody" would make the flag decorative
 * until somebody remembers to configure it.
 */
public interface FieldVisibility {

  /**
   * One rule: a field a role may read.
   *
   * @param fieldKey the field's key, as the type system spells it
   * @param builtInRole the built-in role it is granted to, or null
   * @param roleDefinitionId the tenant-owned role it is granted to, or null. Exactly one of the two
   *     is set
   */
  record Rule(String fieldKey, String builtInRole, UUID roleDefinitionId) {}

  /**
   * What a role may read of the sensitive fields.
   *
   * <p>A question that can be asked many times rather than a set that has to be enumerated. Two
   * reasons, and the second is the one that matters: a redaction pass asks about every attribute of
   * every item on a page, so the rules are read once and answered from memory; and {@code OWNER}
   * and {@code ADMIN} read <em>everything</em>, which is not a set this block could produce — the
   * field keys are the tenant's type system, and {@code authorization} deliberately cannot see it.
   */
  interface SensitiveAccess {

    /**
     * Whether this role may read one sensitive field.
     *
     * @param fieldKey the field's key, as the type system spells it
     * @return {@code true} when the value is shown rather than removed
     */
    boolean allows(String fieldKey);
  }

  /**
   * What a role may read of the sensitive fields, in the tenant being acted for.
   *
   * <p>Keys rather than definitions, because a rule is about a field key and applies to every type
   * that has one: a purchase price is the same thing on a book as on a drill.
   *
   * @param role the role in question
   * @return the decision, to be asked once per field
   */
  SensitiveAccess sensitiveAccessFor(RoleRef role);

  /**
   * Every rule this tenant has made.
   *
   * @return the rules, field order
   */
  List<Rule> rules();

  /**
   * Grants a role the right to read a sensitive field.
   *
   * <p>Granting one twice is not an error, for the reason deleting twice is not: a client retrying
   * a request whose answer it never saw.
   *
   * @param fieldKey the field's key
   * @param role the role to grant it to
   * @param actor who is granting it
   */
  void allow(String fieldKey, RoleRef role, UUID actor);

  /**
   * Withdraws such a grant.
   *
   * <p>Withdrawing one that was never made is not an error either. What it cannot do is take a
   * field away from {@code OWNER}: a tenant whose owner cannot read its own data is a tenant nobody
   * can administer, and the default exists for that reason.
   *
   * @param fieldKey the field's key
   * @param role the role to withdraw it from
   * @param actor who is withdrawing it
   */
  void revoke(String fieldKey, RoleRef role, UUID actor);
}
