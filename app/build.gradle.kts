// The Spring Boot application. One Gradle project, eighteen building blocks as
// packages — the boundary is enforced by Spring Modulith and ArchUnit, not by
// the build (ADR-0002, app/README.md).

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    // Argon2id (REQ-SEC-010). Spring Security's encoder has no implementation of
    // its own and silently is not there without this.
    implementation(libs.bouncycastle)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Sessions live in Valkey, not in the JVM heap: the api role scales
    // horizontally and a session must survive the instance that created it
    // (06 Deployment view).
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.session.data.redis)

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jpa)
    runtimeOnly(libs.spring.modulith.actuator)
    runtimeOnly(libs.spring.modulith.observability)

    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit5)
}

// The integration tests start PostgreSQL with the *production* role script, so
// that NOBYPASSRLS is the property under test rather than an assumption. Copying
// it in at build time keeps the test independent of the working directory and
// makes a drifted copy impossible - there is only one file.
tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("deploy/postgres/initdb/00-roles.sql")) {
        into("db")
    }
}

tasks.withType<Test>().configureEach {
    // Integration tests run against the same image digests as production.
    // H2 is forbidden: JSONB, ltree and row-level security behave differently,
    // which is exactly where the bugs would be (CLAUDE.md, REQ-NFR-030).
    systemProperty("spring.profiles.active", "test")
}

