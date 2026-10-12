package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The windows and the excerpt fall where the measurement tooling's did, on the same text; the EPUB reader's text is the tooling's. */
class WindowsTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test void theWindowsFallWhereTheToolingsDid(@TempDir Path tmp) throws Exception {
        Path epub = tmp.resolve("sample-en.epub");
        try (InputStream in = WindowsTest.class.getResourceAsStream("/dna/sample-en.epub")) { Files.write(epub, in.readAllBytes()); }
        JsonNode want;
        try (InputStream in = WindowsTest.class.getResourceAsStream("/dna/sample-windows.json")) { want = M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        List<String> words = Windows.words(EpubText.chapters(epub));
        assertEquals(want.get("words").asInt(), words.size());
        List<Windows.Window> wins = Windows.cut(words, want.get("k").asInt(), want.get("w").asInt());
        assertEquals(want.get("windows").size(), wins.size());
        for (int i = 0; i < wins.size(); i++) {
            JsonNode w = want.get("windows").get(i); Windows.Window g = wins.get(i);
            assertEquals(w.get("pos").asDouble(), g.pos(), 0.0, "position of window " + i);
            assertEquals(w.get("of").asInt(), g.of());
            String[] ws = g.text().split(" ");
            assertEquals(w.get("first").asText(), ws[0]); assertEquals(w.get("last").asText(), ws[ws.length - 1]); assertEquals(w.get("words").asInt(), ws.length);
        }
        String excerpt = Windows.excerpt(words);
        assertEquals(want.get("excerpt_words").asInt(), Windows.words(excerpt).size());
        assertEquals(want.get("excerpt_head").asText(), excerpt.substring(0, 60));
        assertEquals(want.get("excerpt_tail").asText(), excerpt.substring(excerpt.length() - 60));
        assertEquals(4, Windows.cut(words).size(), "6,678 words give four settled windows of 1,500");
        assertTrue(Windows.cut(words.subList(0, 2999)).isEmpty(), "under two windows' worth gives none");
        assertNull(Windows.excerpt(words.subList(0, 4999)));
    }

    @Test void htmlBecomesTextTheWayTheToolingsParserMadeIt() {
        String html = "<?xml version='1.0'?>\n<!DOCTYPE html>\n<html><head><title>T</title><style>p{}</style></head><body><h1>One</h1>\n<!-- gone --><p>A &amp; B<br/>c</p><svg><text>no</text></svg><div>d</div></body></html>";
        assertEquals("\n\n\nOne\n\n\nA & B\n\nc\n\nd\n", EpubText.text(html));
    }

    @Test void aLooseFileTellsItsOwnRecord(@TempDir Path tmp) throws Exception {
        Path epub = tmp.resolve("Some Title - Some Author.epub");
        try (InputStream in = WindowsTest.class.getResourceAsStream("/dna/sample-en.epub")) { Files.write(epub, in.readAllBytes()); }
        Map<String, Object> r = EpubText.record(epub);
        assertEquals("The Harbour Ledger", r.get("title")); assertEquals("Tamsin Vorle", r.get("authors")); assertEquals("2021", r.get("year")); assertEquals("en", r.get("language"));
        assertEquals(List.of("Invented prose", "Testing"), r.get("tags")); assertEquals("9780000000999", r.get("isbn"));
        Files.write(tmp.resolve("Bare Name - Another Author_3.epub"), new byte[]{0});
        Map<String, Object> bare = EpubText.record(tmp.resolve("Bare Name - Another Author_3.epub"));
        assertEquals("Bare Name", bare.get("title")); assertEquals("Another Author", bare.get("authors"));
    }

    @Test void roundingIsPythons() {
        assertEquals(0.67, Py.round(2.0 / 3, 2)); assertEquals(0.33, Py.round(1.0 / 3, 2)); assertEquals(2.67, Py.round(2.675, 2), "2.675 is below the tie in binary, as Python sees it");
        assertEquals(0.5, Py.round(0.5, 2)); assertEquals(1.0, Py.round(1.0, 1));
        assertEquals(1.0, Py.pstdev(List.of(1, 3)), 1e-12); assertEquals(2.0, Py.fmean(List.of(1, 2, 3)), 0.0); assertEquals(2.5, Py.median(List.of(1, 2, 3, 4)), 0.0);
    }
}
