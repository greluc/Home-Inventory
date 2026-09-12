import com.google.protobuf.gradle.id

// The Spring Boot application. One Gradle project, eighteen building blocks as
// packages — the boundary is enforced by Spring Modulith and ArchUnit, not by
// the build (ADR-0002, app/README.md).

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotbugs)
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
    // The worker generates media derivatives from an event `api` publishes, and
    // the two are separate processes (ADR-0051). The outbox in
    // `outbox.event_publication` is the source of truth; AMQP is the delivery.
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.modulith.events.amqp)
    runtimeOnly(libs.spring.modulith.actuator)
    runtimeOnly(libs.spring.modulith.observability)

    // The BlobStore contract lives in `proto/` and is Apache-2.0 (ADR-0018). The
    // in-core `filesystem` adapter is a CLIENT of the in-deployment `blobstore`
    // service, which speaks it (ADR-0043) - so the core generates a client here
    // and depends on nothing in that directory beyond the generated code.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    // grpc-java's generated stubs reference javax.annotation.Generated, which
    // left the JDK in 11. Without it the generated sources do not compile.
    compileOnly(libs.javax.annotation.api)

    // Generates the OpenAPI document from the running application (ADR-0049).
    // `implementation` rather than a test dependency: the document is produced
    // from the real context, and a version that existed only in tests would be a
    // document describing a program that is not the one that ships.
    implementation(libs.springdoc.openapi.webmvc)
    implementation(libs.therapi.javadoc)
    annotationProcessor(libs.therapi.javadoc.scribe)

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
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.bouncycastle.pkix)
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


// The BlobStore contract is generated from `proto/`, which is shared with the
// plugin SDKs and with the Rust service in `blobstore/`. One definition, three
// implementations, and none of them may drift from it (ADR-0043).
sourceSets {
    named("main") {
        proto { srcDir(rootProject.file("proto")) }
    }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        id("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
    }
    generateProtoTasks {
        // Only the main source set: the contract is generated once, and asking
        // for it in `test` as well would produce a second copy of every class on
        // the test classpath.
        //
        // The service stubs, not only the messages. Without the `grpc` plugin the
        // build produces the request and response types and no client at all,
        // which compiles and does nothing.
        ofSourceSet("main").forEach { task ->
            task.plugins {
                // Guarded, because the protobuf plugin evaluates this block more
                // than once during configuration and registering the same name
                // twice is an error rather than a no-op.
                if (findByName("grpc") == null) {
                    create("grpc")
                }
            }
        }
    }
}

// SpotBugs with find-sec-bugs (REQ-SEC-076). It reads bytecode, so it sees what
// the compiler produced rather than what the source looked like — which is the
// point for a class of finding that lives in what a framework generates around
// the code somebody wrote.
dependencies {
    spotbugsPlugins(libs.findsecbugs)
}

spotbugs {
    // `max` effort, `low` threshold: this runs on a codebase of a few thousand
    // lines, where the whole analysis costs seconds, and a threshold that hides
    // findings is a threshold that hides the one that mattered.
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.LOW
    excludeFilter = rootProject.file("config/spotbugs-exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("sarif") {
        // SARIF, so GitHub shows a finding on the line that caused it rather than
        // in a log somebody has to open.
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/${name}.sarif")
    }
    reports.create("html") { required = true }
}

// The TEST sources are not analysed. SpotBugs runs to find what could be attacked
// in the code that SHIPS, and test code is neither shipped nor reachable — while
// it is full of the shapes find-sec-bugs is designed to shout about: a throwaway
// certificate authority, a hardcoded fixture password, a temporary file named by
// the test. Analysing it would bury the findings that matter under the ones that
// cannot (REQ-SEC-076).
tasks.named("spotbugsTest") { enabled = false }

// The generated protobuf classes are not ours to fix, and SpotBugs has plenty to
// say about generated builders. Analysing them would bury every real finding.
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    classes = classes?.filter { !it.path.contains("plugin${File.separator}v1") }
}

// The OpenAPI document is a build output that is committed (ADR-0049), like the
// Quadlet units and `web/nginx/default.conf`. `:app:test` fails while it and the
// code disagree; this task is how an intended change is written down.
// The check REQ-API-001 names. It is the same test the `test` task already runs —
// registered under its own name because a requirement that names a command is a
// requirement somebody will type, and one that does not exist reads as a project
// that stopped caring.
tasks.register<Test>("openApiCheck") {
    description = "Fails while api/openapi.yaml and the implementation disagree."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("de.greluc.homeinv.OpenApiDocumentIT") }

    systemProperty("spring.profiles.active", "test")

    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateOpenApi") {
    description = "Regenerates api/openapi.yaml from the implementation."
    group = "documentation"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("de.greluc.homeinv.OpenApiDocumentIT") }

    systemProperty("spring.profiles.active", "test")
    systemProperty("homeinv.openapi.regenerate", "true")

    // Never up to date: the point of running it is to look at the repository
    // again, and its inputs are the whole application.
    outputs.upToDateWhen { false }
}
