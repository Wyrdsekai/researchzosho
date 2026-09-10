// Root build — ResearchZosho (研究蔵書). Java 21 floor, Gradle Kotlin DSL, two modules.
val jacksonVersion = "2.21.1"
// the root carries the version too: the Central bundle file is named from it
version = "0.1.3"

subprojects {
    apply(plugin = "java")

    group = "org.researchzosho"
    version = "0.1.3"

    // 21 is the FLOOR, stated as `options.release` rather than a toolchain pin: a Gradle toolchain is an
    // EXACT match, and a box holding only JDK 25 could not build at all under `languageVersion = 21`
    // (measured on macOS with current Temurin). This compiles with whatever JDK runs the build while
    // emitting Java 21 bytecode and checking against the Java 21 API.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
    }

    extra["jacksonVersion"] = jacksonVersion

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// ── Publishing the client to Maven Central ─────────────────────────────────────────────────────────
//
// The same shape as hermod's: no third-party publishing plugin. The signed artifacts are staged into a
// local Maven-layout directory, zipped, and the zip is uploaded to the Central Publisher Portal
// (docs/RELEASING.md §7). Only the client is published; the librarian ships as the tarball.
//
//   ./gradlew centralBundle   ->  build/central/researchzosho-client-<version>.zip

val centralStaging = layout.buildDirectory.dir("central-staging")

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "centralStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
                }
            }
        }
    }
    // Publish from the PUBLIC tree, so the artifact is built from the source people can read. The export
    // drops scripts/export-public.sh, so its presence means "this is the private tree". Local publishing
    // (mavenLocal) stays allowed anywhere: it is how you rehearse.
    tasks.matching { it.name.startsWith("publish") && !it.name.contains("MavenLocal") }.configureEach {
        doFirst {
            if (rootProject.file("scripts/export-public.sh").exists()) {
                throw GradleException("Refusing to publish from the private tree. Run scripts/export-public.sh, then publish from ../researchzosho-oss.")
            }
        }
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Stage the signed client artifacts and zip them into a Central Portal bundle."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("publishAllPublicationsToCentralStagingRepository") })
    from(centralStaging)
    exclude("**/maven-metadata*")                                                        // not part of a release bundle
    exclude("**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")      // the Portal rejects checksums of signatures
    archiveFileName = "researchzosho-client-${version}.zip"
    destinationDirectory = layout.buildDirectory.dir("central")
    doLast { logger.lifecycle("bundle: ${archiveFile.get().asFile}") }
}
