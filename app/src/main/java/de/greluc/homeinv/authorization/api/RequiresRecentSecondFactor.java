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
 * The endpoint asks for the second factor again before it acts (REQ-AUTH-011).
 *
 * <p>12 §12.4 names the operations: granting permissions, deleting a tenant, exporting, and reading
 * {@code sensitive} fields. The first three are endpoints and carry this; the fourth is not an
 * endpoint but a field in an answer, and is handled where the field is removed — a whole request
 * refused because one column of a list is sensitive would be a 403 people learn to click past.
 *
 * <p>What counts as "again" is {@link SecondFactorPolicy#RECONFIRMATION_WINDOW}: fifteen minutes
 * from the last time a code was accepted in this session, whether at the login or at
 * {@code POST /api/v1/auth/mfa/step-up}.
 *
 * <p>It is not a permission and does not replace one. An endpoint carries both: the permission says
 * who may, and this says how recently they proved it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresRecentSecondFactor {}
