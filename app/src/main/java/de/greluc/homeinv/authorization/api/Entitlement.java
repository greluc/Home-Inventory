/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * Something an account may do on the <b>instance</b>, rather than within a tenant (ADR-0057).
 *
 * <p>Deliberately not a {@link Permission} and deliberately not on the {@link Role} ladder. Those
 * are evaluated against a tenant context: {@code AccessControl.require(Permission)} reads the role
 * the current session holds <em>in the tenant it is acting for</em>. An entitlement is read where
 * there is no such context — when a person creates their first tenant, or when the operator
 * administers the instance itself — and no tenant could grant one anyway. A tenant able to hand out
 * "may create a tenant" would be handing out the right to create a second one.
 *
 * <p>There are two, and the separation between them is the point rather than an oversight. An
 * operator administers the instance; a creator accumulates tenants of their own. Making one imply
 * the other would mean that anybody who can grant can also quietly grow.
 */
public enum Entitlement {

  /**
   * Administers the instance itself: grants entitlements, sets instance-wide quota defaults,
   * installs plugins, and impersonates (REQ-SEC-072).
   *
   * <p>Grants nothing inside any tenant. Row-level security answers to {@code app.tenant_id} and
   * not to who asked, so an operator who is not a member of a tenant reads none of its rows — which
   * is why impersonation exists as its own audited, time-limited act instead of as a side effect of
   * this flag.
   */
  INSTANCE_OPERATOR("instance:operator"),

  /**
   * Creates tenants (REQ-TEN-002), bounded by the account's tenant limit.
   *
   * <p>Off by default. An instance whose every account could create tenants would have no way to
   * tell abuse from use, which is the reason ADR-0003 put a quota beside the entitlement rather
   * than relying on registration being closed.
   */
  CREATE_TENANT("instance:tenant:create");

  private final String id;

  Entitlement(String id) {
    this.id = id;
  }

  /**
   * The stable string this entitlement is written down as.
   *
   * <p>Shaped like a permission id on purpose — it ends up in the same audit entries and the same
   * denial log lines, and a reader should not have to know which of the two mechanisms produced it
   * to know what was refused.
   *
   * @return the id, for logs, audit entries and the registry
   */
  public String id() {
    return id;
  }
}
