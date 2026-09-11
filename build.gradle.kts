// Root build. Holds only what every project shares; the application's own
// dependencies live in app/build.gradle.kts and their versions in
// gradle/libs.versions.toml (REQ-NFR-029).

plugins {
    java
}

// Read once, at the root. Inside `subprojects {}` the name `libs` resolves to
// the generated per-project accessor, which would shadow a val of that name —
// hence the different name and the plain Int.
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
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Not `sourceCompatibility`: a toolchain makes the *compiler* the
            // pinned one, so a contributor launching Gradle on JDK 21 still
            // produces Java 25 bytecode rather than a confusing error.
            languageVersion = JavaLanguageVersion.of(javaLanguageVersion)
            // No vendor pin. The language version is what has to match; forcing
            // one distribution would make every contributor install a specific
            // JDK for identical bytecode. The container image is a separate
            // decision and does name its base (06 Deployment view).
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -processing: Lombok claims its own annotations and javac then reports every
        // Spring and JPA annotation as "unclaimed". The warning is noise, and noise
        // is how a build teaches people to stop reading warnings.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed") }
    }
}
