/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

// The generated Kotlin Multiplatform client (REQ-API-002).
//
// EVERYTHING UNDER src/ IS GENERATED from api/openapi.yaml by
// tools/kotlin_client.py and committed, exactly as the OpenAPI document itself,
// the TypeScript client, the Quadlet units and the ingress configuration are: a
// contract change is then a diff in a pull request. CI runs
// `python tools/kotlin_client.py --check` and fails while the two disagree.
//
// THIS FILE IS NOT GENERATED. The generator writes a sample build file naming
// its own versions; this one names the same dependencies through the version
// catalogue, because a version in a generated file is a version nothing
// renovates (REQ-NFR-029).
//
// ONLY THE JVM TARGET IS BUILT. The source set is `commonMain`, which is what
// makes the client usable from the shared code of the apps in ADR-0013 -- the
// point of generating a multiplatform client rather than a plain Kotlin one. An
// iOS target needs a Mac, and CI is Linux; adding one here would make the build
// pass on one contributor's machine and fail on the next.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
        }
        jvmMain.dependencies {
            // An engine, so the client is runnable and not only compilable. CIO is
            // the one that is pure Kotlin and needs nothing from the platform.
            implementation(libs.ktor.client.cio)
        }
    }
}
