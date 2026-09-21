import com.google.protobuf.gradle.id

/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

// `plugin-oidc` — federated sign-in against an OIDC provider.
//
// AGPL-3.0-or-later like the rest of the first-party code (ADR-0072). The
// CONTRACT it compiles against — `proto/` — is Apache-2.0, which is the promise
// to a third-party plugin author and not to us.
//
// It depends on nothing in the core, and the check below says so the way
// `plugin-api`'s does: a plugin that reached into the application would be a
// privileged path, and a privileged path is what eventually gets used for
// something else (09 §9.9).

plugins {
    application
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotbugs)
}

application {
    mainClass = "de.greluc.homeinv.plugin.oidc.Main"
}

// The same promise ADR-0018 makes for `plugin-api`, checked from this side.
val noCoreOnTheClasspath by tasks.registering {
    description = "Fails when anything from the core reaches plugin-oidc (ADR-0072, REQ-PLG-009)."
    group = "verification"

    val classpath = configurations.named("runtimeClasspath")
    val projectName = project.name
    inputs.files(classpath)

    doLast {
        val core =
            classpath.get().resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { it.group == "de.greluc.homeinv" && it.name != projectName }
                .map { "${it.group}:${it.name}" }
                .distinct()
        require(core.isEmpty()) {
            "A first-party plugin is installed exactly like a third party's and depends on the " +
                "published contract alone (ADR-0072), but this classpath holds: " +
                core.joinToString(", ") + ". Being in this repository buys a plugin nothing."
        }
    }
}

tasks.named("check") { dependsOn(noCoreOnTheClasspath) }

sourceSets {
    named("main") {
        // The contract itself, from the repository root. The same directory
        // `:app` and the four Rust plugins read, because there is one contract.
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
                // Guarded for the reason `:app` guards it: the protobuf plugin
                // evaluates this block more than once during configuration, and
                // registering the same name twice is an error rather than a no-op.
                if (findByName("grpc") == null) {
                    create("grpc")
                }
            }
        }
    }
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

// The same two exclusions `:app` makes, for the same two reasons: test code is
// not shipped, and generated protobuf classes are not ours to fix.
tasks.named("spotbugsTest") { enabled = false }

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    classes = classes?.filter { !it.path.contains("plugin${File.separator}v1") }
}

dependencies {
    // gRPC towards the core, over mTLS. `netty-shaded` rather than plain netty:
    // this is a container with one job and a shaded transport is one fewer
    // version to keep in step with something else.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    // `javax.annotation.Generated`, which grpc-java's generated stubs reference
    // and which left the JDK in 11.
    compileOnly(libs.javax.annotation.api)

    // THE REASON THIS PLUGIN IS IN JAVA (ADR-0072). ID-token validation is where
    // an audited library outweighs an image size: signature, issuer, audience,
    // expiry and nonce, against a key set that rotates.
    implementation(libs.nimbus.jose.jwt)

    // A plain process has no Spring Boot to bring logging along.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
