/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Argon2id cost, with the floor REQ-SEC-010 sets.
 *
 * <p>Configurable because the requirement says "raisable by configuration": hardware gets faster,
 * and a cost fixed in code ages into a weakness nobody notices. Validated because a knob that can
 * be raised can be lowered, and this one looks exactly like a performance setting to somebody
 * chasing a slow login.
 *
 * @param memoryKib memory per hash in KiB; at least 19456, which is the 19 MiB the requirement names
 * @param iterations time cost; at least 2
 * @param parallelism lanes; at least 1
 * @param saltLength salt bytes
 * @param hashLength output bytes
 */
@ConfigurationProperties(prefix = "homeinv.security.password")
public record PasswordHashingProperties(
    Integer memoryKib, Integer iterations, Integer parallelism, Integer saltLength, Integer hashLength) {

  /** 19 MiB, the floor from REQ-SEC-010. */
  private static final int MIN_MEMORY_KIB = 19 * 1024;

  private static final int MIN_ITERATIONS = 2;
  private static final int MIN_PARALLELISM = 1;

  /** Applies the defaults, which are the floor itself. */
  public PasswordHashingProperties {
    memoryKib = memoryKib != null ? memoryKib : MIN_MEMORY_KIB;
    iterations = iterations != null ? iterations : MIN_ITERATIONS;
    parallelism = parallelism != null ? parallelism : MIN_PARALLELISM;
    saltLength = saltLength != null ? saltLength : 16;
    hashLength = hashLength != null ? hashLength : 32;
  }

  /**
   * Refuses a configuration weaker than the requirement allows.
   *
   * <p>At startup, not at first use. A weakened cost discovered on the first login is a weakened
   * cost that has already been running in production.
   */
  @PostConstruct
  public void validate() {
    if (memoryKib < MIN_MEMORY_KIB || iterations < MIN_ITERATIONS || parallelism < MIN_PARALLELISM) {
      throw new IllegalStateException(
          "Argon2id below the floor of REQ-SEC-010 (>= %d KiB, t >= %d, p >= %d); configured: %d KiB, t=%d, p=%d"
              .formatted(
                  MIN_MEMORY_KIB, MIN_ITERATIONS, MIN_PARALLELISM, memoryKib, iterations, parallelism));
    }
  }
}
