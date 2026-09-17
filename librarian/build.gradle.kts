val jacksonVersion: String by extra

plugins {
    `java-library`
    application
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    // Jackson — the library protocol's JSON, the drive's chat JSON, the ledger.
    implementation("com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}")

    // Apache Lucene — the catalog index (BM25 + HNSW dense). 10.4.0 matches the on-disk codec of
    // existing libraries; changing it means a rebuild of every library out there.
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    implementation("org.apache.lucene:lucene-queryparser:10.4.0")

    // Apache PDFBox — PDF → text for web_fetch, the raw tier and `researchzosho add`. Pure Java, so it
    // works on all three platforms without poppler on PATH. The other document formats (DOCX, PPTX, ODT,
    // EPUB) are zip+XML and need no library — see tools/DocText.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // The terminal chat's line editor: history with the arrow keys, Ctrl-R, notices above the prompt. The same version CodeZaiku ships.
    implementation("org.jline:jline:4.0.4")

    // Databases the owner gives read access to: PostgreSQL and MySQL/MariaDB ship in the box beside SQLite (about 2 MB together).
    // MariaDB's driver speaks to MySQL too and is LGPL; Oracle's MySQL driver is GPL. SQL Server, MongoDB and DuckDB are fetched on demand.
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    // Calibre's metadata.db, read only (a Calibre library as a list of books)
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    // HTTP: java.net.http, no dependency. The daemon: com.sun.net.httpserver, no dependency.
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }

    // Some tests assert the docs agree with the code (the usage text, the contract's normative sections).
    inputs.files(fileTree(rootDir.resolve("docs")) { include("**/*.md") })
        .withPropertyName("docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The suite must never read the developer's household config (a live embedder was reached once);
    // and resolving the drive's context window throws when no model answers, so pin it.
    environment("RESEARCHZOSHO_CTX", "8192")
    environment("RESEARCHZOSHO_EMBED", "off")
    environment("RESEARCHZOSHO_RERANK", "off")
    environment("RESEARCHZOSHO_ENRICH", "both")
}

tasks.jar {
    manifest { attributes("Implementation-Title" to "ResearchZosho", "Implementation-Version" to project.version) }
}

application {
    mainClass.set("org.researchzosho.Main")
    applicationName = "researchzosho"
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        // Lucene calls a restricted Linker method; without this the JVM prints four WARNING lines
        // before anything else, and a caller quoting stderr quotes those instead of the reason.
        "--enable-native-access=ALL-UNNAMED",
    )
}

// `zosho`, the short form, ships beside `researchzosho` in the distribution.
val zoshoScript = tasks.register<CreateStartScripts>("zoshoScript") {
    mainClass.set("org.researchzosho.Main")
    applicationName = "zosho"
    outputDir = layout.buildDirectory.dir("scripts-zosho").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    defaultJvmOpts = application.applicationDefaultJvmArgs
}

/** README and LICENSE live under docs/public/ in the private tree and at the ROOT of the exported one. */
fun docFile(name: String): File =
    listOf(rootProject.file(name), rootProject.file("docs/public/$name")).firstOrNull { it.isFile }
        ?: error("neither $name nor docs/public/$name exists — the distribution would ship without it")

distributions {
    main {
        contents {
            from(docFile("README.md")) { into("") }
            from(docFile("LICENSE")) { into("") }
            from(zoshoScript) { into("bin"); filePermissions { unix("rwxr-xr-x") } }
        }
    }
}

// The runtime classpath, for the dev launcher (bin/researchzosho) which caches it.
tasks.register("printCp") {
    val cp = sourceSets["main"].runtimeClasspath
    doLast { println(cp.asPath) }
}

// A run from the source tree has no jar manifest; the version goes into a resource so the pages and --version can say it.
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    doLast { file("$destinationDir/org/researchzosho/version.txt").apply { parentFile.mkdirs(); writeText(v) } }
}
