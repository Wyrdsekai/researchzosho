package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A database the owner gives read access to: the statement guard, the registry, the schema, a query, hidden columns, the saved result, who may use it. */
class DatabasesTest {

    static final ObjectMapper M = new ObjectMapper();

    @AfterEach void reset() { Databases.fileOverride = null; }

    static Path shop(Path home) throws Exception {
        Path db = home.resolve("shop.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT, email TEXT, last_update TEXT)");
            st.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer INTEGER, total REAL, placed TEXT, note TEXT)");
            st.execute("INSERT INTO customers VALUES (1,'Ada','ada@example.org','2026-01-01'),(2,'Grace','grace@example.org','2026-02-01')");
            st.execute("INSERT INTO orders VALUES (1,1,19.5,'2026-03-01','please delete the old one'),(2,1,5.0,'2026-03-09',''),(3,2,100.0,'2026-04-02','update me; drop it')");
        }
        return db;
    }

    @Test
    void onlyAStatementThatReadsMayRun() {
        for (String ok : List.of("SELECT * FROM orders", "select count(*) from orders;", "WITH t AS (SELECT 1 AS x) SELECT x FROM t", "EXPLAIN SELECT 1", "SHOW TABLES",
                "SELECT replace(name, 'a', 'b'), last_update FROM customers", "SELECT note FROM orders WHERE note = 'please delete the old one; drop it'", "SELECT \"update\" FROM t", "-- a comment\nSELECT 1", "SELECT 1 /* drop table x */"))
            assertNull(SqlGuard.refuse(ok), ok + " -> " + SqlGuard.refuse(ok));
        for (String bad : List.of("", "DELETE FROM orders", "UPDATE orders SET total = 0", "INSERT INTO orders VALUES (9,9,9,'x','y')", "DROP TABLE orders", "SELECT 1; DROP TABLE orders",
                "WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone", "SELECT * INTO copy FROM orders", "SELECT * FROM orders FOR UPDATE", "COPY orders TO '/tmp/x'",
                "SELECT pg_read_file('/etc/passwd')", "SELECT load_file('/etc/passwd')", "SELECT pg_sleep(60)", "PRAGMA writable_schema = 1", "ATTACH DATABASE 'x' AS y", "CALL do_it()",
                "SELECT * FROM orders INTO OUTFILE '/tmp/x'", "SET search_path = x", "BEGIN", "VACUUM"))
            assertNotNull(SqlGuard.refuse(bad), "should be refused: " + bad);
        assertTrue(SqlGuard.refuse("DELETE FROM orders").contains("must start with SELECT"), SqlGuard.refuse("DELETE FROM orders"));
        assertTrue(SqlGuard.refuse("SELECT 1; DROP TABLE x").contains("one statement"));
        assertNotNull(SqlGuard.refuseMongo("[{\"$out\": \"copy\"}]")); assertNotNull(SqlGuard.refuseMongo("{\"$where\": \"sleep(1000)\"}"));
        assertNull(SqlGuard.refuseMongo("[{\"$match\": {\"a\": 1}}, {\"$group\": {\"_id\": \"$a\", \"n\": {\"$sum\": 1}}}]"));
    }

    @Test
    void aDatabaseIsAddedReadQueriedAndItsResultSaved(@TempDir Path home) throws Exception {
        Databases.fileOverride = home.resolve("databases.json");
        Path file = shop(home);
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // a path to a SQLite file is enough; the kind is read from it; a wrong address is refused before anything is saved
        assertThrows(java.io.IOException.class, () -> Databases.add("nope", home.resolve("missing.db").toString(), List.of()));
        assertThrows(java.io.IOException.class, () -> Databases.add("bad name!", file.toString(), List.of()));
        Databases.Db db = Databases.add("shop", file.toString(), List.of("email"));
        assertEquals("sqlite", db.kind());
        assertEquals(1, Databases.list().size()); assertNotNull(Databases.get("SHOP")); assertTrue(Databases.any());
        assertEquals("postgres://reader:***@db.example:5432/shop", new Databases.Db("x", "postgres", "postgres://reader:s3cret@db.example:5432/shop", List.of(), "").shown(), "the password is never shown");
        // the schema: tables, columns, counts, sample rows; a hidden column shows no values
        String schema = Databases.schema(db);
        assertTrue(schema.contains("## customers — 2 row(s)") && schema.contains("## orders — 3 row(s)") && schema.contains("- total real"), schema);
        assertTrue(schema.contains("- email text (values hidden)") && !schema.contains("ada@example.org") && schema.contains("[hidden]"), schema);
        // a query: rows as a table, capped, saved as a page to cite; the hidden column stays hidden whatever the query
        Databases.Result r = Databases.query(store, db, "SELECT c.name, c.email, SUM(o.total) AS spent FROM customers c JOIN orders o ON o.customer = c.id GROUP BY c.name, c.email ORDER BY spent DESC", 0);
        assertEquals(List.of("name", "email", "spent"), r.columns());
        assertEquals(List.of("Grace", "[hidden]", "100.0"), r.rows().get(0));
        assertTrue(r.saved().startsWith("db://shop/"), r.saved());
        assertTrue(r.text().contains("| Grace | [hidden] | 100.0 |") && r.text().contains("SELECT c.name"), r.text());
        Path raw = RawCapture.find(store, r.saved());
        assertNotNull(raw, "the result is a saved page"); assertTrue(RawCapture.read(raw)[2].contains("| Ada | [hidden] | 24.5 |"));
        Databases.Result capped = Databases.query(store, db, "SELECT id FROM orders ORDER BY id", 2);
        assertEquals(2, capped.rows().size()); assertTrue(capped.more()); assertTrue(capped.text().contains("more exist"));
        // writes are refused in plain words, and the data is untouched
        java.io.IOException e = assertThrows(java.io.IOException.class, () -> Databases.query(store, db, "DELETE FROM orders", 0));
        assertTrue(e.getMessage().contains("Only queries that read"), e.getMessage());
        assertThrows(java.io.IOException.class, () -> Databases.query(store, db, "SELECT 1; DELETE FROM orders", 0));
        assertEquals("3", Databases.query(store, db, "SELECT COUNT(*) FROM orders", 0).rows().get(0).get(0));
        // and the connection itself cannot write, whatever reaches it
        try (Connection c = Databases.connect(db); Statement st = c.createStatement()) { assertThrows(Exception.class, () -> st.executeUpdate("DELETE FROM orders")); }
        // the protocol: the owner may, a stranger may not; list never shows a password
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode owner = M.createObjectNode(); owner.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode listed = p.db(owner.deepCopy().put("op", "list"));
        assertEquals("shop", listed.path("databases").get(0).path("name").asText());
        ObjectNode q = p.db(owner.deepCopy().put("op", "query").put("database", "shop").put("sql", "SELECT COUNT(*) AS n FROM customers"));
        assertEquals("2", q.path("rows").get(0).get(0).asText()); assertTrue(q.path("summary").asText().startsWith("1 row(s)."), q.path("summary").asText());
        assertTrue(p.db(owner.deepCopy().put("op", "schema").put("database", "shop")).path("schema").asText().contains("## orders"));
        assertThrows(ProtocolError.class, () -> p.db(owner.deepCopy().put("op", "query").put("database", "shop").put("sql", "DROP TABLE orders")));
        ObjectNode stranger = M.createObjectNode().put("op", "list"); stranger.putObject("patron").put("did", "did:key:zStranger").put("name", "s").put("runtime", "mcp");
        Patrons.setDefault(store, Patrons.Level.write);
        assertThrows(ProtocolError.class, () -> p.db(stranger), "a stranger with write access still may not read the owner's databases");
        // the run tools
        assertTrue(new Researcher.DbSchemaTool().execute(M.createObjectNode()).contains("- shop (SQLite)"));
        assertTrue(new Researcher.DbSchemaTool().execute(M.createObjectNode().put("database", "shop")).contains("## customers"));
        String out = new Researcher.DbQueryTool(store).execute(M.createObjectNode().put("database", "shop").put("sql", "SELECT name FROM customers ORDER BY name"));
        assertTrue(out.startsWith("saved as db://shop/") && out.contains("| Ada |"), out);
        assertTrue(new Researcher.DbQueryTool(store).execute(M.createObjectNode().put("database", "shop").put("sql", "UPDATE customers SET name = 'x'")).startsWith("ERROR: Only queries that read"));
        // survey db:<name>: the schema is read, one draft claim, directions
        var drives = Explain.DRIVES; Explain.DRIVES = () -> null;
        try {
            ObjectNode s = p.survey(owner.deepCopy().put("database", "shop"));
            assertEquals("db", s.path("kind").asText()); assertEquals("shop", s.path("name").asText());
            assertTrue(s.path("summary").asText().startsWith("Read the database shop (2 tables, SQLite)"), s.path("summary").asText());
            assertTrue(s.path("options").size() >= 3);
        } finally { Explain.DRIVES = drives; }
        // removing it keeps what was saved
        assertTrue(Databases.remove("shop")); assertFalse(Databases.any()); assertNotNull(RawCapture.find(store, r.saved()));
        // a bare path gets its scheme, a Windows one too (its drive colon is not a scheme); an address that has one is left as it is
        assertEquals("sqlite:" + file.toAbsolutePath(), Databases.normalise("sqlite", file.toString()));
        assertTrue(Databases.normalise("sqlite", "C:\\data\\shop.db").startsWith("sqlite:"), Databases.normalise("sqlite", "C:\\data\\shop.db"));
        assertEquals("sqlite:/data/shop.db", Databases.normalise("sqlite", "sqlite:/data/shop.db"));
        // the address SQLite is given: a Windows drive path needs three slashes, and a space, ?, # or % is escaped
        assertEquals("file:/home/me/Calibre%20Library/metadata.db", Calibre.sqliteUri("/home/me/Calibre Library/metadata.db"));
        assertEquals("file:///C:/Users/me/Calibre%20Library/metadata.db", Calibre.sqliteUri("C:\\Users\\me\\Calibre Library\\metadata.db"));
        assertEquals("file:/data/what%3f%23100%25.db", Calibre.sqliteUri("/data/what?#100%.db"));
        assertEquals("sqlite", Databases.kindOf(file.toString())); assertEquals("postgres", Databases.kindOf("postgres://u@h/db")); assertEquals("mysql", Databases.kindOf("mariadb://u@h/db"));
        assertEquals("mongo", Databases.kindOf("mongodb://h/db")); assertEquals("sqlserver", Databases.kindOf("sqlserver://u@h/db")); assertNull(Databases.kindOf("ftp://nowhere"));
    }
}
