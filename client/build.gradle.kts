val jacksonVersion: String by extra

plugins {
    `java-library`
    `maven-publish`
    signing
}

// ResearchZosho's Java client — The Librarian's library protocol over HTTP. Transport and types only:
// Jackson for JSON, java.net.http for the wire, nothing of the library itself. A patron embeds this jar.
// Artifact: org.researchzosho:client, on Maven Central.
dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}")

    // tests start the real daemon in-process against a temporary library
    testImplementation(project(":librarian"))
}

java {
    // Maven Central requires both a sources jar and a javadoc jar
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    (options as StandardJavadocDocletOptions).addStringOption("Xmaxwarns", "1")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "client"
            from(components["java"])
            pom {
                name = "ResearchZosho client"
                description = "The Java client for ResearchZosho's library protocol: the Librarian's tools over HTTP, with bearer tokens. Transport and types; Jackson only."
                url = "https://github.com/Wyrdsekai/researchzosho"
                developers {
                    developer {
                        id = "wyrdsekai"
                        name = "Wyrdsekai"
                        url = "https://github.com/Wyrdsekai"
                    }
                }
                scm {
                    url = "https://github.com/Wyrdsekai/researchzosho"
                    connection = "scm:git:https://github.com/Wyrdsekai/researchzosho.git"
                    developerConnection = "scm:git:ssh://git@github.com/Wyrdsekai/researchzosho.git"
                }
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
            }
        }
    }
}

// "Jackson only", checked: a published POM that quietly picked up a logger or the librarian itself would
// hand every patron the library's dependencies. Fail the build instead of finding out from a consumer.
tasks.register("checkJacksonOnly") {
    val runtime = configurations.named("runtimeClasspath")
    doLast {
        val strangers = runtime.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
            .filter { it.group != "com.fasterxml.jackson.core" }
        if (strangers.isNotEmpty()) throw GradleException("the client must depend on Jackson only, but resolved: $strangers")
    }
}
tasks.named("check") { dependsOn("checkJacksonOnly") }

// Every artifact signed, as Central requires. Never breaks a plain build: signing is required only when a
// publish task runs, and with no keyring properties it falls back to the gpg agent, which keeps the
// passphrase out of gradle.properties.
signing {
    val signingKeyId: String? = findProperty("signing.keyId") as String?
    val inMemoryKey: String? = findProperty("signingKey") as String?
    val inMemoryPass: String? = findProperty("signingPassword") as String?
    setRequired({ gradle.taskGraph.allTasks.any { it.name.startsWith("publish") } })
    if (inMemoryKey != null) useInMemoryPgpKeys(inMemoryKey, inMemoryPass)
    else if (signingKeyId == null) useGpgCmd()
    sign(publishing.publications["maven"])
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
    environment("RESEARCHZOSHO_EMBED", "off")
    environment("RESEARCHZOSHO_RERANK", "off")
    environment("RESEARCHZOSHO_CTX", "8192")
}
