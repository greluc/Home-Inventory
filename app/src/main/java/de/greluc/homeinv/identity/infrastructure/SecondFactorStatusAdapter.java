/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.authorization.api.SecondFactorStatus;
import de.greluc.homeinv.identity.api.SecondFactor;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers {@code authorization}'s one question about a credential (REQ-AUTH-003).
 *
 * <p>The port is declared there and implemented here, which is the direction that keeps the two
 * blocks acyclic — this block already depends on that one for entitlements. What crosses is a
 * boolean: which roles need a factor is a decision over there, and what a credential is stays here.
 */
@Component
@RequiredArgsConstructor
public class SecondFactorStatusAdapter implements SecondFactorStatus {

  private final SecondFactor secondFactor;

  @Override
  public boolean isEnrolled(UUID userId) {
    return secondFactor.isRequiredFor(userId);
  }
}
