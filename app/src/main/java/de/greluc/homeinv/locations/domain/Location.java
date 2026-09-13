/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.platform.LtreeType;
import org.hibernate.annotations.Type;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A place where things are: a building, a room, a shelf, a box.
 *
 * <p>Locations form a tree of arbitrary depth, capped at twelve levels
 * ({@code REQ-CORE-040}). Two columns describe the same structure on purpose:
 * {@code parentId} is the truth that foreign keys and cascades follow, and {@code path} is the
 * accelerator that turns "everything below the cellar" into one index lookup instead of a recursive
 * query ({@code REQ-CORE-049}).
 *
 * <h2>Why the path is built from ids and not from names</h2>
 *
 * <p>An {@code ltree} label admits only {@code [A-Za-z0-9_]}. Real location names do not — "Küche
 * (oben)" has an umlaut, a space and brackets — so a path built from names would need a
 * transliteration, and two rooms whose names transliterated alike would collide in a structure that
 * must be unambiguous.
 *
 * <p>The stronger reason is that names change. A path made of names would have to be rewritten
 * across an entire subtree every time somebody corrected a typo, and a rename is the most ordinary
 * edit there is. The label is therefore the location's own id with the dashes removed: stable for
 * the life of the row, unique by construction, and never in need of escaping.
 *
 * <p>The cost is that a path is not human-readable. That is acceptable because it was never meant
 * to be read — {@code REQ-CORE-044} asks for a location's path to be *retrievable without
 * recursion*, and the readable form is assembled from the names of the rows the path names.
 */
@Entity
@Table(schema = "locations", name = "location")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Location {

  /** The deepest tree the schema admits; the database carries the same limit as a constraint. */
  public static final int MAX_DEPTH = 12;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /**
   * Which kind of place this is — a category VERSION, not the category.
   *
   * <p>The same rule an item follows for its type (REQ-CORE-025): a location is written against the
   * fields the category declared at that moment, and a later edit to the category leaves it alone.
   * Callers name a category; {@code catalog} resolves it to the published version, and only this
   * block stores the answer.
   */
  @Column(name = "category_version_id", nullable = false)
  private UUID categoryVersionId;

  /** The parent, or null for a root. */
  @Column(name = "parent_id")
  private UUID parentId;

  /** The name a person reads. Never blank, and not part of {@link #path}. */
  @Column(name = "name", nullable = false)
  private String name;

  /**
   * The materialised path, dot-separated ids, ending with this location's own label.
   *
   * <p>A {@code String} in Java and an {@code ltree} in the database, joined by {@link LtreeType}.
   * The two obvious shortcuts — mapping it as {@code varchar}, or as {@code OTHER} — both fail, and
   * that class records how.
   *
   * <p>Nothing in Java reads the path as a structure. Every query that treats it as one is
   * hand-written SQL, where the {@code ltree} operators live.
   */
  @Type(LtreeType.class)
  @Column(name = "path", nullable = false, columnDefinition = "ltree")
  private String path;

  /** Distance from the root; zero for a root. Kept in step with {@link #path} by a constraint. */
  @Column(name = "depth", nullable = false)
  private int depth;

  /** Whether the place travels with its contents. Always false at stage 0. */
  @Column(name = "is_mobile", nullable = false)
  private boolean mobile;

  /** When a container was closed, or null. Stage 2 honours it; stage 0 only carries it. */
  @Column(name = "sealed_at")
  private Instant sealedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  private Location(
      UUID id,
      UUID tenantId,
      UUID categoryVersionId,
      UUID parentId,
      String name,
      String path,
      int depth,
      UUID actor,
      Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.categoryVersionId = categoryVersionId;
    this.parentId = parentId;
    this.name = name;
    this.path = path;
    this.depth = depth;
    this.createdAt = now;
    this.updatedAt = now;
    this.createdBy = actor;
    this.updatedBy = actor;
  }

  /**
   * Creates a root location.
   *
   * @param id the identifier
   * @param tenantId the owning tenant
   * @param categoryVersionId the published version of the category this place is
   * @param name the name; must not be blank
   * @param actor the user creating it
   * @param now the creation instant
   * @return the new root, not yet persisted
   */
  public static Location createRoot(
      UUID id, UUID tenantId, UUID categoryVersionId, String name, UUID actor, Instant now) {
    requireName(name);
    return new Location(id, tenantId, categoryVersionId, null, name, labelOf(id), 0, actor, now);
  }

  /**
   * Creates a child of an existing location.
   *
   * @param id the identifier
   * @param tenantId the owning tenant
   * @param categoryVersionId the published version of the category this place is
   * @param parent the parent, which supplies the path this one extends
   * @param name the name; must not be blank
   * @param actor the user creating it
   * @param now the creation instant
   * @return the new child, not yet persisted
   * @throws TooDeepException when the parent already sits at the depth limit. Checked here rather
   *     than left to the database constraint, so the caller gets a named failure instead of a
   *     constraint violation surfacing as a {@code 500}
   */
  public static Location createChild(
      UUID id,
      UUID tenantId,
      UUID categoryVersionId,
      Location parent,
      String name,
      UUID actor,
      Instant now) {
    requireName(name);
    int depth = parent.depth + 1;
    if (depth > MAX_DEPTH) {
      throw new TooDeepException(parent.id, MAX_DEPTH);
    }
    return new Location(
        id,
        tenantId,
        categoryVersionId,
        parent.id,
        name,
        parent.path + "." + labelOf(id),
        depth,
        actor,
        now);
  }

  /**
   * Renames the location.
   *
   * <p>Touches no path, by design: the path is made of ids precisely so that a rename stays a
   * single-row update instead of a subtree rewrite.
   *
   * @param name the new name; must not be blank
   * @param actor the user making the change
   * @param now the instant of the change
   */
  public void rename(String name, UUID actor, Instant now) {
    requireName(name);
    this.name = name;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Marks the location deleted, leaving a tombstone.
   *
   * @param actor the user deleting it
   * @param now the instant of the deletion
   */
  public void markDeleted(UUID actor, Instant now) {
    if (this.deletedAt != null) {
      return;
    }
    this.deletedAt = now;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * The {@code ltree} label for an id.
   *
   * @param id the location id
   * @return the id's hex digits without dashes, which is a valid label by construction
   */
  private static String labelOf(UUID id) {
    return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A location needs a name");
    }
  }
}
