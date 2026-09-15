/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.audit.api.AuditTrail;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Makes "every mutating action is recorded" a property of the boundary (REQ-SEC-068).
 *
 * <h2>Two halves of one guarantee</h2>
 *
 * <p>A block that knows what changed records its own entry, with a diff — {@code item.updated} with
 * the fields and their before and after. Only the block can produce that.
 *
 * <p>What only the boundary can produce is <b>completeness</b>. A mutating request that succeeded
 * and recorded nothing gets a plain entry here, naming the method and the path. So a service that
 * forgets, or one written later by somebody who did not read this, still leaves a trace — and the
 * requirement does not rest on everybody remembering.
 *
 * <h2>Why the fallback carries no diff</h2>
 *
 * <p>The boundary has the request body and must not record it. A body carries {@code sensitive}
 * field values, and an audit log that held what a role may not read would be a way to read it
 * (REQ-SEC-027). A plain entry says that something was changed and by whom; the block that knows
 * which fields is the one that may say so.
 *
 * <h2>Only after a success</h2>
 *
 * <p>A request answered {@code 4xx} changed nothing, and one answered {@code 5xx} rolled back. An
 * entry for either would be a record of something that did not happen, which is the opposite of
 * what the log is for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTrailInterceptor implements HandlerInterceptor {

  /** The methods that change something. */
  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

  /** What the {@code client} column takes, and therefore what a header may contribute. */
  private static final int MAX_CLIENT = 300;

  /** Anything that could end a line or move a cursor, which is what forges a log entry. */
  private static final java.util.regex.Pattern CONTROL_CHARACTERS =
      java.util.regex.Pattern.compile("\\p{Cntrl}+");

  private final AuditLog audit;
  private final TransactionTemplate transactions;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {
    // Set here and cleared in afterCompletion, which is why this is `establish`
    // and not the scoped `runWith`: the two halves of a servlet's lifetime are
    // two methods.
    CallerContext.current()
        .ifPresent(caller -> AuditTrail.establish(originOf(request, caller.userId())));
    return true;
  }

  @Override
  public void afterCompletion(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler,
      Exception failure) {
    try {
      if (shouldRecord(request, response)) {
        fallbackEntry(request);
      }
    } finally {
      AuditTrail.clear();
    }
  }

  /**
   * Whether the boundary owes an entry for this request.
   *
   * @param request what was asked
   * @param response what was answered
   * @return {@code true} for a successful mutating request that nothing recorded
   */
  private boolean shouldRecord(HttpServletRequest request, HttpServletResponse response) {
    return MUTATING.contains(request.getMethod())
        && response.getStatus() >= 200
        && response.getStatus() < 300
        && !AuditTrail.wasRecorded()
        && AuditTrail.current().isPresent()
        && TenantContext.current().isPresent();
  }

  /**
   * Writes the plain entry, in a transaction of its own.
   *
   * <p>The request's transaction has committed by the time this runs, so this opens one. That is
   * the difference between this entry and a block's: a block's entry is inside the transaction of
   * the change and disappears with a rollback, while this one records a change that is already
   * durable.
   *
   * <p>A failure here is logged and swallowed. The alternative is answering {@code 500} to a caller
   * whose change succeeded, which would turn a bookkeeping problem into a data-loss report.
   *
   * @param request what was asked
   */
  private void fallbackEntry(HttpServletRequest request) {
    String action = request.getMethod().toLowerCase(java.util.Locale.ROOT) + " " + path(request);
    try {
      transactions.executeWithoutResult(
          status -> audit.record(action, "request", null, Map.of()));
    } catch (RuntimeException unwritten) {
      log.error(
          "A mutating request succeeded and its audit entry could not be written: {}",
          action,
          unwritten);
    }
  }

  /**
   * The matched pattern rather than the concrete path.
   *
   * <p>{@code put /api/v1/items/{id}} and not the id, which is already in the entry's own columns
   * where a block recorded one — and which would otherwise make every action look distinct.
   *
   * @param request the request
   * @return the pattern, or the path when Spring matched none
   */
  private static String path(HttpServletRequest request) {
    Object pattern =
        request.getAttribute(
            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? request.getRequestURI() : pattern.toString();
  }

  /**
   * Who is acting, and from where.
   *
   * @param request the request
   * @param actorId the signed-in account
   * @return the origin
   */
  private static AuditTrail.Origin originOf(HttpServletRequest request, UUID actorId) {
    return AuditTrail.Origin.ofUser(
        actorId,
        request.getRemoteAddr(),
        clientOf(request.getHeader("User-Agent")),
        MDC.get("traceId"));
  }

  /**
   * The client string, bounded and stripped of anything that could forge a line.
   *
   * <p>A header is whatever the caller sent. Two things follow and both are handled here rather
   * than trusted to the column: a control character in it would let a caller write what looks like
   * a second log line, and a value longer than the column's 300 characters would be refused by the
   * database <i>after</i> the change committed — and the failure is swallowed, so the entry would
   * simply not exist. The bound belongs where the value is read.
   *
   * @param header what the caller sent, possibly {@code null}
   * @return the client, or {@code null} when nothing usable was sent
   */
  private static String clientOf(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String printable = CONTROL_CHARACTERS.matcher(header).replaceAll(" ").trim();
    return printable.length() <= MAX_CLIENT ? printable : printable.substring(0, MAX_CLIENT);
  }
}
