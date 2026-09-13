/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.QuotaExceededException;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims and releases the four tenant quotas (REQ-TEN-009).
 *
 * <h2>Three stocks in PostgreSQL, one rate in Valkey</h2>
 *
 * <p>Items, bytes and plugins are counts of things that exist, so they are carried forward in
 * {@code tenancy.quota_usage} — 07 §7.8: "carried forward, not counted on every query". Counting a
 * million items to answer "may I create one" would make the guard cost more than the operation.
 *
 * <p>API calls are a monthly allowance, and a write to PostgreSQL on every request would be a write
 * to PostgreSQL on every request. The counter lives in Valkey under a key that carries the month,
 * so it expires by itself and the next month starts at zero without anything having to reset it.
 *
 * <h2>What happens when Valkey is not there</h2>
 *
 * <p>The call is allowed and the fact is logged. REQ-NFR-012 says a Valkey failure degrades the
 * system rather than stopping it, and refusing every request because a counter is unreachable would
 * turn a cache outage into an outage. The alternative — counting in PostgreSQL as a fallback — would
 * put the write back on the request path exactly when the system is already unwell.
 */
@Service
@Slf4j
public class DefaultQuotaGuard implements QuotaGuard {

  /**
   * Increments the usage and returns the new total, creating the row on first use.
   *
   * <p>One statement, so two concurrent creations cannot both read the old value and both decide
   * there is room. The {@code ON CONFLICT} target is the unique on {@code (tenant_id, quota)}.
   */
  private static final String CLAIM =
      "insert into tenancy.quota_usage (tenant_id, quota, used, created_by, updated_by)"
          + " values (?, ?, ?, ?, ?)"
          + " on conflict (tenant_id, quota) do update"
          + " set used = tenancy.quota_usage.used + excluded.used, updated_at = now()"
          + " returning used";

  /** Gives usage back, clamped at zero so a counter cannot go negative and hide its drift. */
  private static final String RELEASE =
      "update tenancy.quota_usage set used = greatest(used - ?, 0), updated_at = now()"
          + " where tenant_id = ? and quota = ?";

  /** What this tenant is allowed of one quota, where it has a limit of its own. */
  private static final String LIMIT =
      "select permitted from tenancy.tenant_quota"
          + " where tenant_id = ? and quota = ? and deleted_at is null";

  /** What this tenant has used, for the three stocks. */
  private static final String USAGE =
      "select quota, used from tenancy.quota_usage where tenant_id = ?";

  /** The month a counter belongs to, which is also what makes the key expire on its own. */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

  /**
   * How long an API-call counter is kept.
   *
   * <p>Longer than a month, so the last days of one are not lost to an early expiry, and short
   * enough that Valkey does not accumulate a key per tenant per month forever.
   */
  private static final Duration COUNTER_TTL = Duration.ofDays(40);

  private final JdbcClient jdbc;
  private final StringRedisTemplate redis;
  private final Clock clock;
  private final Map<Quota, Long> defaults;

