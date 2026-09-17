package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Databases the library owner has given the library access to. A connection is added from the command
 * line by the owner and kept in {@code ~/.researchzosho/databases.json}, outside the library folder, so
 * its password is never shelved, never sent to the model, and never shown. Access is read-only, held
 * four ways: a read-only database user is what the documentation asks for; the connection is opened
 * read-only; every statement passes {@link SqlGuard}; and nothing is ever committed.
 *
 * <p>The model gets two things: the schema (tables, columns, row counts, a few sample rows) and the
 * rows of one reading query at a time, capped. Each result is saved as a page, so a report can cite it
 * and the citation check can read a claim against the rows.
 */
public final class Databases {

    private static final ObjectMapper M = new ObjectMapper();

    public static final int DEFAULT_ROWS = org.researchzosho.Config.getInt("RESEARCHZOSHO_DB_ROWS", 100);
    public static final int MAX_ROWS = org.researchzosho.Config.getInt("RESEARCHZOSHO_DB_MAX_ROWS", 500);
    static final int TIMEOUT_SECONDS = org.researchzosho.Config.getInt("RESEARCHZOSHO_DB_TIMEOUT_SECONDS", 30);
    static final int SCHEMA_TABLES = 60, SAMPLE_ROWS = 3, CELL_CHARS = 200;

    /** One connection as the owner added it. {@code hide}: column names, or table.column, whose values are never shown. */
    public record Db(String name, String kind, String url, List<String> hide, String addedAt) {
        /** The address without its password, for showing. */
        public String shown() { return url.replaceAll("(://[^:/@]+):[^@/]*@", "$1:***@"); }
        boolean hidden(String table, String column) {
            String c = column.toLowerCase(Locale.ROOT), tc = (table == null ? "" : table.toLowerCase(Locale.ROOT)) + "." + c;
            for (String h : hide) { String x = h.toLowerCase(Locale.ROOT).strip(); if (x.equals(c) || x.equals(tc)) return true; }
            return false;
        }
    }

    /** The rows of a query: column names, the rows as text, whether more rows existed, and the page the result was saved as. */
    public record Result(List<String> columns, List<List<String>> rows, boolean more, String saved, String text) { }

    private Databases() { }

    // ---- the registry ----

    /** Tests point this at a scratch file; production never sets it. The real file holds the owner's passwords. */
    static volatile Path fileOverride = null;

    /** Beside the config file, wherever that is: an install that moves its config (RESEARCHZOSHO_CONFIG) moves this with it. */
    public static Path file() { return fileOverride != null ? fileOverride : org.researchzosho.Config.userConfigPath().toAbsolutePath().resolveSibling("databases.json"); }

    public static List<Db> list() throws IOException {
        List<Db> out = new ArrayList<>();
        if (!Files.exists(file())) return out;
        JsonNode root = M.readTree(Files.readString(file(), StandardCharsets.UTF_8));
        var it = root.fields();
        while (it.hasNext()) {
            var e = it.next(); JsonNode d = e.getValue();
            List<String> hide = new ArrayList<>(); for (JsonNode h : d.path("hide")) hide.add(h.asText());
            out.add(new Db(e.getKey(), d.path("kind").asText(), d.path("url").asText(), hide, d.path("added_at").asText()));
        }
        return out;
    }

    public static Db get(String name) throws IOException {
        for (Db d : list()) if (d.name().equalsIgnoreCase(name == null ? "" : name.strip())) return d;
        return null;
    }

    /** Whether any connection exists: the tools are offered only then. */
    public static boolean any() { try { return !list().isEmpty(); } catch (IOException e) { return false; } }

