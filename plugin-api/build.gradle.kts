/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */

// The interfaces a plugin implements, and the manifest model both sides read.
//
// Apache-2.0 and NOT AGPL, and the separation is structural rather than a
// statement of intent (ADR-0018): a plugin author compiling against AGPL
// interfaces would find the AGPL reaching into their plugin, which would make
// "license your plugin however you like" a false promise.
//
// So this project depends on NOTHING in the core, and on nothing that is not
// permissively licensed. `PluginApiBoundaryTest` in :app checks the first half
// and fails the build on a violation; the second half is why the dependency list
// below is as short as it is.

plugins {
    java
}

// The promise of ADR-0018, made mechanical.
//
// A plugin author compiling against AGPL interfaces would find the AGPL reaching
// into their plugin. The separation is therefore checked rather than intended:
// this task fails the build if anything from the core ever reaches this
// project's classpath, by any route including a transitive one.
//
// It runs as part of `check`, so `./gradlew build` enforces it and nobody has to
// remember to.
val noCoreOnTheClasspath by tasks.registering {
    description = "Fails when anything from the core reaches plugin-api (ADR-0018, REQ-PLG-009)."
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
    // SnakeYAML, Apache-2.0. A manifest is YAML (09 §9.3) and this is the one
    // thing this project cannot do without. Deliberately NOT Jackson: the core
    // is on Jackson 3 and an SDK user is not, and a manifest parser that forced
    // a JSON stack on a plugin author would be the coupling this module exists
    // to avoid.
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
