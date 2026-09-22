// Lets Gradle fetch a JDK 25 for anyone who has none, so `./gradlew build`
// works on a fresh checkout rather than failing with a toolchain error.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The repository name is the one long-form exception; every technical
// identifier uses the short form (CLAUDE.md). The Gradle root project is
// therefore `home-inv`, not `Home-Inventory`.
rootProject.name = "home-inv"

// The blocks are *packages*, not Gradle modules: "the package structure is the
// architecture" (app/README.md, ADR-0002). Splitting them into subprojects
// would make the boundary a build concern, and Spring Modulith plus ArchUnit
// already enforce it where it belongs — in the code.
include(":app")

// Apache-2.0 and depends on nothing in the core, which is the promise ADR-0018
// makes to plugin authors and the reason it is a project of its own rather than
// a package in :app. A Gradle project is how the "no dependency on core" rule
// becomes something a build can check instead of something a reviewer has to.
include(":plugin-api")

// The one first-party plugin in Java (ADR-0072). A Gradle project rather than a
// package, for the same reason `plugin-api` is one: it ships as its own container,
// it compiles against the protobuf contract in `proto/` and it depends on nothing
// in the core -- which a project makes checkable and a package would not.
//
// The other four plugins are Rust and are built by cargo, beside this build rather
// than inside it.
include(":plugins:oidc")

// The generated Kotlin client of REQ-API-002, beside the document it is generated
// from. A Gradle project because it has to COMPILE in CI -- that is the
// requirement's acceptance criterion -- and a project of its own because it is
// Kotlin Multiplatform and shares no toolchain with the Java above it.
include(":api-client-kotlin")
project(":api-client-kotlin").projectDir = file("api/clients/kotlin")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
