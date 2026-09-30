/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * A tag as everything outside this block sees it.
 *
 * @param id the tag
 * @param name the name a person reads, unique per tenant among the live ones
 * @param groupId the group it belongs to, or {@code null}
 * @param colour {@code #rrggbb}, or {@code null} when the tenant chose none (REQ-CORE-062)
 * @param icon an icon name for clients, or {@code null}
 * @param mergedInto what this tag became, or {@code null} while it is itself. A tag with a value
 *     here is a tombstone: it is offered nowhere, carries nothing, and exists so a client holding
 *     its id is redirected rather than told the tag never existed (REQ-CORE-063)
 */
public record TagView(UUID id, String name, @Nullable UUID groupId, @Nullable String colour, @Nullable String icon, @Nullable UUID mergedInto) {}
