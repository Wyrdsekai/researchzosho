package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The measured layer counts what the measurement tooling counted: two invented EPUBs (an English one with dialogue, eight chapters and a
 * short note; a Japanese one), their features by the Python measurer as the expected values, every feature to the digit — the EPUB reader,
 * the tokeniser, the sentence split, the lexicon shares and the arc with its Hurst exponent and entropy.
 */
class MeasuredParityTest {

    static final ObjectMapper M = new ObjectMapper();

    static Path copy(Path dir, String name) throws Exception {
        try (InputStream in = MeasuredParityTest.class.getResourceAsStream("/dna/" + name)) { assertNotNull(in, name); Path p = dir.resolve(name); Files.write(p, in.readAllBytes()); return p; }
    }

    static JsonNode expected() throws Exception {
        try (InputStream in = MeasuredParityTest.class.getResourceAsStream("/dna/sample-measured.json")) { return M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
    }

    static List<String> compare(JsonNode want, ObjectNode got) {
        List<String> diffs = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = want.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode g = got.get(e.getKey());
            if (g == null) { diffs.add(e.getKey() + ": missing"); continue; }
            if (e.getValue().isNumber()) { if (!g.isNumber() || Math.abs(e.getValue().asDouble() - g.asDouble()) > 1e-9) diffs.add(e.getKey() + ": expected " + e.getValue() + ", got " + g); }
            else if (!e.getValue().equals(g)) diffs.add(e.getKey() + ": expected " + e.getValue() + ", got " + g);
        }
        for (Iterator<String> it = got.fieldNames(); it.hasNext(); ) { String k = it.next(); if (!want.has(k)) diffs.add(k + ": unexpected " + got.get(k)); }
        return diffs;
    }

    @Test void theEnglishSampleMeasuresAsTheToolingMeasuredIt(@TempDir Path tmp) throws Exception {
        JsonNode want = expected().get("sample-en");
        List<String> chapters = EpubText.chapters(copy(tmp, "sample-en.epub"));
        assertEquals(want.get("chapters_read").asInt(), chapters.size());
        for (int i = 0; i < chapters.size(); i++) assertEquals(want.get("chapter_words").get(i).asInt(), Measured.count(chapters.get(i)), "words of chapter " + i);
        assertTrue(chapters.get(0).contains("A note"), "the spine's order, not the entries' names: " + chapters.get(0).substring(0, Math.min(80, chapters.get(0).length())));
        List<String> diffs = compare(want.get("features"), Measured.features(chapters));
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
    }

    @Test void theJapaneseSampleMeasuresAsTheToolingMeasuredIt(@TempDir Path tmp) throws Exception {
        JsonNode want = expected().get("sample-ja");
        List<String> chapters = EpubText.chapters(copy(tmp, "sample-ja.epub"));
        assertEquals(want.get("chapters_read").asInt(), chapters.size());
        ObjectNode got = Measured.features(chapters);
        assertEquals("ja", got.get("language_guess").asText());
        List<String> diffs = compare(want.get("features"), got);
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
    }

    @Test void tooLittleTextIsSaidNotMeasured() {
        ObjectNode f = Measured.features(List.of("A few words only."));
        assertEquals("too little text", f.get("note").asText());
        assertFalse(f.has("sentences"));
    }

    @Test void theLexiconIsThere() {
        assertTrue(Measured.LEX.size() > 7000, "the VADER lexicon ships as a resource");
        assertEquals(3.2, Measured.LEX.get("love"), 0.01);
    }
}
