plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "home-inv"

include(":app")

include(":plugin-api")

include(":plugins:oidc")

include(":api-client-kotlin")
project(":api-client-kotlin").projectDir = file("api/clients/kotlin")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