  /**
   * @param jdbc the database client
   * @param redis where the monthly API-call counter lives
   * @param clock the clock the month is read from
   * @param items the instance-wide default for {@code ITEM_COUNT}
   * @param storageBytes the instance-wide default for {@code STORAGE_BYTES}
   * @param plugins the instance-wide default for {@code PLUGIN_COUNT}
   * @param apiCalls the instance-wide default for {@code API_CALLS}, per calendar month
   */
  public DefaultQuotaGuard(
      JdbcClient jdbc,
      StringRedisTemplate redis,
      Clock clock,
      @Value("${HOMEINV_QUOTA_ITEMS:100000}") long items,
      @Value("${HOMEINV_QUOTA_STORAGE_BYTES:53687091200}") long storageBytes,
      @Value("${HOMEINV_QUOTA_PLUGINS:10}") long plugins,
      @Value("${HOMEINV_QUOTA_API_CALLS:100000}") long apiCalls) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.clock = clock;
    this.defaults =
        Map.of(
            Quota.ITEM_COUNT, items,
            Quota.STORAGE_BYTES, storageBytes,
            Quota.PLUGIN_COUNT, plugins,
            Quota.API_CALLS, apiCalls);
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code SUPPORTS} rather than {@code MANDATORY}, and the difference matters in both
   * directions. It <b>joins</b> the caller's transaction where there is one, which is what makes a
   * failed creation return its claim with the rollback; and it does not <b>demand</b> one, because
   * the API-call counter is claimed from an interceptor that runs before any transaction exists and
   * never touches the database.
   */
  @Override
  @Transactional(propagation = Propagation.SUPPORTS)
  public void require(Quota quota, long amount) {
    if (amount <= 0) {
      return;
    }
    UUID tenantId = TenantContext.require();
    long permitted = permittedFor(tenantId, quota);

    if (quota == Quota.API_CALLS) {
      requireCalls(tenantId, amount, permitted);
      return;
    }

    long used =
        jdbc.sql(CLAIM)
            .params(tenantId, quota.name(), amount, actor(), actor())
            .query(Long.class)
            .single();

    if (used > permitted) {
      // Thrown after the increment, so the transaction's rollback is what returns
      // the claim. Checking first and writing second would be a race: two
      // requests both find room, both take it, and the tenant is over.
      log.info("Refused a {} claim of {} for tenant {}: {} of {}.",
          quota, amount, tenantId, used, permitted);
      throw new QuotaExceededException(quota, used, permitted);
    }
  }

  @Override
  @Transactional(propagation = Propagation.SUPPORTS)
  public void release(Quota quota, long amount) {
    if (amount <= 0 || quota == Quota.API_CALLS) {
      // A month's calls are not given back. They were made.
      return;
    }
    jdbc.sql(RELEASE).params(amount, TenantContext.require(), quota.name()).update();
  }

  @Override
  @Transactional(readOnly = true)
  public List<QuotaView> usage() {
    UUID tenantId = TenantContext.require();
    Map<String, Long> used = counters(tenantId);

    List<QuotaView> views = new ArrayList<>();
    for (Quota quota : Quota.values()) {
      long current =
          quota == Quota.API_CALLS
              ? callsThisMonth(tenantId)
              : used.getOrDefault(quota.name(), 0L);
      views.add(new QuotaView(quota, current, permittedFor(tenantId, quota)));
    }
    return views;
  }

  /**
   * Counts one or more API calls against this month's allowance.
   *
   * @param tenantId whose allowance
   * @param amount how many calls
   * @param permitted how many are allowed this month
   * @throws QuotaExceededException when the month is spent
   */
  private void requireCalls(UUID tenantId, long amount, long permitted) {
    String key = callKey(tenantId);
    Long used;
    try {
      used = redis.opsForValue().increment(key, amount);
      if (used != null && used <= amount) {
        // First write of the month: give the key an expiry, so nothing has to
        // sweep last month's counters.
        redis.expire(key, COUNTER_TTL);
      }
    } catch (RedisConnectionFailureException unreachable) {
      // Degrade rather than refuse (REQ-NFR-012). A cache outage that stopped
      // every request would be an outage; an over-generous month is recoverable.
      log.warn("The API-call quota is not being counted: Valkey is unreachable.", unreachable);
      return;
    }

    if (used != null && used > permitted) {
      log.info("Refused a call for tenant {}: {} of {} this month.", tenantId, used, permitted);
      throw new QuotaExceededException(Quota.API_CALLS, used, permitted);
    }
  }

  /**
   * How many calls this tenant has made this month.
   *
   * @param tenantId whose allowance
   * @return the count, or zero when there is none or Valkey cannot be reached
   */
  private long callsThisMonth(UUID tenantId) {
    try {
      String value = redis.opsForValue().get(callKey(tenantId));
      return value == null ? 0L : Long.parseLong(value);
    } catch (RedisConnectionFailureException | NumberFormatException unusable) {
      return 0L;
    }
  }

  /**
   * The Valkey key for a tenant's calls in the current calendar month.
   *
   * <p>The month is in the key rather than in a reset job: a key that names its month is one that
   * nothing has to clear, and the switch at midnight on the first is a different key rather than an
   * operation that can fail.
   *
   * @param tenantId whose allowance
   * @return the key
   */
  private String callKey(UUID tenantId) {
    return "quota:calls:" + tenantId + ":" + MONTH.format(clock.instant().atZone(ZoneOffset.UTC));
  }

  /**
   * What this tenant is allowed of one quota.
   *
   * @param tenantId the tenant
   * @param quota which bound
   * @return the tenant's own limit where it has one, otherwise the instance-wide default
   */
  private long permittedFor(UUID tenantId, Quota quota) {
    Optional<Long> own =
        jdbc.sql(LIMIT).params(tenantId, quota.name()).query(Long.class).optional();
    return own.orElseGet(() -> defaults.get(quota));
  }

  /**
   * Every counter this tenant has.
   *
   * @param tenantId the tenant
   * @return the used amount by quota name
   */
  private Map<String, Long> counters(UUID tenantId) {
    Map<String, Long> used = new java.util.HashMap<>();
    jdbc.sql(USAGE)
        .param(tenantId)
        .query((rs, rowNum) -> used.put(rs.getString("quota"), rs.getLong("used")))
        .list();
    return used;
  }

  /**
   * Who the row records as having moved the counter.
   *
   * <p>The caller where there is one, and null where there is not — a background job, a test. The
   * column is documentation rather than a foreign key, so a null is a smaller lie than a made-up id.
   *
   * @return the caller's id, or null
   */
  private static UUID actor() {
    return de.greluc.homeinv.platform.CallerContext.current()
        .map(de.greluc.homeinv.platform.CallerContext.Caller::userId)
        .orElse(null);
  }
}
