/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.AccessDeniedException;
import de.greluc.homeinv.identity.api.InvalidCredentialsException;
import de.greluc.homeinv.identity.api.InvalidSecondFactorException;
import de.greluc.homeinv.identity.api.FederatedSignIn;
import de.greluc.homeinv.identity.api.RegistrationClosedException;
import de.greluc.homeinv.identity.api.SecondFactorAlreadyEnrolledException;
import de.greluc.homeinv.idempotency.api.IdempotencyKeyConflictException;
import de.greluc.homeinv.identity.api.SecondFactorRequiredException;
import de.greluc.homeinv.identity.api.TooManyAttemptsException;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import de.greluc.homeinv.inventory.api.BundleCycleException;
import de.greluc.homeinv.inventory.api.ItemLentException;
import de.greluc.homeinv.inventory.api.ItemStateException;
import de.greluc.homeinv.notification.api.UnservedTriggerException;
import de.greluc.homeinv.portability.api.ExportNotReadyException;
import de.greluc.homeinv.inventory.api.ItemAlreadyExistsException;
import de.greluc.homeinv.locations.api.InvalidMoveException;
import de.greluc.homeinv.locations.api.LocationNotEmptyException;
import de.greluc.homeinv.catalog.api.InvalidAttributesException;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.authorization.api.RoleNameTakenException;
import de.greluc.homeinv.authorization.api.SecondFactorMissingException;
import de.greluc.homeinv.authorization.api.SecondFactorStaleException;
import de.greluc.homeinv.locations.api.NameTakenException;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tenancy.api.InvitationAlreadyOpenException;
import de.greluc.homeinv.tenancy.api.InvitationNotYoursException;
import de.greluc.homeinv.tenancy.api.InvitationUnusableException;
import de.greluc.homeinv.tenancy.api.LastOwnerException;
import de.greluc.homeinv.tenancy.api.AlreadyPendingDeletionException;
import de.greluc.homeinv.tenancy.api.QuotaExceededException;
import de.greluc.homeinv.tenancy.api.RevocationUnusableException;
import de.greluc.homeinv.tenancy.api.TenantInaccessibleException;
import de.greluc.homeinv.tenancy.api.RoleEscalationException;
import de.greluc.homeinv.tenancy.api.TenantLimitReachedException;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.media.api.MalwareDetectedException;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.UnsupportedMediaTypeException;
import de.greluc.homeinv.notification.api.WebhookTargets;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.StaleVersionException;
import de.greluc.homeinv.platform.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
   * Answers a catalogue lookup that resolved nothing.
   *
   * <p>{@code catalog} raises its own {@link de.greluc.homeinv.catalog.api.TypeRegistry
   * .UnknownTypeException} rather than {@link NotFoundException} — a type, a version, a field or a
   * value list that this tenant does not have, or that is archived. It means exactly what a
   * {@code NotFoundException} means and was answered {@code 500} until 2026-09-14, because nothing
   * here named it: twelve catalogue endpoints declared {@code NOT_FOUND} and could not produce it.
   * {@code EndpointNegativeCoverageIT} found them by calling every endpoint with an id nothing has.
   *
   * <p>The exception's own message is the detail. It names what was looked for and never what was
   * found, which is the rule its Javadoc states, so it discloses nothing a caller did not already
   * send.
   *
   * @param exception the failure
   * @param request the request, for the {@code instance} member
   * @return a {@code 404} problem detail
   */
  @ExceptionHandler(de.greluc.homeinv.catalog.api.TypeRegistry.UnknownTypeException.class)
  public ProblemDetail handleUnknownType(
      de.greluc.homeinv.catalog.api.TypeRegistry.UnknownTypeException exception,
      HttpServletRequest request) {
    // DEBUG for the same reason a NotFoundException is: an id that resolves to
    // nothing is an ordinary answer, and anybody could otherwise fill the log.
    log.debug("Unknown catalogue reference: {}", exception.getMessage());
    return problem(ProblemType.NOT_FOUND, exception.getMessage(), request);
  }

  /**
   * Answers a path variable or parameter the framework could not convert.
   *
   * <p>{@code /api/v1/items/not-a-uuid} is a malformed request and not a server fault, and it was
   * answered {@code 500} until 2026-09-14 — logged at {@code ERROR}, with a stack trace, by the
   * catch-all below. Spring's own {@code MethodArgumentTypeMismatchException} is not an {@code
   * ErrorResponse}, so the catch-all's {@code ErrorResponse} branch never saw it and every mistyped
   * id in the world looked, in the log, like a bug in this application.
   *
   * <p>Nothing of the exception reaches the caller. Its message quotes the value that failed to
   * convert, and a value is the caller's own input echoed back — harmless here, and a habit worth
   * not forming (08 §8.2).
   *
   * @param exception the conversion failure
   * @param request the request, for the {@code instance} member
   * @return a {@code 400} problem detail
   */
  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleUnconvertible(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception,
      HttpServletRequest request) {
    log.debug("Not convertible: {}", exception.getName());
    return problem(
        ProblemType.MALFORMED_REQUEST,
        "One of the values in the path or query string is not of the expected type.",
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
   * Answers a tenant the account may not have, because it already has as many as it may.
   *
   * <p>Both numbers travel as members rather than only in the sentence: 05 §5.1 says a quota
   * refusal carries "the current and permitted amount", and a client showing that pair should not
   * have to read English to find it.
   *
   * @param exception the refusal, carrying the two numbers
   * @param request the request, for the {@code instance} member
   * @return a {@code 403} with {@code quota-exceeded}
   */
  @ExceptionHandler(TenantLimitReachedException.class)
  public ProblemDetail handleTenantLimit(
      TenantLimitReachedException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            ProblemType.QUOTA_EXCEEDED,
            "This account is in as many tenants as it may be. Ask the operator to raise the limit.",
            request);
    problem.setProperty("current", exception.getCurrent());
    problem.setProperty("permitted", exception.getPermitted());
    problem.setProperty("quota", "tenants-per-user");
    return problem;
  }

  /**
   * Answers a tenant-owned role whose name is taken (REQ-TEN-006).
   *
   * <p>The same token a location's sibling-name collision gets, and for the same reason: a client
   * has to tell "that name is taken" from "that identifier is taken", and only one of the two is
   * fixed by choosing a different id.
   *
   * @param exception the refusal, naming the name
   * @param request the request, for the {@code instance} member
   * @return a {@code 409} with {@code name-taken}
   */
  @ExceptionHandler(RoleNameTakenException.class)
  public ProblemDetail handleRoleNameTaken(
      RoleNameTakenException exception, HttpServletRequest request) {
    ProblemDetail problem = problem(ProblemType.NAME_TAKEN, exception.getMessage(), request);
    problem.setProperty("name", exception.getName());
    return problem;
  }

  /**
   * Answers a request to a tenant that is suspended or waiting to be erased (open point O26).
   *
   * <p>One token for both states, and no member saying which. That is the point: a caller able to
   * tell them apart learns something about a tenant they are being kept out of.
   *
   * @param exception the refusal
   * @param request the request, for the {@code instance} member
   * @return a {@code 403} with {@code tenant-inaccessible}
   */
  @ExceptionHandler(TenantInaccessibleException.class)
  public ProblemDetail handleTenantInaccessible(
      TenantInaccessibleException exception, HttpServletRequest request) {
    return problem(ProblemType.TENANT_INACCESSIBLE, exception.getMessage(), request);
  }

  /**
   * Answers a second erasure request for a tenant that already has one.
   *
   * @param exception the refusal, carrying when the erasure begins
   * @param request the request, for the {@code instance} member
   * @return a {@code 409} with {@code deletion-pending}
   */
  @ExceptionHandler(AlreadyPendingDeletionException.class)
  public ProblemDetail handleAlreadyPendingDeletion(
      AlreadyPendingDeletionException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemType.DELETION_PENDING, exception.getMessage(), request);
    problem.setProperty("eraseAfter", exception.getEraseAfter().toString());
    return problem;
  }

  /**
   * Answers a revocation link that is unknown, spent, or past its grace period.
   *
   * @param exception the refusal
   * @param request the request, for the {@code instance} member
   * @return a {@code 410} with {@code revocation-unusable}
   */
  @ExceptionHandler(RevocationUnusableException.class)
  public ProblemDetail handleRevocationUnusable(
      RevocationUnusableException exception, HttpServletRequest request) {
    return problem(ProblemType.REVOCATION_UNUSABLE, exception.getMessage(), request);
  }

  /**
   * Answers an operation that would take the tenant past a quota (REQ-TEN-009).
   *
   * <p>Both numbers and the quota's name travel as members, because 05 §5.1 says a quota refusal
   * carries "the current and permitted amount" and a client showing that pair should not have to
   * parse English to find it.
   *
   * @param exception the refusal, carrying the quota and both numbers
   * @param request the request, for the {@code instance} member
   * @return a {@code 403} with {@code quota-exceeded}
   */
  @ExceptionHandler(QuotaExceededException.class)
  public ProblemDetail handleQuotaExceeded(
      QuotaExceededException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            ProblemType.QUOTA_EXCEEDED,
            "This tenant has reached its quota. Ask the operator to raise it.",
            request);
    problem.setProperty("quota", exception.getQuota().name());
    problem.setProperty("current", exception.getCurrent());
    problem.setProperty("permitted", exception.getPermitted());
    return problem;
  }

  /**
   * Answers an attempt to grant or withdraw more than the caller holds (REQ-TEN-010).
   *
   * <p>Both roles travel as members. A client showing "you cannot make somebody an OWNER" needs to
   * know which two rungs were involved, and reading that out of an English sentence is how a
   * translated interface ends up guessing.
   *
   * @param exception the refusal, carrying both roles
   * @param request the request, for the {@code instance} member
   * @return a {@code 403} with {@code role-escalation}
   */
  @ExceptionHandler(RoleEscalationException.class)
  public ProblemDetail handleRoleEscalation(
      RoleEscalationException exception, HttpServletRequest request) {
    // REQ-TEN-010 asks for the attempt to be logged as well as rejected. It is
    // logged in the application layer too; this line is what an operator reading
    // the access log sees, with the caller already in the MDC.
    log.warn("Refused a role grant: {}", exception.getMessage());
    ProblemDetail problem =
        problem(
            ProblemType.ROLE_ESCALATION,
            "You cannot grant or withdraw a role with permissions you do not hold yourself.",
            request);
    problem.setProperty("actorRole", exception.getActorRole());
    problem.setProperty("targetRole", exception.getTargetRole());
    return problem;
  }

  /**
   * Answers a change that would leave the tenant without an owner.
   *
   * @param exception the refusal
   * @param request the request, for the {@code instance} member
   * @return a {@code 409} with {@code last-owner}
   */
  @ExceptionHandler(LastOwnerException.class)
  public ProblemDetail handleLastOwner(
      LastOwnerException exception, HttpServletRequest request) {
    return problem(ProblemType.LAST_OWNER, exception.getMessage(), request);
  }

  /**
   * Answers an invitation for an address that already has one, or is already a member.
   *
   * @param exception the refusal, which names the open invitation where there is one
   * @param request the request, for the {@code instance} member
   * @return a {@code 409} with {@code invitation-already-open}
   */
  @ExceptionHandler(InvitationAlreadyOpenException.class)
  public ProblemDetail handleInvitationAlreadyOpen(
      InvitationAlreadyOpenException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemType.INVITATION_ALREADY_OPEN, exception.getMessage(), request);
    if (exception.getInvitationId() != null) {
      problem.setProperty("invitationId", exception.getInvitationId().toString());
    }
    return problem;
  }

  /**
   * Answers a token that is unknown, used, withdrawn or expired.
   *
   * <p>The four are one answer and carry no member saying which. That is the point: a caller able
   * to tell "this was real and has been used" from "this was never real" learns that somebody was
   * invited here (REQ-TEN-004).
   *
   * @param exception the refusal
   * @param request the request, for the {@code instance} member
   * @return a {@code 410} with {@code invitation-unusable}
   */
  @ExceptionHandler(InvitationUnusableException.class)
  public ProblemDetail handleInvitationUnusable(
      InvitationUnusableException exception, HttpServletRequest request) {
    return problem(ProblemType.INVITATION_UNUSABLE, exception.getMessage(), request);
  }

  /**
   * Answers an acceptance for an address whose account the caller is not signed in as.
   *
   * @param exception the refusal
   * @param request the request, for the {@code instance} member
   * @return a {@code 403} with {@code invitation-not-yours}
   */
  @ExceptionHandler(InvitationNotYoursException.class)
  public ProblemDetail handleInvitationNotYours(
      InvitationNotYoursException exception, HttpServletRequest request) {
    return problem(ProblemType.INVITATION_NOT_YOURS, exception.getMessage(), request);
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
   * Answers a key a tenant has already used for the same kind of definition.
   *
   * @param exception the conflict, carrying the key
   * @param request the request
   * @return a {@code 409} problem detail naming the key
   */
  @ExceptionHandler(TypeAdministration.TypeKeyTakenException.class)
  public ProblemDetail handleTypeKeyTaken(
      TypeAdministration.TypeKeyTakenException exception, HttpServletRequest request) {
    ProblemDetail problem = problem(ProblemType.TYPE_KEY_TAKEN, exception.getMessage(), request);
    problem.setProperty("key", exception.getKey());
    return problem;
  }

  /**
   * Answers an edit to a published type version.
   *
   * <p>The version id travels back because there is exactly one useful next move — start a draft
   * from it — and a client that was not told which version cannot offer it.
   *
   * @param exception the refusal, carrying the version
   * @param request the request
   * @return a {@code 409} problem detail naming the version
   */
  @ExceptionHandler(TypeAdministration.VersionFrozenException.class)
  public ProblemDetail handleVersionFrozen(
      TypeAdministration.VersionFrozenException exception, HttpServletRequest request) {
    ProblemDetail problem = problem(ProblemType.VERSION_FROZEN, exception.getMessage(), request);
    problem.setProperty("versionId", exception.getVersionId().toString());
    return problem;
  }

  /**
   * Answers a type that widens a field it inherits.
   *
   * <p>The field key is echoed back; the property that was widened is in the message, because a
   * person reading "required" out of context learns nothing and the sentence says it plainly.
   *
   * @param exception the refusal, carrying the field
   * @param request the request
   * @return a {@code 422} problem detail naming the field
   */
  @ExceptionHandler(TypeAdministration.ConstraintLoosenedException.class)
  public ProblemDetail handleConstraintLoosened(
      TypeAdministration.ConstraintLoosenedException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemType.CONSTRAINT_LOOSENED, exception.getMessage(), request);
    problem.setProperty("fieldKey", exception.getFieldKey());
    return problem;
  }

  /**
   * Answers a tag name, or a tag group key, the tenant has already used.
   *
   * <p>The same token as a location's sibling name: from a client's point of view both say "that
   * name is taken, pick another", and inventing a second token for the same branch would make a
   * client handle two.
   *
   * @param exception the conflict, carrying the name
   * @param request the request
   * @return a {@code 409} problem detail naming the name
   */
  /**
   * Answers a saved search whose name the tenant already uses (REQ-SRCH-008).
   *
   * <p>A {@code 409} naming the name, because that is what the caller has to change. The name is
   * the tenant's own text and reaches the response as it was sent, which is what makes the message
   * actionable; it is not a credential and nothing is looked up with it.
   *
   * @param exception the refusal, carrying the name
   * @param request the request
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(de.greluc.homeinv.search.api.SavedSearches.SavedSearchNameTakenException.class)
  public ProblemDetail handleSavedSearchNameTaken(
      de.greluc.homeinv.search.api.SavedSearches.SavedSearchNameTakenException exception,
      HttpServletRequest request) {
    ProblemDetail problem = problem(ProblemType.NAME_TAKEN, exception.getMessage(), request);
    problem.setProperty("name", exception.getName());
    return problem;
  }

  @ExceptionHandler(TagService.TagNameTakenException.class)
  public ProblemDetail handleTagNameTaken(
      TagService.TagNameTakenException exception, HttpServletRequest request) {
    ProblemDetail problem = problem(ProblemType.NAME_TAKEN, exception.getMessage(), request);
    problem.setProperty("name", exception.getName());
    return problem;
  }

  /**
   * Answers a webhook target at an address the tenant already uses (REQ-API-010).
   *
   * <p>A {@code 409} rather than the constraint violation reaching the surface as a {@code 500}.
   * The address is the tenant's own configuration and reaches the response as it was sent, which is
   * what makes the message actionable — and it is not a secret: the signing secret is the secret,
   * and it is in a different column and in no response at all.
   *
   * @param exception the refusal, carrying the address
   * @param request the request
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(WebhookTargets.WebhookTargetUrlTakenException.class)
  public ProblemDetail handleWebhookTargetUrlTaken(
      WebhookTargets.WebhookTargetUrlTakenException exception, HttpServletRequest request) {
    return problem(ProblemType.RESOURCE_EXISTS, exception.getMessage(), request);
  }

  /**
   * Answers a location whose name a sibling already has.
   *
   * <p>{@code 409} and not {@code 422}: a client branching on the status has to be able to tell a
   * conflict from a bad field, which is the same reasoning {@code resource-exists} carries. The
   * name is echoed back because the caller sent it, and the parent because "a sibling" is only
   * meaningful with one — {@code null} among the roots, which is a fact and not an omission.
   *
   * @param exception the conflict, carrying the name and the parent
   * @param request the request
   * @return a {@code 409} problem detail naming the name
   */
  @ExceptionHandler(NameTakenException.class)
  public ProblemDetail handleNameTaken(NameTakenException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            ProblemType.NAME_TAKEN,
            "Another place in the same spot is already called that. Names have to be unique "
                + "among the things in one place.",
            request);
    problem.setProperty("name", exception.getName());
    if (exception.getParentId() != null) {
      problem.setProperty("parentId", exception.getParentId().toString());
    }
    return problem;
  }

  /**
   * Answers a login that failed.
   *
   * <p>One answer for every reason: unknown address, wrong password, locked account. Not logged
   * here — the service already logged the actual reason, which an operator needs and a caller
   * must not have (REQ-SEC-110).
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
   * Answers a login that has passed the password and owes a code (REQ-AUTH-002).
   *
   * <p>Its own type rather than a plain {@code 401}, because it is the one case where the client
   * must do something other than ask for the password again.
   *
   * @param exception the half-done login
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(SecondFactorRequiredException.class)
  public ProblemDetail handleSecondFactorRequired(
      SecondFactorRequiredException exception, HttpServletRequest request) {
    return problem(
        ProblemType.SECOND_FACTOR_REQUIRED,
        "This account is protected by a second factor. Post the code to /api/v1/auth/mfa.",
        request);
  }

  /**
   * Answers an invitation that would have created an account on an instance that makes none.
   *
   * @param exception the refusal, carrying what the caller should do
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(RegistrationClosedException.class)
  public ProblemDetail handleRegistrationClosed(
      RegistrationClosedException exception, HttpServletRequest request) {
    return problem(ProblemType.REGISTRATION_CLOSED, exception.getMessage(), request);
  }

  /**
   * Answers a federated callback whose flow is gone (REQ-AUTH-005).
   *
   * <p>Unknown, expired and already spent are one answer. Telling them apart would let
   * somebody probe for sign-ins in flight, and the caller's way out is the same in all three:
   * begin again.
   *
   * @param exception what was raised
   * @param request the request, for the instance URI
   * @return the problem document
   */
  @ExceptionHandler(FederatedSignIn.UnknownFlowException.class)
  public ProblemDetail handleUnknownFederatedFlow(
      FederatedSignIn.UnknownFlowException exception, HttpServletRequest request) {
    return problem(ProblemType.FEDERATED_FLOW_UNKNOWN, exception.getMessage(), request);
  }

  /**
   * Answers a federated sign-in with no provider to perform it (REQ-PLG-007).
   *
   * <p>Not installed and installed-but-unreachable are one answer, because what a stranger at
   * a login page learns from the difference is something about the deployment rather than
   * about their own request.
   *
   * @param exception what was raised
   * @param request the request, for the instance URI
   * @return the problem document
   */
  @ExceptionHandler(FederatedSignIn.NoIdentityProviderException.class)
  public ProblemDetail handleNoIdentityProvider(
      FederatedSignIn.NoIdentityProviderException exception, HttpServletRequest request) {
    return problem(ProblemType.PLUGIN_UNAVAILABLE, exception.getMessage(), request);
  }

  /**
   * Answers a federated flow that finished and is refused (REQ-AUTH-006).
   *
   * <p>The refusal already knows which of the four it is; this turns it into the document, so
   * that every error on this surface keeps one shape (REQ-API-003).
   *
   * @param exception what was raised, carrying the type
   * @param request the request, for the instance URI
   * @return the problem document
   */
  @ExceptionHandler(FederatedAuthController.FederatedRefusedException.class)
  public ProblemDetail handleFederatedRefusal(
      FederatedAuthController.FederatedRefusedException exception, HttpServletRequest request) {
    return problem(exception.type(), exception.getMessage(), request);
  }

  /**
   * Answers a role being used without the second factor it requires (REQ-AUTH-003).
   *
   * <p>The detail says what to do about it, and it is the exception's own message rather than a
   * sentence written here: the two places that raise it — a session and a grant — need different
   * ones, and both are about somebody enrolling.
   *
   * @param exception the refusal, carrying what the caller should do
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(SecondFactorMissingException.class)
  public ProblemDetail handleSecondFactorMissing(
      SecondFactorMissingException exception, HttpServletRequest request) {
    return problem(ProblemType.SECOND_FACTOR_MISSING, exception.getMessage(), request);
  }

  /**
   * Answers an operation whose re-confirmation has run out (REQ-AUTH-011).
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(SecondFactorStaleException.class)
  public ProblemDetail handleSecondFactorStale(
      SecondFactorStaleException exception, HttpServletRequest request) {
    return problem(ProblemType.SECOND_FACTOR_STALE, exception.getMessage(), request);
  }

  /**
   * Answers a code that is not valid (REQ-AUTH-002).
   *
   * <p>One answer for a wrong code, a spent one and an account with no second factor: telling them
   * apart would say whether a guess was close.
   *
   * @param exception the rejection
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(InvalidSecondFactorException.class)
  public ProblemDetail handleInvalidSecondFactor(
      InvalidSecondFactorException exception, HttpServletRequest request) {
    return problem(ProblemType.SECOND_FACTOR_INVALID, "That code is not valid.", request);
  }

  /**
   * Answers an enrolment over a confirmed one (REQ-AUTH-002).
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return the problem
   */
  @ExceptionHandler(SecondFactorAlreadyEnrolledException.class)
  public ProblemDetail handleSecondFactorEnrolled(
      SecondFactorAlreadyEnrolledException exception, HttpServletRequest request) {
    return problem(
        ProblemType.SECOND_FACTOR_ENROLLED,
        "This account already has a second factor. Remove it before enrolling another.",
        request);
  }

  /**
   * Answers a reset token that is unknown, expired or already spent (REQ-SEC-018).
   *
   * <p>One answer for all three. Telling them apart would tell somebody holding a stolen token
   * which one they have, and the useful half — "ask for a new one" — is the same either way.
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(de.greluc.homeinv.identity.api.PasswordReset.InvalidResetTokenException.class)
  public ProblemDetail handleInvalidResetToken(
      de.greluc.homeinv.identity.api.PasswordReset.InvalidResetTokenException exception,
      HttpServletRequest request) {
    return problem(ProblemType.VALIDATION_FAILED, exception.getMessage(), request);
  }

  /**
   * Answers a password the policy refuses (REQ-SEC-011).
   *
   * <p>The message is for the person choosing it rather than for a client to branch on: there is
   * one rule, and saying what it is beats a token they would have to look up.
   *
   * @param exception the refusal, carrying what to tell them
   * @param request the request, for the instance URI
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(WeakPasswordException.class)
  public ProblemDetail handleWeakPassword(
      WeakPasswordException exception, HttpServletRequest request) {
    return problem(ProblemType.VALIDATION_FAILED, exception.getMessage(), request);
  }

  /**
   * Answers a login the throttle is holding back (REQ-SEC-012).
   *
   * <p>Carries {@code Retry-After} in seconds, so a client waits the right amount instead of
   * retrying immediately and making the delay grow. Distinct from a wrong password on purpose: a
   * client that cannot tell them apart will hammer.
   *
   * <p>The header <b>and</b> the property, because they are read by different things: a browser
   * and every HTTP library know what {@code Retry-After} means without being told, and a problem
   * document is what a client of this API parses. Until 2026-09-21 only the property was written,
   * which made the requirement's "the response is 429 with {@code Retry-After}" false of the one
   * endpoint that had a throttle at all.
   *
   * @param exception the throttle decision, carrying the remaining wait
   * @param request the request
   * @param response the response, which is where a header can still be set from here
   * @return a {@code 429} problem detail with the wait in seconds
   */
  @ExceptionHandler(TooManyAttemptsException.class)
  public ProblemDetail handleTooManyAttempts(
      TooManyAttemptsException exception,
      HttpServletRequest request,
      HttpServletResponse response) {
    ProblemDetail problem =
        problem(ProblemType.RATE_LIMITED, "Too many attempts. Try again shortly.",
            request);
    long seconds = exception.getRetryAfter().toSeconds();
    problem.setProperty("retryAfterSeconds", seconds);
    response.setHeader("Retry-After", Long.toString(seconds));
    return problem;
  }

  /**
   * Answers a caller who is simply going too fast (REQ-SEC-064).
   *
   * <p>The same problem type as the login throttle above, and that is deliberate: which limit was
   * reached — the per-user one, the per-tenant one, the per-address one or the stricter one on the
   * authentication endpoints — is an operational detail, and what a client does about any of them
   * is identical. The {@code RateLimit} headers the interceptor already wrote say which scope it
   * was, for anybody who wants to know.
   *
   * @param exception the refusal, carrying the wait and the scope
   * @param request the request
   * @param response the response, for {@code Retry-After}
   * @return a {@code 429} problem detail
   */
  @ExceptionHandler(RateLimitedException.class)
  public ProblemDetail handleRateLimited(
      RateLimitedException exception, HttpServletRequest request, HttpServletResponse response) {
    ProblemDetail problem =
        problem(
            ProblemType.RATE_LIMITED,
            "Too many requests. Wait for the time in Retry-After and try again.",
            request);
    long seconds = Math.max(1, exception.getRetryAfter().toSeconds());
    problem.setProperty("retryAfterSeconds", seconds);
    response.setHeader("Retry-After", Long.toString(seconds));
    return problem;
  }

  /**
   * Answers a move that would break the tree (REQ-CORE-045, REQ-CORE-047).
   *
   * <p>The detail says which of the two it was — a place moving into itself, or a category that
   * does not take this kind of place — because the fix differs even though the token does not.
   *
   * @param exception the refusal, carrying which
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(IdempotencyKeyConflictException.class)
  public ProblemDetail handleIdempotencyConflict(
      IdempotencyKeyConflictException exception, HttpServletRequest request) {
    return problem(ProblemType.IDEMPOTENCY_KEY_CONFLICT, exception.getMessage(), request);
  }

  /**
   * Answers a write on a single resource that arrived without {@code If-Match} (REQ-API-004).
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 428} problem detail
   */
  @ExceptionHandler(PreconditionRequiredException.class)
  public ProblemDetail handlePreconditionRequired(
      PreconditionRequiredException exception, HttpServletRequest request) {
    return problem(ProblemType.PRECONDITION_REQUIRED, exception.getMessage(), request);
  }

  /**
   * Answers a write whose {@code If-Match} was not the resource's version (REQ-API-004).
   *
   * <p>Carries both versions: a client that knows what it had and what is there can say so, which
   * is the difference between "somebody changed this" and a dialogue nobody can act on.
   *
   * @param exception the refusal, carrying both versions
   * @param request the request, for the instance URI
   * @return a {@code 412} problem detail
   */
  @ExceptionHandler(StaleVersionException.class)
  public ProblemDetail handleStaleVersion(
      StaleVersionException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemType.PRECONDITION_FAILED, exception.getMessage(), request);
    problem.setProperty("expectedVersion", exception.getExpected());
    problem.setProperty("currentVersion", exception.getCurrent());
    return problem;
  }

  /**
   * Answers an {@code If-Match} this API could not have issued (REQ-API-004).
   *
   * @param exception the refusal, carrying what arrived
   * @param request the request, for the instance URI
   * @return a {@code 412} problem detail
   */
  @ExceptionHandler(PreconditionMalformedException.class)
  public ProblemDetail handleMalformedPrecondition(
      PreconditionMalformedException exception, HttpServletRequest request) {
    return problem(ProblemType.PRECONDITION_FAILED, exception.getMessage(), request);
  }

  /**
   * Answers a membership that would make a bundle contain itself (REQ-CORE-007).
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(BundleCycleException.class)
  public ProblemDetail handleBundleCycle(
      BundleCycleException exception, HttpServletRequest request) {
    return problem(ProblemType.BUNDLE_CYCLE, exception.getMessage(), request);
  }

  /**
   * Answers a request that cannot be served while the item is out (REQ-LIFE-005).
   *
   * <p>Raised by two places -- lending something already lent, and trashing something somebody
   * else has -- and answered identically, because the caller does the same thing about both.
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(ItemLentException.class)
  public ProblemDetail handleItemLent(ItemLentException exception, HttpServletRequest request) {
    return problem(ProblemType.ITEM_LENT, exception.getMessage(), request);
  }

  /**
   * Answers a request the item's state refuses (04 §4.4, REQ-LIFE-007).
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(ItemStateException.class)
  public ProblemDetail handleItemState(ItemStateException exception, HttpServletRequest request) {
    return problem(ProblemType.ITEM_STATE, exception.getMessage(), request);
  }

  /**
   * Answers a reminder rule naming a trigger nothing serves here (REQ-NOTI-003).
   *
   * <p>Refused when the rule is written rather than when it runs, so the person who can still pick
   * another trigger is the one who hears about it.
   *
   * @param exception the refusal, whose message names the triggers that do work
   * @param request the request, for the instance URI
   * @return a {@code 422} problem detail
   */
  @ExceptionHandler(UnservedTriggerException.class)
  public ProblemDetail handleUnservedTrigger(
      UnservedTriggerException exception, HttpServletRequest request) {
    return problem(ProblemType.UNSERVED_TRIGGER, exception.getMessage(), request);
  }

  /**
   * Answers an archive asked for before it was built (REQ-PORT-005).
   *
   * @param exception the refusal, whose message names the state instead
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(ExportNotReadyException.class)
  public ProblemDetail handleExportNotReady(
      ExportNotReadyException exception, HttpServletRequest request) {
    return problem(ProblemType.EXPORT_NOT_READY, exception.getMessage(), request);
  }

  /**
   * Answers a document asked for on an instance that cannot render one (REQ-LIFE-016).
   *
   * <p>A {@code 409} rather than a {@code 501}, because the report is there: the figures and the
   * table are served by the endpoints beside this one, and what is missing is a plugin an operator
   * installs. The detail says so, which is what tells a caller to use the other two rather than to
   * report a fault.
   *
   * @param exception the refusal
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(
      de.greluc.homeinv.inventory.api.InsuranceDocuments.NoRendererException.class)
  public ProblemDetail handleNoRenderer(
      de.greluc.homeinv.inventory.api.InsuranceDocuments.NoRendererException exception,
      HttpServletRequest request) {
    return problem(ProblemType.NO_DOCUMENT_RENDERER, exception.getMessage(), request);
  }

  /**
   * Answers a move that would break the tree (REQ-CORE-045, REQ-CORE-047).
   *
   * <p>The detail says which of the two it was — a place moving into itself, or a category that
   * does not take this kind of place — because the fix differs even though the token does not.
   *
   * @param exception the refusal, carrying which
   * @param request the request, for the instance URI
   * @return a {@code 409} problem detail
   */
  @ExceptionHandler(InvalidMoveException.class)
  public ProblemDetail handleInvalidMove(
      InvalidMoveException exception, HttpServletRequest request) {
    return problem(ProblemType.INVALID_MOVE, exception.getMessage(), request);
  }

  /**
   * Answers a location that cannot be deleted because something is inside it.
   *
   * @param exception the refusal, carrying how much is in there
   * @param request the request, for the instance URI
   * @return the problem
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
   * Answers an attribute set that does not match its type version (REQ-CORE-005).
   *
   * @param exception the violations the validator found
   * @param request the request
   * @return a {@code 422} problem detail carrying one entry per offending value
   */
  @ExceptionHandler(InvalidAttributesException.class)
  public ProblemDetail handleInvalidAttributes(
      InvalidAttributesException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            ProblemType.VALIDATION_FAILED,
            "One or more attributes do not match the type this was written against.",
            request);
    // The paths, because REQ-CORE-005's acceptance is "422 with the field path" —
    // a client told only that something is invalid has to guess which field to
    // mark. The message travels with each one and the VALUE never does: a
    // rejected attribute set may hold a licence key, and a problem document is
    // logged by proxies.
    problem.setProperty(
        "errors",
        exception.getViolations().stream()
            .map(violation -> Map.of("path", violation.path(), "message", violation.message()))
            .toList());
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
    // DEBUG and not WARN. The finding itself was logged at WARN by the worker that
    // made it, once; this is the caller being told about it, which happens on
    // every poll and is not a second security event.
    log.debug("Reporting a scanner finding to a caller: {}", exception.getSignature());
    return problem(
        ProblemType.MALWARE_DETECTED,
        "The malware scan found something in this file, so it was discarded and is not "
            + "attached to anything.",
        request);
  }

  /**
   * Answers a file that has no verdict yet.
   *
   * <p>A {@code 503} and not a {@code 422}: the file may be perfectly good, the system has not said
   * so yet, and the client should ask again rather than conclude it is bad (ADR-0024).
   *
   * <p>Since ADR-0054 this answers a {@code GET} and not the upload. The upload was accepted and
   * is stored; what has not happened is the scan, which runs in the worker. The message says so,
   * because "uploads are refused" — which it said until 2026-09-12 — describes behaviour this
   * system no longer has and would send a client away with a file it has in fact kept.
   *
   * @param exception the failure
   * @param request the request
   * @return a {@code 503} problem detail
   */
  @ExceptionHandler(ScannerUnavailableException.class)
  public ProblemDetail handleScannerDown(
      ScannerUnavailableException exception, HttpServletRequest request) {
    // INFO, because on this path it is ordinary: a client polling a file the
    // worker has not reached yet gets one of these per poll. The scanner actually
    // being unreachable is logged by the worker, which is the half that knows.
    log.info("A caller asked for a file that has no verdict yet: {}", exception.getMessage());
    return problem(
        ProblemType.SCAN_UNAVAILABLE,
        "This file has been accepted and is being checked for malware. It becomes available "
            + "once the check has finished.",
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
