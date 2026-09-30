/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What an id in the archive means on this instance (REQ-PORT-003, REQ-PORT-004).
 *
 * <h2>Why merging by id is not enough on its own</h2>
 *
 * <p>Rows merge by id: one that is not here is inserted, one that is here is overwritten. That
 * works for everything a person made — their items, their places, their tags — because those ids
 * exist once in the world.
 *
 * <p>It does not work for the rows <b>provisioning</b> creates. Every tenant is given the same
 * built-in item types, location categories and value lists, and each copy has its own id. An
 * archive from one instance therefore carries a type {@code general} with one id while the
 * receiving tenant already has a type {@code general} with another — and {@code UNIQUE (tenant_id,
 * key)} means only one of them may exist. Inserting the archive's copy fails; ignoring it leaves
 * every imported item pointing at a type that is not there.
 *
 * <p>So the catalogue is matched by its <b>natural key</b> instead — the key of a type, the version
 * number under it, the key of a field under that — and this records what the archive's id turned
 * into. Every later block asks before it writes a reference.
 *
 * <h2>Not shared between imports</h2>
 *
 * <p>One of these exists per import and is thrown away with it. It is not a persisted mapping
 * table: the second import of the same archive re-derives the same answers from the same natural
 * keys, and a stored map would be a second source of truth that could disagree with the rows.
 */
public final class Remapping {

  private final Map<UUID, UUID> byArchiveId = new HashMap<>();

  /**
   * Records that a row arrived under a different id than the archive gave it.
   *
   * @param archiveId what the archive called it
   * @param localId what it is here
   */
  public void remap(UUID archiveId, UUID localId) {
    if (archiveId != null && localId != null && !archiveId.equals(localId)) {
      byArchiveId.put(archiveId, localId);
    }
  }

  /**
   * What an archive id means here.
   *
   * @param archiveId an id as the archive wrote it, possibly null
   * @return the local id, or the same id when nothing was remapped — which is the common case and
   *     the reason this is safe to call on every reference rather than only on the ones somebody
   *     remembered
   */
  public UUID resolve(UUID archiveId) {
    return byArchiveId.getOrDefault(archiveId, archiveId);
  }

  /**
   * How many ids turned into something else.
   *
   * @return the count, for the import report
   */
  public int size() {
    return byArchiveId.size();
  }
}
