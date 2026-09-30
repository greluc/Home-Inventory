/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    java
}

val noCoreOnTheClasspath by tasks.registering {
    description = "Fails when anything from the core reaches plugin-api (REQ-CON-008, ADR-0018, REQ-PLG-009)."
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
            "plugin-api is Apache-2.0 and must depend on nothing in the core (ADR-0018), " +
                "but its runtime classpath holds: ${core.joinToString(", ")}. " +
                "A plugin author compiling against AGPL interfaces would find the AGPL reaching " +
                "into their plugin, which would make \"license your plugin however you like\" " +
                "a false promise."
        }
    }
}

tasks.named("check") { dependsOn(noCoreOnTheClasspath) }

dependencies {
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
