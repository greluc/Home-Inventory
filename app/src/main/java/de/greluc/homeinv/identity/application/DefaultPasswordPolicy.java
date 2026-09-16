/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import de.greluc.homeinv.identity.infrastructure.BreachedPasswordList;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.PasswordBreachCheck;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The rules of {@code REQ-SEC-011}, applied wherever a password is chosen.
 *
 * <p>Counts <b>code points</b> rather than {@code String.length()}. A password of twelve emoji is
 * twelve characters to the person who typed it and twenty-four to {@code length()}, and a rule that
 * disagreed with its own error message about what "12 characters" means is a rule people work
 * around rather than meet.
 *
 * <p>Nothing here trims or normalises: a leading space is a character somebody chose, and silently
 * removing it would mean the password that was accepted is not the password that was stored.
 *
 * <h2>Length first, then the list, then a plugin if there is one</h2>
 *
 * <p>Order matters for what the person is told. Something short and breached is told it is short,
 * because that is the fixable thing; and the list is only reached by passwords long enough to have
 * passed, which today is about 1 500 of its 100 000 entries (ADR-0067).
 *
 * <p>The plugin is an <b>addition</b>. It can only refuse more: a plugin that says a password is
 * fine does not make it fine, and one that cannot be reached leaves the list's verdict standing —
 * it was never the floor. What it receives is a five-character SHA-1 prefix and never a password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPasswordPolicy implements PasswordPolicy {

  /** How much of the SHA-1 leaves: the k-anonymity prefix the range query is built on. */
  private static final int PREFIX_LENGTH = 5;

  private final BreachedPasswordList breached;
  private final ExtensionRegistry extensions;

  @Override
  public void check(String password) {
    if (password == null || password.codePointCount(0, password.length()) < MINIMUM_LENGTH) {
      throw new WeakPasswordException(
          "A password needs at least " + MINIMUM_LENGTH + " characters.");
    }
    if (breached.contains(password)) {
      // What the person is told and what they are not: that it is known, and
      // nothing about where from. "This password appears in a breach list" is
      // actionable; naming the list would only invite an argument about it.
      throw new WeakPasswordException(
          "This password appears in a list of passwords that are already known to attackers."
              + " Choose a different one.");
    }
    if (aServiceKnowsIt(password)) {
      throw new WeakPasswordException(
          "This password appears in a list of passwords that are already known to attackers."
              + " Choose a different one.");
    }
  }

  /**
   * Asks the breach plugin, if an operator installed one (ADR-0067).
   *
   * <p>An <b>instance</b> resolution, because a password is chosen where there is often no tenant:
   * at registration, and at a reset asked for from the login page. That is the second and last
   * caller of {@code lookupForInstance}, and {@code ArchitectureRulesTest} names it.
   *
   * @param password the password, which does not leave this method
   * @return whether a service knows it; {@code false} when no plugin is installed or it could not
   *     be reached
   */
  private boolean aServiceKnowsIt(String password) {
    Optional<PasswordBreachCheck> service = extensions.lookupForInstance(PasswordBreachCheck.class);
    if (service.isEmpty()) {
      return false;
    }

    String hash = sha1Hex(password);
    String prefix = hash.substring(0, PREFIX_LENGTH);
    String suffix = hash.substring(PREFIX_LENGTH);
    try {
      return service
          .get()
          .suffixesFor(CallContext.forInstance("", "", 0), prefix)
          .suffixes()
          .stream()
          .anyMatch(known -> asciiUpper(known).equals(suffix));
    } catch (PluginException unreachable) {
      // The list already had its say. An unreachable plugin must not refuse a
      // password nobody has evidence against, and must not be an outage that
      // stops people changing their password.
      log.warn(
          "The password breach service could not be reached; the shipped list's verdict stands: {}",
          unreachable.getMessage());
      return false;
    }
  }

  /**
   * Upper-cases the sixteen hexadecimal characters and nothing else.
   *
   * <p>Not {@code toUpperCase}, and not {@code equalsIgnoreCase}: what is being folded here came
   * from a <b>plugin</b>, and a Unicode case fold on untrusted input immediately before a security
   * comparison is the shape that makes `ı` meet `I`. A hexadecimal digit has no such neighbours, so
   * the fold is restricted to {@code a}–{@code f} and every other character is left exactly as it
   * arrived — where it will simply fail to match.
   *
   * @param value what the plugin answered
   * @return the value with lower-case hexadecimal letters raised
   */
  private static String asciiUpper(String value) {
    char[] characters = value.toCharArray();
    for (int index = 0; index < characters.length; index++) {
      if (characters[index] >= 'a' && characters[index] <= 'f') {
        characters[index] = (char) (characters[index] - ('a' - 'A'));
      }
    }
    return new String(characters);
  }

  /**
   * The SHA-1 of a password, upper-case hexadecimal.
   *
   * <p>SHA-1 by protocol rather than by choice: the range query every such service answers is
   * defined over it. It is a lookup key here and not a security property — the password's own
   * storage is Argon2id (REQ-SEC-010) — and only five of its forty characters ever leave.
   *
   * @param password the password
   * @return the digest
   */
  private static String sha1Hex(String password) {
    try {
      return HexFormat.of()
          .withUpperCase()
          .formatHex(
              MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-1 is not available in this JVM", impossible);
    }
  }
}
