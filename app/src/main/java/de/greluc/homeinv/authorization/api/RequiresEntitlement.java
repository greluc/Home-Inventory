/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The instance-level entitlement a caller must hold to reach this endpoint (ADR-0057).
 *
 * <p>The third of the three markers an endpoint may carry, beside {@link RequiresPermission} and
 * {@link PublicEndpoint}, and {@code ArchitectureRulesTest} counts it as one: the rule is that every
 * handler declares what it needs, not that every handler needs a permission.
 *
 * <p>It exists because a permission cannot express this. {@link Permission} is evaluated against the
 * role a session holds <em>in the tenant it acts for</em>, and these endpoints run where there is no
 * such tenant — {@code POST /api/v1/tenants} creates the caller's first one, and
 * {@code /api/v1/instance/**} is about the instance rather than about any tenant on it.
 *
 * <p>An endpoint carries this <b>or</b> {@link RequiresPermission}, never both. Two declarations
 * would be two answers to "what does this need", and the one a reader trusts would be whichever
 * they read first.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresEntitlement {

  /**
   * The entitlement required.
   *
   * @return the entitlement
   */
  Entitlement value();
}
