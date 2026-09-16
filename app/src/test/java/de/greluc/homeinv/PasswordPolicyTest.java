/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import de.greluc.homeinv.identity.application.DefaultPasswordPolicy;
import de.greluc.homeinv.identity.infrastructure.BreachedPasswordList;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.PasswordBreachCheck;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a password has to be (REQ-SEC-011, ADR-0067).
 *
 * <p>Three rules, and two of them are absences: twelve characters, <b>no</b> forced complexity,
 * <b>no</b> forced rotation — plus the list of passwords somebody else has already had taken. The
 * absences are tested too, because a well-meaning later change is exactly how they disappear.
 */
@DisplayName("The password policy")
class PasswordPolicyTest {

  private final BreachedPasswordList list = new BreachedPasswordList();

  /** What an installation with no breach plugin resolves: nothing. */
  private static final ExtensionRegistry NO_PLUGIN = registryReturning(null);

  private final PasswordPolicy policy = new DefaultPasswordPolicy(list, NO_PLUGIN);

  @Test
  @DisplayName("refuses anything under twelve characters")
  void tooShort() {
    assertThatThrownBy(() -> policy.check("short-pass1"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("at least 12");
  }

  @Test
  @DisplayName("counts characters as a person types them, not as Java stores them")
  void codePointsRatherThanUnits() {
    // Twelve emoji are twelve characters to whoever chose them and twenty-four
    // to String.length(). A rule that disagreed with its own message about what
    // "12 characters" means is one people work around rather than meet.
    assertThatCode(() -> policy.check("🔑🔒🗝🛡🔐🧩🪪🔏🧱🪤🕵🦺")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a long password that is already on the list")
  void breached() {
    // Twelve characters, no repetition, looks invented — and it is the
    // keyboard walk `q1w2e3r4t5y6`, which is in the list precisely because so
    // many people have chosen it.
    assertThatThrownBy(() -> policy.check("q1w2e3r4t5y6"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("already known to attackers");
  }

  @Test
  @DisplayName("says length first when a password is both short and breached")
  void lengthBeforeList() {
    // "password" is on the list and is also eight characters. The person is told
    // the fixable thing.
    assertThatThrownBy(() -> policy.check("password"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("at least 12");
  }

  @Test
  @DisplayName("asks for no digit, no capital and no symbol")
  void noForcedComplexity() {
    // Twelve lower-case letters and nothing else. REQ-SEC-011 forbids forced
    // complexity, and this is what forbidding it looks like from the outside:
    // the rule that would reject this must not exist.
    assertThatCode(() -> policy.check("kitchenwindow")).doesNotThrowAnyException();
    assertThatCode(() -> policy.check("a quiet afternoon by the lake"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("carries the whole list it shipped with")
  void theListIsComplete() {
    // A truncated or missing resource would make every check pass, quietly. The
    // count is the cheapest way to notice, and the constructor already refuses
    // an absent file outright.
    assertThat(list.size()).isEqualTo(100_000);
  }

  @Test
  @DisplayName("asks a breach plugin, and never shows it a password")
  void theServiceSeesOnlyAPrefix() {
    AtomicReference<String> asked = new AtomicReference<>();
    AtomicReference<CallContext> context = new AtomicReference<>();
    // The real SHA-1 of "kitchenwindowseat". The service claims to know its
    // suffix, so the password is refused although the shipped list has never
    // heard of it — which is the only way to see that the plugin was consulted.
    String hash = "6599057EB761E8B185290EE0DE3E61EE77B4DA94";
    PasswordPolicy withPlugin =
        new DefaultPasswordPolicy(
            list,
            registryReturning(
                (callContext, prefix) -> {
                  context.set(callContext);
                  asked.set(prefix);
                  return new PasswordBreachCheck.Range(List.of(hash.substring(5)));
                }));

    assertThatThrownBy(() -> withPlugin.check("kitchenwindowseat"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("already known to attackers");

    // Five characters, and they are the first five of the hash rather than of
    // anything a person typed.
    assertThat(asked.get()).hasSize(5).isEqualTo(hash.substring(0, 5));
    // An instance call: a password is chosen where there is often no tenant.
    assertThat(context.get().scope()).isEqualTo(CallContext.Scope.INSTANCE);
    assertThat(context.get().tenantId()).isNull();
  }

  @Test
  @DisplayName("accepts what the service does not know")
  void theServiceKnowsNothing() {
    PasswordPolicy withPlugin =
        new DefaultPasswordPolicy(
            list, registryReturning((callContext, prefix) -> new PasswordBreachCheck.Range(List.of())));

    assertThatCode(() -> withPlugin.check("kitchenwindowseat")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("keeps the list's verdict when the service cannot be reached")
  void theServiceIsDown() {
    PasswordPolicy withPlugin =
        new DefaultPasswordPolicy(
            list,
            registryReturning(
                (callContext, prefix) -> {
                  throw new PluginException(
                      PluginException.Kind.UNAVAILABLE, "the service is not answering");
                }));

    // An unreachable plugin weakens nothing and blocks nobody: it was never the
    // floor, and an outage must not stop people changing their password.
    assertThatCode(() -> withPlugin.check("kitchenwindowseat")).doesNotThrowAnyException();
    // And the list still refuses what it knows.
    assertThatThrownBy(() -> withPlugin.check("q1w2e3r4t5y6"))
        .isInstanceOf(WeakPasswordException.class);
  }

  /**
   * A registry that resolves the breach port to one implementation, or to none.
   *
   * @param service what to answer with, or {@code null} for an installation with no plugin
   * @return the registry
   */
  private static ExtensionRegistry registryReturning(PasswordBreachCheck service) {
    return new ExtensionRegistry() {
      @Override
      public <T> Optional<T> lookup(Class<T> port, UUID tenantId) {
        return Optional.empty();
      }

      @Override
      public <T> List<T> lookupAll(Class<T> port, UUID tenantId) {
        return List.of();
      }

      @Override
      @SuppressWarnings("unchecked")
      public <T> Optional<T> lookupForInstance(Class<T> port) {
        return port.equals(PasswordBreachCheck.class)
            ? Optional.ofNullable((T) service)
            : Optional.empty();
      }
    };
  }
}
