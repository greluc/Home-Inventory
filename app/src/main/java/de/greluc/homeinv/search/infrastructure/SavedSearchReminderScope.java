/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.notification.api.ReminderScope;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.search.api.SavedSearches;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A reminder rule's saved search, answered by {@code search} (REQ-NOTI-001).
 *
 * <p>The implementation of {@link ReminderScope}, and it lives here rather than in {@code
 * notification} for the reason the port exists at all: {@code search} already depends on {@code
 * inventory}, and {@code inventory} depends on {@code notification}, so a call in the other
 * direction closes a three-hop cycle. Everything points into {@code notification}.
 *
 * <p>Thin, because there is nothing to decide: the work is {@code SavedSearches}', and this says
 * which part of it a reminder rule needs.
 */
@Component
@RequiredArgsConstructor
public class SavedSearchReminderScope implements ReminderScope {

  private final SavedSearches searches;

  @Override
  public Set<UUID> idsMatching(UUID savedSearchId, int limit) {
    return searches.matchingIds(savedSearchId, limit);
  }

  @Override
  public boolean exists(UUID savedSearchId) {
    try {
      searches.get(savedSearchId);
      return true;
    } catch (NotFoundException absent) {
      // Absent and "another tenant's" are the same answer here, which is what
      // REQ-SEC-025 asks for -- the caller turns it into the same refusal either
      // way, and telling them apart is the disclosure the rule forbids.
      return false;
    }
  }
}
