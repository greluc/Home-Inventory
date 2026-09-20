/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item came back from a loan (REQ-LIFE-005).
 *
 * <p>Published only when a loan actually closed. Recording a return twice raises this once, because
 * the second call changes nothing — and a consumer cancelling an overdue reminder on it would
 * otherwise be told about a thing that came back twice.
 *
 * @param tenantId whose item
 * @param itemId which item
 * @param loanId the loan that was closed
 * @param returnedOn when it came back
 */
@Externalized("homeinv.inventory::item-returned.v1")
public record ItemReturned(UUID tenantId, UUID itemId, UUID loanId, LocalDate returnedOn) {}
