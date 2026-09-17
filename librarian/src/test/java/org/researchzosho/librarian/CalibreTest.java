package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** A Calibre library as a list of books: the database first, the OPF files when it cannot be read, then items as usual. */
class CalibreTest {

    static final ObjectMapper M = new ObjectMapper();
    static Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    /** A library the shape Calibre writes: metadata.db with the tables the query reads, and a metadata.opf per book. */
    static Path library(Path home, boolean withDb) throws Exception {
        Path lib = home.resolve("Calibre Library");
        Path a = lib.resolve("David Pugh/Tides, Surges and Mean Sea-Level (1)"); Files.createDirectories(a);
        Files.writeString(a.resolve("metadata.opf"), "<?xml version='1.0' encoding='utf-8'?><package><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">"
                + "<dc:title>Tides, Surges and Mean Sea-Level</dc:title><dc:creator opf:role=\"aut\">David Pugh</dc:creator><dc:date>1987-06-01T00:00:00+00:00</dc:date>"
                + "<dc:publisher>Wiley</dc:publisher><dc:identifier opf:scheme=\"ISBN\">9780471915058</dc:identifier><dc:subject>Oceanography</dc:subject><dc:subject>Tides</dc:subject></metadata></package>");
        Files.writeString(a.resolve("Tides.epub"), "x"); Files.writeString(a.resolve("cover.jpg"), "x");
        Path b = lib.resolve("Ursula K. Le Guin/A Wizard of Earthsea (2)"); Files.createDirectories(b);
        Files.writeString(b.resolve("metadata.opf"), "<?xml version='1.0' encoding='utf-8'?><package><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">"
                + "<dc:title>A Wizard of Earthsea</dc:title><dc:creator opf:role=\"aut\">Ursula K. Le Guin</dc:creator><dc:date>1968-01-01T00:00:00+00:00</dc:date>"
                + "<meta name=\"calibre:series\" content=\"Earthsea Cycle\"/><meta name=\"calibre:series_index\" content=\"1.0\"/></metadata></package>");
        Files.writeString(b.resolve("Wizard.mobi"), "x");
        if (withDb) {
            Class.forName("org.sqlite.JDBC");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + lib.resolve("metadata.db")); Statement st = c.createStatement()) {
                for (String ddl : new String[]{
                        "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, pubdate TEXT, isbn TEXT, series_index REAL)",
                        "CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_authors_link (book INTEGER, author INTEGER)",
                        "CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_series_link (book INTEGER, series INTEGER)",
                        "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_tags_link (book INTEGER, tag INTEGER)",
                        "CREATE TABLE identifiers (book INTEGER, type TEXT, val TEXT)",
                        "CREATE TABLE publishers (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_publishers_link (book INTEGER, publisher INTEGER)",
                        "CREATE TABLE data (book INTEGER, format TEXT)",
                        "INSERT INTO books VALUES (1, 'Tides, Surges and Mean Sea-Level', '1987-06-01 00:00:00+00:00', '', 1.0), (2, 'A Wizard of Earthsea', '1968-01-01 00:00:00+00:00', '', 1.0), (3, 'Untitled draft', '0101-01-01 00:00:00+00:00', '', 1.0)",
                        "INSERT INTO authors VALUES (1, 'David Pugh'), (2, 'Ursula K. Le Guin'), (3, 'Charles Le Guin')",
                        "INSERT INTO books_authors_link VALUES (1, 1), (2, 2), (2, 3)",
                        "INSERT INTO series VALUES (1, 'Earthsea Cycle')", "INSERT INTO books_series_link VALUES (2, 1)",
                        "INSERT INTO tags VALUES (1, 'Oceanography'), (2, 'Tides')", "INSERT INTO books_tags_link VALUES (1, 1), (1, 2)",
                        "INSERT INTO identifiers VALUES (1, 'isbn', '9780471915058')",
                        "INSERT INTO publishers VALUES (1, 'Wiley')", "INSERT INTO books_publishers_link VALUES (1, 1)",
                        "INSERT INTO data VALUES (1, 'EPUB'), (1, 'PDF'), (2, 'MOBI')"}) st.execute(ddl);
            }
        }
        return lib;
    }

    @Test
    void theDatabaseIsReadFirstReadOnlyAndTheBooksComeOutAsACsvWithATitleColumn(@TempDir Path home) throws Exception {
        Path lib = library(home, true);
        assertTrue(Calibre.isLibrary(lib)); assertFalse(Calibre.isLibrary(home));
        Calibre.Shelf shelf = Calibre.read(lib);
        assertEquals("metadata.db", shelf.readFrom());
        assertEquals(3, shelf.books().size());
        Calibre.Book tides = shelf.books().get(0), wizard = shelf.books().get(1), draft = shelf.books().get(2);
        assertEquals("Tides, Surges and Mean Sea-Level", tides.title()); assertEquals("David Pugh", tides.authors()); assertEquals("1987", tides.year());
        assertEquals("Oceanography, Tides", tides.tags()); assertEquals("9780471915058", tides.isbn()); assertEquals("Wiley", tides.publisher()); assertEquals("EPUB, PDF", tides.formats());
        assertEquals("Ursula K. Le Guin & Charles Le Guin", wizard.authors()); assertEquals("Earthsea Cycle #1", wizard.series());
        assertEquals("", draft.year(), "Calibre's no-date is not a year");
        String csv = Calibre.csv(shelf.books());
        assertTrue(csv.startsWith("title,authors,year,series,tags,isbn,publisher,formats\n\"Tides, Surges and Mean Sea-Level\",David Pugh,1987,,\"Oceanography, Tides\",9780471915058,Wiley,\"EPUB, PDF\"\n"), csv);
        // items reads that CSV: the title column is picked by its name, the rest is the note
        List<Items.Item> items = Items.parse(csv, null);
        assertEquals(3, items.size());
        assertEquals("Tides, Surges and Mean Sea-Level", items.get(0).name());
        assertEquals("David Pugh, 1987, Oceanography, Tides, 9780471915058, Wiley, EPUB, PDF", items.get(0).note());
        assertEquals("A Wizard of Earthsea", items.get(1).name());
        // a CSV with the title column second still finds it without --column
        assertEquals("Dune", Items.parse("author,title,year\nHerbert,Dune,1965\n", null).get(0).name());
        assertEquals("Herbert", Items.parse("author,title,year\nHerbert,Dune,1965\n", "author").get(0).name(), "--column still wins");
        // a bare metadata.db handed to any reader — add, items, a research run's file read — is the books, not binary fragments
        org.researchzosho.tools.DocText.Doc doc = org.researchzosho.tools.DocText.convert(Files.readAllBytes(lib.resolve("metadata.db")), "metadata.db");
        assertEquals("calibre", doc.kind()); assertEquals("Calibre library (3 books)", doc.title());
        assertTrue(doc.text().startsWith("title,authors,year,series,tags,isbn,publisher,formats\n"), doc.text());
        LibraryStore store2 = new LibraryStore(home.resolve("lib2")); store2.init();
        new LibrarianIndex(store2, Embeddings.none()).rebuild();
        ObjectNode a2 = M.createObjectNode().put("path", lib.resolve("metadata.db").toString()).put("as", "none");
        a2.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r2 = new LibraryProtocol(store2).items(a2);
        assertEquals(3, r2.path("taken").asInt()); assertEquals("Calibre library (3 books)", r2.path("title").asText()); assertEquals("metadata.db", r2.path("read_from").asText());
        assertEquals("A Wizard of Earthsea", r2.path("items").get(1).path("item").asText());
        // any other SQLite file is its tables and counts, not fragments
        Path other = home.resolve("other.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + other); Statement st = c.createStatement()) { st.execute("CREATE TABLE notes (id INTEGER, body TEXT)"); st.execute("INSERT INTO notes VALUES (1, 'a'), (2, 'b')"); }
        org.researchzosho.tools.DocText.Doc od = org.researchzosho.tools.DocText.convert(Files.readAllBytes(other), "other.db");
        assertEquals("sqlite", od.kind()); assertTrue(od.text().contains("- notes: 2 row(s)"), od.text());
        assertFalse(org.researchzosho.tools.DocText.isSqlite("SQLite format 3 is a phrase".getBytes()), "the header is sixteen exact bytes, not the words");
        // a question that names the database: the library reads it in before the run, and tells the run where to look
        LibraryStore store3 = new LibraryStore(home.resolve("lib3")); store3.init();
        new LibrarianIndex(store3, Embeddings.none()).rebuild();
        // an old unreadable copy, as an earlier version saved it: the readable one replaces it
        java.nio.file.Path old = store3.rawDir().resolve("2026-09-01-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((lib.resolve("metadata.db").toUri().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 6) + ".md");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "---\nurl: " + lib.resolve("metadata.db").toUri() + "\ntitle: metadata\n---\nSQLite format 3\u0000\u0010\u0000\u0001\u0001 binary \u0000\u0000\u0000");
        assertTrue(RawCapture.looksBinary(RawCapture.read(old)[2]), "a database saved as text is unreadable");
        ObjectNode ask = M.createObjectNode().put("question", "First, read the calibre database at " + lib.resolve("metadata.db") + " with sqlite3, then recommend books I do not own.");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        LibraryProtocol p3 = new LibraryProtocol(store3);
        ObjectNode filed = p3.research(ask);
        assertTrue(filed.path("job_id").asText().startsWith("J-"));
        assertEquals(3, Holdings.size(store3), "the books are a list the run can look up");
        assertFalse(Files.exists(old), "the old unreadable copy of the file is gone");
        var jobs3 = new Jobs(store3, j -> { throw new IllegalStateException("read only"); });
        String q3 = jobs3.active().get(0).path("args").path("question").asText();
        assertTrue(q3.contains("[Before this run the library read in: the Calibre library at " + lib.resolve("metadata.db")) && q3.contains("(3 books)") && q3.contains("holdings tool"), q3);
        // a stranger's question does not make the library open files
        ObjectNode stranger = M.createObjectNode().put("question", "Read " + lib.resolve("metadata.db") + " and list the books in it please.");
        stranger.putObject("patron").put("did", "did:key:zStranger").put("name", "s").put("runtime", "mcp");
        Patrons.setDefault(store3, Patrons.Level.write);
        assertTrue(p3.readInNamedFiles(stranger.path("question").asText(), stranger.path("patron"), Patrons.Patron.from(stranger)).isEmpty());
        // a path with a space in it (Calibre's own default folder) is found whole, with the punctuation after it left out
        assertEquals(List.of(lib.resolve("metadata.db")), LibraryProtocol.namedPaths("read " + lib.resolve("metadata.db") + ", then stop."));
        assertEquals(List.of(lib), LibraryProtocol.namedPaths("my books are in " + lib + " (the whole folder)"));
        // a path that does not exist is left alone
        assertTrue(p3.readInNamedFiles("see /no/such/file.db please", ask.path("patron"), Patrons.Patron.from(ask)).isEmpty());
        // an update repairs what an earlier version saved wrongly, by itself, once per version
        LibraryStore store4 = new LibraryStore(home.resolve("lib4")); store4.init();
        new LibrarianIndex(store4, Embeddings.none()).rebuild();
        String loc = lib.resolve("metadata.db").toUri().toString();
        java.nio.file.Path bad = store4.rawDir().resolve("2026-09-01-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(loc.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 6) + ".md");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "---\nurl: " + loc + "\ntitle: metadata\n---\nSQLite format 3\u0000\u0010\u0000\u0001\u0001 binary \u0000\u0000\u0000");
        java.nio.file.Path orphan = store4.rawDir().resolve("2026-09-01-aaaaaaaaaaaa.md");
        Files.writeString(orphan, "---\nurl: file:///no/such/file.pdf\ntitle: gone\n---\n%PDF-1.7 \u0000\u0001\u0002 binary");
        assertEquals(2, Repairs.unreadable(store4).size());
        assertFalse(Repairs.doneFor(store4, "9.9.9"));
        Repairs.Outcome fixed = Repairs.onceFor(store4, "9.9.9");
        assertEquals(1, fixed.repaired().size(), fixed.toString());
        assertTrue(fixed.repaired().get(0).contains("is now the list \"Calibre library (3 books)\""), fixed.repaired().get(0));
        assertEquals(1, fixed.leftAlone().size(), "the page whose file is gone is left alone and counted");
        assertFalse(Files.exists(bad), "the unreadable copy is gone"); assertTrue(Files.exists(orphan));
        assertEquals(3, Holdings.size(store4), "and the books can be looked up");
        assertTrue(fixed.summary().startsWith("1 repaired, 1 unreadable and left alone"), fixed.summary());
        assertNull(Repairs.onceFor(store4, "9.9.9"), "once per version");
        assertNotNull(Repairs.onceFor(store4, "9.9.10"), "and again after the next update");
        // the database stays untouched: the file is the same afterwards
        long before = Files.size(lib.resolve("metadata.db"));
        Calibre.read(lib);
        assertEquals(before, Files.size(lib.resolve("metadata.db")));
    }

    @Test
    void withoutTheDatabaseTheOpfFilesAreReadAndTheProtocolTakesTheFolderAsAList(@TempDir Path home) throws Exception {
        Path lib = library(home, false);
        Calibre.Shelf shelf = Calibre.read(lib);
        assertEquals("metadata.opf files", shelf.readFrom());
        assertEquals(2, shelf.books().size());
        Calibre.Book tides = shelf.books().get(0), wizard = shelf.books().get(1);
        assertEquals("Tides, Surges and Mean Sea-Level", tides.title()); assertEquals("David Pugh", tides.authors()); assertEquals("1987", tides.year());
        assertEquals("Oceanography, Tides", tides.tags()); assertEquals("9780471915058", tides.isbn()); assertEquals("Wiley", tides.publisher()); assertEquals("EPUB", tides.formats(), "the cover is not a format");
        assertEquals("Earthsea Cycle #1", wizard.series()); assertEquals("MOBI", wizard.formats());
        // a broken database falls back to the OPF files and says so
        Files.writeString(lib.resolve("metadata.db"), "not a database");
        Calibre.Shelf fallback = Calibre.read(lib);
        assertEquals(2, fallback.books().size());
        assertTrue(fallback.readFrom().startsWith("metadata.opf files (metadata.db could not be read"), fallback.readFrom());
        Files.delete(lib.resolve("metadata.db"));
        // the protocol: a folder that is a Calibre library is its books; as=none looks and files nothing
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("path", lib.toString()).put("as", "none");
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.items(a);
        assertEquals(2, r.path("taken").asInt(), r.toString());
        assertEquals("Calibre library Calibre Library", r.path("title").asText());
        assertEquals("metadata.opf files", r.path("read_from").asText());
        assertEquals("Tides, Surges and Mean Sea-Level", r.path("items").get(0).path("item").asText());
        assertTrue(r.path("items").get(0).path("question").asText().startsWith("Tides, Surges and Mean Sea-Level: what it is"), r.path("items").get(0).path("question").asText());
        assertEquals(0, Frontier.read(store).size());
        // a folder that is not a Calibre library is refused with the way out named
        Path plain = home.resolve("plain"); Files.createDirectories(plain);
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.items(a.deepCopy().put("path", plain.toString())));
        assertTrue(e.getMessage().contains("not a Calibre library") && e.getMessage().contains("library_add"), e.getMessage());
    }
}
