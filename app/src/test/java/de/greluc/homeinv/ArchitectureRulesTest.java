/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.base.DescribedPredicate;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresEntitlement;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules that are not style preferences ({@code CLAUDE.md}, "The rules CI enforces here").
 *
 * <p>Spring Modulith proves the boundaries <em>between</em> blocks. These prove the rules
 * <em>inside</em> one, which Modulith has no opinion about — and they are the ones that decay
 * quietly, because breaking them never fails at runtime. A domain class that imports Spring works
 * perfectly until somebody wants to test it without a container, or extract the block.
 */
@DisplayName("The architecture rules")
class ArchitectureRulesTest {

  /** Every annotation that turns a method into a GraphQL resolver. */
  private static final List<Class<? extends Annotation>> RESOLVERS =
      List.of(
          org.springframework.graphql.data.method.annotation.QueryMapping.class,
          org.springframework.graphql.data.method.annotation.SchemaMapping.class,
          org.springframework.graphql.data.method.annotation.BatchMapping.class);

  /** Every annotation that turns a method into an HTTP handler. */
  private static final List<Class<? extends Annotation>> MAPPINGS =
      List.of(
          RequestMapping.class,
          GetMapping.class,
          PostMapping.class,
          PutMapping.class,
          PatchMapping.class,
          DeleteMapping.class);

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.homeinv");

  /** Field names whose value must never be printed by an inherited {@code toString}. */
  private static final Pattern SECRET_FIELD =
      Pattern.compile("(?i)(password|secret|credential|token|apikey|privatekey)");

