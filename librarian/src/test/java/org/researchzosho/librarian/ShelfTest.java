package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.MyShelfTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The person's own shelf (0.6.0): the Calibre library read into a profile where ratings outrank possession, "do I have this", and what was liked on a subject. */
class ShelfTest {

    static final ObjectMapper M = new ObjectMapper();

    @AfterEach void reset() { Shelf.override = null; }

    /** A Calibre-shaped database with six invented books. */
    static Path calibre(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path db = dir.resolve("metadata.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + Calibre.sqliteUri(db.toAbsolutePath().toString())); Statement st = c.createStatement()) {
            for (String ddl : List.of(
                    "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, sort TEXT, timestamp TEXT, pubdate TEXT, series_index REAL DEFAULT 1.0, isbn TEXT DEFAULT '', path TEXT DEFAULT '')",
                    "CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT, sort TEXT)", "CREATE TABLE books_authors_link (id INTEGER PRIMARY KEY, book INTEGER, author INTEGER)",
                    "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_tags_link (id INTEGER PRIMARY KEY, book INTEGER, tag INTEGER)",
                    "CREATE TABLE ratings (id INTEGER PRIMARY KEY, rating INTEGER)", "CREATE TABLE books_ratings_link (id INTEGER PRIMARY KEY, book INTEGER, rating INTEGER)",
                    "CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_series_link (id INTEGER PRIMARY KEY, book INTEGER, series INTEGER)",
                    "CREATE TABLE languages (id INTEGER PRIMARY KEY, lang_code TEXT)", "CREATE TABLE books_languages_link (id INTEGER PRIMARY KEY, book INTEGER, lang_code INTEGER, item_order INTEGER DEFAULT 0)",
                    "CREATE TABLE identifiers (id INTEGER PRIMARY KEY, book INTEGER, type TEXT, val TEXT)", "CREATE TABLE data (id INTEGER PRIMARY KEY, book INTEGER, format TEXT, name TEXT, uncompressed_size INTEGER)",
                    "INSERT INTO books VALUES (1,'The Salt Road: A Walk Across Hokkaido','Salt Road','2024-03-01 10:00:00+00:00','2019-05-01 00:00:00+00:00',1.0,'',''),"
                            + "(2,'Moths of the Northern Islands','Moths','2023-01-05 10:00:00+00:00','2015-01-01 00:00:00+00:00',1.0,'',''),"
                            + "(3,'Kitchen Ledger','Kitchen Ledger','2022-06-06 10:00:00+00:00','2010-01-01 00:00:00+00:00',1.0,'',''),"
                            + "(4,'A Grammar of Lost Rivers','Grammar','2021-02-02 10:00:00+00:00','2001-01-01 00:00:00+00:00',1.0,'',''),"
                            + "(5,'Unrated Thing','Unrated','2024-08-08 10:00:00+00:00','2022-01-01 00:00:00+00:00',1.0,'',''),"
                            + "(6,'Harbour Lights','Harbour Lights','2020-09-09 10:00:00+00:00','1998-01-01 00:00:00+00:00',2.0,'','')",
                    "INSERT INTO authors VALUES (1,'Tamsin Vorle','Vorle'),(2,'Idris Quell','Quell'),(3,'Pell Marrow','Marrow'),(4,'Anonymous','Anonymous')",
                    "INSERT INTO books_authors_link VALUES (1,1,1),(2,2,2),(3,3,2),(4,4,1),(5,5,4),(6,6,3)",
                    "INSERT INTO tags VALUES (1,'Travel'),(2,'Hokkaido (Japan)'),(3,'Walking'),(4,'Natural history'),(5,'Moths'),(6,'Cooking'),(7,'General'),(8,'Linguistics'),(9,'Science Fiction'),(10,'Space Opera')",
                    "INSERT INTO books_tags_link VALUES (1,1,1),(2,1,2),(3,1,3),(4,2,4),(5,2,5),(6,3,6),(7,3,7),(8,4,8),(9,5,9),(10,6,9),(11,6,10)",
                    "INSERT INTO ratings VALUES (1,10),(2,8),(3,4),(4,9)", "INSERT INTO books_ratings_link VALUES (1,1,1),(2,2,2),(3,3,2),(4,4,3),(5,6,4)",
                    "INSERT INTO series VALUES (1,'Harbour')", "INSERT INTO books_series_link VALUES (1,6,1)",
                    "INSERT INTO languages VALUES (1,'eng')", "INSERT INTO books_languages_link VALUES (1,1,1,0),(2,2,1,0),(3,3,1,0),(4,4,1,0),(5,5,1,0),(6,6,1,0)",
                    "INSERT INTO identifiers VALUES (1,1,'isbn','9780000000011'),(2,2,'isbn','9780000000028')")) st.execute(ddl);
            st.execute("INSERT INTO authors VALUES (5,'Bulk Author','Author, Bulk')");
            for (int i = 1; i <= 60; i++) {   // an import that came with the files: sixty books, one day, every one with five stars nobody gave
                st.execute("INSERT INTO books VALUES (" + (100 + i) + ",'Bulk Title " + i + "','Bulk Title " + i + "','2013-12-05 10:00:00+00:00','2005-01-01 00:00:00+00:00',1.0,'','')");
                st.execute("INSERT INTO books_authors_link VALUES (" + (100 + i) + "," + (100 + i) + ",5)");
                st.execute("INSERT INTO books_ratings_link VALUES (" + (100 + i) + "," + (100 + i) + ",1)");
                st.execute("INSERT INTO books_tags_link VALUES (" + (100 + i) + "," + (100 + i) + ",9)");
                st.execute("INSERT INTO books_languages_link VALUES (" + (100 + i) + "," + (100 + i) + ",1,0)");
            }
        }
        return db;
    }

