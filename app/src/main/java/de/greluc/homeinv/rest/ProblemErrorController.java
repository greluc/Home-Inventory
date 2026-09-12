/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.PublicEndpoint;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The last error surface: what the servlet container reports on its own (REQ-API-003).
 *
 * <p>{@link ApiExceptionHandler} covers everything that leaves a controller and {@link
 * ProblemEntryPoint} covers a missing session. Neither sees a failure in a servlet filter, a request
 * the container rejected before dispatching it, or {@code response.sendError} from anywhere. Those
 * are forwarded to {@code /error}, where Spring Boot's own controller answered
 * {@code {"timestamp": …, "error": "Internal Server Error"}} — JSON, and not the one shape this API
 * promises. Defining an {@link ErrorController} bean replaces it.
 *
 * <p>Boot's whitelabel page is switched off in {@code application.yaml} and that was never enough:
 * it removes the HTML variant, not the JSON one.
 *
 * <p>{@code @Hidden} keeps it out of the OpenAPI document. It is not a path a client calls — the
 * container forwards to it — and describing it would put an endpoint in the contract that answers
 * nothing when requested directly.
 *
 * <h2>What it discloses</h2>
 *
 * <p>The status, and nothing else. The {@code javax.servlet.error.message} and
 * {@code .exception} attributes are deliberately not read — {@code server.error.include-message} is
 * {@code never} by default for the same reason, and a message the container copied out of an
 * exception is written for an operator.
 */
@RestController
@Hidden
@Slf4j
public class ProblemErrorController implements ErrorController {

  /**
   * Answers the error dispatch.
   *
   * @param request the original request, carrying the container's error attributes
   * @return the problem document for the status the container decided on
   */
  @RequestMapping(value = "/error", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "It is not an endpoint a client calls. The container dispatches to it after a "
              + "request has already been refused, and demanding a permission to be told "
              + "why would answer a 401 with a 403.")
  public ResponseEntity<ProblemDetail> handle(HttpServletRequest request) {
    int status = statusOf(request);

    if (status >= 500) {
      // The exception itself, when the container kept it. ApiExceptionHandler
      // already logged anything that came through a controller, so this line is
      // about the failures it never saw — a filter, or the container itself.
      Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
      log.error(
          "Error dispatch for {} with status {}",
          request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
          status,
          failure instanceof Throwable throwable ? throwable : null);
    }

    ProblemDetail problem =
        Problems.forStatus(status, "The request could not be served.", forwardedUri(request));
    return ResponseEntity.status(problem.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * The status the container decided on.
   *
   * @param request the error dispatch
   * @return the status, or {@code 500} when the attribute is missing or unusable
   */
  private static int statusOf(HttpServletRequest request) {
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    return status instanceof Integer code && code >= 100 && code < 600 ? code : 500;
  }

  /**
   * The request, with {@code getRequestURI} answering the path the caller asked for.
   *
   * <p>On an error dispatch {@code getRequestURI} is {@code /error}. The {@code instance} member of
   * a problem document is meant to identify the occurrence, and every error pointing at
   * {@code /error} would identify nothing.
   *
   * @param request the error dispatch
   * @return a view of it whose URI is the original one
   */
  private static HttpServletRequest forwardedUri(HttpServletRequest request) {
    Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    if (!(original instanceof String uri)) {
      return request;
    }
    return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
      @Override
      public String getRequestURI() {
        return uri;
      }
    };
  }
}
