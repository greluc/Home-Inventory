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
      return attributesJson;
    }

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
      JsonNode value = attributes.get(key);
      String text = value.isTextual() ? value.asString() : value.toString();
      if (!crypto.isSealed(text)) {
        continue;
      }
      attributes.put(key, crypto.open(entityId, key, text));
      changed = true;
    }
    return changed ? json.writeValueAsString(attributes) : attributesJson;
  }
}
