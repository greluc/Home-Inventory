/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.AccessDeniedException;
import de.greluc.homeinv.identity.api.InvalidCredentialsException;
import de.greluc.homeinv.identity.api.TooManyAttemptsException;
import de.greluc.homeinv.inventory.api.ItemAlreadyExistsException;
import de.greluc.homeinv.locations.api.LocationNotEmptyException;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.media.api.MalwareDetectedException;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.UnsupportedMediaTypeException;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

  /** What may be echoed back as a field name. A JSON member name can be anything at all. */
  private static final Pattern SAFE_FIELD_NAME = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,64}");

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
    return problem(ProblemType.NOT_FOUND, "No such " + exception.getResource() + " is visible to you.",
        request);
  }

  /**
   * Answers a caller whose role does not hold the permission the operation needs.
   *
   * <p>A {@code 403} here is only ever about a permission, never about a resource the caller cannot
   * see: a resource in another tenant is a {@code 404}, because a {@code 403} confirms it exists
   * (REQ-SEC-025). The detail names no resource for the same reason.
   *
   * @param exception the denial, naming the permission
   * @param request the request
   * @return a {@code 403} problem detail
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    // At WARN: a denial is either an attack or a misconfigured role, and both are
    // worth seeing. The caller is already in the MDC of every line (REQ-NFR-041).
    log.warn("Forbidden: {}", exception.getMessage());
    return problem(ProblemType.FORBIDDEN, "Your role does not permit this operation.",
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
        problem(ProblemType.RESOURCE_EXISTS, "An item with this id already exists in this tenant with different content.",
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
    return problem(ProblemType.UNAUTHENTICATED, "The e-mail address or password is not correct.",
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
        problem(ProblemType.RATE_LIMITED, "Too many failed login attempts. Try again shortly.",
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
        problem(ProblemType.RESOURCE_EXISTS, exception.getMessage(),
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
        problem(ProblemType.VALIDATION_FAILED, exception.getMessage(),
            request);
    problem.setProperty("maxDepth", exception.getMaxDepth());
    return problem;
  }

  /**
   * Answers a cursor that is malformed, forged, or from a different query.
   *
   * <p>A {@code 400}, not an empty page. Returning results anyway is the failure this whole
   * mechanism exists to prevent: a client would page past rows it should have seen and never learn
   * that it did (REQ-SEC-106, REQ-SRCH-009).
   *
   * @param exception the rejection
   * @param request the request
   * @return a {@code 400} problem detail
   */
  @ExceptionHandler(InvalidCursorException.class)
  public ProblemDetail handleInvalidCursor(
      InvalidCursorException exception, HttpServletRequest request) {
    return problem(ProblemType.MALFORMED_REQUEST, "The pagination cursor is not valid for this query. Start from the first page.",
        request);
  }

  /**
   * Answers an upload the scanner rejected.
   *
   * <p>The signature is logged and never returned. An uploader who learns which payloads this
   * scanner detects learns it one upload at a time.
   *
   * @param exception the rejection
   * @param request the request
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(MalwareDetectedException.class)
  public ProblemDetail handleMalware(MalwareDetectedException exception, HttpServletRequest request) {
    log.warn("Upload rejected by the malware scanner: {}", exception.getSignature());
    return problem(ProblemType.MALWARE_DETECTED, "The malware scan rejected this upload.",
        request);
  }

  /**
   * Answers an upload that could not be scanned.
   *
   * <p>A {@code 503} and not a {@code 422}: the upload may be fine, the system could not say so, and
   * the client should retry rather than conclude the file is bad (ADR-0024).
   *
   * @param exception the failure
   * @param request the request
   * @return a {@code 503} problem detail
   */
  @ExceptionHandler(ScannerUnavailableException.class)
  public ProblemDetail handleScannerDown(
      ScannerUnavailableException exception, HttpServletRequest request) {
    log.error("The malware scanner is unavailable; uploads are refused", exception);
    return problem(ProblemType.SCAN_UNAVAILABLE, "Uploads are refused while the malware scanner cannot be reached. Try again shortly.",
        request);
  }

  /**
   * Answers an upload beyond a size or pixel limit.
   *
   * @param exception the rejection, carrying which limit and what was presented
   * @param request the request
   * @return a {@code 413} problem detail
   */
  @ExceptionHandler(PayloadTooLargeException.class)
  public ProblemDetail handleTooLarge(
      PayloadTooLargeException exception, HttpServletRequest request) {
    return problem(ProblemType.PAYLOAD_TOO_LARGE, exception.getMessage(),
        request);
  }

  /**
   * Answers an upload whose detected type is not on the allowlist.
   *
   * @param exception the rejection, carrying what was detected
   * @param request the request
   * @return a {@code 422} problem detail naming the detected type
   */
  @ExceptionHandler(UnsupportedMediaTypeException.class)
  public ProblemDetail handleUnsupportedType(
      UnsupportedMediaTypeException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemType.VALIDATION_FAILED, exception.getMessage(),
            request);
    problem.setProperty("detectedType", exception.getDetectedType());
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
        problem(ProblemType.VALIDATION_FAILED, "The request is well formed but violates a rule.",
            request);
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * Answers a query parameter or path variable that violates a constraint.
   *
   * <p>The counterpart to {@link #handleValidation}, for the arguments that are not a request body.
   * Spring answers these {@code 400} by default, and that is the wrong half of the pair: a
   * {@code limit} of 5000 against a documented maximum of 200 is well formed and out of range,
   * which is a {@code 422} in every other case this API handles. Two statuses for one kind of
   * mistake is a distinction a client has to learn for no reason (REQ-API-003).
   *
   * <p>The bound itself is not negotiable: a request for 5000 rows is refused rather than quietly
   * reduced to 200, because a client that asks for 5000, receives 200 and is not told will page
   * wrongly and never find out (REQ-NFR-010).
   *
   * @param exception the failure, carrying one result per offending argument
   * @param request the request
   * @return a {@code 422} problem detail carrying an {@code errors} array
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ProblemDetail handleParameterValidation(
      HandlerMethodValidationException exception, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    exception
        .getParameterValidationResults()
        .forEach(
            result ->
                result
                    .getResolvableErrors()
                    .forEach(
                        error ->
                            errors.add(
                                Map.of(
                                    "field",
                                    result.getMethodParameter().getParameterName() == null
                                        ? "parameter"
                                        : result.getMethodParameter().getParameterName(),
                                    "message",
                                    error.getDefaultMessage() == null
                                        ? "invalid"
                                        : error.getDefaultMessage()))));

    ProblemDetail problem =
        problem(ProblemType.VALIDATION_FAILED, "A request parameter is well formed but outside its permitted range.",
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
    return problem(ProblemType.VALIDATION_FAILED, exception.getMessage(),
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

    // A body that stopped because it hit the limit is not a parse failure either.
    // `JsonBodyLimitFilter` refuses a declared Content-Length before anything is
    // read; a chunked body is only known to be oversized while Jackson is reading
    // it, and what reaches here is the limit's exception wrapped in a parse one.
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof PayloadTooLargeException) {
        return handleTooLarge((PayloadTooLargeException) cause, request);
      }
    }

    // An unknown field is not a parse failure and must not be answered as one.
    // The body was well-formed; it named something this endpoint does not accept,
    // and REQ-SEC-029 makes that a rejection rather than something to ignore. A
    // client sending `tenantId` or `version` in a create request needs to be told
    // which word was refused, or they will believe it was honoured.
    String unknown = unknownFieldOf(exception);
    if (unknown != null) {
      log.debug("Unknown field in request body: {}", unknown);
      ProblemDetail problem =
          problem(ProblemType.VALIDATION_FAILED, "The request contains a field this endpoint does not accept.",
              request);
      problem.setProperty("errors", List.of(Map.of("field", unknown, "message", "unknown field")));
      return problem;
    }

    // The parser's own message can quote the payload. It is not echoed, because a
    // malformed body may contain whatever the sender put in it.
    log.debug("Unreadable request body", exception);
    return problem(ProblemType.MALFORMED_REQUEST, "The request body could not be parsed.",
        request);
  }

  /**
   * Answers an upload the servlet container refused before the pipeline saw it.
   *
   * <p>{@code spring.servlet.multipart.max-file-size} is the outer bound that stops an unbounded
   * read; the upload pipeline's own, lower ceiling is the one users meet. Whichever fires, the
   * answer is the same document — which is what {@code REQ-NFR-078} asks for in so many words: an
   * oversized body is rejected by {@code api} as {@code application/problem+json} and not by
   * {@code web} as markup.
   *
   * @param exception the container's refusal
   * @param request the request
   * @return a {@code 413} problem detail
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleUploadTooLarge(
      MaxUploadSizeExceededException exception, HttpServletRequest request) {
    log.debug("Upload refused by the container limit", exception);
    return problem(ProblemType.PAYLOAD_TOO_LARGE, "The upload exceeds the size this server accepts.",
        request);
  }

  /**
   * Answers everything else at all — the framework's own refusals, and the unanticipated.
   *
   * <p>Two cases in one handler, because {@code @ExceptionHandler} matches on a class and the
   * framework's refusals do not share one. An unknown path, a method a path does not support, a
   * {@code Content-Type} nothing reads, a query parameter that is not a UUID: Spring reports each as
   * an {@link ErrorResponse}, but {@code HttpMediaTypeNotSupportedException} arrives through {@code
   * ServletException} and {@code ErrorResponseException} through {@code NestedRuntimeException}.
   * Registering the interface is not possible; testing for it here is, and it is total.
   *
   * <p>Anything that is <em>not</em> an {@code ErrorResponse} is a {@code 500}: a bug in a handler,
   * a datastore that went away mid-request, a library throwing something nobody anticipated. The
   * class Javadoc says every exception that escapes a controller becomes RFC 9457, and before this
   * handler existed that sentence was true only of the ones somebody had thought of.
   *
   * <p>Nothing about the failure reaches the caller — not the class, not the message, and not the
   * framework's own {@code detail}, which quotes the request ("Invalid UUID string: …"). All of it
   * is written for an operator; the {@code traceId} in the response is the entire mechanism by which
   * "it said something went wrong" becomes a specific line in the log ({@code REQ-NFR-042}).
   *
   * @param exception the failure
   * @param request the request
   * @return the problem detail for the status the framework chose, or a {@code 500} that discloses
   *     nothing
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(
      Exception exception, HttpServletRequest request) {

    if (exception instanceof ErrorResponse refusal) {
      int status = refusal.getStatusCode().value();
      log.debug("Refused by the framework with {}: {}", status, exception.getMessage());
      ProblemDetail refused =
          Problems.forStatus(
              status, "The request was refused before it reached a handler.", request);
      return ResponseEntity.status(refused.getStatus()).body(refused);
    }

    // ERROR, with the stack trace: this is the branch that means the application
    // met something it has no answer for, and every occurrence is worth a look.
    log.error("Unhandled exception while serving {}", request.getRequestURI(), exception);
    ProblemDetail unexpected = problem(ProblemType.INTERNAL_ERROR, Problems.INTERNAL_DETAIL, request);
    return ResponseEntity.status(unexpected.getStatus()).body(unexpected);
  }

  /**
   * The name of the unknown field, when that is why the body was refused.
   *
   * <p>The name is echoed back, and it came from the request — so it is checked against a narrow
   * pattern first. A JSON member name can be any string at all, including one carrying terminal
   * escape sequences or a few kilobytes of text, and a problem document is read by a person.
   *
   * @param exception the wrapper Spring threw
   * @return the field name, or {@code null} when the cause was something else
   */
  private static String unknownFieldOf(HttpMessageNotReadableException exception) {
    Throwable cause = exception.getCause();
    if (!(cause instanceof UnrecognizedPropertyException unrecognised)) {
      return null;
    }
    String name = unrecognised.getPropertyName();
    return name != null && SAFE_FIELD_NAME.matcher(name).matches() ? name : "(unnamed)";
  }

  /**
   * Builds a problem detail with the members every response in this application carries.
   *
   * <p>Delegates, because {@link ProblemEntryPoint} and {@link ProblemErrorController} answer the
   * requests that never reach a controller and have to produce the identical shape.
   *
   * <p>The status and the title come from the type rather than from the call site. They used to be
   * arguments, and two call sites had drifted: a {@code 429} titled "Too many attempts" and a
   * {@code 409} titled "Location not empty", both of which RFC 9457 §3.1.4 says should be the same
   * for every occurrence of a type — the varying part is {@code detail}, which both of them also
   * carry.
   *
   * @param type the condition, which decides the status and the title
   * @param detail what happened, in a sentence a user could read
   * @param request the request, for the {@code instance} member
   * @return the problem detail, ready to return
   */
  private static ProblemDetail problem(
      ProblemType type, String detail, HttpServletRequest request) {
    return Problems.of(type, detail, request);
  }
}
