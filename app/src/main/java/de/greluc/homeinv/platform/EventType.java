/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The name of every domain event, as the world outside this deployment knows it (REQ-API-010).
 *
 * <h2>Why these are an enum and not strings on the events</h2>
 *
 * <p>An event type leaves the deployment: a tenant administrator names it in a webhook
 * subscription, it is stored in {@code notification.webhook_target.event_types}, and a receiver
 * branches on it in a system nobody here controls. A typo in a string literal would be discovered
 * by somebody else's integration going quiet. Here it does not compile.
 *
 * <p>The published list is <a href="../../../../../../docs/reference/event-types.yaml">{@code
 * docs/reference/event-types.yaml}</a>, and {@code EventTypeRegistryTest} fails when the two sets
 * differ in either direction — the treatment {@code problem-types.yaml} and {@code
 * plugin-health-states.yaml} get.
 *
 * <h2>The noun is load-bearing</h2>
 *
 * <p>{@link #noun()} — everything before the dot — is what a live stream sends an open view
 * (REQ-API-011). {@code item.moved} and {@code item.type-changed} both arrive there as {@code
 * item}, because a view showing items refreshes for either and there is nothing it would do
 * differently. A webhook receives the whole name, because an integration mirroring into another
 * system does care which happened.
 *
 * <h2>Never renamed</h2>
 *
 * <p>Adding one is ordinary. Changing one is a breaking change to a stranger's configuration, and
 * the constant is not where it would be noticed — so it is said here and in the registry both.
 */
public enum EventType {

  /** Somebody added a thing to the inventory. */
  ITEM_CREATED("item.created"),
  /** A name, a description or an attribute changed. */
  ITEM_UPDATED("item.updated"),
  /** The item is somewhere else. Its own type, because "what is in the shed" is its own question. */
  ITEM_MOVED("item.moved"),
  /** The item was re-typed, so its attribute set is a different one. */
  ITEM_TYPE_CHANGED("item.type-changed"),
  /** Stage one of two: out of sight and recoverable, nothing gone yet (REQ-CORE-020). */
  ITEM_DELETED("item.deleted"),
  /** A deletion was undone before the bin was emptied. */
  ITEM_RESTORED("item.restored"),
  /** Stage two. A receiver mirroring the inventory deletes its own copy here, not at the first. */
  ITEM_PURGED("item.purged"),
  /** Somebody borrowed it (REQ-LIFE-005). */
  ITEM_LENT("item.lent"),
  /** It is back (REQ-LIFE-005). */
  ITEM_RETURNED("item.returned"),
  /** Sold, given away, scrapped or lost (REQ-LIFE-007). The row stays; the thing is not here. */
  ITEM_DISPOSED("item.disposed"),

  /**
   * A whole subtree changed place.
   *
   * <p>No {@code item.moved} is raised for the items under it — a receiver that mirrors paths
   * re-reads the subtree.
   */
  LOCATION_MOVED("location.moved"),

  /** A new tag exists. Nothing wears it yet. */
  TAG_CREATED("tag.created"),
  /** The tag went onto something, and the subject is that something rather than the tag. */
  TAG_ASSIGNED("tag.assigned"),
  /** The tag came off something. */
  TAG_UNASSIGNED("tag.unassigned"),

  /** The tenant's type system gained an item type or a location category. */
  TYPE_CREATED("type.created"),
  /** A field became searchable or stopped being so, which changes what the index holds. */
  TYPE_FIELD_SEARCHABILITY_CHANGED("type.field-searchability-changed"),

  /** A photo or document finished scanning and re-encoding; its derivatives are not made yet. */
  MEDIA_OBJECT_STORED("media.object-stored");

  private static final Map<String, EventType> BY_ID =
      Stream.of(values()).collect(Collectors.toMap(EventType::id, Function.identity()));

  private final String id;

  EventType(String id) {
    this.id = id;
  }

  /**
   * The name as it is stored, subscribed to and delivered.
   *
   * @return {@code item.created} and the like, never null
   */
  public String id() {
    return id;
  }

  /**
   * The part before the dot, which is the kind a live stream sends (REQ-API-011).
   *
   * @return one word, lower case
   */
  public String noun() {
    return id.substring(0, id.indexOf('.'));
  }

  /**
   * The type with this name.
   *
   * <p>Empty rather than an exception: the caller is a subscription being validated, and an unknown
   * name there is a bad request rather than a broken deployment.
   *
   * @param id the stored or submitted name
   * @return the type, or empty when nothing in this deployment raises it
   */
  public static Optional<EventType> of(String id) {
    return Optional.ofNullable(BY_ID.get(id));
  }
}
