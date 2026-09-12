/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 documents this application answers with.
 *
 * <p>Three places produce an error response and they must produce the same shape: {@link
 * ApiExceptionHandler} for anything a controller throws, {@link ProblemEntryPoint} for a request
 * that never reached a controller because it carried no session, and {@link ProblemErrorController}
 * for everything the servlet container reports on its own. Before this class the first had a private
 * builder and the other two did not exist, which is how a deployment ended up with three error
 * formats while {@code REQ-API-003} promised one.
 *
 * <h2>The status decides the type, not the other way round</h2>
 *
 * <p>{@link #forStatus} exists because the two callers outside the advice know only a status code —
 * Spring's own request matching refused the request, and what it threw is behind a servlet
 * attribute. The mapping is total by construction: a status with no entry becomes a {@code 500} with
 * {@code internal-error} rather than keeping a status whose type is not in the registry, because
 * {@code docs/reference/problem-types.yaml} pins the status per token and a response that broke that
 * pairing would be one {@code oasdiff} cannot reason about.
 */
final class Problems {

  /**
   * Which type answers a status the framework chose, when several could.
   *
   * <p>{@code 422} is both {@code validation-failed} and {@code malware-detected}, and {@code 503}
   * is {@code scan-unavailable}; neither of the second ones is something Spring's request matching
   * can decide, so the mapping names the one that is.
   */
  private static final Map<Integer, ProblemType> BY_STATUS =
      Map.ofEntries(
          Map.entry(400, ProblemType.MALFORMED_REQUEST),
          Map.entry(401, ProblemType.UNAUTHENTICATED),
          Map.entry(403, ProblemType.FORBIDDEN),
          Map.entry(404, ProblemType.NOT_FOUND),
          Map.entry(405, ProblemType.METHOD_NOT_ALLOWED),
          Map.entry(406, ProblemType.NOT_ACCEPTABLE),
          Map.entry(409, ProblemType.RESOURCE_EXISTS),
          Map.entry(413, ProblemType.PAYLOAD_TOO_LARGE),
          Map.entry(415, ProblemType.UNSUPPORTED_MEDIA_TYPE),
          Map.entry(422, ProblemType.VALIDATION_FAILED),
          Map.entry(429, ProblemType.RATE_LIMITED));

  /** What a caller is told about a failure nobody anticipated. The log line has the rest. */
  static final String INTERNAL_DETAIL = "Something went wrong. Quote the traceId when reporting it.";

  private Problems() {}

  /**
   * Builds a problem document with the members every response in this application carries.
   *
   * @param type the condition, which decides the status, the type URI and the title
   * @param detail what happened, in a sentence a user could read
   * @param request the request, for the {@code instance} member
   * @return the document, ready to return
   */
  static ProblemDetail of(ProblemType type, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
    problem.setType(type.uri());
    problem.setTitle(type.title());
    problem.setInstance(URI.create(request.getRequestURI()));

    String traceId = MDC.get(TraceIdFilter.TRACE_ID);
    if (traceId != null) {
      problem.setProperty(TraceIdFilter.TRACE_ID, traceId);
    }
    return problem;
  }

  /**
   * Builds the document for a status this application did not choose itself.
   *
   * @param status the status the container or the framework decided on
   * @param detail the sentence for the caller
   * @param request the request
   * @return the document, with {@code internal-error} and a {@code 500} for any status this
   *     application has no registered type for
   */
  static ProblemDetail forStatus(int status, String detail, HttpServletRequest request) {
    ProblemType type = BY_STATUS.get(status);
    if (type == null) {
      return of(ProblemType.INTERNAL_ERROR, INTERNAL_DETAIL, request);
    }
    return of(type, detail, request);
  }
}
