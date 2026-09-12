/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The failures this endpoint can produce that cannot be read off its signature.
 *
 * <p>An error response is part of a contract — REQ-API-001 asks for the API to be "fully described",
 * and a document that says every endpoint answers {@code 200} describes half of one. The failures go
 * in the OpenAPI document, and they are generated from here rather than written into it, for the
 * same reason the rest of the document is (ADR-0049).
 *
 * <h2>What not to declare</h2>
 *
 * <p>Everything derivable is derived, in {@code OpenApiConfiguration}, and declaring it again would
 * be a second thing to keep right:
 *
 * <ul>
 *   <li>{@code 500}, {@code 405} and {@code 406} are on every endpoint there is.
 *   <li>{@code 401} is on every endpoint that is not {@code @PublicEndpoint}.
 *   <li>{@code 403} is on every endpoint that carries {@code @RequiresPermission}.
 *   <li>{@code 400}, {@code 413}, {@code 415} and {@code 422} come with a {@code @RequestBody}, and
 *       {@code 422} also with any parameter carrying a validation constraint.
 * </ul>
 *
 * <p>What remains is what the endpoint knows and nothing else does: that this one can conflict, that
 * this one can be told to look at something that is not there, that this one runs a malware scan.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CanFail {

  /**
   * The conditions this endpoint can answer with, beyond the derivable ones.
   *
   * @return the problem types
   */
  ProblemType[] value();
}
