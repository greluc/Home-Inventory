/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.method.HandlerMethod;

/**
 * The coverage check REQ-SEC-026 names.
 *
 * <p>"Every endpoint has negative tests for <em>no permission → 403</em> and <em>foreign tenant →
 * 404</em>." The requirement said "a coverage check in CI" and there was none, which is how six tag
 * endpoints declared {@code NOT_FOUND} for months while answering {@code 500}, {@code 204} and an
 * empty page instead.
 *
 * <h2>It calls them rather than counting them</h2>
 *
 * <p>Decided with the owner on 2026-09-14. A static check — does some test mention this path? —
 * proves that somebody typed the name. The tag endpoints would have passed it: they were tested,
 * just never negatively. So this asks Spring for every mapping it actually registered and drives
 * each one, which also means the check grows by itself: a new endpoint is covered the day it is
 * written, without anybody remembering to add it here.
 *
 * <h2>What is skipped, and why it is derived rather than listed</h2>
 *
 * <p>Two exclusions fall out of the code and need no list. An endpoint marked
 * {@link PublicEndpoint} has no permission to lack. An endpoint whose permission
 * <b>every</b> role holds — {@code GUEST} already reads items, places and the type
 * definitions behind them — has no caller who could lack it, so "no permission → 403" is vacuous
 * for it and saying otherwise would be a test that asserts nothing.
 *
 * <h2>Why the second rule is shaped the way it is</h2>
 *
 * <p>"An id this tenant cannot see is a {@code 404}" is only checkable where the request can be
 * formed at all, and seventeen endpoints need a body or a query string this check has no way to
 * invent: {@code PUT /items/&#123;id&#125;} without a name is a {@code 422} about the body long
 * before anything is looked up. Listing those seventeen by hand would be a list nobody maintains
 * and a place to hide a real gap.
 *
 * <p>So the rule is total instead. For an id nothing has, an endpoint may answer {@code 404}, or
 * refuse the request with another {@code 4xx} when it could not be formed — and it may <b>never</b>
 * answer:
 *
 * <ul>
 *   <li>{@code 2xx}, which acted on something that is not there;
 *   <li>{@code 403}, which confirms the row exists and is what REQ-SEC-025 forbids in as many
 *       words;
 *   <li>{@code 5xx}, which is the application falling over on ordinary input — twelve catalogue
 *       endpoints did exactly that until 2026-09-14, because {@code UnknownTypeException} had no
 *       handler.
 * </ul>
 *
 * <p>That rule needs one exception, and it is a closed list with a reason, the same shape
 * {@code MigrationRulesTest} uses for the tables that are not domain tables.
 */
