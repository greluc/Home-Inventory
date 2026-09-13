/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.bootstrap;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.tenancy.api.TenantProvisioning;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Creates the first account and the tenant it owns, then exits (REQ-TEN-012).
 *
 * <h2>Why a service of its own</h2>
 *
 * <p>A freshly deployed instance had no account and no tenant, and nothing anywhere could create
 * either: {@code TenantProvisioningService} was reachable only from tests. Nobody could sign in, so
 * stage 0's "an item can be created, photographed, stored and found again" was unreachable through
 * the product. ADR-0028 already said the registration path is "the operator creating the first
 * account directly" and never said how.
 *
 * <p>The shape is {@code migrate}'s, for {@code migrate}'s reasons (ADR-0041, ADR-0053): the same
 * image, because an account created by a different build is an account created against different
 * validation; a different <em>process</em>, because creating an owner is the most privileged action
 * this system performs and it has no business living in the process that serves the internet.
 *
 * <h2>Idempotent, because it runs on every deployment</h2>
 *
 * <p>Not only on the first. A second run finds the address taken and does nothing — it must not
 * create a second owner, and it must not reset a password the operator has since changed, which a
 * redeploy would then undo without saying so.
 *
 * <p>The exit code is what the runtime reads: Quadlet's {@code Type=oneshot} and Compose's
 * {@code service_completed_successfully} both gate {@code api} on it.
 */
@Slf4j
@Component
@Profile("bootstrap")
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

  private final UserProvisioning users;
  private final AccountAdministration accounts;
  private final TenantProvisioning tenants;
  private final ConfigurableApplicationContext context;

  /** The address the first owner signs in with. */
  @Value("${HOMEINV_BOOTSTRAP_EMAIL:}")
  private String email;

  /** What the interface calls them until they change it. */
  @Value("${HOMEINV_BOOTSTRAP_DISPLAY_NAME:Owner}")
  private String displayName;

  /** The interface language the first owner starts in. */
  @Value("${HOMEINV_BOOTSTRAP_LOCALE:en}")
  private String locale;

  /** The tenant's display name. One tenant at stage 0; stage 1 adds the rest. */
  @Value("${HOMEINV_BOOTSTRAP_TENANT_NAME:Home}")
  private String tenantName;

  /**
   * The first owner's password, from {@code HOMEINV_BOOTSTRAP_PASSWORD_FILE}.
   *
   * <p>A file and never an environment value holding the secret itself (REQ-NFR-054), and no default
   * (REQ-NFR-046): a generated one would be a password an operator never sees and never changes, and
   * "insecure but running" is not a mode this project has.
   */
  @Value("${homeinv.secret.bootstrap-password:}")
  private String password;

  @Override
  public void run(ApplicationArguments args) {
    if (email.isBlank() || password.isBlank()) {
      // Both or neither. An instance with no owner is not broken — it is one
      // somebody has not finished setting up — so this says what is missing and
      // stops, rather than inventing an account.
      log.error(
          "Nothing to do: set HOMEINV_BOOTSTRAP_EMAIL and mount "
              + "HOMEINV_BOOTSTRAP_PASSWORD_FILE, then run this service again.");
      exit(1);
      return;
    }

    try {
      Optional<UUID> created = users.createIfAbsent(email, displayName, locale, password);
      if (created.isEmpty()) {
        restoreOperatorIfNoneIsLeft();
        exit(0);
        return;
      }

      // The first account is the instance operator and may create tenants
      // (ADR-0057). Both are granted here because there is nobody else who could
      // grant them: an instance whose first account holds neither is one nobody
      // can administer and nobody can add a second tenant to.
      UUID owner = created.get();
      accounts.replaceEntitlements(owner, true, true, null, owner);

      UUID tenantId = tenants.provision(tenantName, owner);
      log.info(
          "Created the first owner and tenant {}. Sign in at HOMEINV_PUBLIC_BASE_URL.", tenantId);
      exit(0);
    } catch (RuntimeException failed) {
      // The message is what an operator reads and the exit code is what the
      // runtime acts on; `api` does not start on a non-zero one.
      log.error("Could not create the first owner.", failed);
      exit(1);
    }
  }

  /**
   * Gives the bootstrap account the operator entitlement back, but only if nobody has it.
   *
   * <p>The recovery path ADR-0057 names. Nothing in the application stops the last operator
   * clearing their own flag — deciding in code which operator is the last one is a question the
   * application cannot answer while somebody else is deleting an account — so the way back is this
   * service, which the operator already runs on every deployment.
   *
   * <p>Deliberately conditional on there being <b>no</b> operator at all. Restoring it
   * unconditionally would mean an instance that has deliberately moved operatorship to somebody
   * else gets it handed back to the bootstrap address on the next redeploy, silently, forever.
   */
  private void restoreOperatorIfNoneIsLeft() {
    if (accounts.hasInstanceOperator()) {
      log.info("This instance already has its owner; nothing to do.");
      return;
    }
    accounts
        .byEmail(email)
        .ifPresentOrElse(
            account -> {
              accounts.replaceEntitlements(
                  account.id(),
                  true,
                  account.mayCreateTenants(),
                  account.tenantLimit(),
                  account.id());
              log.warn(
                  "This instance had no operator left. Restored it on the bootstrap account {}.",
                  account.id());
            },
            () ->
                log.error(
                    "This instance has no operator, and the bootstrap address belongs to no live "
                        + "account. Set HOMEINV_BOOTSTRAP_EMAIL to an existing account."));
  }

  private void exit(int code) {
    System.exit(SpringApplication.exit(context, () -> code));
  }
}