    /** Add a connection. The kind is read from the address. Connects once to check it, and refuses when it cannot. */
    public static Db add(String name, String url, List<String> hide) throws IOException {
        String n = name == null ? "" : name.strip();
        if (!n.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")) throw new IOException("A database name is letters, digits, - and _, up to 40 characters.");
        String kind = kindOf(url);
        if (kind == null) throw new IOException("Could not tell what kind of database this is. Use an address that starts with sqlite:, postgres://, mysql://, sqlserver://, mongodb:// or duckdb:, or give the path of a SQLite file.");
        Db db = new Db(n, kind, normalise(kind, url), hide == null ? List.of() : List.copyOf(hide), Instant.now().toString());
        DbDrivers.Kind k = DbDrivers.kind(kind);
        if (!DbDrivers.installed(k)) throw new IOException("The " + k.label() + " driver is not installed. Run: researchzosho db driver install " + k.name() + " (" + k.sizeNote() + ")");
        schema(db);   // connects and reads: a wrong address or password fails here, before it is saved
        ObjectNode root = Files.exists(file()) ? (ObjectNode) M.readTree(Files.readString(file(), StandardCharsets.UTF_8)) : M.createObjectNode();
        ObjectNode d = root.putObject(db.name());
        d.put("kind", db.kind()); d.put("url", db.url()); d.put("added_at", db.addedAt());
        ArrayNode h = d.putArray("hide"); db.hide().forEach(h::add);
        write(root);
        return db;
    }

    public static boolean remove(String name) throws IOException {
        if (!Files.exists(file())) return false;
        ObjectNode root = (ObjectNode) M.readTree(Files.readString(file(), StandardCharsets.UTF_8));
        String key = null; var it = root.fieldNames(); while (it.hasNext()) { String k = it.next(); if (k.equalsIgnoreCase(name)) key = k; }
        if (key == null) return false;
        root.remove(key); write(root);
        return true;
    }

    private static void write(ObjectNode root) throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), M.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(file(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) { }   // it holds passwords
    }

    /** The kind an address names, or null. A path to an existing file is SQLite, or DuckDB for .duckdb, .csv, .parquet and folders of those. */
    static String kindOf(String url) {
        String u = url == null ? "" : url.strip(), l = u.toLowerCase(Locale.ROOT);
        if (l.startsWith("sqlite:") || l.startsWith("jdbc:sqlite:")) return "sqlite";
        if (l.startsWith("postgres://") || l.startsWith("postgresql://") || l.startsWith("jdbc:postgresql:")) return "postgres";
        if (l.startsWith("mysql://") || l.startsWith("mariadb://") || l.startsWith("jdbc:mysql:") || l.startsWith("jdbc:mariadb:")) return "mysql";
        if (l.startsWith("sqlserver://") || l.startsWith("mssql://") || l.startsWith("jdbc:sqlserver:")) return "sqlserver";
        if (l.startsWith("mongodb://") || l.startsWith("mongodb+srv://")) return "mongo";
        if (l.startsWith("duckdb:") || l.startsWith("jdbc:duckdb:")) return "duckdb";
        try {
            Path p = Path.of(u.startsWith("~/") ? System.getProperty("user.home") + u.substring(1) : u);
            if (Files.isDirectory(p) || l.endsWith(".duckdb") || l.endsWith(".csv") || l.endsWith(".parquet") || l.endsWith(".tsv")) return Files.exists(p) ? "duckdb" : null;
            if (Files.isRegularFile(p)) return "sqlite";
        } catch (Exception ignored) { }
        return null;
    }

    static String normalise(String kind, String url) {
        String u = url.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        boolean hasScheme = lower.startsWith("sqlite:") || lower.startsWith("duckdb:") || lower.startsWith("jdbc:");
        // a bare path gets its scheme. "C:\\data\\x.db" holds a colon and is still a bare path.
        if ((kind.equals("sqlite") || kind.equals("duckdb")) && !hasScheme) {
            Path p = Path.of(u.startsWith("~/") ? System.getProperty("user.home") + u.substring(1) : u).toAbsolutePath().normalize();
            return kind + ":" + p;
        }
        return u;
    }

    // ---- connecting ----

    private record Target(String jdbc, Properties props) { }

