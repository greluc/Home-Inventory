/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.tenancy.api.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating a tenant (REQ-TEN-002).
 *
 * <p>The one endpoint in the application gated on an <b>entitlement</b> rather than a permission,
 * and it has to be: the caller may hold no role anywhere yet, and a tenant that could grant "may
 * create a tenant" would be granting the right to create a second one (ADR-0057).
 *
 * <p>Reading, renaming and deleting a tenant are permissions inside it and arrive with the member
 * administration; this controller is deliberately the creation alone, so that the entitlement gate
 * covers exactly the act nothing else can authorise.
 */
@Tag(name = "Tenants", description = "Creating a tenant, reading it, and asking for it to be erased.")
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

  private final TenantService tenants;

  /**
   * Creates a tenant and makes the caller its owner.
   *
   * <p>The session is not switched to it. A creation that silently moved the caller out of the
   * tenant they were working in would lose whatever they had open; the client switches with
   * {@code POST /api/v1/me/tenant} when the person asks for it.
   *
   * @param request the tenant's name
   * @param user the authenticated caller, who becomes {@code OWNER}
   * @return the new tenant
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresEntitlement(Entitlement.CREATE_TENANT)
  @CanFail({ProblemType.QUOTA_EXCEEDED, ProblemType.VALIDATION_FAILED, ProblemType.FORBIDDEN})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TenantView> create(
      @Valid @RequestBody CreateTenantRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    UUID id = tenants.create(request.name(), user.userId());
    return ResponseEntity.created(URI.create("/api/v1/tenants/" + id))
        .body(new TenantView(id, request.name(), "OWNER"));
  }

  /**
   * The body of a tenant creation.
   *
   * @param name what to call it. The only thing a new tenant needs: everything else it has —
   *     the built-in item type, the location categories — is seeded for it
   */
  public record CreateTenantRequest(@NotBlank @Size(max = 200) String name) {}

  /**
   * A tenant as its member sees it.
   *
   * @param id the tenant
   * @param name its display name
   * @param role the role the caller holds in it
   */
  public record TenantView(UUID id, String name, String role) {}
}
