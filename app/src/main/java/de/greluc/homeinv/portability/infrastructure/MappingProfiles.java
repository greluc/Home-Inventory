/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.infrastructure;

import de.greluc.homeinv.portability.api.MappingProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Every mapping profile a tenant may import with (REQ-PORT-002).
 *
 * <p>The two that ship are constants and the tenant's own are rows, and this is the one place that
 * knows the difference. Both the request that accepts an upload and the worker that reads it ask
 * here — the request so that a name nobody recognises is refused while somebody is still looking at
 * the screen, the worker because a profile may have been edited in between and the file is read
 * with what the profile says now.
 */
@Component
@RequiredArgsConstructor
public class MappingProfiles {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  /**
   * All of them, built-in first.
   *
   * @return the profiles
   */
  @Transactional(readOnly = true)
  public List<MappingProfile> all() {
    List<MappingProfile> profiles = new ArrayList<>(MappingProfile.BUILT_IN);
    profiles.addAll(tenantOwned());
    return List.copyOf(profiles);
  }

  /**
   * One by key.
   *
   * <p>A tenant's own wins over a built-in of the same key, which is how a tenant changes one of
   * the two that ship: copy it, keep the key, edit the columns.
   *
   * @param key the key
   * @return the profile, or empty when nothing has that key
   */
  @Transactional(readOnly = true)
  public Optional<MappingProfile> profileOf(String key) {
    if (key == null) {
      return Optional.empty();
    }
    return tenantOwned().stream()
        .filter(profile -> profile.key().equals(key))
        .findFirst()
        .or(() -> MappingProfile.builtIn(key));
  }

  /**
   * The profiles this tenant wrote for itself.
   *
   * @return them, by key
   */
  private List<MappingProfile> tenantOwned() {
    return jdbc
        .sql(
            """
            select key, name, source, column_map::text as column_map, default_currency,
                   item_type_key
            from portability.mapping_profile
            order by key
            """)
        .query(
            (rs, rowNum) ->
                new MappingProfile(
                    rs.getString("key"),
                    rs.getString("name"),
                    rs.getString("source"),
                    columnsOf(rs.getString("column_map")),
                    rs.getString("default_currency"),
                    rs.getString("item_type_key")))
        .list();
  }

  /**
   * A stored column map as a map.
   *
   * @param stored the JSON object
   * @return the pairs
   */
  private Map<String, String> columnsOf(String stored) {
    @SuppressWarnings("unchecked")
    Map<String, String> columns = json.readValue(stored, Map.class);
    return columns;
  }
}
