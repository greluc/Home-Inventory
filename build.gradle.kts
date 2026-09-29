plugins {
    java
}

val javaLanguageVersion =
    extensions.getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("java").orElseThrow()
        .requiredVersion.toInt()

allprojects {
    group = "de.greluc.homeinv"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    if (name == "api-client-kotlin") {
        return@subprojects
    }

    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaLanguageVersion)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed") }
    }
}
