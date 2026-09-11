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
 * States that an endpoint is reachable without holding a permission (REQ-SEC-023).
 *
 * <p>The counterpart to {@link RequiresPermission}, and deliberately not the absence of it. An
 * endpoint with neither fails the build; an endpoint with this one is a decision somebody made and
 * wrote down, with a {@link #reason()} a reviewer can disagree with.
 *
 * <p>There are exactly three legitimate shapes at stage 0: an endpoint that establishes the session
 * (login), one that reports on it (who am I), and one whose authorisation is carried by the request
 * itself rather than by a session — the signed media URL, which a browser following an
 * {@code <img src>} sends no cookie with.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

  /**
   * Why this endpoint needs no permission.
   *
   * <p>Required, and not documentation: an exemption whose justification has to be typed out is one
   * somebody has to have thought about, and one a reviewer can argue with.
   *
   * @return the reason
   */
  String reason();
}
