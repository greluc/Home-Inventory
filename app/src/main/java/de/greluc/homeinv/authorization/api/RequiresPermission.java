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
 * The permission a caller must hold to reach this endpoint (REQ-SEC-023).
 *
 * <p>Every handler method in the access layer carries this or {@link PublicEndpoint}, and
 * {@code ArchitectureRulesTest} fails the build when one carries neither. The default is deny, and
 * the annotation is how an endpoint stops being denied — so a forgotten annotation is a build
 * failure rather than a public endpoint.
 *
 * <h2>What it is not</h2>
 *
 * <p>It is a <em>coarse</em> check: does this caller's role hold this permission at all. It says
 * nothing about the particular object being touched, and it cannot: the object is not loaded yet
 * when the interceptor runs. The object-level check is
 * {@link AccessControl#require(Permission, TenantOwned)}, in the application layer, against an
 * already-loaded resource — which is what closes the time-of-check/time-of-use gap 04 §4.3 names
 * (REQ-SEC-024).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

  /**
   * The permission required.
   *
   * @return the permission
   */
  Permission value();
}
