/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password encoder, Argon2id.
 *
 * <p>In {@code platform} because it depends on no block and several may need it; the HTTP filter
 * chain that used to sit beside it moved to the access layer, because wiring a filter that reads
 * {@code AuthenticatedUser} made the shared kernel depend on {@code identity}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordHashingProperties.class)
public class PasswordEncoderConfiguration {

  /**
   * Creates the encoder every password in the system passes through.
   *
   * <p>{@code REQ-SEC-010} sets the floor - at least 19 MiB of memory, two iterations, one lane -
   * and says it must be raisable by configuration, which is why the values come from
   * {@link PasswordHashingProperties} rather than being constants. That class refuses anything below
   * the floor at startup, so a deployment cannot weaken it by editing a value that looks like a
   * performance knob.
   *
   * <p>The encoder writes PHC strings, so each hash carries the parameters it was made with. That is
   * what lets a login notice a hash made at a lower cost and rehash it - the only path by which a
   * raised cost ever reaches an existing account.
   *
   * @param properties the configured cost, validated against the requirement's floor
   * @return the encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder(PasswordHashingProperties properties) {
    return new Argon2PasswordEncoder(
        properties.saltLength(),
        properties.hashLength(),
        properties.parallelism(),
        properties.memoryKib(),
        properties.iterations());
  }
}
