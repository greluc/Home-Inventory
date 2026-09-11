/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.application.InvalidCredentialsException;
import de.greluc.homeinv.identity.application.TooManyAttemptsException;
import de.greluc.homeinv.inventory.application.ItemAlreadyExistsException;
import de.greluc.homeinv.locations.application.LocationNotEmptyException;
import de.greluc.homeinv.locations.domain.TooDeepException;
import de.greluc.homeinv.platform.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception that escapes a controller into RFC 9457 {@code application/problem+json}.
 *
 * <p>{@code REQ-API-003} makes this the only error format on every HTTP surface, which is why the
 * whitelabel error page is switched off in {@code application.yaml}: a second, undocumented shape
 * for errors is worse than an ugly one, because clients would have to parse both.
 *
 * <p>Every response carries a {@code traceId} that appears in the server log for the same request
 * ({@code REQ-NFR-042}). That is the entire mechanism by which a user's report — "it said
 * something went wrong" — becomes a specific request an operator can look at.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

  /**
   * Answers a lookup that found nothing.
   *
   * @param exception the failure, carrying the resource kind and the id
   * @param request the request, for the {@code instance} member
   * @return a {@code 404} problem detail
   */
  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail handleNotFound(NotFoundException exception, HttpServletRequest request) {
    // Not logged above DEBUG: a 404 is an ordinary answer, and logging it at WARN
    // lets anyone fill the log by requesting random ids.
    log.debug("Not found: {}", exception.getMessage());
    return problem(
        HttpStatus.NOT_FOUND,
        ProblemTypes.NOT_FOUND,
        "Not found",
        "No such " + exception.getResource() + " is visible to you.",
        request);
  }

  /**
   * Answers a creation whose id is taken by something else.
   *
   * <p>Only reachable because the client may choose the id (ADR-0016). An identical creation is not
   * an error and never gets here — it returns the existing item with a {@code 200}.
   *
   * @param exception the conflict, carrying the id
   * @param request the request
   * @return a {@code 409} problem detail naming the id
   */
  @ExceptionHandler(ItemAlreadyExistsException.class)
  public ProblemDetail handleAlreadyExists(
      ItemAlreadyExistsException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            ProblemTypes.RESOURCE_EXISTS,
            "Resource exists",
            "An item with this id already exists in this tenant with different content.",
            request);
    problem.setProperty("id", exception.getId().toString());
    return problem;
  }

  /**
   * Answers a login that failed.
   *
   * <p>One answer for every reason: unknown address, wrong password, locked account. Not logged
   * here — the service already logged the actual reason, which an operator needs and a caller
   * must not have (REQ-SEC-016).
   *
   * @param exception the failure
   * @param request the request
   * @return a {@code 401} problem detail
   */
  @ExceptionHandler(InvalidCredentialsException.class)
  public ProblemDetail handleInvalidCredentials(
      InvalidCredentialsException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        ProblemTypes.UNAUTHENTICATED,
        "Unauthenticated",
        "The e-mail address or password is not correct.",
        request);
  }

  /**
   * Answers a login the throttle is holding back.
   *
   * <p>Carries {@code Retry-After} in seconds, so a client waits the right amount instead of
   * retrying immediately and making the delay grow. Distinct from a wrong password on purpose: a
   * client that cannot tell them apart will hammer.
   *
   * @param exception the throttle decision, carrying the remaining wait
   * @param request the request
   * @return a {@code 429} problem detail with the wait in seconds
   */
  @ExceptionHandler(TooManyAttemptsException.class)
  public ProblemDetail handleTooManyAttempts(
      TooManyAttemptsException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.TOO_MANY_REQUESTS,
            ProblemTypes.RATE_LIMITED,
            "Too many attempts",
            "Too many failed login attempts. Try again shortly.",
            request);
    problem.setProperty("retryAfterSeconds", exception.getRetryAfter().toSeconds());
    return problem;
  }

  /**
   * Answers a deletion refused because the location is not empty.
   *
   * @param exception the refusal, carrying why
   * @param request the request
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(LocationNotEmptyException.class)
  public ProblemDetail handleLocationNotEmpty(
      LocationNotEmptyException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            ProblemTypes.RESOURCE_EXISTS,
            "Location not empty",
            exception.getMessage(),
            request);
    problem.setProperty("locationId", exception.getLocationId().toString());
    return problem;
  }

  /**
   * Answers a location that would sit deeper than the tree allows.
   *
   * @param exception the refusal, carrying the limit
   * @param request the request
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(TooDeepException.class)
  public ProblemDetail handleTooDeep(TooDeepException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ProblemTypes.VALIDATION_FAILED,
            "Validation failed",
            exception.getMessage(),
            request);
    problem.setProperty("maxDepth", exception.getMaxDepth());
    return problem;
  }

  /**
   * Answers a request whose body violates a constraint.
   *
   * <p>The field paths are what makes this usable: a form marks the fields that are wrong instead
   * of showing one sentence, which is the difference between a user fixing the input and guessing.
   *
   * @param exception the binding failure
   * @param request the request
   * @return a {@code 422} problem detail carrying an {@code errors} array of field paths
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    exception
        .getBindingResult()
        .getFieldErrors()
        .forEach(
            error ->
                errors.add(
                    Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage())));

    ProblemDetail problem =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ProblemTypes.VALIDATION_FAILED,
            "Validation failed",
            "The request is well formed but violates a rule.",
            request);
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * Answers a domain invariant broken below the validation layer.
   *
   * <p>Reaching here means a rule the aggregate enforces was not also expressed as a bean
   * validation constraint. That is a gap worth noticing, so it is logged at WARN with the message —
   * the response stays a {@code 422} either way, because from the caller's side it is the same kind
   * of mistake.
   *
   * @param exception the failure
   * @param request the request
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleDomainRule(
      IllegalArgumentException exception, HttpServletRequest request) {
    log.warn(
        "A domain invariant was reached without a matching validation constraint: {}",
        exception.getMessage());
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ProblemTypes.VALIDATION_FAILED,
        "Validation failed",
        exception.getMessage(),
        request);
  }

  /**
   * Answers a body that could not be parsed.
   *
   * @param exception the parse failure
   * @param request the request
   * @return a {@code 400} problem detail
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadable(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    // The parser's own message can quote the payload. It is not echoed, because a
    // malformed body may contain whatever the sender put in it.
    log.debug("Unreadable request body", exception);
    return problem(
        HttpStatus.BAD_REQUEST,
        ProblemTypes.MALFORMED_REQUEST,
        "Malformed request",
        "The request body could not be parsed.",
        request);
  }

  /**
   * Builds a problem detail with the members every response in this application carries.
   *
   * @param status the HTTP status
   * @param type the registered type URI
   * @param title a short, stable, human-readable summary
   * @param detail what happened, in a sentence a user could read
   * @param request the request, for the {@code instance} member
   * @return the problem detail, ready to return
   */
  private static ProblemDetail problem(
      HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));

    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