    /** The JDBC address and the user and password, taken out of a postgres:// style address. */
    static Target target(Db db) throws IOException {
        Properties props = new Properties();
        String u = db.url();
        if (u.startsWith("jdbc:")) return new Target(u, props);
        switch (db.kind()) {
            case "sqlite" -> { return new Target("jdbc:sqlite:" + Calibre.sqliteUri(u.substring("sqlite:".length())) + "?mode=ro", props); }
            case "duckdb" -> { props.setProperty("duckdb.read_only", "true"); String p = u.substring("duckdb:".length()); return new Target(Files.isRegularFile(Path.of(p)) && p.endsWith(".duckdb") ? "jdbc:duckdb:" + p : "jdbc:duckdb:", propsFor(p)); }
            default -> { }
        }
        URI uri;
        try { uri = URI.create(u); } catch (Exception e) { throw new IOException("The address of " + db.name() + " is not a valid url."); }
        if (uri.getUserInfo() != null) {
            String[] up = uri.getUserInfo().split(":", 2);
            props.setProperty("user", java.net.URLDecoder.decode(up[0], StandardCharsets.UTF_8));
            if (up.length > 1) props.setProperty("password", java.net.URLDecoder.decode(up[1], StandardCharsets.UTF_8));
        }
        String host = uri.getHost(), path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", ""), query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        int port = uri.getPort();
        return switch (db.kind()) {
            case "postgres" -> new Target("jdbc:postgresql://" + host + (port > 0 ? ":" + port : "") + "/" + path + query, props);
            case "mysql" -> new Target("jdbc:mariadb://" + host + (port > 0 ? ":" + port : "") + "/" + path + query, props);
            case "sqlserver" -> new Target("jdbc:sqlserver://" + host + (port > 0 ? ":" + port : "") + ";databaseName=" + path + ";encrypt=true;trustServerCertificate=true", props);
            default -> throw new IOException("No JDBC address for a " + db.kind() + " database.");
        };
    }

    private static Properties propsFor(String path) { Properties p = new Properties(); if (path.endsWith(".duckdb")) p.setProperty("duckdb.read_only", "true"); return p; }

