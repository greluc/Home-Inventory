import com.google.protobuf.gradle.id

/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

plugins {
    application
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.cyclonedx)
}

application {
    mainClass = "de.greluc.homeinv.plugin.oidc.Main"
}

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

tasks.cyclonedxDirectBom {
    schemaVersion = org.cyclonedx.Version.VERSION_16
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    jsonOutput = layout.buildDirectory.file("sbom/home-inv-plugin-oidc-sbom.json")
    xmlOutput = layout.buildDirectory.file("sbom/home-inv-plugin-oidc-sbom.xml")
    includeConfigs = listOf("runtimeClasspath")
}

tasks.named("build") { dependsOn(tasks.named("cyclonedxDirectBom")) }

tasks.named("build") { dependsOn(tasks.named("installDist")) }

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

dependencies {
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    compileOnly(libs.javax.annotation.api)

    implementation(libs.nimbus.jose.jwt)

    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
