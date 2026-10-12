package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.Config;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.DnaCard;
import org.researchzosho.librarian.dna.Labels;
import org.researchzosho.librarian.dna.ShelfDna;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shelf book's DNA (0.6.0): the EPUB behind the Calibre row is found, its measured layer counted, its labelled layer made by the model
 * once and kept by the file's hash and the prompt's version, the night's step reads the shelf within its budget and says what it did,
 * and the my_shelf protocol hands the card over.
 */
class ShelfDnaTest {

    static final ObjectMapper M = new ObjectMapper();

    @AfterEach void reset() { Shelf.override = null; Dna.useDir(null); }

    /** The shelf test's library with one more book that has an EPUB file: the invented English sample. */
    static Path shelfWithAFile(Path dir) throws Exception {
        Path db = ShelfTest.calibre(dir);
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + Calibre.sqliteUri(db.toAbsolutePath().toString())); Statement st = c.createStatement()) {
            st.execute("INSERT INTO books VALUES (7,'The Harbour Ledger','Harbour Ledger','2024-01-02 10:00:00+00:00','2021-03-04 00:00:00+00:00',1.0,'','Tamsin Vorle/The Harbour Ledger (7)')");
            st.execute("INSERT INTO books_authors_link VALUES (7,7,1)");
            st.execute("INSERT INTO books_tags_link VALUES (12,7,1)");
            st.execute("INSERT INTO data VALUES (1,7,'EPUB','The Harbour Ledger - Tamsin Vorle',18871)");
        }
        Path book = dir.resolve("Tamsin Vorle").resolve("The Harbour Ledger (7)");
        Files.createDirectories(book);
        try (InputStream in = ShelfDnaTest.class.getResourceAsStream("/dna/sample-en.epub")) { Files.write(book.resolve("The Harbour Ledger - Tamsin Vorle.epub"), in.readAllBytes()); }
        return db;
    }

    /** A model with canned replies: the book-level answer first, then one window reply per call, counted. */
    static Labels.Model stub(AtomicInteger calls) {
        return ShelfDna.named((system, user, max) -> {
            int n = calls.incrementAndGet();
            if (system.equals(Labels.BOOK_SYSTEM)) return "{\"pace\": \"steady\", \"mood\": [\"wry\", \"warm\"], \"style\": \"plain\", \"drive\": \"character\", \"scale\": \"modest\", \"setting\": \"a harbour town, the 1990s\", \"hardness\": \"not fantastic\", \"darkness\": \"mild\", \"violence\": \"none\", \"explicitness\": \"none\", \"humour\": \"light\", \"demand\": \"easy\", \"audience\": \"adult\", \"kind\": \"novel\", \"themes\": [\"harbours\", \"ledgers\", \"small towns\"], \"what_it_feels_like\": \"A quiet book about a town and its books.\", \"confidence\": \"medium\"}";
            assertTrue(user.contains("heart-warming"), "a novel's windows get the fiction vocabulary");
            boolean odd = n % 2 == 1;
            return "{\"mood_most\": [" + (odd ? "\"gentle\", \"nostalgic\"" : "\"whimsical\", \"gentle\"") + "], \"mood_least\": \"menacing\", \"pace\": \"" + (odd ? "slower" : "about the same") + "\", \"darkness\": \"lighter\", \"scale\": \"a few people\", \"humour\": \"a little\", \"style\": \"about the same\", \"drive\": \"character\", \"topics\": [\"harbour\", \"ledgers\", \"" + (odd ? "boats" : "shops") + "\"], \"what_happens\": \"People talk in the town.\"}";
        }, "stub");
    }

    @Test void aShelfBookIsReadCountedLabelledAndKept(@TempDir Path tmp) throws Exception {
        Shelf.override = shelfWithAFile(tmp.resolve("calibre"));
        Dna.useDir(tmp.resolve("dna"));
        Shelf.Book ledger = Shelf.books().stream().filter(b -> b.id() == 7).findFirst().orElseThrow();
        assertNotNull(ShelfDna.epub(ledger));
        assertNull(ShelfDna.epub(Shelf.books().stream().filter(b -> b.id() == 1).findFirst().orElseThrow()), "a book without a file has no EPUB");
        ObjectNode measured = ShelfDna.measure(ledger);
        assertEquals(6678, measured.path("measured").path("features").path("words").asInt());
        assertEquals("The Harbour Ledger", measured.path("record").path("title").asText());
        assertTrue(measured.path("record").path("sources").has("calibre"));
        assertFalse(measured.path("record").has("rating"), "the person's stars are preference, not the book");
        assertEquals(1, Dna.counts(Dna.BOOK)[0]); assertEquals(0, Dna.counts(Dna.BOOK)[1]);
        AtomicInteger calls = new AtomicInteger();
        List<String> progress = new ArrayList<>();
        ObjectNode d = ShelfDna.label(ledger, stub(calls), progress::add);
        assertEquals(5, calls.get(), "the book-level question and four windows of 1,500 over 6,678 words");
        assertEquals(List.of("the book-level question over the excerpt", "window 1 of 4", "window 2 of 4", "window 3 of 4", "window 4 of 4"), progress);
        ObjectNode l = (ObjectNode) d.path("labelled");
        assertEquals(4, l.path("windows").asInt()); assertEquals("novel", l.path("kind").asText()); assertEquals("stub", l.path("model").asText()); assertEquals(Labels.VERSION, l.path("version").asText());
        assertEquals(1.0, l.path("mood").path("gentle").asDouble()); assertEquals(0.5, l.path("mood").path("nostalgic").asDouble()); assertEquals("menacing", l.path("least").asText());
        assertEquals(0.5, l.path("scales").path("pace").path("agreement").asDouble()); assertEquals(1.0, l.path("scales").path("darkness").path("agreement").asDouble());
        assertEquals("harbour", l.path("topics").get(0).asText()); assertEquals(4, l.path("topics").size());
        assertEquals("adult", l.path("book").path("audience").asText());
        assertEquals(1, Dna.labelled(Dna.BOOK).size()); assertEquals(1, Dna.counts(Dna.BOOK)[1]);
        // kept: the same book again asks the model nothing
        ShelfDna.label(ledger, stub(calls), null);
        assertEquals(5, calls.get());
        // a new prompt version: the old labels are stale, the index drops them, the book is labelled again
        ObjectNode stale = Dna.read(Dna.BOOK, d.path("key").asText());
        ((ObjectNode) stale.path("labelled")).put("version", "labelled-0.0");
        Dna.write(stale);
        assertEquals(0, Dna.labelled(Dna.BOOK).size());
        ShelfDna.label(ledger, stub(calls), null);
        assertEquals(10, calls.get());
        assertEquals(1, Dna.labelled(Dna.BOOK).size());
        String card = DnaCard.text(Dna.read(Dna.BOOK, d.path("key").asText()));
        assertTrue(card.contains("6,678 words in 8 chapters"), card);
        assertTrue(card.contains("Over 4 windows of the text, against an ordinary novel: more gentle in 4 of 4, nostalgic in 2 of 4"), card);
        assertTrue(card.contains("Themes: harbours, ledgers, small towns."), card);
        assertTrue(card.contains("Pace: slower in 2 of 4, rising towards the end."), card);
    }

    @Test void theNightReadsTheShelfWithinItsBudgetAndSaysWhatItDid(@TempDir Path tmp) throws Exception {
        Shelf.override = shelfWithAFile(tmp.resolve("calibre"));
        Dna.useDir(tmp.resolve("dna"));
        AtomicInteger calls = new AtomicInteger();
        String said = ShelfDna.nightly(60_000, stub(calls), () -> false);
        assertEquals("1 of the 1 books with a file have their DNA counted and 1 are labelled; tonight 1 labelled (0 seconds a book) and 0 counted", said.replaceAll("\\((\\d+) seconds", "(0 seconds"));
        assertEquals(5, calls.get());
        assertEquals("1 of the 1 books with a file have their DNA counted and 1 are labelled; tonight 0 labelled and 0 counted", ShelfDna.nightly(60_000, stub(calls), () -> false));
        assertEquals(5, calls.get(), "a second night asks nothing for a book already read");
        Dna.useDir(tmp.resolve("dna2"));
        assertEquals("1 of the 1 books with a file have their DNA counted and 0 are labelled; tonight 0 labelled and 1 counted (no model answered, so none was labelled)", ShelfDna.nightly(60_000, null, () -> false));
        assertEquals("turned off (RESEARCHZOSHO_DNA_MINUTES is 0)", ShelfDna.nightly(0, null, () -> false));
        Shelf.override = tmp.resolve("nowhere.db");
        assertEquals("no Calibre library is set", ShelfDna.nightly(60_000, null, () -> false));
    }

    @Test void theProtocolHandsTheCardOver(@TempDir Path tmp) throws Exception {
        Shelf.override = shelfWithAFile(tmp.resolve("calibre"));
        Dna.useDir(tmp.resolve("dna"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "p").put("runtime", "cli");
        a.put("op", "dna"); a.put("title", "The Harbour Ledger"); a.put("label", false);
        ObjectNode r = new LibraryProtocol(store).myShelf(a);
        assertTrue(r.path("found").asBoolean());
        assertTrue(r.path("summary").asText().startsWith("The Harbour Ledger — Tamsin Vorle (2021): its measured layer."), r.path("summary").asText());
        assertTrue(r.path("summary").asText().endsWith("No model answers now, so the labelled layer waits for the night housekeeping."), r.path("summary").asText());
        assertTrue(r.path("text").asText().contains("6,678 words"));
        a.put("title", "No Such Book");
        assertEquals("No book on the shelf has that title.", new LibraryProtocol(store).myShelf(a).path("summary").asText());
        a.put("title", "The Salt Road");
        assertEquals("The shelf has \"The Salt Road: A Walk Across Hokkaido\" but no EPUB file for it, so there is no text to read.", new LibraryProtocol(store).myShelf(a).path("summary").asText());
    }

    @Test void theProtocolSaysWhenACallWasShortenedTo240Minutes(@TempDir Path tmp) throws Exception {
        // the operator asked for 480 minutes and got 240 without a word (2026-10-11). The read without a title asks the configured
        // drive, so the test runs from its own home with the drive at a closed port: on the Mac mini a model answers at the default
        // address and this test read the fixture book with it (the export suite there, 2026-10-11)
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:9");
            Shelf.override = shelfWithAFile(tmp.resolve("calibre"));
            Dna.useDir(tmp.resolve("dna"));
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "p").put("runtime", "cli");
            a.put("op", "dna"); a.put("minutes", 480);
            String said = new LibraryProtocol(store).myShelf(a).path("summary").asText();
            assertTrue(said.contains("(no model answered, so none was labelled). One call reads for at most 240 minutes, so this one was shortened to 240. The night housekeeping goes on from here, 20 minutes a night."), said);
            a.put("minutes", 30);
            said = new LibraryProtocol(store).myShelf(a).path("summary").asText();
            assertTrue(!said.contains("shortened"), said);
            assertEquals("", LibraryProtocol.shortened(240));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
