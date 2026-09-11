/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} before a handler runs (REQ-SEC-023).
 *
 * <h2>Deny by default, twice over</h2>
 *
 * <p>A handler carrying neither annotation fails the <em>build</em>, in
 * {@code ArchitectureRulesTest}. This class is the run-time half of the same rule and it refuses the
 * same case: an endpoint that reaches production without an annotation — through a code path the
 * ArchUnit rule does not see, a dynamically registered handler — is denied rather than allowed. A
 * default of "no annotation means public" would make every forgotten annotation a public endpoint,
 * which is the failure mode REQ-SEC-023 exists to remove.
 *
 * <h2>What this does not decide</h2>
 *
 * <p>Whether the caller may touch the <em>particular</em> object. The object is not loaded when this
 * runs, and loading it here to decide would put the decision on a different read than the one the
 * handler acts on. That check is {@code AccessControl.require(permission, resource)}, in the
 * application layer, against the already-loaded object (REQ-SEC-024).
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

  private final AccessControl accessControl;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)) {
      // Static resources, the error dispatcher, the actuator's own handlers.
      // None of them is a controller of ours, and none is covered by REQ-SEC-023.
      return true;
    }

    if (method.getMethodAnnotation(PublicEndpoint.class) != null) {
      return true;
    }

    RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
    if (required == null) {
      // Not an exception a client should be able to distinguish from a genuine
      // denial, and not something the application should serve around. It throws
      // rather than returning false so that the failure reaches the log with the
      // method name in it.
      throw new IllegalStateException(
          ("%s carries neither @RequiresPermission nor @PublicEndpoint. The default is deny "
                  + "(REQ-SEC-023).")
              .formatted(method.getMethod()));
    }

    accessControl.require(required.value());
    return true;
  }
}