@DisplayName("Every endpoint")
class EndpointNegativeCoverageIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** What every role holds, so no caller can lack it and the 403 case cannot be posed. */
  private static final Set<Permission> HELD_BY_EVERYONE = Role.GUEST.permissions();

  /**
   * The endpoints that answer {@code 2xx} for something that is not there, and are right to.
   *
   * <p>A closed list, and it has one entry. Removing a top-level resource is idempotent: a client
   * that never saw the answer to its first attempt sends the second, and being told the thing is
   * gone is what it wanted. Nothing leaks by it either — a role of another tenant and a role that
   * never existed both answer {@code 204}, which is the indistinguishability REQ-SEC-025 is for.
   *
   * <p>What is <b>not</b> on this list, deliberately, is a sub-resource: {@code DELETE
   * /items/&#123;id&#125;/relations/&#123;relationId&#125;} answered {@code 204} for an item this
   * tenant cannot see until 2026-09-14, and that is not idempotence, it is an endpoint declining to
   * answer about the thing in its own path.
   */
  private static final Set<String> IDEMPOTENT_ABOUT_A_WHOLE_RESOURCE =
      Set.of("DELETE /api/v1/roles/{id}");

  /**
   * How many endpoints may refuse the request instead of answering {@code 404}.
   *
   * <p><b>Twenty-three today</b>, and every one of them needs a body or a query string this check
   * cannot invent. The number is here because the rule above has a soft edge: an endpoint that
   * regressed from {@code 404} to {@code 400} would still satisfy it, and so would one that gained
   * a required field and quietly stopped being reachable. Counting them turns that from a silent
   * loss of coverage into a failing build.
   *
   * <p>Where it has been, each step an ordinary new endpoint with a body:
   *
   * <ul>
   *   <li>17 → 18 on 2026-09-14, {@code PUT /api/v1/saved-searches/{id}}, which takes a name and a
   *       query (REQ-SRCH-008)
   *   <li>18 → 19 on 2026-09-16, {@code POST /api/v1/items/{id}/maintenance}, which takes a date
   *       and a kind of work (REQ-LIFE-003)
   *   <li>19 → 21 on 2026-09-16, {@code POST /api/v1/items/{id}/loans} and {@code
   *       POST /api/v1/items/{id}/loans/{loanId}/return}, which take a borrower and a date
   *       (REQ-LIFE-005)
   *   <li>21 → 22 on 2026-09-20, {@code POST /api/v1/items/{id}/disposal}, which takes what
   *       became of the item and when (REQ-LIFE-007)
   *   <li>22 → 23 on 2026-09-20, {@code PUT /api/v1/reminder-rules/{id}}, which takes a trigger, an
   *       offset and a channel (REQ-NOTI-001)
   *   <li>23 → 24 on 2026-09-20, {@code PUT /api/v1/plugins/{pluginId}/settings/{key}}, which
   *       takes the value to store (REQ-PLG-017)
   *   <li>24 → 25 on 2026-09-21, {@code PUT /api/v1/webhooks/{id}}, which takes a URL, the event
   *       types and optionally a new signing secret (REQ-API-010)
   * </ul>
   *
   * <p>*The prose said "Eighteen today" over a constant of 19 between the second and third of
   * those: the sentence was extended instead of the count being restated, which is the drift a
   * number written twice invites. It is written once now, and the list carries the history.*
   *
   * <p>It goes <b>down</b> freely — an endpoint that becomes reachable is a better-covered
   * endpoint. It goes <b>up</b> only with a reason, and the reason is worth writing down in the
   * commit: a new endpoint that takes a body is ordinary, an existing one that stopped answering
   * {@code 404} is not.
   */
  private static final int MOST_THAT_MAY_REFUSE_INSTEAD = 25;

  @Autowired private RequestMappingHandlerMapping mappings;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("refuses a caller whose role does not hold what it needs (REQ-SEC-026)")
  void everyEndpointRefusesACallerWithoutThePermission() throws Exception {
    UUID tenantId = aTenantWithAnOwner("coverage-403");
    MockHttpSession guest = aMemberOf(tenantId, "coverage-403-guest@example.org", "GUEST");

    List<String> answeredOtherwise = new ArrayList<>();
    for (Endpoint endpoint : endpoints()) {
      if (endpoint.permission() == null || HELD_BY_EVERYONE.contains(endpoint.permission())) {
        continue;
      }
      int status = call(endpoint, guest);
      if (status != 403) {
        answeredOtherwise.add(endpoint + " answered " + status);
      }
    }

    assertThat(answeredOtherwise)
        .as(
            "every endpoint a GUEST may not reach answers 403. An endpoint that answers something "
                + "else either decides its own access or fails before the decision is made")
        .isEmpty();
  }

  @Test
  @DisplayName("says 404 for an id this tenant cannot see (REQ-SEC-025, REQ-SEC-026)")
  void everyEndpointWithAnIdAnswersNotFound() throws Exception {
    UUID tenantId = aTenantWithAnOwner("coverage-404");
    MockHttpSession owner = signIn("coverage-404@example.org", PASSWORD);

    List<String> answeredOtherwise = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    int answeredNotFound = 0;
    for (Endpoint endpoint : endpoints()) {
      if (!endpoint.takesAnId() || endpoint.permission() == null) {
        continue;
      }
      if (IDEMPOTENT_ABOUT_A_WHOLE_RESOURCE.contains(endpoint.signature())) {
        continue;
      }
      int status = call(endpoint, owner);
      if (status == 404) {
        answeredNotFound++;
        continue;
      }
      // Another 4xx means the request could not be formed without knowledge this
      // check does not have -- a body, a query string -- so it was refused before
      // anything was looked up, which discloses nothing. 403, 2xx and 5xx are
      // each a different way of being wrong.
      if (status >= 400 && status < 500 && status != 403) {
        refused.add(endpoint.signature());
        continue;
      }
      answeredOtherwise.add(endpoint + " answered " + status);
    }

    assertThat(answeredOtherwise)
        .as(
            "for an id this tenant cannot see, an endpoint answers 404 or refuses the request. A "
                + "2xx acted on something that is not there, a 403 confirms it exists, and a 5xx "
                + "is the application falling over on ordinary input")
        .isEmpty();

    assertThat(answeredNotFound)
        .as("the check is worth running only while it actually reaches most endpoints")
        .isGreaterThan(refused.size());

    assertThat(refused)
        .as(
            "these need a request this check cannot invent, so they are counted rather than "
                + "listed. One more than before is either a new endpoint that takes a body -- "
                + "raise the number and say so -- or an endpoint that stopped answering 404, "
                + "which is the regression this count exists to catch")
        .hasSizeLessThanOrEqualTo(MOST_THAT_MAY_REFUSE_INSTEAD);
  }

  // -------------------------------------------------------------------------

  /**
   * Every mapping Spring registered under {@code /api/v1}, one per path and method.
   *
   * @return the endpoints, in a stable order so a failure reads the same twice
   */
  private List<Endpoint> endpoints() {
    List<Endpoint> found = new ArrayList<>();
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      HandlerMethod handler = entry.getValue();
      if (handler.getMethodAnnotation(PublicEndpoint.class) != null
          || handler.getMethodAnnotation(RequiresEntitlement.class) != null) {
        continue;
      }
      RequiresPermission required = handler.getMethodAnnotation(RequiresPermission.class);
      Set<String> patterns =
          info.getPathPatternsCondition() == null
              ? Set.of()
              : new TreeSet<>(info.getPathPatternsCondition().getPatternValues());
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/v1/")) {
          continue;
        }
        for (RequestMethod method : methods) {
          found.add(
              new Endpoint(
                  pattern,
                  method.name(),
                  required == null ? null : required.value(),
                  handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName(),
                  pathVariablesOf(handler)));
        }
      }
    }
    found.sort(java.util.Comparator.comparing(Endpoint::pattern).thenComparing(Endpoint::method));
    return found;
  }

  /**
   * The path variables a handler declares, by name and type.
   *
   * <p>The type is what decides the value: a {@code long} revision number and a {@link UUID} id are
   * both "{@code {x}}" in the pattern, and nothing else tells them apart.
   *
   * @param handler the controller method
   * @return each variable's name and the type it is bound to
   */
  private static java.util.Map<String, Class<?>> pathVariablesOf(HandlerMethod handler) {
    java.util.Map<String, Class<?>> variables = new java.util.LinkedHashMap<>();
    for (org.springframework.core.MethodParameter parameter : handler.getMethodParameters()) {
      org.springframework.web.bind.annotation.PathVariable declared =
          parameter.getParameterAnnotation(
              org.springframework.web.bind.annotation.PathVariable.class);
      if (declared == null) {
        continue;
      }
      parameter.initParameterNameDiscovery(
          new org.springframework.core.DefaultParameterNameDiscoverer());
      String name = declared.value().isBlank() ? parameter.getParameterName() : declared.value();
      if (name != null) {
        variables.put(name, parameter.getParameterType());
      }
    }
    return variables;
  }

  /**
   * Calls one endpoint with an id nothing has, and answers with the status.
   *
   * <p>Every path variable gets a value of its own, from {@link Endpoint#pathWithNothingBehindIt()}.
   * For the {@code 404} case that is the point; for the {@code 403} case it makes no difference,
   * because the permission is decided before the handler ever runs.
   *
   * <p>{@code If-Match} is sent on every write. Without it a write on a single resource is refused
   * with {@code 428} before anything is looked up (REQ-API-004), which would say nothing about
   * whether the row is visible. The version sent cannot match anything; what is under test is that
   * the row is not found first.
   *
   * @param endpoint which endpoint
   * @param session the caller
   * @return the status it answered
   * @throws Exception when the request itself fails, which is the test failing
   */
  private int call(Endpoint endpoint, MockHttpSession session) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(
                HttpMethod.valueOf(endpoint.method()), endpoint.pathWithNothingBehindIt())
            .session(session);
    if (!"GET".equals(endpoint.method()) && !"HEAD".equals(endpoint.method())) {
      request = request.with(csrf()).header("If-Match", "\"1\"");
    }
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  /**
   * One endpoint, as this check sees it.
   *
   * @param pattern the path template Spring registered
   * @param method the HTTP method
   * @param permission what the handler declares it needs, or {@code null} when it declares none
   * @param handler the controller method, for a failure message somebody can act on
   */
  private record Endpoint(
      String pattern,
      String method,
      Permission permission,
      String handler,
      java.util.Map<String, Class<?>> pathVariables) {

    /**
     * Whether the path carries a variable, which is what makes "an id this tenant cannot see"
     * sayable at all.
     *
     * @return true when the pattern has a path variable
     */
    boolean takesAnId() {
      return pattern.contains("{");
    }

    /**
     * How this endpoint is named in the exception list.
     *
     * @return the method and the path template, as a person would write them
     */
    String signature() {
      return method + " " + pattern;
    }

    /**
     * The path with every variable filled in by something this tenant has no row for.
     *
     * <p>A value <b>per variable</b>, and of the variable's own type. Both matter, and both were
     * learned on the first run of this check: one value reused for every variable turned
     * {@code /tags/{id}/merge-into/{targetId}} into a request to merge a tag into itself, which is
     * a {@code 422} about the request rather than an answer about the row; and a {@link UUID}
     * where {@code /items/{id}/revisions/{revision}/restore} wants a number is malformed input,
     * which says nothing about visibility either.
     *
     * @return the path, ready to send
     */
    String pathWithNothingBehindIt() {
      String path = pattern;
      for (var variable : pathVariables.entrySet()) {
        Class<?> type = variable.getValue();
        boolean numeric =
            Number.class.isAssignableFrom(type)
                || type == int.class
                || type == long.class
                || type == short.class;
        String value = numeric ? "1" : UUID.randomUUID().toString();
        path = path.replace("{" + variable.getKey() + "}", value);
      }
      // Anything the handler did not declare by name -- a variable bound into
      // a map, or one this walk could not see -- still has to be filled in, or
      // the request would go out with a brace in the path.
      return path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
      return method + " " + pattern + " (" + handler + ")";
    }
  }

  /**
   * A tenant with an owner signed in.
   *
   * @param name what to call it, also the local part of the owner's address
   * @return the tenant
   */
  private UUID aTenantWithAnOwner(String name) {
    String email = name + "@example.org";
    UUID userId = anAccount(email);
    return provisioning.provision("Coverage " + name, userId);
  }

  /**
   * A second member of a tenant, in a role, signed in.
   *
   * @param tenantId the tenant
   * @param email the member's address
   * @param role the built-in role they hold
   * @return their session
   * @throws Exception when the sign-in fails, which is the test failing
   */
  private MockHttpSession aMemberOf(UUID tenantId, String email, String role) throws Exception {
    UUID userId = anAccount(email);
    // Outside the transaction, not inside it: the transaction manager publishes
    // `app.tenant_id` when the transaction begins, so a context established in
    // the callback arrives after the connection has been configured and the
    // insert is refused by the policy this test relies on.
    TenantContext.runAs(
        tenantId,
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "insert into tenancy.membership "
                                + "(id, tenant_id, user_id, role, created_at, updated_at, version) "
                                + "values (?, ?, ?, ?, now(), now(), 1)")
                        .params(UUID.randomUUID(), tenantId, userId, role)
                        .update()));
    return signIn(email, PASSWORD);
  }

  /**
   * An account with a password and an enrolled second factor.
   *
   * @param email the address it answers to
   * @return the account's id
   */
  private UUID anAccount(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    email.substring(0, email.indexOf('@')),
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // REQ-AUTH-003: a role that requires a second factor is refused every request
    // until one exists. The enrolment loop is SecondFactorIT's subject; here it is
    // a precondition.
    enrolSecondFactor(userId);
    return userId;
  }
}