    /** A read-only connection that never commits. */
    static Connection connect(Db db) throws IOException {
        DbDrivers.Kind k = DbDrivers.kind(db.kind());
        Target t = target(db);
        try {
            Driver driver = (Driver) Class.forName(k.driverClass(), true, DbDrivers.loader(k)).getDeclaredConstructor().newInstance();
            java.sql.DriverManager.setLoginTimeout(15);
            Connection c = driver.connect(t.jdbc(), t.props());
            if (c == null) throw new IOException("The " + k.label() + " driver did not accept the address of " + db.name() + ".");
            try { c.setReadOnly(true); } catch (Exception ignored) { }
            try { c.setAutoCommit(false); } catch (Exception ignored) { }
            try (Statement st = c.createStatement()) {
                if (db.kind().equals("postgres")) st.execute("SET default_transaction_read_only = on");
                if (db.kind().equals("mysql")) st.execute("SET SESSION TRANSACTION READ ONLY");
            } catch (Exception ignored) { }
            if (db.kind().equals("duckdb")) {
                // not best-effort: if file access cannot be turned off, the connection is not handed out
                try (Statement st = c.createStatement()) { duckViews(st, db); }
                catch (Exception e) { try { c.close(); } catch (Exception x) { } throw new IOException("Could not prepare " + db.name() + " safely: " + e.getMessage()); }
            }
            return c;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Could not connect to " + db.name() + ": " + clean(e, t)); }
    }

    /**
     * For a folder or a data file: one table per CSV, TSV, Parquet or JSON file, named after the file, loaded into
     * memory. Then DuckDB's access to files and the network is turned off for the session and that setting is locked.
     * DuckDB has many functions that read any file on disk (read_csv, read_text, glob…); a word list would never name
     * them all, so the engine itself is told no.
     */
    private static void duckViews(Statement st, Db db) throws Exception {
        Path p = Path.of(db.url().substring("duckdb:".length()));
        if (Files.isRegularFile(p) && p.toString().endsWith(".duckdb")) { lockDuck(st); return; }
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(p)) { try (var l = Files.list(p)) { l.filter(Files::isRegularFile).sorted().forEach(files::add); } } else files.add(p);
        for (Path f : files) {
            String n = f.getFileName().toString(), lower = n.toLowerCase(Locale.ROOT);
            String reader = lower.endsWith(".csv") || lower.endsWith(".tsv") ? "read_csv_auto" : lower.endsWith(".parquet") ? "read_parquet" : lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".ndjson") ? "read_json_auto" : null;
            if (reader == null) continue;
            String view = n.replaceFirst("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_]", "_");
            st.execute("CREATE TABLE \"" + view + "\" AS SELECT * FROM " + reader + "('" + f.toString().replace("'", "''") + "')");
        }
        lockDuck(st);
    }

    private static void lockDuck(Statement st) throws Exception {
        st.execute("SET enable_external_access = false");
        st.execute("SET lock_configuration = true");
    }

    /** An error message with no password in it. */
    private static String clean(Throwable e, Target t) {
        String m = String.valueOf(e.getMessage() == null ? e : e.getMessage());
        String pw = t.props().getProperty("password");
        if (pw != null && !pw.isEmpty()) m = m.replace(pw, "***");
        return m.replaceAll("(://[^:/@\\s]+):[^@/\\s]*@", "$1:***@");
    }

    // ---- the schema ----

    /** Tables, columns, row counts and a few sample rows, as text the model can read. Hidden columns show no values. */
    public static String schema(Db db) throws IOException {
        if (db.kind().equals("mongo")) return mongoSchema(db);
        StringBuilder sb = new StringBuilder("Database " + db.name() + " (" + DbDrivers.kind(db.kind()).label() + "). Read-only.\n");
        try (Connection c = connect(db)) {
            DatabaseMetaData md = c.getMetaData();
            String q = md.getIdentifierQuoteString(); if (q == null || q.isBlank()) q = "\"";
            List<String[]> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", new String[]{"TABLE", "VIEW", "BASE TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM"), name = rs.getString("TABLE_NAME");
                    String s = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
                    if (s.equals("information_schema") || s.startsWith("pg_") || s.equals("sys") || s.equals("mysql") || s.equals("performance_schema") || name.startsWith("sqlite_")) continue;
                    tables.add(new String[]{schema, name, rs.getString("TABLE_TYPE")});
                }
            }
            sb.append(tables.size()).append(" table(s)").append(tables.size() > SCHEMA_TABLES ? ", the first " + SCHEMA_TABLES + " shown" : "").append(".\n");
            for (String[] t : tables.subList(0, Math.min(SCHEMA_TABLES, tables.size()))) {
                String qualified = (t[0] == null || t[0].isBlank() || t[0].equalsIgnoreCase("public") || t[0].equalsIgnoreCase("main") || t[0].equalsIgnoreCase("dbo") ? "" : q + t[0] + q + ".") + q + t[1] + q;
                sb.append("\n## ").append(t[1]).append(t[2] != null && t[2].contains("VIEW") ? " (view)" : "");
                try (Statement st = c.createStatement()) { st.setQueryTimeout(5); try (ResultSet n = st.executeQuery("SELECT COUNT(*) FROM " + qualified)) { if (n.next()) sb.append(" — ").append(n.getLong(1)).append(" row(s)"); } } catch (Exception ignored) { try { c.rollback(); } catch (Exception x) { } }
                sb.append('\n');
                List<String> cols = new ArrayList<>();
                try (ResultSet rs = md.getColumns(c.getCatalog(), t[0], t[1], "%")) {
                    while (rs.next()) { String col = rs.getString("COLUMN_NAME"); cols.add(col); sb.append("- ").append(col).append(" ").append(String.valueOf(rs.getString("TYPE_NAME")).toLowerCase(Locale.ROOT)).append(db.hidden(t[1], col) ? " (values hidden)" : "").append('\n'); }
                }
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(5); st.setMaxRows(SAMPLE_ROWS);
                    try (ResultSet rs = st.executeQuery("SELECT * FROM " + qualified)) {
                        List<List<String>> rows = rows(db, t[1], rs, SAMPLE_ROWS);
                        if (!rows.isEmpty()) { sb.append("sample rows:\n"); for (List<String> r : rows) sb.append("  ").append(String.join(" | ", r)).append('\n'); }
                    }
                } catch (Exception ignored) { try { c.rollback(); } catch (Exception x) { } }
            }
            try { c.rollback(); } catch (Exception ignored) { }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Could not read the schema of " + db.name() + ": " + e.getMessage()); }
        return sb.toString();
    }

    private static String mongoSchema(Db db) throws IOException {
        StringBuilder sb = new StringBuilder("Database " + db.name() + " (MongoDB). Read-only.\n");
        try (MongoAccess m = new MongoAccess(db.url(), MongoAccess.databaseOf(db.url()))) {
            List<String> cols = m.collections();
            sb.append(cols.size()).append(" collection(s).\n");
            for (String c : cols.subList(0, Math.min(SCHEMA_TABLES, cols.size()))) {
                sb.append("\n## ").append(c).append(" — about ").append(m.count(c)).append(" document(s)\n");
                m.fields(c, 20).forEach((f, type) -> sb.append("- ").append(f).append(" ").append(type).append(db.hidden(c, f) ? " (values hidden)" : "").append('\n'));
            }
        }
        return sb.toString();
    }

    // ---- a query ----

    /** One reading query. Refused with a plain reason when it is not one. The rows are capped, and the result is saved as a page. */
    public static Result query(LibraryStore store, Db db, String sql, int limit) throws IOException {
        if (db.kind().equals("mongo")) throw new IOException("This is a MongoDB database. Query it with a collection and a filter or a pipeline, not SQL.");
        String no = SqlGuard.refuse(sql, db.kind());
        if (no != null) throw new IOException(no);
        int cap = Math.max(1, Math.min(MAX_ROWS, limit <= 0 ? DEFAULT_ROWS : limit));
        List<String> columns = new ArrayList<>(); List<List<String>> rows; boolean more;
        try (Connection c = connect(db); Statement st = c.createStatement()) {
            st.setQueryTimeout(TIMEOUT_SECONDS); st.setMaxRows(cap + 1);
            try (ResultSet rs = st.executeQuery(sql.strip().replaceAll(";+\\s*$", ""))) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                rows = rows(db, null, rs, cap + 1);
            } finally { try { c.rollback(); } catch (Exception ignored) { } }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("The query failed: " + e.getMessage()); }
        more = rows.size() > cap; if (more) rows = rows.subList(0, cap);
        return saved(store, db, sql.strip(), columns, rows, more);
    }

    /** One MongoDB read: find(filter) or aggregate(pipeline) on a collection. */
    public static Result queryMongo(LibraryStore store, Db db, String collection, String filterJson, String pipelineJson, int limit) throws IOException {
        if (!db.kind().equals("mongo")) throw new IOException(db.name() + " is not a MongoDB database. Query it with SQL.");
        if (collection == null || collection.isBlank()) throw new IOException("Name the collection to read.");
        String no = SqlGuard.refuseMongo((filterJson == null ? "" : filterJson) + (pipelineJson == null ? "" : pipelineJson));
        if (no != null) throw new IOException(no);
        int cap = Math.max(1, Math.min(MAX_ROWS, limit <= 0 ? DEFAULT_ROWS : limit));
        List<JsonNode> docs;
        try (MongoAccess m = new MongoAccess(db.url(), MongoAccess.databaseOf(db.url()))) { docs = m.read(collection, filterJson, pipelineJson, cap + 1); }
        boolean more = docs.size() > cap; if (more) docs = docs.subList(0, cap);
        Set<String> keys = new TreeSet<>();
        for (JsonNode d : docs) d.fieldNames().forEachRemaining(keys::add);
        List<String> columns = new ArrayList<>(keys);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode d : docs) { List<String> r = new ArrayList<>(); for (String k : columns) r.add(db.hidden(collection, k) ? "[hidden]" : cell(d.has(k) ? plain(d.get(k)) : "")); rows.add(r); }
        String what = collection + (pipelineJson != null && !pipelineJson.isBlank() ? ".aggregate(" + pipelineJson.strip() + ")" : ".find(" + (filterJson == null || filterJson.isBlank() ? "{}" : filterJson.strip()) + ")");
        return saved(store, db, what, columns, rows, more);
    }

    private static List<List<String>> rows(Db db, String table, ResultSet rs, int max) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        List<List<String>> out = new ArrayList<>();
        while (rs.next() && out.size() < max) {
            List<String> r = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String col = md.getColumnLabel(i), tbl = table;
                if (tbl == null) { try { tbl = md.getTableName(i); } catch (Exception e) { tbl = ""; } }
                if (db.hidden(tbl, col)) { r.add("[hidden]"); continue; }
                Object v;
                try { v = rs.getObject(i); } catch (Exception e) { v = rs.getString(i); }
                r.add(v == null ? "" : v instanceof byte[] b ? "[binary, " + b.length + " bytes]" : cell(String.valueOf(v)));
            }
            out.add(r);
        }
        return out;
    }

    /** A MongoDB value as a person writes it: an id as its text, a date as a date, a long number as a number. */
    static String plain(JsonNode v) {
        if (v.isTextual()) return v.asText();
        if (v.isObject() && v.size() == 1) {
            if (v.has("$oid")) return v.get("$oid").asText();
            if (v.has("$date")) return v.get("$date").isTextual() ? v.get("$date").asText() : v.get("$date").toString();
            for (String k : List.of("$numberLong", "$numberDecimal", "$numberInt", "$numberDouble")) if (v.has(k)) return v.get(k).asText();
        }
        return v.toString();
    }

    private static String cell(String v) { String s = v.replaceAll("\\s+", " ").replace("|", "\\|").strip(); return s.length() > CELL_CHARS ? s.substring(0, CELL_CHARS) + "…" : s; }

    /** The result as a table, saved as a page a report can cite. The locator carries the query and the result, so a changed result is a new page. */
    private static Result saved(LibraryStore store, Db db, String query, List<String> columns, List<List<String>> rows, boolean more) {
        StringBuilder t = new StringBuilder();
        t.append("Query on the database ").append(db.name()).append(" (").append(DbDrivers.kind(db.kind()).label()).append("), read at ").append(Instant.now()).append(".\n\n```\n").append(query).append("\n```\n\n");
        t.append(rows.size()).append(" row(s)").append(more ? ", more exist (the limit was reached)" : "").append(".\n\n");
        if (!columns.isEmpty()) {
            t.append("| ").append(String.join(" | ", columns)).append(" |\n|").append("---|".repeat(columns.size())).append('\n');
            for (List<String> r : rows) t.append("| ").append(String.join(" | ", r)).append(" |\n");
        }
        String text = t.toString(), saved = "";
        if (store != null) {
            String locator = "db://" + db.name() + "/" + Conversations.hash8(query) + "-" + Conversations.hash8(String.valueOf(rows));
            Path p = RawCapture.capture(store, locator, text, "Query on " + db.name() + ": " + Acquisitions.compress(query.replaceAll("\\s+", " "), 80), "db:" + db.name(), "databases");
            saved = p == null ? "" : locator;
        }
        return new Result(columns, rows, more, saved, text);
    }

    // ---- where the rows go ----

    /**
     * Whether the model that would see the rows runs somewhere else. A model on this machine or this network keeps
     * the data here; a hosted one receives whatever a query returns. Returns the host when it looks hosted, or null.
     */
    public static String hostedModel() {
        String drive = org.researchzosho.Config.get("RESEARCHZOSHO_DRIVE");
        if (drive == null || drive.isBlank()) return null;
        try {
            String host = URI.create(drive.strip()).getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (!h.contains(".") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home") || h.equals("localhost")) return null;
            if (h.matches("[0-9.]+") || h.contains(":")) { InetAddress a = InetAddress.getByName(h); return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() ? null : host; }
            return host;
        } catch (Exception e) { return null; }
    }

    public static String hostedWarning() {
        String host = hostedModel();
        return host == null ? "" : "Warning: your model runs at " + host + ", which is not on this machine or this network. Rows a query returns will be sent there. Hide sensitive columns with --hide, or use a local model.";
    }
}
