/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.RequiresRecentSecondFactor;
import de.greluc.homeinv.authorization.api.SecondFactorPolicy;
import de.greluc.homeinv.authorization.api.SecondFactorStaleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks for the second factor again before a critical operation (REQ-AUTH-011).
 *
 * <p>An endpoint says so with {@link RequiresRecentSecondFactor}; this refuses it when the last
 * accepted code is older than {@link SecondFactorPolicy#RECONFIRMATION_WINDOW}, or when this
 * session has never had one accepted at all. The way back is
 * {@code POST /api/v1/auth/mfa/step-up}, which the problem's detail names.
 *
 * <p>It does not ask whether the account <em>has</em> a factor: a session that has proved none
 * cannot pass, and whether that is because there is none to prove is
 * {@link SecondFactorLockInterceptor}'s question, answered before this one runs with a different
 * type — "set one up" and "enter a code" are different sentences to a person.
 */
@Component
@RequiredArgsConstructor
public class SecondFactorFreshnessInterceptor implements HandlerInterceptor {

  private final SecondFactorPolicy policy;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)
        || !method.hasMethodAnnotation(RequiresRecentSecondFactor.class)) {
      return true;
    }
    if (!policy.provedRecently(SessionEstablisher.secondFactorProvedAt(request))) {
      throw new SecondFactorStaleException();
    }
    return true;
  }
}
