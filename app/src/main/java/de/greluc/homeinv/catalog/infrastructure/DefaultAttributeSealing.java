/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.authorization.api.FieldVisibility;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.SecondFactorPolicy;
import de.greluc.homeinv.catalog.api.AttributeSealing;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.crypto.api.SensitiveValues;
import de.greluc.homeinv.platform.CallerContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Completes an attribute set from what is stored, then seals what the type marks sensitive.
 *
 * <p>The order is the contract: {@link #merged} before the validation, {@link #sealed} after it.
 * {@link AttributeSealing} says why each half sits where it does.
 *
 * <p>Both halves ask the same question first — <em>may this caller read this field?</em> — and both
 * answer it the same way the redaction does, through {@code authorization} and the freshness of the
 * caller's second factor. With no caller at all, the answer is no: a background run, a
 * reconciliation or a test that forgot to establish a context must not be handed a value that a
 * person would have to prove themselves for.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultAttributeSealing implements AttributeSealing {

  private final TypeRegistry types;
  private final FieldVisibility visibility;
  private final SecondFactorPolicy secondFactor;
  private final SensitiveValues crypto;
  private final ObjectMapper json;

  @Override
  public String merged(
      UUID typeVersionId, UUID entityId, String incomingJson, String storedJson) {

    List<FieldDefinitionView> sensitive = sensitiveFieldsOf(typeVersionId);
    if (sensitive.isEmpty()) {
      return incomingJson;
    }
    ObjectNode incoming = objectOf(incomingJson);
    ObjectNode stored = objectOf(storedJson);
    if (incoming == null) {
      return incomingJson;
    }

    FieldVisibility.SensitiveAccess access = accessNow();
    for (FieldDefinitionView field : sensitive) {
      String key = field.key();
      if (access.allows(key)) {
        // The caller saw it and sent one back; theirs wins, whatever it is.
        continue;
      }
      // Whatever the request says here is ignored: the caller was shown nothing
      // and therefore has nothing to say about it.
      incoming.remove(key);
      if (stored == null || !stored.has(key)) {
        continue;
      }
      JsonNode value = stored.get(key);
      String text = value.isTextual() ? value.asString() : value.toString();
      // Opened, because what comes out of here is validated next and a schema
      // cannot check ciphertext. It is sealed again on the way back in.
      incoming.put(key, crypto.isSealed(text) ? crypto.open(entityId, key, text) : text);
    }
    return json.writeValueAsString(incoming);
  }

  @Override
  public String sealed(UUID typeVersionId, UUID entityId, String plaintextJson) {
    List<FieldDefinitionView> sensitive = sensitiveFieldsOf(typeVersionId);
    if (sensitive.isEmpty()) {
      return plaintextJson;
    }
    ObjectNode attributes = objectOf(plaintextJson);
    if (attributes == null) {
      return plaintextJson;
    }

    boolean changed = false;
    for (FieldDefinitionView field : sensitive) {
      String key = field.key();
      JsonNode value = attributes.get(key);
      if (value == null || value.isNull()) {
        continue;
      }
      String text = value.isTextual() ? value.asString() : value.toString();
      if (crypto.isSealed(text)) {
        // A restored revision carries the sealed value, because a snapshot is
        // stored whole. Sealing it again would make it unreadable in one step and
        // unrecoverable in two.
        continue;
      }
      attributes.put(key, crypto.seal(entityId, key, text));
      changed = true;
    }
    return changed ? json.writeValueAsString(attributes) : plaintextJson;
  }

  // -------------------------------------------------------------------------

  /**
   * The sensitive fields of a type version, or nothing when it has none.
   *
   * @param typeVersionId the version, or {@code null}
   * @return the sensitive fields
   */
  private List<FieldDefinitionView> sensitiveFieldsOf(UUID typeVersionId) {
    if (typeVersionId == null) {
      return List.of();
    }
    return types.fields(typeVersionId).stream().filter(FieldDefinitionView::sensitive).toList();
  }

  /**
   * Which sensitive fields this caller may read right now.
   *
   * <p>The permission and the freshness of the second factor, exactly as the redaction reads them —
   * REQ-AUTH-011 makes reading one of these an operation that asks for the factor again, and a
   * write path that took a laxer view would be a way round the read path.
   *
   * @return the test to apply per field key
   */
  private FieldVisibility.SensitiveAccess accessNow() {
    boolean recentlyProved =
        CallerContext.current()
            .map(caller -> secondFactor.provedRecently(caller.secondFactorAt()))
            .orElse(false);
    FieldVisibility.SensitiveAccess granted =
        visibility.sensitiveAccessFor(
            CallerContext.current()
                .map(caller -> new RoleRef(caller.role(), caller.roleDefinitionId()))
                .orElse(null));
    return key -> recentlyProved && granted.allows(key);
  }

  /**
   * A JSON text as an object, or {@code null} when it is neither.
   *
   * @param text the stored or incoming attributes
   * @return the object, or {@code null}
   */
  private ObjectNode objectOf(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    JsonNode parsed = json.readTree(text);
    if (parsed instanceof ObjectNode object) {
      return object;
    }
    log.warn("An attribute set is not an object; nothing was sealed.");
    return null;
  }
}
