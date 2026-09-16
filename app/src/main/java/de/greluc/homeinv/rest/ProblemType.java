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

  /**
   * The password was right and the account holds a second factor (REQ-AUTH-002).
   *
   * <p>A {@code 401} like a wrong password, and a different token, because it is the one case where
   * the client must do something other than ask for the password again: it asks for the code and
   * posts it to {@code /api/v1/auth/mfa}. Saying this much costs nothing — whoever sees it has
   * already presented the right password for the address.
   */
  SECOND_FACTOR_REQUIRED(
      "second-factor-required", HttpStatus.UNAUTHORIZED, "Second factor required"),

  /**
   * The code presented is not valid (REQ-AUTH-002).
   *
   * <p>One token for a wrong code, a code from a time step already spent, a recovery code already
   * used and an account with no second factor at all. Telling them apart would say whether a guess
   * was close, and "already used" would confirm that the code existed.
   */
  SECOND_FACTOR_INVALID("second-factor-invalid", HttpStatus.UNAUTHORIZED, "Second factor invalid"),

  /**
   * The account already has a confirmed second factor (REQ-AUTH-002).
   *
   * <p>A {@code 409}: enrolling again would replace a working authenticator with an unproven one,
   * and the way out is to remove the old one, which asks for a code.
   */
  SECOND_FACTOR_ENROLLED("second-factor-enrolled", HttpStatus.CONFLICT, "Second factor enrolled"),

  /**
   * The role requires a second factor and the account has none (REQ-AUTH-003).
   *
   * <p>A {@code 403} and not a {@code 401}: the caller is authenticated, and what is missing is not
   * a credential for this request but an authenticator on the account. Distinct from
   * {@link #FORBIDDEN}, because the client can do something about this one — it sends the person to
   * the enrolment rather than telling them they may not be here.
   */
  SECOND_FACTOR_MISSING("second-factor-missing", HttpStatus.FORBIDDEN, "Second factor missing"),

  /**
   * The operation needs the second factor proved again (REQ-AUTH-011).
   *
   * <p>Its own token beside {@link #SECOND_FACTOR_MISSING}, because the two ask for different
   * things: one to set an authenticator up, the other to enter a code from the one that is there.
   */
  SECOND_FACTOR_STALE("second-factor-stale", HttpStatus.FORBIDDEN, "Second factor stale"),

  /**
   * This instance creates no accounts (REQ-AUTH-004).
   *
   * <p>A {@code 403} on the one call that would have created one: accepting an invitation for an
   * address nobody has, while {@code HOMEINV_REGISTRATION_MODE} is {@code closed}. Its own token
   * because what the caller should do about it is specific — ask the operator for an account, then
   * accept the invitation while signed in — and the invitation itself is still good.
   */
  REGISTRATION_CLOSED("registration-closed", HttpStatus.FORBIDDEN, "Registration closed"),

  /** Authenticated, and not permitted on a resource they may know exists. */
  FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),

  /**
   * A quota would be exceeded by this operation (REQ-TEN-009).
   *
   * <p>Carries the current and the permitted amount, because a client that has to parse the
   * sentence to show "3 of 3 used" is a client that shows the wrong number in one of the two
   * languages. A {@code 403} and not a {@code 429}: waiting does not help, and {@code Retry-After}
   * would be a lie (05 §5.1).
   */
  QUOTA_EXCEEDED("quota-exceeded", HttpStatus.FORBIDDEN, "Quota exceeded"),

  /**
   * Somebody tried to grant or withdraw a role carrying permissions they do not hold (REQ-TEN-010).
   *
   * <p>Distinct from {@link #FORBIDDEN}, which says the caller may not do this at all. Here they may
   * administer members and reached past their own rung of the ladder, which is a different thing to
   * tell somebody and a different thing to find in a log.
   */
  ROLE_ESCALATION("role-escalation", HttpStatus.FORBIDDEN, "Role escalation"),

  /**
   * The invited address has an account and the caller is not signed in as it (REQ-TEN-004).
   *
   * <p>Holding the token proves the mailbox, which is enough to create the account the invitation
   * names. It is not enough to attach a membership to an account that already belongs to somebody.
   */
  INVITATION_NOT_YOURS("invitation-not-yours", HttpStatus.FORBIDDEN, "Invitation not yours"),

  /**
   * The tenant is suspended or waiting to be erased (open point O26).
   *
   * <p>One token for <b>both</b> states. Two would say which state a tenant is in, and that is a
   * status oracle over a boundary REQ-SEC-025 closes deliberately — the same reason
   * {@link #NOT_FOUND} must not be split. A member sees the state in the administration view, where
   * they are authenticated and entitled to it.
   */
  TENANT_INACCESSIBLE("tenant-inaccessible", HttpStatus.FORBIDDEN, "Tenant inaccessible"),

  /**
   * The resource does not exist, <em>or</em> exists and is not visible to this caller.
   *
   * <p>The two are deliberately one token. Separating them would let a caller confirm that a foreign
   * id exists, which is the enumeration answered identically either way (REQ-SEC-025).
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
   * This tenant already has an unused invitation for the address, or the person is already a member.
   *
   * <p>Two live invitations to one address would be two working tokens, and withdrawing the one an
   * administrator remembers would leave the other one open.
   */
  INVITATION_ALREADY_OPEN("invitation-already-open", HttpStatus.CONFLICT, "Invitation already open"),

  /**
   * The change would leave the tenant without an owner.
   *
   * <p>A tenant with no owner is stranded rather than degraded: nobody can invite into it, promote
   * anybody or delete it, and the instance operator cannot either, because operating the instance
   * grants nothing inside a tenant (ADR-0057).
   */
  LAST_OWNER("last-owner", HttpStatus.CONFLICT, "Last owner"),

  /**
   * The tenant has already been asked to be erased (REQ-TEN-011).
   *
   * <p>Carries when the erasure begins, so a client can say "already requested, and it happens on
   * the 12th" rather than "something went wrong".
   */
  DELETION_PENDING("deletion-pending", HttpStatus.CONFLICT, "Deletion pending"),

  /**
   * One {@code Idempotency-Key} was spent on two different requests (REQ-API-005).
   *
   * <p>A {@code 409}: the key says "the same request as before" and the body says otherwise, and
   * only the client knows which it meant. Answering with the earlier result would make a request
   * that changed nothing look like one that worked.
   */
  IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", HttpStatus.CONFLICT,
      "Idempotency-Key already used"),

  /**
   * A write on a single resource arrived without {@code If-Match} (REQ-API-004).
   *
   * <p>{@code 428}, the status invented for exactly this: the server could perform the request and
   * refuses to, because performing it would risk the lost update the header prevents. There is no
   * blind overwrite.
   */
  PRECONDITION_REQUIRED("precondition-required", HttpStatus.PRECONDITION_REQUIRED,
      "If-Match is required"),

  /**
   * The {@code If-Match} does not name the version the resource has (REQ-API-004).
   *
   * <p>Somebody else wrote in between, or the tag was never one this API issued. Both get the same
   * answer because the caller does the same thing about both: read the resource again.
   */
  PRECONDITION_FAILED("precondition-failed", HttpStatus.PRECONDITION_FAILED,
      "The resource has moved on"),

  /**
   * The membership would make a bundle contain itself (REQ-CORE-007).
   *
   * <p>A {@code 409} for the same reason {@code INVALID_MOVE} is: nothing about the request is
   * malformed, and the same request against a different bundle works. Its own token rather than
   * that one, because a client acts on them in different places — one is a tree of places, the
   * other a graph of things — and a shared token would have to be disambiguated by reading the
   * detail text, which is exactly what a stable {@code type} exists to avoid.
   */
  BUNDLE_CYCLE("bundle-cycle", HttpStatus.CONFLICT, "Bundle would contain itself"),

  /**
   * The item is out on loan, and what was asked cannot be done while it is (REQ-LIFE-005).
   *
   * <p>Two requests get it, and a caller does the same thing about both: record the return first.
   * Lending something somebody already has would open a second loan on one object, and trashing it
   * would throw away the only record of who to ask for it back — which is what REQ-LIFE-005 means
   * by "a lent item is *not deletable*".
   *
   * <p>A {@code 409} rather than a {@code 422}: nothing about the request is malformed, and the
   * identical request succeeds once the thing is back.
   */
  ITEM_LENT("item-lent", HttpStatus.CONFLICT, "The item is lent out"),

  /**
   * The move would break the tree (REQ-CORE-045, REQ-CORE-047).
   *
   * <p>A {@code 409}: nothing about the request is malformed, and the same request would work
   * against a different target. Two causes — a place moving into its own subtree, and a category
   * that does not take the kind of place being moved into it — because the caller does the same
   * thing about both: choose somewhere else.
   */
  INVALID_MOVE("invalid-move", HttpStatus.CONFLICT, "Invalid move"),

  /**
   * A sibling already carries the name a location was to be given.
   *
   * <p>Separate from {@link #RESOURCE_EXISTS}, which is about a client-chosen <em>id</em>: a
   * client branching on the status has to be able to tell "that identifier is taken" from
   * "that name is taken", and only one of the two is fixed by choosing a different id.
   */
  NAME_TAKEN("name-taken", HttpStatus.CONFLICT, "Name taken"),

  /**
   * A key a tenant chose for a definition is already in use for the same kind of thing.
   *
   * <p>Separate from both neighbours above: a key is not an id a client chose and not a name among
   * siblings. It is the stable identifier of a definition, it appears inside every item that uses
   * the field, and it is unique per tenant.
   */
  TYPE_KEY_TAKEN("type-key-taken", HttpStatus.CONFLICT, "Type key taken"),

  /**
   * A published type version was edited.
   *
   * <p>{@code 409} and not {@code 422}: nothing about the request is malformed, and the same body
   * against a draft succeeds. The answer carries the version id so a client can offer to start a
   * draft from it.
   */
  VERSION_FROZEN("version-frozen", HttpStatus.CONFLICT, "Version frozen"),

  /** An inheriting type widened a field it inherits, which REQ-CORE-024 permits one way only. */
  CONSTRAINT_LOOSENED(
      "constraint-loosened", HttpStatus.UNPROCESSABLE_CONTENT, "Constraint loosened"),

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
   * The invitation cannot be used: unknown, used, withdrawn or expired (REQ-TEN-004).
   *
   * <p>One token for all four, deliberately. Telling them apart would let whoever holds a link learn
   * that somebody was invited to this instance, which is what the single use is meant to end rather
   * than advertise. A {@code 410} and not a {@code 404}, because the caller followed a link meant
   * for them and "this no longer works" is true of every one of the four.
   */
  INVITATION_UNUSABLE("invitation-unusable", HttpStatus.GONE, "Invitation unusable"),

  /**
   * The revocation link no longer works: unknown, already used, or past the grace period.
   *
   * <p>One token for all three, for the reason {@link #INVITATION_UNUSABLE} is one for four:
   * telling them apart would let whoever holds a link learn that a tenant here was asked to be
   * erased.
   */
  REVOCATION_UNUSABLE("revocation-unusable", HttpStatus.GONE, "Revocation unusable"),

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
