/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The {@code problem.type} values this application emits, with the status each one carries.
 *
 * <p>The set is not open. {@code docs/reference/problem-types.yaml} is the registry, and {@code
 * REQ-API-003} makes it the contract clients branch on — "never on prose". A constant here without a
 * row there is a type no client knows, which is the same as no type at all; {@code
 * ProblemTypeRegistryTest} compares the two in both directions and fails the build on either
 * mismatch.
 *
 * <h2>Why the status lives here too</h2>
 *
 * <p>Because the registry treats the pairing as part of the contract: "changing the status code of
 * an existing case" is a breaking change, which only means something if there is one status per
 * token. It used to be a second argument at every call site, and two of them had drifted into
 * building the URI inline from a string — the exact failure this being a closed list was supposed to
 * prevent.
 *
 * <h2>Why an enum rather than constants</h2>
 *
 * <p>An enum is a closed list that can be iterated, compared with the registry, and named in an
 * annotation. {@link CanFail} is the last of those: an endpoint declares which of these it can
 * produce, and the OpenAPI document is generated from the declarations rather than from a list
 * somebody keeps beside the code.
 */
public enum ProblemType {

  /** The request could not be parsed, or an unknown field was present. */
  MALFORMED_REQUEST("malformed-request", HttpStatus.BAD_REQUEST, "Malformed request"),

  /** No valid credential was presented. */
  UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Unauthenticated"),

  /** Authenticated, and not permitted on a resource they may know exists. */
  FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),

  /**
   * The resource does not exist, <em>or</em> exists and is not visible to this caller.
   *
   * <p>The two are deliberately one token. Separating them would let a caller confirm that a foreign
   * id exists, which is the enumeration answered identically either way (REQ-SEC-016).
   */
  NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Not found"),

  /** The path exists and does not support this method. */
  METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),

  /** The caller's {@code Accept} header allows nothing this endpoint produces. */
  NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),

  /**
   * A creating {@code POST} supplied an id that exists with different content.
   *
   * <p>The same id with the <em>same</em> content is not an error: it returns {@code 200} instead of
   * {@code 201}, which is what makes a retried creation safe when the client never saw the first
   * answer (REQ-CORE-001).
   */
  RESOURCE_EXISTS("resource-exists", HttpStatus.CONFLICT, "Resource exists"),

  /**
   * A sibling already carries the name a location was to be given.
   *
   * <p>Separate from {@link #RESOURCE_EXISTS}, which is about a client-chosen <em>id</em>: a
   * client branching on the status has to be able to tell "that identifier is taken" from
   * "that name is taken", and only one of the two is fixed by choosing a different id.
   */
  NAME_TAKEN("name-taken", HttpStatus.CONFLICT, "Name taken"),

  /** The body exceeds the JSON limit, or an upload exceeds its size or pixel limit. */
  PAYLOAD_TOO_LARGE("payload-too-large", HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),

  /**
   * The request's {@code Content-Type} is not one this endpoint reads.
   *
   * <p>About the header. An upload whose <em>bytes</em> are a type this system does not store is a
   * {@code 422} with {@link #VALIDATION_FAILED}, decided after sniffing.
   */
  UNSUPPORTED_MEDIA_TYPE(
      "unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),

  /**
   * Syntactically valid, and violating a domain rule.
   *
   * <p>Carries the field paths, so a form can mark the fields rather than showing a sentence.
   */
  VALIDATION_FAILED("validation-failed", HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed"),

  /** The malware scan found something; the blob is discarded. */
  MALWARE_DETECTED("malware-detected", HttpStatus.UNPROCESSABLE_CONTENT, "Malware detected"),

  /** A per-user, per-tenant or per-IP rate limit was reached. */
  RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Rate limited"),

  /**
   * Something failed that this application has no specific answer for.
   *
   * <p>The one type whose {@code detail} says nothing. An exception message is written for an
   * operator and regularly carries a query, a path or an identifier; the {@code traceId} is what
   * connects the caller's report to the log line that has all of it (REQ-NFR-042).
   */
  INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),

  /**
   * The file has no verdict yet: it is {@code PENDING_SCAN}, or the scanner could not be reached and
   * it is {@code SCAN_FAILED}. Either way it is not retrievable.
   *
   * <p>A {@code 503} and not a {@code 422}: the file may be fine and the system cannot say so yet
   * (ADR-0024). Answered by {@code GET /api/v1/media/{id}} rather than by the upload, which is
   * answered {@code 202} before the scan runs (ADR-0054).
   */
  SCAN_UNAVAILABLE("scan-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Scan unavailable");

  /**
   * The namespace every type lives under.
   *
   * <p>A URI, not a URL that resolves: RFC 9457 requires an identifier, and making it
   * dereferenceable would mean the error format depends on a web server being up. {@code
   * home-inv.example} is reserved for documentation and cannot be registered by anyone (ADR-0023's
   * namespace decision).
   */
  public static final String NAMESPACE = "https://home-inv.example/problems/";

  private final String token;
  private final HttpStatus status;
  private final String title;

  ProblemType(String token, HttpStatus status, String title) {
    this.token = token;
    this.status = status;
    this.title = title;
  }

  /**
   * The registry's name for this condition.
   *
   * @return the token, as {@code docs/reference/problem-types.yaml} spells it
   */
  public String token() {
    return token;
  }

  /**
   * The identifier a client branches on.
   *
   * @return the full type URI
   */
  public URI uri() {
    return URI.create(NAMESPACE + token);
  }

  /**
   * The status this condition is always answered with.
   *
   * @return the status
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * The stable, human-readable name, which becomes the document's {@code title}.
   *
   * @return the title
   */
  public String title() {
    return title;
  }
}
