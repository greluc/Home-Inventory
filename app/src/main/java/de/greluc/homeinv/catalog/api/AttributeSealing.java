/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * Turns an attribute set into the one that goes into the database (ADR-0019, REQ-SEC-046).
 *
 * <h2>Two steps, and the validation sits between them</h2>
 *
 * <p>A write with sensitive fields passes through three stages, in this order and not another:
 *
 * <ol>
 *   <li>{@link #merged} — what the caller sent, with every sensitive field it may not read put back
 *       from what is stored, <b>in plaintext</b>;
 *   <li>the schema validation the block already does, which therefore sees a complete document;
 *   <li>{@link #sealed} — the same document with every sensitive field encrypted.
 * </ol>
 *
 * <p>Merging first is what makes a <em>required</em> sensitive field possible at all. A caller
 * without the permission for one never receives it, so it cannot send it back — and a validation
 * run before the merge would refuse every such write for a field the person is not allowed to know
 * exists. Sealing last is what keeps the validation meaningful: a schema cannot check a licence key
 * that has already become ciphertext.
 *
 * <h2>The write-back hazard</h2>
 *
 * <p>The merge is not a convenience. A caller without the permission reads a record without the
 * field — the redaction removes it — and then sends the record back to change something else. Left
 * alone, that write deletes a licence key the person was never shown and could not know was there.
 * Nobody would report it accurately, because from where they stood nothing happened.
 *
 * <p>Here rather than in {@code inventory} or {@code locations} for the reason the redaction gives:
 * which fields are sensitive is a fact about the <em>type system</em>, and both of those blocks
 * already ask this one what their attributes mean.
 */
public interface AttributeSealing {

  /**
   * What the caller sent, completed from what is stored.
   *
   * <p>Every sensitive field the caller may not read is taken from the stored record and opened, so
   * the result is a plaintext document ready to be validated. Fields the caller may read are taken
   * from the request: they saw the value and sent one back.
   *
   * @param typeVersionId the version the attributes were written against
   * @param entityId the item or location they belong to, which each stored seal is bound to
   * @param incomingJson what the caller sent
   * @param storedJson what is in the database now, or {@code null} when the record is new
   * @return the complete attribute set, in plaintext
   */
  String merged(UUID typeVersionId, UUID entityId, String incomingJson, String storedJson);

  /**
   * The attributes as they are to be stored.
   *
   * <p>Every field the type marks {@code sensitive} is encrypted under the tenant's data key, bound
   * to the tenant, the record and the field key. A value already in the sealed format is left as it
   * is rather than sealed twice — which is what a restored revision carries, since a snapshot is
   * stored whole.
   *
   * @param typeVersionId the version the attributes were written against
   * @param entityId the item or location they belong to, bound into each seal
   * @param plaintextJson the validated document from {@link #merged}
   * @return the JSON to store
   */
  String sealed(UUID typeVersionId, UUID entityId, String plaintextJson);
}
