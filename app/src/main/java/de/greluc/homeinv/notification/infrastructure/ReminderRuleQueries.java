/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

import de.greluc.homeinv.notification.api.ReminderRules.ReminderRuleView;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reminder rules and what they have raised, in SQL (REQ-NOTI-001).
 *
 * <p>Two tables with different natures, which is why they are read differently: a rule is edited
 * and carries a version, a reminder record is written once and never touched. The second is the
 * more interesting of the two — {@link #recordRaised} is the whole idempotency mechanism, and it is
 * a unique index rather than a read-then-write, because the run may overlap itself.
 */
@Component
@RequiredArgsConstructor
public class ReminderRuleQueries {

  /** The columns a rule view needs, written out (REQ-SEC-031). */
  private static final String RULES =
      """
      select id, name, trigger_kind, saved_search_id, offset_days, channel_key,
             enabled, version, updated_at
      from notification.notification_rule
      where tenant_id = ?
      order by created_at, id
      """;

  private static final String ONE_RULE =
      """
      select id, name, trigger_kind, saved_search_id, offset_days, channel_key,
             enabled, version, updated_at
      from notification.notification_rule
      where tenant_id = ? and id = ?
      """;

  private static final String ENABLED_RULES =
      """
      select id, name, trigger_kind, saved_search_id, offset_days, channel_key,
             enabled, version, updated_at
      from notification.notification_rule
      where tenant_id = ? and enabled
      order by created_at, id
      """;

  private final JdbcClient jdbc;

  /**
   * Which tenants have a rule at all.
   *
   * <p>Through the {@code SECURITY DEFINER} function of {@code V59}: the reminder run has no tenant
   * context, because it is looking for the ones that need one (07 §7.5). It returns ids and nothing
   * else.
   *
   * @return the tenants with at least one enabled rule
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithRules() {
    return jdbc
        .sql("select tenant_id from notification.tenants_with_enabled_rules()")
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * Every rule of the tenant in context.
   *
   * @param tenantId whose
   * @return the rules, oldest first
   */
  @Transactional(readOnly = true)
  public List<ReminderRuleView> all(UUID tenantId) {
    return jdbc.sql(RULES).param(tenantId).query(ReminderRuleQueries::toView).list();
  }

  /**
   * The enabled rules of the tenant in context.
   *
   * @param tenantId whose
   * @return the rules that fire, oldest first
   */
  @Transactional(readOnly = true)
  public List<ReminderRuleView> enabled(UUID tenantId) {
    return jdbc.sql(ENABLED_RULES).param(tenantId).query(ReminderRuleQueries::toView).list();
  }

  /**
   * One rule.
   *
   * @param tenantId whose
   * @param id which one
   * @return it, or empty when this tenant has no such rule
   */
  @Transactional(readOnly = true)
  public Optional<ReminderRuleView> byId(UUID tenantId, UUID id) {
    return jdbc.sql(ONE_RULE).param(tenantId).param(id).query(ReminderRuleQueries::toView).optional();
  }

  /**
   * Writes a new rule.
   *
   * @param tenantId whose
   * @param name what a person calls it
   * @param trigger what it watches
   * @param savedSearchId which things, or {@code null}
   * @param offsetDays days relative to the trigger's date
   * @param channelKey which channel
   * @param enabled whether it fires
   * @param actor who created it
   * @return the new rule's id
   */
  @Transactional
  public UUID insert(
      UUID tenantId,
      String name,
      ReminderTrigger trigger,
      UUID savedSearchId,
      int offsetDays,
      String channelKey,
      boolean enabled,
      UUID actor) {
    return jdbc
        .sql(
            """
            insert into notification.notification_rule
                (tenant_id, name, trigger_kind, saved_search_id, offset_days, channel_key,
                 enabled, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """)
        .param(tenantId)
        .param(name.strip())
        .param(trigger.name())
        .param(savedSearchId)
        .param(offsetDays)
        .param(channelKey.strip())
        .param(enabled)
        .param(actor)
        .param(actor)
        .query(UUID.class)
        .single();
  }

  /**
   * Replaces a rule.
   *
   * @param tenantId whose
   * @param id which one
   * @param name what a person calls it
   * @param trigger what it watches
   * @param savedSearchId which things, or {@code null}
   * @param offsetDays days relative to the trigger's date
   * @param channelKey which channel
   * @param enabled whether it fires
   * @param actor who changed it
   * @return how many rows changed, which is one or none
   */
  @Transactional
  public int update(
      UUID tenantId,
      UUID id,
      String name,
      ReminderTrigger trigger,
      UUID savedSearchId,
      int offsetDays,
      String channelKey,
      boolean enabled,
      UUID actor) {
    return jdbc
        .sql(
            """
            update notification.notification_rule
               set name = ?, trigger_kind = ?, saved_search_id = ?, offset_days = ?,
                   channel_key = ?, enabled = ?,
                   version = version + 1, updated_at = now(), updated_by = ?
             where tenant_id = ? and id = ?
            """)
        .param(name.strip())
        .param(trigger.name())
        .param(savedSearchId)
        .param(offsetDays)
        .param(channelKey.strip())
        .param(enabled)
        .param(actor)
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * Removes a rule, and with it the record of what it had already raised.
   *
   * @param tenantId whose
   * @param id which one
   * @return how many rows went, which is one or none
   */
  @Transactional
  public int delete(UUID tenantId, UUID id) {
    return jdbc
        .sql("delete from notification.notification_rule where tenant_id = ? and id = ?")
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * Records that a reminder was raised, and says whether this call is the one that raised it.
   *
   * <p>The idempotency of the whole feature. An insert and a caught duplicate rather than a read
   * followed by a write: the run can overlap itself — a slow pass and the next one — and two
   * transactions both finding nothing recorded would both send. The unique index decides, which is
   * the one place that can.
   *
   * @param tenantId whose
   * @param ruleId which rule
   * @param subjectKind what kind of thing it is about
   * @param subjectId which thing
   * @param dueOn the date the reminder is about, so that moving the date raises a new one
   * @return {@code true} when this call recorded it and the caller should send; {@code false} when
   *     it was already recorded and the caller must not
   */
  @Transactional
  public boolean recordRaised(
      UUID tenantId, UUID ruleId, String subjectKind, UUID subjectId, LocalDate dueOn) {
    try {
      jdbc.sql(
              """
              insert into notification.reminder
                  (tenant_id, rule_id, subject_kind, subject_id, due_on)
              values (?, ?, ?, ?, ?)
              """)
          .param(tenantId)
          .param(ruleId)
          .param(subjectKind)
          .param(subjectId)
          .param(dueOn)
          .update();
      return true;
    } catch (DuplicateKeyException alreadySaid) {
      return false;
    }
  }

  /**
   * Who in this tenant asked to hear about this kind on this channel (REQ-NOTI-006).
   *
   * <p>A reminder has no single recipient the way an invitation does — a rule is the tenant's, not
   * one person's — so the recipients are whoever <b>subscribed</b>. That is REQ-NOTI-006 applied
   * unchanged: nothing is delivered to somebody who did not ask for it, and a rule nobody
   * subscribed to raises nothing rather than shouting at everyone.
   *
   * <p>Matched on the rule's channel as well as the kind, because the rule says which channel it
   * wants and a person who asked for e-mail has not asked for a webhook.
   *
   * @param tenantId whose
   * @param kind the notification kind the trigger produces
   * @param channelKey the channel the rule names
   * @return one row per subscriber, with the address they gave
   */
  @Transactional(readOnly = true)
  public List<Subscriber> subscribersOf(UUID tenantId, String kind, String channelKey) {
    return jdbc
        .sql(
            """
            select s.user_id, s.address, coalesce(u.locale, 'en') as locale
            from notification.subscription s
            left join identity.app_user u on u.id = s.user_id
            where s.tenant_id = ? and s.kind = ? and s.channel_key = ? and s.enabled
            order by s.user_id
            """)
        .param(tenantId)
        .param(kind)
        .param(channelKey)
        .query(
            (ResultSet rs, int row) ->
                new Subscriber(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("address"),
                    rs.getString("locale")))
        .list();
  }

  /**
   * One person who asked to be reminded.
   *
   * @param userId who
   * @param address where, in the channel's own scheme
   * @param language their interface language, so the message is not written in somebody else's
   */
  public record Subscriber(UUID userId, String address, String language) {}

  private static ReminderRuleView toView(ResultSet rs, int rowNum) throws SQLException {
    return new ReminderRuleView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        ReminderTrigger.valueOf(rs.getString("trigger_kind")),
        rs.getObject("saved_search_id", UUID.class),
        rs.getInt("offset_days"),
        rs.getString("channel_key"),
        rs.getBoolean("enabled"),
        rs.getLong("version"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
