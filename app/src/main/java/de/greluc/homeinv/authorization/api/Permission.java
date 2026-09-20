/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * Every permission this stage defines, as {@code <block>:<resource>:<action>} (04 §4.3).
 *
 * <p>An enum rather than free strings, and that is the whole reason this type exists: a permission
 * named by a string is a permission that can be misspelt at the point of the check, and a misspelt
 * permission is one nobody holds — so the endpoint denies everyone, which looks like a
 * configuration problem and gets "fixed" by removing the check.
 *
 * <p>Stage 1 adds tenant-owned roles that <em>extend</em> the built-ins (04 §4.3). They will grant
 * combinations of these same permissions; the catalogue of permissions is core code and stays here,
 * because a tenant defining its own permission would be a tenant defining what the application
 * does.
 */
public enum Permission {

  /** Read an item. */
  ITEM_READ("inventory:item:read"),
  /** Create an item. */
  ITEM_CREATE("inventory:item:create"),
  /** Change an item. */
  ITEM_UPDATE("inventory:item:update"),
  /** Delete an item — the first stage, which puts it in the trash and can be undone. */
  ITEM_DELETE("inventory:item:delete"),
  /**
   * Remove an item for good, with its attachments (REQ-CORE-009).
   *
   * <p>Separate from {@link #ITEM_DELETE} because it is the one operation on an item that cannot be
   * undone. Trashing is a decision a person can change their mind about; this is not, and the two
   * being one permission would mean nobody could be given the reversible half alone.
   */
  ITEM_PURGE("inventory:item:purge"),

  /** Read a location. */
  LOCATION_READ("locations:location:read"),
  /** Create a location. */
  LOCATION_CREATE("locations:location:create"),
  /** Change a location. */
  LOCATION_UPDATE("locations:location:update"),
  /** Delete a location. */
  LOCATION_DELETE("locations:location:delete"),

  /** Search across the tenant's items. */
  SEARCH_QUERY("search:index:query"),
  /**
   * Create or change a saved search (REQ-SRCH-008).
   *
   * <p>Separate from {@link #SEARCH_QUERY}, which is reading: a saved search is the tenant's and
   * everybody sees it, so adding one is a change to what everybody sees. Reading them needs only
   * {@code SEARCH_QUERY} — a list of questions is not more sensitive than the answers.
   */
  SAVED_SEARCH_WRITE("search:saved-search:write"),
  /** Delete a saved search. Its own permission, because it takes a list away from everybody. */
  SAVED_SEARCH_DELETE("search:saved-search:delete"),

  /**
   * Create or change a reminder rule (REQ-NOTI-001).
   *
   * <p>A member's, exactly as {@link #SAVED_SEARCH_WRITE} is and for the same reason: a rule is the
   * tenant's and everybody in it is reminded by it, so writing one changes what everybody sees.
   * Reading them needs only {@link #SEARCH_QUERY} — a list of rules is not more sensitive than the
   * things it watches.
   */
  REMINDER_RULE_WRITE("notification:reminder-rule:write"),
  /**
   * Delete a reminder rule. An administrator's, because it takes a reminder away from everybody and
   * the person who notices is the one who was relying on it.
   */
  REMINDER_RULE_DELETE("notification:reminder-rule:delete"),

  /**
   * See which plugins the operator installed, and what this tenant has permitted them
   * (REQ-PLG-005).
   *
   * <p>Reading, and a member's: what a plugin may do here is something anybody working in the
   * tenant has an interest in knowing, and a list of installed software is not a secret from the
   * people whose data it can reach.
   */
  PLUGIN_READ("plugins:plugin:read"),
  /**
   * Grant or withdraw a capability, for this tenant (REQ-PLG-005, REQ-PLG-006).
   *
   * <p>An administrator's. 09 §9.4 is explicit that the decision is a <i>tenant administrator</i>
   * seeing the capabilities in plain language and agreeing to them, and it is the one act that lets
   * foreign code touch this tenant's data at all.
   */
  PLUGIN_CONSENT("plugins:capability:consent"),

  /** Read a media object's metadata and obtain a signed URL for it. */
  MEDIA_READ("media:object:read"),
  /** Upload a media object and attach it. */
  MEDIA_CREATE("media:object:create"),
  /** Detach or delete a media object. */
  MEDIA_DELETE("media:object:delete"),

  /**
   * Read the tenant's type system: types, categories, their fields and their schemas.
   *
   * <p>Held by every role, the share link included. A client cannot render an item's attributes
   * without the definitions behind them, so withholding this would not hide the values — it would
   * show them as unlabelled keys.
   */
  TYPE_READ("catalog:type:read"),
  /** Create an item type or a location category. */
  TYPE_CREATE("catalog:type:create"),
  /** Change a type, add or tighten a field, publish a version. */
  TYPE_UPDATE("catalog:type:update"),
  /** Archive a type, or finally remove a deprecated field and the values under it. */
  TYPE_DELETE("catalog:type:delete"),

