package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadInTest {
    @Test
    void aReportIsReadInAnotherLanguageTranslatedOnceAndKeptAndAPersonReadsEveryReportInTheirs(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = CrossLinksTest.report(store, "What does Acme Corp make? A short report.", "\n## Products\n\nAcme Corp makes widgets in Boston [1].\n\n## Sources\n\n[1] https://example.org/acme\n");
        Researcher.Drive drive = TranslateTest.standIn(text -> "【訳】" + text);
        Translate.Translated t = Translate.reportIn(store, id, "ja", drive);
        assertTrue(t.fresh() && t.pieces() >= 3 && t.kept() == 0, t.summary());
        assertTrue(t.summary().startsWith("The report \"What does Acme Corp make?"), t.summary());
        assertTrue(t.markdown().contains("【訳】## Products") && t.markdown().contains("[1]") && t.markdown().contains("https://example.org/acme"), t.markdown());
        assertTrue(Files.exists(Translate.reportFile(store, id, "ja")));
        assertNotNull(Translate.currentReport(store, id, "Japanese"));
        Translate.Translated again = Translate.reportIn(store, id, "ja", null);   // no model needed: the report has not changed
        assertFalse(again.fresh()); assertEquals(t.markdown(), again.markdown());
        // the page: Read in at the head, the line says what it was written in and read in, the body translated; as written at its own address
        LibraryProtocol p = new LibraryProtocol(store);
        String page = Pages.entry(store, p, Patrons.Patron.PERSON, id, Map.of("in", "ja"));
        assertTrue(page.contains("Written in English, read in Japanese.") && page.contains("【訳】") && page.contains("<b>Read in</b>") && page.contains("?in=written\">as written</a>") && page.contains("Always read in Japanese"), page);
        String original = Pages.entry(store, p, Patrons.Patron.PERSON, id, Map.of());
        assertTrue(!original.contains("【訳】") && original.contains("?in=ja\">日本語</a>") && !original.contains("Written in English"), original);   // English, read as written: the default is left unsaid
        // the person's own language: every report opens in it, Past says what each was written in, and one not yet translated says so
        assertEquals("", ReadIn.of(store, Patrons.Patron.PERSON));
        ObjectNode a = new ObjectMapper().createObjectNode(); a.put("op", "read_in"); a.put("value", "Japanese"); a.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
        String note = p.settings(a).path("note").asText();
        assertTrue(note.startsWith("You read every report in Japanese now."), note);
        assertEquals("ja", ReadIn.of(store, Patrons.Patron.PERSON));
        ObjectNode list = new ObjectMapper().createObjectNode(); list.put("op", "list"); list.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
        assertEquals("ja", p.settings(list).path("read_in").asText());
        String mine = Pages.entry(store, p, Patrons.Patron.PERSON, id, Map.of());
        assertTrue(mine.contains("【訳】") && mine.contains("Written in English, read in Japanese.") && mine.contains("As written from now on"), mine);
        String id2 = CrossLinksTest.report(store, "Where is Acme Corp? Another short report.", "");
        String notYet = Pages.entry(store, p, Patrons.Patron.PERSON, id2, Map.of());
        assertTrue(notYet.contains("Written in English.") && notYet.contains("No translation into Japanese yet") && !notYet.contains("【訳】"), notYet);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            String past = Pages.past(d, store, p, Patrons.Patron.PERSON, Map.of());
            assertTrue(past.contains("Written in English, read in Japanese.") && past.contains("Written in English.</span>"), past);
        } finally { d.stop(); }
        // through the protocol: library_get with in, the library's model stood in for; a report in the language asked for is as it is
        Translate.DRIVE = () -> TranslateTest.standIn(text -> "JA: " + text);
        try {
            ObjectNode g = new ObjectMapper().createObjectNode(); g.put("id", id2); g.put("in", "ja");
            JsonNode r = p.get(g).path("entry");
            assertEquals("en", r.path("written_in").asText()); assertEquals("ja", r.path("read_in").asText());
            assertTrue(r.path("body").asText().contains("JA: ") && r.path("title").asText().contains("JA: "), r.toString());
            g.put("in", "English"); r = p.get(g).path("entry");
            assertEquals("en", r.path("read_in").asText()); assertFalse(r.path("body").asText().contains("JA: "), r.toString());
        } finally { Translate.DRIVE = Explain::drive; }
        a.put("value", ""); assertTrue(p.settings(a).path("note").asText().contains("as it was written"));
        assertEquals("", ReadIn.of(store, Patrons.Patron.PERSON));
        // a report whose language was decided when it was filed carries it on its record, and says so on its page and its Past row, whoever reads it
        String id3 = store.nextInvestigationId("Wo ist Acme Corp? Ein kurzer Bericht.");
        store.write(new Investigation(id3, "Wo ist Acme Corp? Ein kurzer Bericht.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), "## Frage\n\nWo ist Acme Corp?\n\n## Antwort\n\nIn Boston [1].\n", "de"));
        Investigation de = store.investigation(id3);
        assertEquals("de", de.language()); assertEquals("de", Translate.writtenIn(de)); assertEquals("en", Translate.writtenIn(store.investigation(id2)), "a report with no record is read off its letters");
        assertTrue(Files.readString(store.investigationsDir().resolve(id3 + ".md")).contains("\nlanguage: de\n"));
        String german = Pages.entry(store, p, Patrons.Patron.PERSON, id3, Map.of());
        assertTrue(german.contains("Written in German.</p>") && german.contains("?in=en\">English</a>") && german.contains("?in=ja\">日本語</a>"), german);
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try { String past = Pages.past(d, store, p, Patrons.Patron.PERSON, Map.of()); assertTrue(past.contains("Written in German.</span>") && !past.contains("Written in English"), past); } finally { d.stop(); }
        ObjectNode g3 = new ObjectMapper().createObjectNode(); g3.put("id", id3);
        assertEquals("de", p.get(g3).path("entry").path("language").asText());
        assertFalse(p.get(new ObjectMapper().createObjectNode().put("id", id2)).path("entry").has("language"), "nothing recorded, nothing said");
        // the language the writer was told to write in: the question's own ask first, else the library's setting
        assertEquals("en", Researcher.languageDecided("in English, under 300 words")); assertEquals("ja", Researcher.languageDecided("a table, in Japanese"));
    }

    @Test
    void theLibrarysLanguageIsInTheWritersBriefUnlessTheQuestionAsksForOne(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        try {
            Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
            assertEquals("", Translate.libraryLanguage()); assertEquals("", Researcher.languageLine("")); assertEquals("", Researcher.languageDecided("a table"));
            Config.set("RESEARCHZOSHO_LANGUAGE", "ja");
            assertEquals("Japanese", Translate.libraryLanguage()); assertEquals("ja", Researcher.languageDecided("a table")); assertEquals("en", Researcher.languageDecided("in English"));
            assertTrue(Researcher.languageLine("").contains("in Japanese, whatever language the question"), Researcher.languageLine(""));
            assertTrue(Researcher.languageLine("a table, by year").contains("Japanese"));
            assertEquals("", Researcher.languageLine("in English, under 300 words"));
            Config.set("RESEARCHZOSHO_LANGUAGE", "Swahili");
            assertEquals("Swahili", Translate.libraryLanguage()); assertEquals("swahili", Researcher.languageDecided("")); assertEquals("Swahili", ReadIn.name("swahili"));
            assertNotNull(Settings.named("language"));
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }
}
