import com.google.protobuf.gradle.id
import java.util.Base64

// The Spring Boot application. One Gradle project, eighteen building blocks as
// packages — the boundary is enforced by Spring Modulith and ArchUnit, not by
// the build (ADR-0002, app/README.md).

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotbugs)
}

// Two transitive versions Spring Boot's BOM pins, raised past the ones trivy
// refuses. Both are SERVED to the internet by this application, which is why
// they are overridden here rather than waited out until the next Boot release:
//
//   * tomcat-embed-core 11.0.24 carries three CRITICAL advisories — a security
//     constraint bypass and two authentication bypasses (CVE-2026-65182,
//     CVE-2026-65905, CVE-2026-68525). It is the servlet container every request
//     arrives through.
//   * amqp-client 5.30.0 carries three HIGH ones (CVE-2026-63337, CVE-2026-69219,
//     CVE-2026-69220). Boot pins it BELOW what its own starter asks for — the
//     dependency graph reads `5.31.0 -> 5.30.0`.
//
// These properties are the BOM's own, so raising them moves every module that
// resolves through it rather than one edge of the graph. Remove an entry once
// Boot's managed version passes it; `./gradlew :app:dependencies` and the image
// scan both say when that is.
extra["tomcat.version"] = "11.0.25"
extra["rabbit-amqp-client.version"] = "5.35.0"

dependencies {
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    // Argon2id (REQ-SEC-010). Spring Security's encoder has no implementation of
    // its own and silently is not there without this.
    implementation(libs.bouncycastle)
    implementation(libs.spring.boot.starter.validation)
    // The attribute validator of the configurable type system (ADR-0056). It
    // checks the generated document itself, so the server and an offline client
    // reach the same verdict rather than two implementations of the same rules.
    implementation(libs.json.schema.validator)
    // WebAuthn/passkeys (REQ-AUTH-002). Configured with no metadata service and
    // no certificate-path validation: the core opens no outbound connection
    // (ADR-0026), and a self-hosted instance has nothing to attest against.
    implementation(libs.webauthn4j.core)
    // Only so javac can read webauthn4j's annotated signatures; its own POM marks
    // these provided, and nothing needs them at run time.
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.spring.boot.starter.actuator)

    // Sessions live in Valkey, not in the JVM heap: the api role scales
    // horizontally and a session must survive the instance that created it
    // (06 Deployment view).
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.session.data.redis)

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
    // The authenticator emulator, so the passkey ceremonies are proved against
    // real attestation and assertion objects rather than against a stub that
    // agrees with the code under test.
    testImplementation(libs.webauthn4j.test)
    testImplementation(libs.bouncycastle.pkix)
}

// The integration tests start PostgreSQL with the *production* role script, so
// that NOBYPASSRLS is the property under test rather than an assumption. Copying
// it in at build time keeps the test independent of the working directory and
// makes a drifted copy impossible - there is only one file.
// The URL signing key the tests sign media URLs and pagination cursors with
// (REQ-SEC-106). GENERATED rather than committed, and that is the whole point:
// `.gitignore` refuses `*.key`, so the file every container-based test needs has
// never been in the repository and CI has never had it — every one of those tests
// failed there while passing on the machine that had written the file once, by
// hand, months earlier.
//
// The value is a fixed, obviously-fake literal. It must be: `CLAUDE.md` says never
// use real credentials in tests, and a key that is regenerated at random would
// make a signature from one run unverifiable in the next, which is a flaky test
// nobody would enjoy diagnosing.
val generateTestUrlSigningKey by tasks.registering {
    val target = layout.buildDirectory.file("generated/test-secrets/test-url-signing.key")
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        // 48 bytes, comfortably over UrlSigningKey's 32-byte floor, written as the
        // base64 line a secret manager would hand over — which is the shape the
        // production path reads, so the tests exercise the same branch.
        val material = "home-inv test url signing key - not a secret - REQ-SEC-106"
            .toByteArray(Charsets.UTF_8)
            .copyOf(48)
        file.writeText(Base64.getEncoder().encodeToString(material) + "\n")
    }
}

val generateTestCredentialKey by tasks.registering {
    val target = layout.buildDirectory.file("generated/test-secrets/test-credential.key")
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        // 32 bytes, which is CredentialKey's AES-256 floor, as the base64 line a
        // secret manager would hand over. A fixed value on purpose: it seals
        // nothing that outlives a test container, and a random one would make a
        // failure depend on which run wrote it.
        val material = "home-inv test credential key - not a secret - REQ-AUTH-002"
            .toByteArray(Charsets.UTF_8)
            .copyOf(32)
        file.writeText(Base64.getEncoder().encodeToString(material) + "\n")
    }
}

// The master keys of ADR-0019, in the two-file shape REQ-SEC-049 needs: an
// active version and the one below it, so a test can rotate and prove that
// re-wrapping touches no ciphertext. Fixed values, like every key above: they
// seal nothing that outlives a test container, and a random one would make a
// failure depend on which run wrote it.
val generateTestDataEncryptionKeys by tasks.registering {
    val active = layout.buildDirectory.file("generated/test-secrets/test-data-encryption.key")
    val previous =
        layout.buildDirectory.file("generated/test-secrets/test-data-encryption-previous.key")
    outputs.files(active, previous)
    doLast {
        listOf(
            active to "home-inv test data encryption master key v2 - not a secret",
            previous to "home-inv test data encryption master key v1 - not a secret",
        ).forEach { (target, phrase) ->
            val file = target.get().asFile
            file.parentFile.mkdirs()
            file.writeText(
                Base64.getEncoder()
                    .encodeToString(phrase.toByteArray(Charsets.UTF_8).copyOf(32)) + "\n"
            )
        }
    }
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(generateTestUrlSigningKey, generateTestCredentialKey, generateTestDataEncryptionKeys)
    from(generateTestUrlSigningKey.map { it.outputs.files.singleFile }) {
        into("db")
    }
    from(generateTestCredentialKey.map { it.outputs.files.singleFile }) {
        into("db")
    }
    from(generateTestDataEncryptionKeys.map { it.outputs.files }) {
        into("db")
    }
    from(rootProject.file("deploy/postgres/initdb/00-roles.sql")) {
        into("db")
    }
    // And the service matrix, for the same reason: the tests take their image
    // coordinates from the file the deployment is generated from, so "the same
    // images as production" (REQ-NFR-027) is one list rather than two kept in
    // step by hand. They were not in step - the tests ran rabbitmq:4-alpine
    // against a deployment running 4-management-alpine.
    from(rootProject.file("deploy/services.yaml")) {
        into("deploy")
    }
    // The base the first-party postgres image is built FROM. The tests cannot pull
    // ghcr.io/greluc/home-inv-postgres - it is built from this repository, not
    // published - so they run its base and copy the same role script in. The base
    // is named in one file, and this is that file.
    from(rootProject.file("deploy/images/postgres/Dockerfile")) {
        into("deploy")
        rename { "postgres-image.Dockerfile" }
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
