/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item went out on loan (REQ-LIFE-005).
 *
 * <p>Carries the due date because that is the scalar a consumer branches on — REQ-LIFE-006's
 * overdue reminder needs to know when, and re-reading the loan to learn it would be a query per
 * event. It carries no borrower: who has a thing is personal data, and a consumer that needs it can
 * ask the block that owns it (REQ-NFR-022, and the same reasoning 12 §12.5 applies to fields).
 *
 * @param tenantId whose item
 * @param itemId which item
 * @param loanId the loan that was opened
 * @param dueOn when it is due back, or {@code null} when nothing was agreed
 */
@Externalized("homeinv.inventory::item-lent.v1")
public record ItemLent(UUID tenantId, UUID itemId, UUID loanId, LocalDate dueOn) {}
