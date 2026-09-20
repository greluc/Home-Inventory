/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.infrastructure;

import de.greluc.homeinv.portability.api.TagIngest;
import de.greluc.homeinv.tagging.api.TagService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Puts the tags a CSV names onto an item, creating the ones that are new (REQ-PORT-001).
 *
 * <p>Matched by name, case-insensitively, because {@code tag_name_unique} is on {@code
 * lower(name)} — matching any other way would find nothing and then fail to create a tag that is
 * already there. Assignment goes through {@link TagService}, which is idempotent, so importing the
 * same file twice leaves one of each rather than two.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportedTags implements TagIngest {

  private final TagService tags;
  private final JdbcClient jdbc;

  @Override
  public int assign(UUID itemId, List<String> names, UUID actor) {
    int created = 0;
    for (String each : names) {
      String name = each == null ? "" : each.strip();
      if (name.isEmpty()) {
        continue;
      }
      UUID tagId = named(name);
      if (tagId == null) {
        tagId = tags.create(new TagService.CreateTagCommand(name, null, null, null), actor).id();
        created++;
        log.debug("An import created the tag '{}'", name);
      }
      tags.assign(tagId, TagService.TagTarget.ITEM, itemId, actor);
    }
    return created;
  }

  /**
   * The tag with this name, if the tenant has one.
   *
   * <p>A merged tag is followed to what it became: a file naming the losing side of a merge means
   * the thing the merge produced, and creating a second tag with that name is not possible anyway
   * (REQ-CORE-063).
   *
   * @param name the name
   * @return the tag's id, or null
   */
  private UUID named(String name) {
    return jdbc
        .sql(
            """
            select coalesce(merged_into, id) from tagging.tag
            where lower(name) = lower(?)
            limit 1
            """)
        .param(name)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }
}
