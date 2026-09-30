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

  /**
   * The one {@code @RestController} that is not in the published contract.
   *
   * <p>Spring's error dispatcher. It answers {@code /error} — the path the container forwards to,
   * which no client calls and which springdoc leaves out of the document, as {@code api/openapi.yaml}
   * shows by not containing it. A tag on it would name a group with nothing in it.
   */
  private static final Set<String> UNPUBLISHED = Set.of("ProblemErrorController");

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
    fields()
        .should()
        .notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
        .because("constructor injection only (CLAUDE.md, Conventions)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep SQL out of the domain and the application layer")
  void sqlLivesInInfrastructure() {
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
  @DisplayName("give every published controller a tag it chose itself")
  void everyControllerCarriesAChosenTag() {
    List<String> untagged = new ArrayList<>();

    for (JavaClass controller : CLASSES) {
      if (!controller.getPackageName().startsWith("de.greluc.homeinv.rest")) {
        continue;
      }
      if (!controller.isAnnotatedWith(RestController.class)) {
        continue;
      }
      if (UNPUBLISHED.contains(controller.getSimpleName())) {
        continue;
      }
      if (!controller.isAnnotatedWith(io.swagger.v3.oas.annotations.tags.Tag.class)) {
        untagged.add(controller.getSimpleName());
      }
    }

    assertThat(untagged)
        .as(
            "every controller in the contract carries @Tag with a name it chose and a sentence "
                + "describing it. Without one the generated clients of REQ-API-002 are one class "
                + "each, and springdoc's inferred alternative publishes our class names")
        .isEmpty();
  }

  @Test
  @DisplayName("declare what every GraphQL resolver needs, one field at a time")
  void everyResolverDeclaresItsAccess() {
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
            Set<String> checkedBuilders = Set.of("de/greluc/homeinv/portability/api/ImportSql.java");

    List<String> offenders = new ArrayList<>();

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