    @Test
    void theShelfBecomesAProfileWhereRatingsOutrankPossessionAndATitleIsFoundOrNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Shelf.override = calibre(tmp.resolve("calibre"));
        Files.writeString(Serials.shelvesFile(store), "- night-kitchen | podcast https://example.org/feed.xml | every 7 days | last -\n- moth-papers | moths Hokkaido new species | every 30 days | last -\n", StandardCharsets.UTF_8);

        ObjectNode p = Shelf.profile(store);
        assertEquals(66, p.path("books").asInt()); assertEquals(6, p.path("chosen").asInt()); assertEquals(60, p.path("bulk").asInt(), "sixty books on one day are an import, not picks");
        assertEquals(5, p.path("ratings_given").asInt()); assertEquals(60, p.path("ratings_imported").asInt(), "sixty stars on one day came with the files");
        assertEquals(5, p.path("liked").asInt(), "the chosen books not rated low, and any rated well: an unrated chosen book counts"); assertEquals(1, p.path("disliked").asInt());
        assertEquals("Idris Quell", p.path("authors_liked").get(0).path("author").asText(), "two chosen books; an author chosen once is not listed");
        assertEquals(1, p.path("authors_liked").size(), "the bulk author with sixty imported five-star books is nobody's taste: " + p.path("authors_liked"));
        assertTrue(p.path("subjects_liked").toString().contains("\"tag\":\"Moths\"") && !p.path("subjects_liked").toString().contains("General"), "a kind-word tag says nothing: " + p.path("subjects_liked"));
        assertTrue(p.path("subjects_liked").get(0).has("tilt"));
        assertTrue(p.path("loved").toString().contains("The Salt Road") && p.path("loved").toString().contains("Harbour Lights") && p.path("loved").toString().contains("4.5★") && !p.path("loved").toString().contains("Bulk Title"), p.path("loved").toString());
        assertEquals(6, p.path("chosen_sample").size(), p.path("chosen_sample").toString());
        assertTrue(p.path("disliked_sample").toString().contains("A Grammar of Lost Rivers"));
        assertEquals("night-kitchen — https://example.org/feed.xml", p.path("podcasts_followed").get(0).asText(), "the feed address, for shows like it"); assertTrue(p.path("searches_watched").get(0).asText().startsWith("moth-papers: "));
        assertEquals("Unrated Thing — Anonymous (2022), Science Fiction", p.path("added_lately").get(0).asText());

