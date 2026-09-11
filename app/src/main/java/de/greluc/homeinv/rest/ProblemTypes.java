/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.net.URI;

/**
 * The {@code problem.type} URIs this application emits.
 *
 * <p>The set is not open. {@code docs/reference/problem-types.yaml} is the registry, and
 * {@code REQ-API-003} makes it the contract clients branch on — "never on prose". A constant here
 * without a row there is a type no client knows, which is the same as no type at all.
 *
 * <p>Adding one passes {@code oasdiff} as a minor change; removing one, or changing what it means,
 * is breaking. That asymmetry is why this class is a closed list of constants rather than a helper
 * that builds a URI from a string at the call site.
 */
public final class ProblemTypes {

  /**
   * The namespace every type lives under.
   *
   * <p>A URI, not a URL that resolves: RFC 9457 requires an identifier, and making it dereferenceable
   * would mean the error format depends on a web server being up. {@code home-inv.example} is
   * reserved for documentation and cannot be registered by anyone (ADR-0023's namespace decision).
   */
  private static final String NS = "https://home-inv.example/problems/";

  private ProblemTypes() {}

  /** {@code 400} — the request could not be parsed, or an unknown field was present. */
  public static final URI MALFORMED_REQUEST = URI.create(NS + "malformed-request");

  /** {@code 401} — no valid credential was presented. */
  public static final URI UNAUTHENTICATED = URI.create(NS + "unauthenticated");

  /** {@code 403} — authenticated, and not permitted on a resource they may know exists. */
  public static final URI FORBIDDEN = URI.create(NS + "forbidden");

  /**
   * {@code 404} — the resource does not exist, <em>or</em> exists and is not visible to this
   * caller.
   *
   * <p>The two are deliberately one token. Separating them would let a caller confirm that a
   * foreign id exists, which is the enumeration answered identically either way (REQ-SEC-016).
   */
  public static final URI NOT_FOUND = URI.create(NS + "not-found");

  /** {@code 413} — the body exceeds the JSON limit, or a bulk operation exceeds its entry count. */
  public static final URI PAYLOAD_TOO_LARGE = URI.create(NS + "payload-too-large");

  /**
   * {@code 422} — syntactically valid, and violating a domain rule.
   *
   * <p>Carries the field paths, so a form can mark the fields rather than showing a sentence.
   */
  public static final URI VALIDATION_FAILED = URI.create(NS + "validation-failed");

  /** {@code 429} — a per-user, per-tenant or per-IP rate limit was reached. */
  public static final URI RATE_LIMITED = URI.create(NS + "rate-limited");
}
