/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Every GraphQL resolver is checked before it runs (REQ-SEC-023, ADR-0079).
 *
 * <h2>Why an aspect and not the interceptor REST uses</h2>
 *
 * <p>{@code PermissionInterceptor} is an HTTP interceptor: it sees one handler method per request,
 * which is what a REST endpoint is. A GraphQL request is <b>many</b> resolvers, chosen by the
 * client, each reaching a different block — {@code Item.photos} is {@code media}, {@code
 * Item.history} is {@code audit}. Checking once at the HTTP boundary would have to check the union
 * of everything the schema can reach, which is no check at all.
 *
 * <p>So the check moves to the resolver, and an aspect is what puts it there without every method
 * beginning with the same line. The annotation stays the declaration — the same {@link
 * RequiresPermission} a REST endpoint carries — and {@code ArchitectureRulesTest} refuses a
 * resolver without one, exactly as it does for a controller.
 *
 * <h2>A refusal is a field, not a request</h2>
 *
 * <p>{@link de.greluc.homeinv.authorization.api.AccessDeniedException} propagates out of the
 * resolver and Spring for GraphQL turns it into {@code null} for that field and an entry in {@code
 * errors}, with the rest of the query answered. A member who asks for items and, in passing, their
 * history gets the items.
 *
 * <p>That is the decision of ADR-0079 and not an accident of the framework: refusing the whole
 * query would mean a client cannot ask for anything it is not certain of, which turns a query
 * language into a fixed set of endpoints.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class GraphQlPermissions {

  private final AccessControl accessControl;

  /**
   * Requires what the resolver declares, before it runs.
   *
   * <p>The pointcut is the <b>annotation</b> rather than the package, so a resolver is covered the
   * moment it declares what it needs and a resolver that declares nothing is covered by the
   * architecture rule instead — which fails the build rather than the request.
   *
   * @param joinPoint the resolver call
   * @param required what it declared
   * @return whatever the resolver returned
   * @throws Throwable whatever the resolver threw
   */
  @Around("@annotation(required)")
  public Object check(ProceedingJoinPoint joinPoint, RequiresPermission required) throws Throwable {
    if (!joinPoint.getTarget().getClass().getPackageName().startsWith("de.greluc.homeinv.graphql")) {
      // The same annotation is on every REST endpoint, where the interceptor
      // already checks it. Checking twice would be harmless and would make a
      // denial's stack trace say two different things about where it came from.
      return joinPoint.proceed();
    }
    accessControl.require(required.value());
    if (log.isTraceEnabled()) {
      log.trace(
          "{} passed {}",
          ((MethodSignature) joinPoint.getSignature()).getMethod().getName(),
          required.value().id());
    }
    return joinPoint.proceed();
  }
}
