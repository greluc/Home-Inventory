/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.tenancy.api.TenantLifecycle;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Withdrawing an erasure request, by the token from the link (REQ-TEN-011).
 *
 * <p>Its own controller, and public, because of what a pending deletion does: it stops the tenant
 * answering. Somebody following the link cannot sign in — signing in is exactly what was stopped —
 * so a session cannot be the credential here. The token is: 256 bits from a secure source, stored
 * only as a hash, and good once.
 *
 * <p>The tenant context comes from the token through the {@code SECURITY DEFINER} lookup of
 * 07 §7.5, never from anything in the request. That is the same rule REQ-SEC-004 states for a
 * session's tenant, applied to the second flow that has no session.
 */
@Tag(name = "Tenant revocation", description = "Withdrawing a tenant's sessions and tokens at once.")
@RestController
@RequestMapping("/api/v1/tenant-revocations")
@RequiredArgsConstructor
public class TenantRevocationController {

  private final TenantLifecycle lifecycle;

  /**
   * Withdraws the erasure request the token belongs to.
   *
   * @param token the secret from the link
   * @return the tenant that is active again
   */
  @PostMapping(path = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PublicEndpoint(
      reason =
          "A pending deletion stops the tenant answering, so the owner cannot sign in to "
              + "undo it — requiring a session would make the block irreversible by the one "
              + "person entitled to reverse it. The token is the credential: 256 bits from a "
              + "secure source, stored only as a hash, and good once (REQ-TEN-011).")
  @CanFail(ProblemType.REVOCATION_UNUSABLE)
  public RevokedView revoke(@PathVariable @Size(max = 200) String token) {
    return new RevokedView(lifecycle.revokeDeletion(token), "ACTIVE");
  }

  /**
   * The tenant that is answering again.
   *
   * @param id the tenant
   * @param state its state, which is {@code ACTIVE} by the time this is returned
   */
  public record RevokedView(UUID id, String state) {}
}
