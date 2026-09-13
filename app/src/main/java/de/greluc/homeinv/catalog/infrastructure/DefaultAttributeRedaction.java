/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.authorization.api.FieldVisibility;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.SecondFactorPolicy;
import de.greluc.homeinv.catalog.api.AttributeRedaction;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
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
 * Removes what the caller may not read, from the attributes of one item or place.
 *
 * <h2>Why it never fails open</h2>
 *
 * <p>With no caller — a background job, a reconciliation, a test that forgot to establish one —
 * nothing sensitive is shown. The alternative is a code path where an absent context means "show
 * everything", and absent contexts are exactly what happens when something is wired wrongly.
 *
 * <p>The stored text is parsed and re-serialised rather than edited as a string. A key removed by
 * string surgery is a key removed until somebody's value happens to contain the text being cut.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultAttributeRedaction implements AttributeRedaction {

  private final TypeRegistry types;
  private final FieldVisibility visibility;
  private final SecondFactorPolicy secondFactor;

  /** Opens a value the caller may read; it is stored sealed (ADR-0019). */
  private final de.greluc.homeinv.crypto.api.SensitiveValues crypto;

  private final ObjectMapper json;

  @Override
  public String forCaller(UUID typeVersionId, UUID entityId, String attributesJson) {
    if (attributesJson == null || attributesJson.isBlank() || typeVersionId == null) {
      return attributesJson;
    }

    List<FieldDefinitionView> fields = types.fields(typeVersionId);
    if (fields.stream().noneMatch(FieldDefinitionView::sensitive)) {
      // The common case: nothing about this type is sensitive, so there is
      // nothing to decide and nothing to parse.
      return attributesJson;
    }

    // REQ-AUTH-011: reading a sensitive field is one of the operations that asks
    // for the second factor again. A proof older than the window removes the
    // field rather than refusing the request — a list with one sensitive column
    // in it would otherwise become unreadable, and a 403 somebody meets while
    // scrolling is a 403 they learn to click past (12 §12.4).
    boolean recentlyProved =
        CallerContext.current()
            .map(caller -> secondFactor.provedRecently(caller.secondFactorAt()))
            .orElse(false);

    FieldVisibility.SensitiveAccess granted =
        visibility.sensitiveAccessFor(
            CallerContext.current()
                .map(caller -> new RoleRef(caller.role(), caller.roleDefinitionId()))
                .orElse(null));
    FieldVisibility.SensitiveAccess access = key -> recentlyProved && granted.allows(key);

    JsonNode parsed = json.readTree(attributesJson);
    if (!(parsed instanceof ObjectNode attributes)) {
      // The attribute set is an object by construction — a database CHECK says so
      // — so this is a row that arrived some other way. Nothing is redacted and
      // nothing is guessed at; the value is returned as it was found.
      log.warn("The attributes of type version {} are not an object; nothing was redacted.",
          typeVersionId);
      return attributesJson;
    }

    boolean changed = false;
    for (FieldDefinitionView field : fields) {
      String key = field.key();
      if (!field.sensitive() || !attributes.has(key)) {
        continue;
      }
      if (!access.allows(key)) {
        attributes.remove(key);
        changed = true;
        continue;
      }
      // The caller may read it, so it is opened: the value is stored sealed
      // (ADR-0019), and handing back ciphertext would be showing the field
      // without showing the value.
      JsonNode value = attributes.get(key);
      String text = value.isTextual() ? value.asString() : value.toString();
      if (!crypto.isSealed(text)) {
        // Written before the field was marked sensitive, or before this code
        // existed. Shown as it stands rather than refused: it is the value, and
        // the next write seals it.
        continue;
      }
      attributes.put(key, crypto.open(entityId, key, text));
      changed = true;
    }
    return changed ? json.writeValueAsString(attributes) : attributesJson;
  }
}