  @Test
  @DisplayName("keep the domain free of the framework")
  void domainHasNoFrameworkDependency() {
    // REQ-NFR-022. The aggregates are the part worth keeping portable and the part
    // worth testing without a context; both stop being true the moment a domain
    // class needs Spring to be constructed.
    //
    // JPA is the deliberate exception: the aggregates are mapped with jakarta.persistence,
    // which is a specification rather than a framework and travels with the entity.
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..application..", "..infrastructure..")
        .because(
            "domain code must be constructible and testable without a framework, and must not "
                + "depend on the layers that depend on it (REQ-NFR-022)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep entities inside their block")
  void entitiesDoNotLeave() {
    // REQ-NFR-023. An entity handed outward carries its persistence context with
    // it: the holder can modify the aggregate without passing the use case that
    // guards its invariants, and the call site looks exactly like one returning
    // plain data.
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..api..")
        .should()
        .notHaveRawReturnType(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "an entity",
                javaClass ->
                    javaClass.isAnnotatedWith(jakarta.persistence.Entity.class)))
        .because("only *View types cross a block boundary, never an entity (REQ-NFR-023)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("forbid field injection")
  void noFieldInjection() {
    // A field-injected collaborator cannot be supplied by a constructor, so the
    // class cannot be instantiated in a test without reflection - and a missing
    // dependency surfaces as a NullPointerException at first use rather than as a
    // failure to start.
    fields()
        .should()
        .notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
        .because("constructor injection only (CLAUDE.md, Conventions)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep SQL out of the domain and the application layer")
  void sqlLivesInInfrastructure() {
    // Not purity: a query in a use case is a query that cannot be swapped when the
    // store changes, and one that nobody looks for when tuning. ADR-0017 puts
    // hand-written SQL in infrastructure, next to the repository it belongs to.
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
        .because("hand-written SQL belongs in infrastructure (ADR-0017)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("let no controller reach past a published interface")
  void controllersUseOnlyPublishedTypes() {
    // The access layer decides nothing (REQ-SEC-022) and must therefore also know
    // nothing: a controller that can see a repository is a controller that will
    // eventually use one, and the authorization decision moves with it.
    noClasses()
        .that()
        .resideInAPackage("de.greluc.homeinv.rest..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "..domain..")
        .because(
            "an access adapter translates and decides nothing; reaching a repository is how it "
                + "starts deciding (REQ-SEC-022, ADR-0010)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("refuse an endpoint that says nothing about who may reach it")
  void everyEndpointDeclaresItsAccess() {
    // REQ-SEC-023, and the reason it is a BUILD failure rather than a runtime
    // one: the default is deny, so a forgotten annotation is caught before it
    // ships rather than as a 500 the first time somebody calls the endpoint.
    // PermissionInterceptor refuses the same case at runtime; this is the half
    // that fails in the pull request.
    List<String> undeclared = new ArrayList<>();

    for (JavaClass controller : CLASSES) {
      if (!controller.getPackageName().startsWith("de.greluc.homeinv.rest")) {
        continue;
      }
      if (!controller.isAnnotatedWith(RestController.class)) {
        continue;
      }
      for (JavaMethod method : controller.getMethods()) {
        boolean isHandler =
            MAPPINGS.stream().anyMatch(mapping -> method.isAnnotatedWith(mapping));
        if (!isHandler) {
          continue;
        }
        // Three markers, one rule: every handler DECLARES what it needs. The
        // third arrived with ADR-0057, because an endpoint that creates the
        // caller's first tenant has no tenant to hold a permission in.
        boolean declared =
            method.isAnnotatedWith(RequiresPermission.class)
                || method.isAnnotatedWith(RequiresEntitlement.class)
                || method.isAnnotatedWith(PublicEndpoint.class);
        if (!declared) {
          undeclared.add(controller.getSimpleName() + "." + method.getName());
        }
      }
    }

    assertThat(undeclared)
        .as(
            "every handler carries @RequiresPermission, @RequiresEntitlement or an explicit "
                + "@PublicEndpoint with a written reason; the default is deny (REQ-SEC-023)")
        .isEmpty();
  }

  @Test
  @DisplayName("declare what every GraphQL resolver needs, one field at a time")
  void everyResolverDeclaresItsAccess() {
    // The same rule as the endpoints above and a different mechanism, because a
    // GraphQL request is not one handler: it is as many resolvers as the client
    // asked for, each reaching a different block. `Item.photos` is `media`,
    // `Item.history` is `audit`, and a check at the HTTP boundary would have to
    // check the union of everything the schema can reach — which is no check.
    //
    // `GraphQlPermissions` enforces the annotation at run time; this is the half
    // that fails in the pull request, and it is the half that matters: a resolver
    // with no annotation would simply never be checked, and nothing about the
    // response would say so (REQ-SEC-023, ADR-0079).
    List<String> undeclared = new ArrayList<>();

    for (JavaClass resolver : CLASSES) {
      if (!resolver.getPackageName().startsWith("de.greluc.homeinv.graphql")) {
        continue;
      }
      for (JavaMethod method : resolver.getMethods()) {
        boolean isResolver =
            RESOLVERS.stream().anyMatch(mapping -> method.isAnnotatedWith(mapping));
        if (!isResolver) {
          continue;
        }
        boolean declared =
            method.isAnnotatedWith(RequiresPermission.class)
                || method.isAnnotatedWith(PublicEndpoint.class);
        if (!declared) {
          undeclared.add(resolver.getSimpleName() + "." + method.getName());
        }
      }
    }

    assertThat(undeclared)
        .as(
            "every GraphQL resolver carries @RequiresPermission, or an explicit @PublicEndpoint "
                + "with a written reason. A field nobody declared is a field nobody checks "
                + "(REQ-SEC-023)")
        .isEmpty();
  }

  @Test
  @DisplayName("keep the shared kernel free of every block's domain")
  void theSharedKernelDependsOnNoBlock() {
    // REQ-NFR-024, mechanically. "Contains no domain logic" is not checkable as
    // written — a machine cannot tell a domain rule from a utility — but the
    // property that makes it true is: a shared kernel that depends on no block
    // cannot contain one's domain, because it cannot name any of its types.
    //
    // The direction is the whole point. Every block depends on `platform`; the
    // moment `platform` depends back, the two are one module with a package
    // boundary drawn through it, and every later extraction has to unpick it.
    noClasses()
        .that()
        .resideInAPackage("de.greluc.homeinv.platform..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "de.greluc.homeinv.authorization..",
            "de.greluc.homeinv.bootstrap..",
            "de.greluc.homeinv.catalog..",
            "de.greluc.homeinv.identity..",
            "de.greluc.homeinv.inventory..",
            "de.greluc.homeinv.locations..",
            "de.greluc.homeinv.media..",
            "de.greluc.homeinv.rest..",
            "de.greluc.homeinv.search..",
            "de.greluc.homeinv.tenancy..")
        .because(
            "the shared kernel is shared by every block and owns none of their domains "
                + "(REQ-NFR-024)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("never let a record holding a secret print it")
  void secretsAreNotPrintable() {
    // Spring MVC logs the deserialised request body at DEBUG, through the type's
    // `toString`. A record gets one generated that prints every component, so
    // `LoginRequest` wrote passwords into the log for anybody who turned debug
    // logging on — which is what an operator does when something is wrong
    // (REQ-SEC-050).
    //
    // Records only, and that is the whole point rather than a convenience: an
    // ordinary class inherits `Object.toString`, which prints a hash code. A
    // record is the one shape that prints its contents unless somebody says
    // otherwise.
    ArchRuleDefinition.classes()
        .that(
            new DescribedPredicate<JavaClass>("are records holding a field named like a secret") {
              @Override
              public boolean test(JavaClass type) {
                boolean record =
                    type.getRawSuperclass()
                        .map(parent -> "java.lang.Record".equals(parent.getName()))
                        .orElse(false);
                return record
                    && type.getAllFields().stream()
                        .anyMatch(field -> SECRET_FIELD.matcher(field.getName()).find());
              }
            })
        .should(
            new ArchCondition<JavaClass>("declare a toString that does not print it") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                boolean declared =
                    type.getMethods().stream()
                        .anyMatch(
                            method ->
                                "toString".equals(method.getName())
                                    && method.getRawParameterTypes().isEmpty());
                if (!declared) {
                  events.add(
                      SimpleConditionEvent.violated(
                          type,
                          type.getName()
                              + " is a record holding a secret-shaped component and inherits the"
                              + " generated toString, which prints every one of them. Spring logs"
                              + " request bodies through it (REQ-SEC-050)."));
                }
              }
            })
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep every endpoint of ours in the access layer")
  void controllersLiveInTheAccessLayer() {
    // The companion to the rule above, and the reason PermissionInterceptor may
    // narrow itself to `de.greluc.homeinv.rest`: a controller anywhere else would
    // be one the interceptor lets through, silently, because it looks like a
    // handler the framework contributed. Two rules that each cover the other's
    // gap (REQ-SEC-023).
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideOutsideOfPackage("de.greluc.homeinv.rest..")
        .because(
            "PermissionInterceptor governs handlers in the access layer and lets framework "
                + "handlers through; a controller outside it would be neither (REQ-SEC-023)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("let only the authorization block answer whether somebody may")
  void onlyAuthorizationDecides() {
    // REQ-SEC-022 and ADR-0010: REST, GraphQL and gRPC are adapters that decide
    // nothing. They may DECLARE what is needed - the annotation is in the access
    // layer by design - but the evaluation happens in one place, so that a second
    // surface cannot grow a second set of rules.
    noClasses()
        .that()
        .resideOutsideOfPackages("de.greluc.homeinv.authorization..")
        .should()
        .callMethodWhere(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "reads a role's permissions directly",
                target ->
                    target.getTarget().getOwner().getFullName().equals(Role.class.getName())
                        && target.getTarget().getName().equals("permissions")))
        .because(
            "\"may this caller do this\" is answered in one place; reading the grant set "
                + "elsewhere is how a second answer appears (REQ-SEC-022, ADR-0010)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep every class under the one package root")
  void onePackageRoot() {
    // REQ-CON-001. The root is `de.greluc.homeinv` — the short form, not the
    // repository's long name (CLAUDE.md, "Display name is Home Inventory").
    // A class outside it compiles and runs perfectly; what it breaks is the
    // Modulith scan, which finds blocks by package, and every rule in this file,
    // which asks about that package and would simply not see it.
    //
    // The importer above already scans only that package, so this rule reads
    // THIS MODULE'S COMPILED OUTPUT instead — otherwise it could only ever
    // confirm what it was given. By path and not by package prefix: a prefix
    // wide enough to catch a stray class is also wide enough to catch the JDK.
    JavaClasses everything =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPath(Path.of("build", "classes", "java", "main"));

    List<String> strays =
        everything.stream()
            .map(JavaClass::getName)
            .filter(name -> !name.startsWith("de.greluc.homeinv."))
            .sorted()
            .toList();

    assertThat(strays)
        .as("classes outside the package root de.greluc.homeinv (REQ-CON-001)")
        .isEmpty();
  }

  @Test
  @DisplayName("let only the two named blocks resolve a plugin at instance level")
  void onlyAccountNotificationsResolveAtInstanceLevel() {
    // ADR-0066, amended by ADR-0067. The capability model has a second level so
    // that something reaching an account with no tenant is possible at all. It is
    // a widening, and a widening is only as narrow as its callers: with a named
    // list it stays what it was decided to be, with any caller it becomes a way
    // to reach a plugin without a tenant's consent.
    //
    // Two callers, and each had to argue for itself. `notification` raises the
    // security mail of REQ-NOTI-004 for an account that may be a member of
    // nothing; `identity` asks the optional breach service of REQ-SEC-011 about
    // a password chosen at registration or at a reset from the login page, where
    // there is no tenant either.
    //
    // The plugins block itself is excluded because that is where the method is
    // declared and implemented.
    noClasses()
        .that()
        .resideOutsideOfPackages(
            "de.greluc.homeinv.notification..",
            "de.greluc.homeinv.identity..",
            "de.greluc.homeinv.plugins..")
        .should()
        .callMethodWhere(
            DescribedPredicate.describe(
                "resolves a plugin for the instance rather than for a tenant",
                target ->
                    target
                            .getTarget()
                            .getOwner()
                            .getFullName()
                            .equals(ExtensionRegistry.class.getName())
                        && target.getTarget().getName().equals("lookupForInstance")))
        .because(
            "an instance-level resolution bypasses every tenant's consent by design, and only "
                + "the account notifications of REQ-NOTI-004 and the breach check of REQ-SEC-011 "
                + "are allowed to need that (ADR-0066, ADR-0067)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("let no request type be bound onto an entity")
  void requestsAreNotBoundOntoEntities() {
    // REQ-SEC-030. A controller that binds a request body onto an entity accepts
    // whatever fields that entity happens to have - including `version`,
    // `tenantId` and `deletedAt`, none of which a client may set. A dedicated
    // input type per use case is the only shape where the accepted fields are
    // visible in the signature.
    noClasses()
        .that()
        .resideInAPackage("de.greluc.homeinv.rest..")
        .should()
        .dependOnClassesThat()
        .areAnnotatedWith(Entity.class)
        .because(
            "a request bound onto an entity accepts every column it has, version and tenant "
                + "included (REQ-SEC-030)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("let no money ever touch a double or a float (REQ-NFR-070)")
  void moneyNeverTouchesABinaryFloat() {
    // Stated over the WHOLE application rather than over "the money path",
    // because the money path is not a package: a price reaches a request record,
    // a projection, a report and a total, and a rule that named those four would
    // be a rule that missed the fifth. Nothing here has ever needed a binary
    // float — measures are SI base units in `BigDecimal`, money is `Money` — so
    // the strict form costs nothing and cannot be quietly widened.
    fields()
        .should()
        .notHaveRawType(double.class)
        .andShould()
        .notHaveRawType(float.class)
        .because(
            "0.1 + 0.2 is not 0.3, and a price wrong in the seventh decimal is a price nobody "
                + "can reconcile (ADR-0025, REQ-NFR-070)")
        .check(CLASSES);

    methods()
        .should(
            new ArchCondition<JavaMethod>("declare no double or float") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                boolean binaryFloat =
                    isBinaryFloat(method.getRawReturnType())
                        || method.getRawParameterTypes().stream()
                            .anyMatch(ArchitectureRulesTest::isBinaryFloat);
                if (binaryFloat) {
                  events.add(
                      SimpleConditionEvent.violated(
                          method,
                          method.getFullName()
                              + " takes or returns a double or a float. A constructor, factory or "
                              + "helper that accepts one is how a binary float gets onto the money "
                              + "path (REQ-NFR-070)."));
                }
              }
            })
        .check(CLASSES);
  }

  /**
   * Whether a type is one of the two binary floating-point types.
   *
   * @param type the type
   * @return true for {@code double} and {@code float}
   */
  private static boolean isBinaryFloat(JavaClass type) {
    return type.isEquivalentTo(double.class) || type.isEquivalentTo(float.class);
  }

  @Test
  @DisplayName("keep SQL out of string concatenation")
  void sqlIsNeverConcatenated() {
    // REQ-SEC-031. Dynamic SQL goes through a checked builder whose field and
    // sort names come from an allowlist derived from `field_definition` - never
    // from user input. Anywhere else, a `+` joining a query to something that is
    // not a literal is an injection waiting for a value that came from a request.
    //
    // ArchUnit reads bytecode, where concatenation has already become an
    // invokedynamic and the literals are gone, so this reads the sources - the
    // only place the evidence survives.
    // The one sanctioned builder, and the requirement's own wording sanctions
            // it: "dynamic SQL goes through a CHECKED BUILDER whose field and sort
            // names come from an allowlist". `ImportSql` builds an upsert from a
            // table and its columns, every one of them a Java constant declared by
            // the block that owns the table -- and it refuses any name that is not
            // an identifier before it emits a statement, so the allowlist is
            // enforced in the code rather than assumed by a reader. Nothing it
            // touches has ever seen a request: the values all travel as `?`.
            //
            // The list is closed. A second entry needs the same two properties and
            // a reason written here beside this one.
            Set<String> checkedBuilders = Set.of("de/greluc/homeinv/portability/api/ImportSql.java");

    List<String> offenders = new ArrayList<>();

    // A query split across lines for readability is two literals joined by `+`,
    // and that is not what this rule is about. Adjacent literals are merged
    // first, so what remains is a literal joined to an *expression* - which is
    // the only shape a value can enter through.
    Pattern adjacentLiterals = Pattern.compile("\"\\s*\\+\\s*\"", Pattern.DOTALL);
    Pattern injectable =
        Pattern.compile(
            "\"[^\"]*\\b(select|insert\\s+into|update|delete\\s+from)\\b[^\"]*\"\\s*\\+",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    Path sources = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(sources)) {
      files
          .filter(file -> file.toString().endsWith(".java"))
          .forEach(
              file -> {
                try {
                  String merged = adjacentLiterals.matcher(Files.readString(file)).replaceAll("");
                  String name = sources.relativize(file).toString().replace('\\', '/');
                  if (injectable.matcher(merged).find() && !checkedBuilders.contains(name)) {
                    offenders.add(name);
                  }
                } catch (IOException unreadable) {
                  throw new UncheckedIOException(unreadable);
                }
              });
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }

    assertThat(offenders)
        .as(
            "SQL is parameterised; a query joined to an expression is an injection waiting for a "
                + "value that came from a request (REQ-SEC-031)")
        .isEmpty();
  }
  @Test
  @DisplayName("names no plugin of its own: being first-party buys nothing")
  void noPluginIsPrivilegedByItsName() {
    // ADR-0072 puts the five first-party plugins in `plugins/` in this
    // repository, and 09 §9.9 promises they are "installed, granted and revoked
    // exactly like a third party's -- no privileged path, because a privileged
    // path is what would eventually be used for something else".
    //
    // The cheapest way that promise breaks is a plugin ID in a string literal:
    // one `if (pluginId.equals("de.greluc.homeinv.plugin.smtp"))` and there is a
    // path only our code can take. So the core may not NAME one. It resolves a
    // port through `ExtensionRegistry` and asks `PluginRegistry.permits`; which
    // plugin answers is the operator's and the tenant's business.
    //
    // Comments and Javadoc are stripped first: a chapter reference or an example
    // manifest ID in prose is documentation, and this rule is about code.
    List<String> offenders = new ArrayList<>();
    Pattern blockComments = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    Pattern lineComments = Pattern.compile("//[^\\n]*");
    Pattern pluginId = Pattern.compile("\"de\\.greluc\\.homeinv\\.plugin\\.[a-z]");

    Path sources = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(sources)) {
      files
          .filter(file -> file.toString().endsWith(".java"))
          .forEach(
              file -> {
                try {
                  String code = Files.readString(file);
                  code = blockComments.matcher(code).replaceAll("");
                  code = lineComments.matcher(code).replaceAll("");
                  if (pluginId.matcher(code).find()) {
                    offenders.add(sources.relativize(file).toString().replace('\\', '/'));
                  }
                } catch (IOException unreadable) {
                  throw new UncheckedIOException(unreadable);
                }
              });
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }

    assertThat(offenders)
        .as(
            "the core names no plugin. A first-party plugin is installed, granted and revoked "
                + "exactly like a third party's (ADR-0072, 09 §9.9), and an id in a literal is how "
                + "that stops being true")
        .isEmpty();
  }

  @Test
  @DisplayName("keeps what dials a datastore at startup out of the one-shot roles")
  void nothingThatConnectsAtStartupLoadsInMigrateOrBootstrap() {
    // `migrate` and `bootstrap` are one-shot roles on the `internal` segment and
    // `deploy/services.yaml` says the same thing about both: "it talks to
    // PostgreSQL and to nothing else". They have no Valkey, no broker and no
    // search.
    //
    // A `SmartLifecycle` bean that CONNECTS when the context starts therefore
    // does not merely idle there -- it throws, the refresh is cancelled, and the
    // process exits non-zero. For `migrate` that stops the whole deployment,
    // because `api` and `worker` start only when it exits zero.
    //
    // That is what `liveChangeListener` did on 2026-09-21: a listener container
    // loaded in every profile, dialled `localhost:6379` in `migrate`, and took
    // the rootless smoke suite down under both runtimes. The unit tests could not
    // see it -- they run one context with everything present -- so the rule is
    // written here, where a second such bean will meet it before CI does.
    List<String> offenders = new ArrayList<>();
    Pattern container = Pattern.compile("RedisMessageListenerContainer\\s+\\w+\\s*\\(");
    Pattern excluded = Pattern.compile("@Profile\\(\"[^\"]*!\\s*migrate[^\"]*\"\\)");

    Path sources = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(sources)) {
      files
          .filter(file -> file.toString().endsWith(".java"))
          .forEach(
              file -> {
                try {
                  String code = Files.readString(file);
                  if (container.matcher(code).find() && !excluded.matcher(code).find()) {
                    offenders.add(sources.relativize(file).toString().replace('\\', '/'));
                  }
                } catch (IOException unreadable) {
                  throw new UncheckedIOException(unreadable);
                }
              });
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }

    assertThat(offenders)
        .as(
            "a bean that opens a connection when the context starts must not load in `migrate` or "
                + "`bootstrap`: both talk to PostgreSQL and nothing else, and a refused connection "
                + "there is not a degraded feature but a deployment that does not come up")
        .isEmpty();
  }
}
