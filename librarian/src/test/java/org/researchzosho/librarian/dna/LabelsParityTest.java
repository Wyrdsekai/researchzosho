package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The labelled layer's parser and aggregator give the numbers the measurement tooling gave on the same raw replies: the 49 sample books
 * of the operator's shelf under the per-kind vocabulary (588 windows) and the 23 fiction books under the appeal terms (276 windows), the
 * model's replies as they came, the Python aggregator's output as the expected values. Every mood share, scale, agreement, triple,
 * dynamic and topic of every book, to the digit.
 */
class LabelsParityTest {

    static final ObjectMapper M = new ObjectMapper();

    static String resource(String name) throws Exception {
        try (InputStream in = LabelsParityTest.class.getResourceAsStream("/dna/" + name)) { assertNotNull(in, name); return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    /** The replies by book, in file order; a reply that does not parse is counted. */
    static Map<String, List<Labels.Reply>> replies(String jsonl, int[] unparsed) throws Exception {
        Map<String, List<Labels.Reply>> books = new LinkedHashMap<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode r = M.readTree(line);
            ObjectNode d = Labels.parse(r.path("raw").asText(""));
            if (d == null) { unparsed[0]++; continue; }
            books.computeIfAbsent(r.path("id").asText(), k -> new ArrayList<>()).add(new Labels.Reply(r.path("pos").asDouble(), d));
        }
        return books;
    }

    static void diff(JsonNode want, JsonNode got, String path, List<String> out) {
        if (want.isNumber() && got.isNumber()) { if (Math.abs(want.asDouble() - got.asDouble()) > 1e-9) out.add(path + ": expected " + want + ", got " + got); return; }
        if (want.isObject()) {
            if (!got.isObject()) { out.add(path + ": expected an object, got " + got); return; }
            for (Iterator<String> it = want.fieldNames(); it.hasNext(); ) { String k = it.next(); if (!got.has(k)) out.add(path + "." + k + ": missing"); else diff(want.get(k), got.get(k), path + "." + k, out); }
            for (Iterator<String> it = got.fieldNames(); it.hasNext(); ) { String k = it.next(); if (!want.has(k)) out.add(path + "." + k + ": unexpected " + got.get(k)); }
            return;
        }
        if (want.isArray()) {
            if (!got.isArray() || got.size() != want.size()) { out.add(path + ": expected " + want + ", got " + got); return; }
            for (int i = 0; i < want.size(); i++) diff(want.get(i), got.get(i), path + "[" + i + "]", out);
            return;
        }
        if (!want.equals(got)) out.add(path + ": expected " + want + ", got " + got);
    }

    void parity(String raw, String expected, int books, int windows) throws Exception {
        int[] unparsed = {0};
        Map<String, List<Labels.Reply>> all = replies(resource(raw), unparsed);
        assertEquals(0, unparsed[0], "every reply of the fixture parsed in the tooling too");
        assertEquals(books, all.size());
        assertEquals(windows, all.values().stream().mapToInt(List::size).sum());
        JsonNode want = M.readTree(resource(expected));
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, List<Labels.Reply>> e : all.entrySet()) {
            JsonNode w = want.get(e.getKey());
            assertNotNull(w, "the expected output has book " + e.getKey());
            ObjectNode got = Labels.aggregate(e.getValue());
            for (String field : List.of("windows", "mood", "least", "scales", "drive", "signature")) diff(w.get(field), got.get(field), e.getKey() + "." + field, diffs);
        }
        assertTrue(diffs.isEmpty(), diffs.size() + " difference(s), the first: " + String.join("\n", diffs.subList(0, Math.min(20, diffs.size()))));
    }

    @Test void theFortyNineUnderThePerKindVocabularyAggregateAsTheToolingDid() throws Exception { parity("labels-win49v2.jsonl", "labels-win49v2-aggregated.json", 49, 588); }

    @Test void theFictionBooksUnderTheAppealTermsAggregateAsTheToolingDid() throws Exception { parity("labels-win49v3.jsonl", "labels-win49v3-aggregated.json", 23, 276); }

    @Test void theParserRescuesADroppedQuoteAndForgivesATrailingComma() {
        ObjectNode d = Labels.parse("Here: {\n \"mood_most\": [\"gritty\", \"sardonic\"],\n \"mood_least\": \"gentle\",\n \"pace\": \"faster\",\n \"topics\": [\"heists\", \"cities\"],\n \"what_happens\": The crew meets.\"\n}");
        assertNotNull(d);
        assertEquals("gritty", d.get("mood_most").get(0).asText());
        assertEquals("faster", d.get("pace").asText());
        assertEquals(2, d.get("topics").size());
        assertNotNull(Labels.parse("{\"mood_most\": [\"dry\"], \"pace\": \"slower\",}"));
        assertNull(Labels.parse("no object here"));
        assertNotNull(Labels.parse("{\"pace\": \"slower\"}"), "valid JSON is taken as it is, moods or not");
        assertNull(Labels.parse("{\"pace\": slower}"), "a rescue without the moods is no reply");
    }

    @Test void theVocabularyFollowsTheKind() {
        assertTrue(Labels.windowPrompt("novel").contains("heart-warming"));
        assertTrue(Labels.windowPrompt("poetry").contains("an ordinary novel"));
        assertTrue(Labels.windowPrompt("history").contains("instructional"));
        assertTrue(Labels.windowPrompt("business").contains("an ordinary book of this kind (nonfiction)"));
        assertFalse(Labels.windowPrompt("history").contains("whimsical"));
        assertTrue(Labels.bookPrompt("[OPENING]\nx").contains("\"kind\": one of \"novel\", \"stories\""));
    }
}
