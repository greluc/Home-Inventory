/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.util.UUID;

/**
 * Who the caller is, on the wire.
 *
 * <p>One record for the whole access layer, and it is here rather than nested in a controller
 * because <b>two controllers return it</b>: signing in answers with it, and so does switching
 * tenant. Two identical records nested in two controllers is what it was until 2026-09-21, and the
 * problem with that is not the duplication — springdoc names a schema after the simple class name,
 * so two types called {@code SessionView} publish one shape under one name and silently lose the
 * other. They happened to be identical; the next pair need not be, and nothing would have said so
 * (REQ-API-002, {@code SchemaNameTest}).
 *
 * @param userId the person
 * @param tenantId the tenant this session acts for
 * @param email the address they logged in with
 * @param locale the language on their profile, which the interface starts in (REQ-NFR-033)
 * @param role the membership's role. Returned so the client can decide what to *offer*; it never
 *     decides what is allowed, which happens in the application layer on every request
 *     (REQ-SEC-022). A UI that shows a button nobody may press teaches people to ignore errors.
 */
public record SessionView(
    UUID userId, UUID tenantId, String email, String locale, String role) {}
