/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.tenancy.api.AccountRegistry;
import de.greluc.homeinv.tenancy.api.InvitationNotYoursException;
import de.greluc.homeinv.tenancy.api.InvitationService;
import de.greluc.homeinv.tenancy.api.InvitationUnusableException;
import de.greluc.homeinv.tenancy.domain.Invitation;
import de.greluc.homeinv.tenancy.domain.Membership;
import de.greluc.homeinv.tenancy.domain.Tenant;
import de.greluc.homeinv.tenancy.infrastructure.InvitationRepository;
import de.greluc.homeinv.tenancy.infrastructure.MembershipRepository;
import de.greluc.homeinv.tenancy.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of accepting an invitation.
 *
 * <p>A separate bean rather than a method on {@link DefaultInvitationService}, for the reason
 * {@code TenantBootstrap} exists: a {@code @Transactional} method invoked from inside its own class
 * does not go through the Spring proxy, so the annotation has no effect and the work runs with no
 * transaction at all — silently.
 *
 * <p>The tenant context must already be established when this is called, and the caller establishes
 * it from the token rather than from anything the requester said. {@code TenantAwareTransactionManager}
 * reads the context when the transaction begins, so setting it inside this method would set it
 * after the connection had already been configured for no tenant.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class InvitationRedemption {

  private final InvitationRepository invitations;
  private final MembershipRepository memberships;
  private final TenantRepository tenants;
  private final AccountRegistry accounts;
  private final Clock clock;

  /**
   * Redeems one invitation: creates the account if there is none, and writes the membership.
   *
   * @param invitationId the invitation the token named
   * @param displayName what to call a newly created account
   * @param locale the interface language for a newly created account
   * @param password the new account's password
   * @param signedInAs who is making the request, or null when nobody is signed in
   * @return what the acceptance produced
   * @throws InvitationUnusableException when the invitation is used, withdrawn or expired
   * @throws InvitationNotYoursException when the address has an account and the caller is not it
   */
  @Transactional
  InvitationService.AcceptedInvitation redeem(
      UUID invitationId, String displayName, String locale, String password, UUID signedInAs) {

    Instant now = Instant.now(clock);
    Invitation invitation =
        invitations
            .findById(invitationId)
            .filter(row -> row.isUsable(now))
            // Unknown, used, withdrawn and expired are one answer. Telling them
            // apart would say "somebody was invited here", which is the fact that
            // single use is meant to end rather than advertise.
            .orElseThrow(InvitationUnusableException::new);

    Optional<AccountRegistry.Account> existing = accounts.byEmail(invitation.getEmail());
    boolean created = existing.isEmpty();
    UUID userId;

    if (created) {
      userId =
          accounts.register(
              invitation.getEmail(),
              displayName == null || displayName.isBlank() ? invitation.getEmail() : displayName,
              locale == null || locale.isBlank() ? "en" : locale,
              password);
    } else {
      userId = existing.get().id();
      if (!userId.equals(signedInAs)) {
        // Holding the token proves the mailbox, which is enough to CREATE the
        // account it names — nobody else exists to be harmed. It is not enough to
        // put a membership on an account that already belongs to somebody: a
        // forwarded or intercepted link would otherwise put a stranger in a
        // tenant they never joined.
        throw new InvitationNotYoursException();
      }
    }

    // Already a member — the invitation was issued before they joined some other
    // way. The invitation is still spent, because it was used; the membership is
    // left as it is rather than being silently changed to the invited role.
    if (memberships.findLiveInTenant(userId).isEmpty()) {
      memberships.save(
          Membership.create(
              UUID.randomUUID(), invitation.getTenantId(), userId, invitation.getRole(), now));
    }
    invitation.accept(userId, now);

    log.info(
        "Invitation {} was accepted by {} as {} in tenant {}{}",
        invitation.getId(),
        userId,
        invitation.getRole(),
        invitation.getTenantId(),
        created ? " (account created)" : "");

    String tenantName =
        tenants.findById(invitation.getTenantId()).map(Tenant::getName).orElse(null);
    return new InvitationService.AcceptedInvitation(
        invitation.getTenantId(), tenantName, userId, invitation.getRole(), created);
  }
}