  /** Read the tenant's value lists, which an enumeration field draws on. */
  VALUE_LIST_READ("catalog:value-list:read"),
  /** Create a value list. */
  VALUE_LIST_CREATE("catalog:value-list:create"),
  /** Add an entry to a value list, relabel one, or archive one. */
  VALUE_LIST_UPDATE("catalog:value-list:update"),

  /** Read the tenant's tags and the groups they sit in. */
  TAG_READ("tagging:tag:read"),
  /** Create a tag or a tag group. */
  TAG_CREATE("tagging:tag:create"),
  /** Rename a tag, recolour it, move it between groups, or merge two. */
  TAG_UPDATE("tagging:tag:update"),
  /**
   * Put a tag on an item or a place, or take one off.
   *
   * <p>Separate from {@link #TAG_UPDATE}: labelling things is what everybody working with the
   * inventory does, and editing the tag vocabulary is not. A contributor helping with a stocktake
   * holds this and not that.
   */
  TAG_ASSIGN("tagging:tag:assign"),

  /** Read the tenant's own record: its name and its settings. */
  TENANT_READ("tenancy:tenant:read"),
  /** Rename the tenant, or change its settings. */
  TENANT_UPDATE("tenancy:tenant:update"),

  /** See who else is in this tenant, and in what role. */
  MEMBER_READ("tenancy:member:read"),
  /** Invite somebody into the tenant (REQ-TEN-004). */
  MEMBER_INVITE("tenancy:member:invite"),
  /**
   * Change a member's role.
   *
   * <p>Bounded by REQ-TEN-010 on top of this permission: holding it does not let somebody grant a
   * role whose permissions they do not hold themselves, and {@code OWNER} is grantable only by an
   * {@code OWNER}. A permission is what may be attempted; that rule is what the attempt is measured
   * against.
   */
  MEMBER_UPDATE("tenancy:member:update"),
  /** Remove somebody from the tenant, and withdraw an invitation that has not been used. */
  MEMBER_REMOVE("tenancy:member:remove"),

  /**
   * Ask for the tenant to be erased (REQ-TEN-011).
   *
   * <p>The one permission {@code OWNER} holds and {@code ADMIN} does not, and the reason the two
   * roles are finally different sets rather than only different in what they may grant. Erasing a
   * tenant is the act nobody else can undo after the grace period, and an administrator who could
   * start it could start it on their last day.
   */
  TENANT_DELETE("tenancy:tenant:delete"),

  /**
   * Issue, list and revoke the tenant's machine tokens (REQ-AUTH-010).
   *
   * <p>{@code identity} and not {@code tenancy}: a service account is a way of authenticating, and
   * the block that owns the notion owns the permission. Held by {@code ADMIN} and {@code OWNER}
   * alone — a token carries a role, so whoever may hand one out may hand out that role.
   */
  SERVICE_ACCOUNT_ADMINISTER("identity:service-account:administer"),

  /**
   * Ask for an export of the tenant, and download the archive (REQ-PORT-003, REQ-PORT-005).
   *
   * <p>Its own permission rather than {@code TENANT_READ}, because taking a copy of everything is
   * not the same act as reading things one at a time. The endpoints asked for {@code TENANT_READ}
   * until 2026-09-20, which meant a {@code VIEWER} — including one confined to a single shelf —
   * could download an archive of the whole inventory (ADR-0068, open point O27).
   *
   * <p><b>Whole-tenant.</b> An export is of the tenant and cannot be of a subtree, so a membership
   * confined to one (REQ-TEN-007) does not hold this however senior its role is. See {@link
   * #wholeTenant()}.
   */
  TENANT_EXPORT("portability:export:request", true);

  private final String id;

  /** Whether a membership confined to part of the tree is excluded from this. */
  private final boolean wholeTenant;

  Permission(String id) {
    this(id, false);
  }

  /**
   * A permission that is about the tenant as a whole.
   *
   * @param id the stable identifier
   * @param wholeTenant whether a scoped membership is excluded from it
   */
  Permission(String id, boolean wholeTenant) {
    this.id = id;
    this.wholeTenant = wholeTenant;
  }

  /**
   * Whether this permission is about the whole tenant rather than about things in it.
   *
   * <p>A membership may be confined to part of the location tree (REQ-TEN-007), and such a
   * membership never holds one of these — not because of its role, but because the act has no
   * meaning inside a subtree. An export is the first: there is no archive of a shelf, so somebody
   * who may only see a shelf cannot ask for one and must not receive one of everything instead.
   *
   * <p>Deliberately a property of the <b>permission</b> rather than a check in one endpoint. The
   * next tenant-wide act — a bulk erase, an instance-wide report — is then one flag rather than a
   * rule somebody has to remember.
   *
   * @return {@code true} when a scoped membership is excluded from it
   */
  public boolean wholeTenant() {
    return wholeTenant;
  }

  /**
   * The stable identifier, as it appears in a role definition and in an audit entry.
   *
   * <p>It is the identifier and not {@link #name()} because the enum constant is a Java name and
   * this is data: a tenant-owned role in stage 1 stores it, an audit entry from last year contains
   * it, and neither should change because a constant was renamed.
   *
   * @return the {@code <block>:<resource>:<action>} identifier
   */
  public String id() {
    return id;
  }
}