        String text = MyShelfTool.profile(p);
        assertTrue(text.startsWith("Shelf: 66 books, 6 added by the person one by one (their picks; the profile), 60 from bulk imports (holdings, not taste). Ratings: 5 given by the person, 60 came in with the files and are not counted. Stars given: 5★ 1, 4.5★ 1, 4★ 2, 2★ 1. 5 books count as what the person went for") && text.contains("Idris Quell (2, rated 4★)") && text.contains("Podcasts followed (each with its feed address; podcast_search kind=similar with a feed finds shows like it): night-kitchen — https://example.org/feed.xml."), text);

        assertEquals("shelf", Shelf.have(store, "the salt road", "").get(0).where(), "an article and a subtitle do not hide a book");
        assertEquals(1, Shelf.have(store, "Salt Road: A Walk Across Hokkaido", "Vorle").size());
        assertEquals("shelf", Shelf.have(store, "Salt Road", "Quell").get(0).where(), "a wrong author does not hide the book");
        assertTrue(Shelf.have(store, "Salt Road", "Quell").get(0).detail().endsWith(" — by Tamsin Vorle, not Quell"), Shelf.have(store, "Salt Road", "Quell").get(0).detail());
        assertEquals("shelf", Shelf.have(store, "978-0-00-000002-8", "").get(0).where(), "an ISBN finds the book");
        assertTrue(Shelf.have(store, "Nothing Of The Kind", "").isEmpty());

        assertEquals(List.of("Moths of the Northern Islands"), Shelf.liked("moths", 4, 20).stream().map(Shelf.Book::title).toList());
        assertEquals(2, Shelf.liked("quell", 4, 20).size());
        assertEquals(List.of("The Salt Road: A Walk Across Hokkaido", "Harbour Lights"), Shelf.liked("", 4.5, 20).stream().map(Shelf.Book::title).toList(), "best first");

        MyShelfTool tool = new MyShelfTool(store);
        assertTrue(tool.execute(M.readTree("{\"kind\":\"have\",\"title\":\"Harbour Lights\"}")).startsWith("Already here (1):\n- shelf: Harbour Lights — Pell Marrow (1998), 4.5★, Harbour, Science Fiction, Space Opera — rated"));
        String sf = tool.execute(M.readTree("{\"kind\":\"liked\",\"words\":\"science fiction\"}"));
        assertTrue(sf.contains("2 book(s) the person went for, best first for \"science fiction\":\n- Harbour Lights") && sf.contains("Unrated Thing") && !sf.contains("Bulk Title"), sf);
        ObjectNode a = M.createObjectNode(); a.put("op", "have"); a.put("title", "kitchen ledger"); a.putObject("patron").put("did", "person").put("name", "owner").put("runtime", "cli");
        ObjectNode r = new LibraryProtocol(store).myShelf(a);
        assertEquals(1, r.path("count").asInt()); assertTrue(r.path("summary").asText().startsWith("1 match(es): shelf — Kitchen Ledger"), r.path("summary").asText());
        assertTrue(Librarian.TOOLS.contains("library_my_shelf"));
    }

    @Test
    void withoutACalibreLibraryTheToolSaysSoAndTheRunGoesOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Shelf.override = tmp.resolve("none").resolve("metadata.db");
        MyShelfTool tool = new MyShelfTool(store);
        assertTrue(tool.execute(M.readTree("{\"kind\":\"profile\"}")).startsWith("No Calibre library is set (the setting calibre names its folder or metadata.db)"));
        assertTrue(tool.execute(M.readTree("{\"kind\":\"have\",\"title\":\"Harbour Lights\"}")).contains("no Calibre library is set; only the ledger and the lists were checked"));
        assertTrue(Shelf.have(store, "anything", "").isEmpty());
    }
}
