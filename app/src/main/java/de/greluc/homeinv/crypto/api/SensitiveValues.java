/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.crypto.api;

import java.util.UUID;

/**
 * Sealing and opening the fields a dump must not expose (ADR-0019, REQ-SEC-046…049).
 *
 * <h2>Envelope encryption, and why the AAD is the point</h2>
 *
 * <p>A data key per tenant, wrapped with a master key the deployment mounts as a file. What makes
 * the scheme more than "encrypted at rest" is the additional authenticated data: every ciphertext
 * is bound to its tenant, its entity and its field key, so a value cannot be moved into another
 * record, another field or another tenant. Without that, somebody with write access could copy
 * another person's licence key into a record of their own and have it displayed back.
 *
 * <p>The consequence is stated rather than worked around: a sealed value is <b>not searchable and
 * not sortable</b>. It is never mirrored into {@code item_attr_index} and never handed to the search
 * index, and the type editor refuses a field that asks to be both sensitive and searchable.
 */
public interface SensitiveValues {

  /**
   * Seals a value for storage.
   *
   * <p>A fresh nonce per call, from a CSPRNG and never a counter: several {@code api} instances
   * encrypt at once and share no state, so a counter would need coordination that does not exist.
   *
   * @param entityId the item or location the value belongs to, bound into the ciphertext
   * @param fieldKey the attribute key, bound into the ciphertext
   * @param plaintext the value as the caller wrote it
   * @return the sealed form, base64url without padding, safe to put in JSON
   */
  String seal(UUID entityId, String fieldKey, String plaintext);

  /**
   * Opens a sealed value for a caller who may see it.
   *
   * <p>Fails rather than returns anything doubtful. A tag that does not verify means the value was
   * altered, moved, or was never this tenant's — and the three are indistinguishable on purpose,
   * because the answer to all of them is the same.
   *
   * @param entityId the item or location the value belongs to
   * @param fieldKey the attribute key
   * @param sealed what {@link #seal} produced
   * @return the value
   * @throws IllegalStateException when the value cannot be opened by this tenant's keys
   */
  String open(UUID entityId, String fieldKey, String sealed);

  /**
   * Whether a stored value is in the sealed format.
   *
   * <p>Needed where a field has just been marked sensitive and older rows still hold plaintext: the
   * two have to be told apart without guessing, and a format version in the first byte is what tells
   * them apart.
   *
   * @param value a stored attribute value
   * @return {@code true} when it carries this format's header
   */
  boolean isSealed(String value);
}
