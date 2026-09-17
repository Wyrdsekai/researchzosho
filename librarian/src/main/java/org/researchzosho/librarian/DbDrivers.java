package org.researchzosho.librarian;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The database drivers. SQLite, PostgreSQL and MySQL/MariaDB ship with the program. SQL Server, MongoDB
 * and DuckDB are fetched when the person asks for them, from Maven Central, each jar checked against a
 * pinned SHA-256, into {@code ~/.researchzosho/drivers/}. A fetched driver is loaded from its own class
 * loader, so it never mixes with the program's own classes.
 */
public final class DbDrivers {

    /** One jar of an on-demand driver. */
    public record Jar(String path, String sha256) {
        String file() { return path.substring(path.lastIndexOf('/') + 1); }
    }

    /** A kind of database: its name, the class that drives it, whether it ships in the box, and the jars to fetch when it does not. */
    public record Kind(String name, String label, String driverClass, boolean inBox, List<Jar> jars, String sizeNote) { }

    static final String CENTRAL = org.researchzosho.Config.get("RESEARCHZOSHO_DRIVER_REPO", "https://repo1.maven.org/maven2/");

    public static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("sqlite", new Kind("sqlite", "SQLite", "org.sqlite.JDBC", true, List.of(), ""));
        KINDS.put("postgres", new Kind("postgres", "PostgreSQL", "org.postgresql.Driver", true, List.of(), ""));
        KINDS.put("mysql", new Kind("mysql", "MySQL or MariaDB", "org.mariadb.jdbc.Driver", true, List.of(), ""));
        KINDS.put("sqlserver", new Kind("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", false,
                List.of(new Jar("com/microsoft/sqlserver/mssql-jdbc/13.6.0.jre11/mssql-jdbc-13.6.0.jre11.jar", "4c566e4022c4dcf1489aeb639fe49a0f6ffae3d796fdc2f56b5b8882d4c3bf5a")), "1.5 MB"));
        KINDS.put("mongo", new Kind("mongo", "MongoDB", "com.mongodb.client.MongoClients", false,
                List.of(new Jar("org/mongodb/mongodb-driver-sync/5.11.1/mongodb-driver-sync-5.11.1.jar", "3485ae7f4cb6b5a84a3c50c98ed984e4c317d0d346048441e6abcea14a16e8f6"),
                        new Jar("org/mongodb/mongodb-driver-core/5.11.1/mongodb-driver-core-5.11.1.jar", "39ea5514c2439914e53f0e057cf4015364cc20bda50be71c13e1b46a209638c6"),
                        new Jar("org/mongodb/bson/5.11.1/bson-5.11.1.jar", "daea840f3430618043f433d3a6cbf21404a388386f9fcc0bc2fd3a450aa74594")), "2.6 MB"));
        KINDS.put("duckdb", new Kind("duckdb", "DuckDB (also reads CSV, Parquet and JSON files)", "org.duckdb.DuckDBDriver", false,
                List.of(new Jar("org/duckdb/duckdb_jdbc/1.5.5.1/duckdb_jdbc-1.5.5.1.jar", "22343dd258db1b0b51d37afc776c8dff5b19282829fa5b47f7d5d6fe02b3377a")), "82 MB"));
    }

    private static final Map<String, ClassLoader> LOADERS = new java.util.concurrent.ConcurrentHashMap<>();

    private DbDrivers() { }

    public static Path dir() { return org.researchzosho.Config.userConfigPath().toAbsolutePath().resolveSibling("drivers"); }

    public static Kind kind(String name) { return KINDS.get(name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)); }

    /** Whether the kind's driver can be loaded now. */
    public static boolean installed(Kind k) {
        if (k.inBox()) return true;
        for (Jar j : k.jars()) if (!Files.isRegularFile(dir().resolve(j.file()))) return false;
        return true;
    }

    /** Fetch an on-demand driver. Each jar is checked against its pinned SHA-256 before it is kept. */
    public static List<String> install(Kind k) throws IOException {
        List<String> done = new ArrayList<>();
        if (k.inBox()) return done;
        Files.createDirectories(dir());
        for (Jar j : k.jars()) {
            Path to = dir().resolve(j.file());
            if (Files.isRegularFile(to) && sha256(to).equals(j.sha256())) { done.add(j.file() + " (already there)"); continue; }
            Path tmp = Files.createTempFile(dir(), "fetch-", ".part");
            try (InputStream in = java.net.URI.create(CENTRAL + j.path()).toURL().openStream()) { Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { Files.deleteIfExists(tmp); throw new IOException("Could not download " + j.file() + ": " + e.getMessage()); }
            String got = sha256(tmp);
            if (!got.equals(j.sha256())) { Files.deleteIfExists(tmp); throw new IOException("The downloaded " + j.file() + " does not match its expected checksum. It was not kept."); }
            Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            done.add(j.file());
        }
        LOADERS.remove(k.name());
        return done;
    }

    /** The class loader that sees the kind's driver: the program's own for a driver in the box, a separate one over the fetched jars otherwise. */
    public static ClassLoader loader(Kind k) throws IOException {
        if (k.inBox()) return DbDrivers.class.getClassLoader();
        if (!installed(k)) throw new IOException("The " + k.label() + " driver is not installed. Run: researchzosho db driver install " + k.name() + " (" + k.sizeNote() + ")");
        ClassLoader have = LOADERS.get(k.name());
        if (have != null) return have;
        List<URL> urls = new ArrayList<>();
        for (Jar j : k.jars()) urls.add(dir().resolve(j.file()).toUri().toURL());
        ClassLoader made = new URLClassLoader(urls.toArray(new URL[0]), DbDrivers.class.getClassLoader());
        LOADERS.put(k.name(), made);
        return made;
    }

    static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
