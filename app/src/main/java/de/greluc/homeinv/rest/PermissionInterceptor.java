/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} and {@link RequiresEntitlement} before a handler runs
 * (REQ-SEC-023, ADR-0057).
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

  /** Where this application's own endpoints live, and the only place they may. */
  private static final String ACCESS_LAYER = "de.greluc.homeinv.rest";

  private final AccessControl accessControl;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)) {
      // Static resources and the error dispatcher. Neither is a controller.
      return true;
    }

    if (!method.getBeanType().getPackageName().startsWith(ACCESS_LAYER)) {
      // A handler the framework contributed: the actuator's endpoints, springdoc's
      // document resource, the error controller. REQ-SEC-023 is about the
      // endpoints THIS application defines, and the filter chain is what decides
      // for the rest — the actuator listens on a port bound to `internal`
      // (REQ-SEC-099) and the document resource does not exist outside the build.
      //
      // The narrowing is safe because of the companion rule: ArchUnit refuses a
      // `@RestController` anywhere but `de.greluc.homeinv.rest`, so an endpoint of
      // ours cannot end up on the other side of this check.
      return true;
    }

    if (method.getMethodAnnotation(PublicEndpoint.class) != null) {
      return true;
    }

    RequiresEntitlement entitled = method.getMethodAnnotation(RequiresEntitlement.class);
    RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);

    if (entitled != null && required != null) {
      // Two declarations are two answers to "what does this need", and the one a
      // reader trusts is whichever they read first. Refused rather than resolved
      // by precedence, because a precedence rule is the thing nobody remembers.
      throw new IllegalStateException(
          ("%s carries both @RequiresEntitlement and @RequiresPermission. An endpoint declares one "
                  + "or the other (ADR-0057).")
              .formatted(method.getMethod()));
    }

    if (entitled != null) {
      accessControl.require(entitled.value());
      return true;
    }

    if (required == null) {
      // Not an exception a client should be able to distinguish from a genuine
      // denial, and not something the application should serve around. It throws
      // rather than returning false so that the failure reaches the log with the
      // method name in it.
      throw new IllegalStateException(
          ("%s carries none of @RequiresPermission, @RequiresEntitlement and @PublicEndpoint. "
                  + "The default is deny (REQ-SEC-023).")
              .formatted(method.getMethod()));
    }

    accessControl.require(required.value());
    return true;
  }
}
