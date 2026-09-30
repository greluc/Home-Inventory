import com.google.protobuf.gradle.id
import java.math.BigDecimal
import java.util.zip.ZipFile
import java.util.Base64

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.cyclonedx)
    jacoco
}

extra["tomcat.version"] = "11.0.26"
extra["rabbit-amqp-client.version"] = "5.36.0"

extra["jackson-2-bom.version"] = "2.22.3"
extra["jackson-bom.version"] = "3.2.3"

dependencies {
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.bouncycastle)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.json.schema.validator)
    implementation(libs.jackson.dataformat.csv)
    implementation(libs.webauthn4j.core)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.spring.boot.starter.data.redis)

    implementation(libs.spring.boot.starter.graphql)

    implementation(libs.aspectjweaver)

    implementation(libs.context.propagation)

    implementation(libs.jakarta.annotation.api)

    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.boot.session.data.redis)

    implementation(project(":plugin-api"))

    implementation(libs.opensearch.java)

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jpa)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.modulith.events.amqp)
    runtimeOnly(libs.spring.modulith.actuator)
    runtimeOnly(libs.spring.modulith.observability)

    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    compileOnly(libs.javax.annotation.api)

    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.micrometer)

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
    testImplementation(libs.webauthn4j.test)
    testImplementation(libs.bouncycastle.pkix)
}

val generateTestUrlSigningKey by tasks.registering {
    val target = layout.buildDirectory.file("generated/test-secrets/test-url-signing.key")
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
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
        val material = "home-inv test credential key - not a secret - REQ-AUTH-002"
            .toByteArray(Charsets.UTF_8)
            .copyOf(32)
        file.writeText(Base64.getEncoder().encodeToString(material) + "\n")
    }
}

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

jacoco {
    toolVersion = "0.8.13"
}

val coveredClasses: FileCollection =
    fileTree(layout.buildDirectory.dir("classes/java/main")) {
        exclude("de/greluc/homeinv/plugin/v1/**")
    }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(coveredClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.80")
            }
        }

        rule {
            element = "PACKAGE"
            includes = listOf("de.greluc.homeinv.*.domain")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.90")
            }
        }

        rule {
            element = "CLASS"
            includes = listOf("de.greluc.homeinv.platform.Money")
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = BigDecimal.ONE
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.named<ProcessResources>("processResources") {
    val templates = rootProject.file("docs/reference/type-templates.yaml")
    doFirst {
        if (!templates.isFile) {
            throw GradleException(
                "docs/reference/type-templates.yaml is missing from this build context. The " +
                    "application reads it at startup (REQ-CORE-030); a build without it produces " +
                    "an artifact that cannot start."
            )
        }
    }
    from(templates) {
        into("catalog")
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
    from(rootProject.file("deploy/services.yaml")) {
        into("deploy")
    }
    from(rootProject.file("deploy/images/postgres/Dockerfile")) {
        into("deploy")
        rename { "postgres-image.Dockerfile" }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("spring.profiles.active", "test")

    maxHeapSize = "2g"
}


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
        ofSourceSet("main").forEach { task ->
            task.plugins {
                if (findByName("grpc") == null) {
                    create("grpc")
                }
            }
        }
    }
}

tasks.cyclonedxDirectBom {
    schemaVersion = org.cyclonedx.Version.VERSION_16
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    jsonOutput = layout.buildDirectory.file("sbom/home-inv-sbom.json")
    xmlOutput = layout.buildDirectory.file("sbom/home-inv-sbom.xml")
    includeConfigs = listOf("runtimeClasspath")
}

tasks.named("build") { dependsOn(tasks.named("cyclonedxDirectBom")) }

tasks.named("test") { dependsOn(tasks.named("cyclonedxDirectBom")) }

val sbomTravels by tasks.registering {
    description = "Fails when the boot jar carries no SBOM (REQ-CON-010)."
    group = "verification"

    val jar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
    dependsOn(jar)
    inputs.files(jar)

    doLast {
        val archive = jar.get().archiveFile.get().asFile
        val entry = "META-INF/sbom/application.cdx.json"
        val carried =
            ZipFile(archive).use { zip -> zip.getEntry(entry) != null }
        require(carried) {
            "$archive carries no $entry. REQ-CON-010 asks for the SBOM to travel with the " +
                "artifact, and Spring Boot's plugin put it there for free until now. Add it to " +
                "`bootJar` explicitly."
        }
    }
}

tasks.named("check") { dependsOn(sbomTravels) }

val buildCommit: String =
    providers.environmentVariable("HOMEINV_BUILD_COMMIT").orNull?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers.exec {
                commandLine("git", "rev-parse", "--short=12", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: "unknown"

springBoot {
    buildInfo {
        properties {
            additional.put("commit", buildCommit)
            additional.put("source", "https://github.com/greluc/Home-Inventory")
        }
    }
}

dependencies {
    spotbugsPlugins(libs.findsecbugs)
}

spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.LOW
    excludeFilter = rootProject.file("config/spotbugs-exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("sarif") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/${name}.sarif")
    }
    reports.create("html") { required = true }
}

tasks.named("spotbugsTest") { enabled = false }

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    classes = classes?.filter { !it.path.contains("plugin${File.separator}v1") }
}

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

    outputs.upToDateWhen { false }
}
