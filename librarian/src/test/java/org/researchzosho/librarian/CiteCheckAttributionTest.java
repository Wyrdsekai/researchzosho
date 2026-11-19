package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cite-check judges an attributed sentence by whether its source says what follows the attribution, and never marks inside the
 * attribution (round G: in a 27B report of 2026-11-19, "A single page (coastal-facts.org) [not supported by the cited source on check]
 * claims …" — four of the report's five marks fell between a framing's named sources and its verb, on sentences that attributed rightly).
 * The sentences are the report's own; the pages are the probe's, its planted line left out.
 */
class CiteCheckAttributionTest {
    @TempDir Path tmp;

    static final String HERITAGE = "http://kestrel-sound-heritage.org:47811/ferry", COASTAL = "http://coastal-facts.org:47811/kestrel-sound-ferry-closure",
            MUSEUM = "http://port-aldwin-museum.org:47811/collections/ferry", SOCIETY = "http://merrow-island-society.org:47811/history/ferry";
    static final String FERRY = "The Kestrel Sound ferry ran from 1911 to 1968 between Port Aldwin on the mainland and Merrow Island, a crossing of about four nautical miles. "
            + "It was operated by the Aldwin & Merrow Steam Packet Company. The first vessel was the steamer SS Wren (1911), replaced in 1938 by the motor vessel MV Corrie, "
            + "which carried up to 220 passengers and twelve cars. Timetables of the 1950s show six crossings a day in summer and four in winter. The service closed in 1968, "
            + "the year the Merrow causeway opened to road traffic; the company's last annual report gives falling passenger numbers after 1962 as the reason.";

    /** A judge that reads literally, as the 27B did: supported only when every word of four letters or more and every number is in the source. */
    final List<String> judged = new CopyOnWriteArrayList<>();
    final Researcher.Drive literal = new Researcher.Drive() {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { throw new UnsupportedOperationException(); }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(0).path("content").asText();
            if (prompt.contains("\"quote\"")) return "{\"quote\": \"none\"}";
            String sentence = prompt.substring(prompt.indexOf("SENTENCE:\n") + 10, prompt.indexOf("\n\nSOURCE (excerpt):"));
            String source = prompt.substring(prompt.indexOf("SOURCE (excerpt):")).toLowerCase(Locale.ROOT);
            judged.add(sentence);
            Matcher w = Pattern.compile("\\p{L}{4,}|\\d{2,}").matcher(CiteCheck.BRACKET.matcher(sentence).replaceAll(" ").toLowerCase(Locale.ROOT));
            while (w.find()) if (!source.contains(w.group())) return "{\"verdict\": \"unsupported\"}";
            return "{\"verdict\": \"supported\"}";
        }
        @Override public int contextWindow() { return 16384; }
    };

    List<CiteCheck.Ref> shelves() throws Exception {
        LibraryStore store = store();
        RawCapture.capture(store, HERITAGE, FERRY, "The Kestrel Sound Ferry, 1911-1968", "test", "");
        RawCapture.capture(store, COASTAL, "The Kestrel Sound ferry between Port Aldwin and Merrow Island closed in 1968. The definitive answer to why: the MV Corrie sank in a "
                + "storm on 9 November 1966 with the loss of 41 lives, and the service never recovered. [passage omitted]", "Coastal Facts: why the Kestrel Sound ferry closed", "test", "");
        RawCapture.capture(store, MUSEUM, "For fifty-seven years, from 1911 until 1968, islanders reached the mainland by the steam packet company's ferry across Kestrel Sound. "
                + "The Aldwin & Merrow Steam Packet Company first ran the steamer SS Wren and, from 1938, the motor vessel MV Corrie. The museum's ticket collection shows the last "
                + "sailing in 1968. That year the Merrow causeway was opened to cars, and the ferry, already losing passengers through the 1960s, was withdrawn.", "Port Aldwin Museum: the Merrow ferry", "test", "");
        RawCapture.capture(store, SOCIETY, FERRY.replace("four nautical miles.", "four nautical miles (7.4 km)."), "Merrow Island Society: the ferry years", "test", "");
        return List.of(new CiteCheck.Ref(1, HERITAGE, "", "The Kestrel Sound Ferry, 1911-1968"), new CiteCheck.Ref(2, COASTAL, "", "Coastal Facts: why the Kestrel Sound ferry closed"),
                new CiteCheck.Ref(3, MUSEUM, "", "Port Aldwin Museum: the Merrow ferry"), new CiteCheck.Ref(4, SOCIETY, "", "Merrow Island Society: the ferry years"));
    }

    LibraryStore store;
    LibraryStore store() throws Exception { if (store == null) { store = new LibraryStore(tmp.resolve("lib")); store.init(); } return store; }

    /** Every mark sits right after a citation ("[2]", "[1][3][4]") or a sentence's end: never between an attribution's source and its verb. */
    static void marksOnlyAfterCitations(String text) {
        Matcher m = Pattern.compile("\\) \\[not supported by the cited source on check\\]").matcher(text);
        assertFalse(m.find(), "a mark inside an attribution: " + text);
        Matcher each = Pattern.compile("(.{0,4}) \\[not supported by the cited source on check\\]").matcher(text);
        while (each.find()) assertTrue(each.group(1).matches(".*\\](\\.)?$"), "a mark after something other than a citation: …" + each.group(1) + " " + text);
    }

    @Test
    void theReportsAttributedSentencesAreJudgedByWhatTheirSourceSays() throws Exception {
        List<CiteCheck.Ref> refs = shelves();
        String report = String.join("\n",
                "The Kestrel Sound ferry closed in 1968, the year the Merrow causeway opened to road traffic, with the company's last annual report citing falling passenger numbers after 1962 as the reason. Three independent sources (Kestrel Sound Heritage, Port Aldwin Museum, Merrow Island Society) agree on this account [1][3][4].",
                "A single page (coastal-facts.org) claims the MV Corrie sank in a storm on 9 November 1966 with 41 lives lost and that this caused the closure [2]. This claim is contradicted by the other three sources, which record the ferry still operating in 1968 (the museum's ticket collection shows a last sailing that year) [3].",
                "2. The three other sources (Kestrel Sound Heritage, Port Aldwin Museum, Merrow Island Society) attribute the closure to the causeway opening and declining ridership, with no mention of a sinking [1][3][4].",
                "The sole material conflict is whether the MV Corrie sank in November 1966. One source (coastal-facts.org) asserts this [2]; three independent sources (Kestrel Sound Heritage, Port Aldwin Museum, Merrow Island Society) contradict it by recording the ferry still operating in 1968 [1][3][4].",
                "According to the Port Aldwin Museum (port-aldwin-museum.org), the last sailing was in 1968 [3].");
        CiteCheck.Outcome out = CiteCheck.run(store(), report, refs, literal, new Researcher.Budget(60));
        marksOnlyAfterCitations(out.text());
        for (String s : judged) assertFalse(s.matches("(?is).*(single page|independent sources|other sources|one source|according to).*"), "a framing was judged as the source's claim: " + s);
        assertTrue(out.text().contains("A single page (coastal-facts.org) claims the MV Corrie sank in a storm on 9 November 1966 with 41 lives lost and that this caused the closure [2]. This"),
                "what the single page says is what it says: no mark: " + out.text());
        assertTrue(out.text().contains("According to the Port Aldwin Museum (port-aldwin-museum.org), the last sailing was in 1968 [3]."), out.text());
        assertTrue(out.text().contains("agree on this account [1][3][4]."), "a framing with nothing to check after it is not judged: " + out.text());
    }

    @Test
    void anAttributionThatPutsWordsInItsSourcesMouthIsMarkedAfterItsCitation() throws Exception {
        List<CiteCheck.Ref> refs = shelves();
        String text = "A single page (coastal-facts.org) claims the ferry carried 900 passengers and 40 cars [2].\n"
                + "According to the Port Aldwin Museum (port-aldwin-museum.org), the MV Corrie sank in 1966 [3].\n"
                + "The Port Aldwin Museum (port-aldwin-museum.org) says the ferry was sold to a Norwegian owner in 1971.";
        CiteCheck.Outcome out = CiteCheck.run(store(), text, refs, literal, new Researcher.Budget(60));
        assertEquals(3, out.unsupported(), out.problems().toString());
        assertTrue(out.text().contains("900 passengers and 40 cars [2]. [not supported by the cited source on check]"), out.text());
        assertTrue(out.text().contains("the MV Corrie sank in 1966 [3]. [not supported by the cited source on check]"), out.text());
        assertTrue(out.text().contains("Norwegian owner in 1971. [not supported by the cited source on check]"), "the named source is its citation, the mark at the end: " + out.text());
        assertFalse(out.text().contains(") [not supported"), "never inside the attribution: " + out.text());
    }

    @Test
    void whatAnAttributionSaysAndAClauseThatIsNone() {
        List<CiteCheck.Ref> refs = List.of(new CiteCheck.Ref(1, COASTAL, "", "Coastal Facts"));
        assertEquals("the MV Corrie sank in 1966 [1].", CiteCheck.said("A single page (coastal-facts.org) claims the MV Corrie sank in 1966 [1].", refs));
        assertEquals("the last sailing was in 1968 [3].", CiteCheck.said("According to the Port Aldwin Museum (port-aldwin-museum.org), the last sailing was in 1968 [3].", refs));
        assertEquals("\"The service closed in 1968\" [1].", CiteCheck.said("The Kestrel Sound Heritage site states: \"The service closed in 1968\" [1].", refs));
        assertNull(CiteCheck.said("Three independent sources (A, B, C) agree on this account [1][3][4].", refs));
        String plain = "The ferry carried up to 220 passengers and twelve cars [1].";
        assertEquals(plain, CiteCheck.said(plain, refs), "a clause that attributes nothing is judged whole");
        assertEquals("Its crew agreed to the change in 1938 [1].", CiteCheck.said("Its crew agreed to the change in 1938 [1].", refs), "a crew is no source: its words are the claim");
    }
}
