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

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
