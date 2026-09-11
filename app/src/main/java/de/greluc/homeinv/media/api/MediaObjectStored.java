/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A media object has been stored, scanned and re-encoded; its derivatives are not made yet.
 *
 * <h2>Why this leaves the process at all</h2>
 *
 * <p>{@code thumb} and {@code preview} are generated in the {@code worker}
 * (04 §4.1), and {@code api} and {@code worker} are separate processes running the same image. The
 * broker is how the one that accepted the upload hands the work to the one that is named as doing
 * it, and it is in stage 0 for exactly this reason ([ADR-0051]).
 *
 * <h2>What it carries, and what it deliberately does not</h2>
 *
 * <p>Three identifiers and nothing else. Not the bytes — they are in the blob store, and a message
 * broker is not a file transfer. Not the caller — the derivation acts for the tenant, not for a
 * person, and a message that named one would invite a consumer to make a decision on their behalf.
 *
 * <p>The tenant is in the payload because a consumer has no session: it establishes the tenant
 * context from this field before it touches a row, and without it every query it makes returns
 * nothing.
 *
 * <h2>Delivery is at-least-once</h2>
 *
 * <p>The outbox in {@code outbox.event_publication} is the source of truth and redelivers after a
 * broker outage, so a consumer will see the same event twice. The consumer is idempotent: it checks
 * {@code derived_at} and returns (REQ-NFR-013).
 *
 * @param tenantId the tenant the object belongs to; the consumer's tenant context comes from here
 * @param mediaObjectId which object
 * @param sha256 the content address of the stored {@code full} variant, from which the others are
 *     derived
 */
@Externalized("homeinv.media::media-object-stored")
public record MediaObjectStored(UUID tenantId, UUID mediaObjectId, String sha256) {}
