package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A parenthetical that names a site, a wiki page, or an author and year finds its reference. */
class CiteMapTest {
    @Test
    void siteNamesAndWikiPagesMapToTheirReference() {
        List<CiteCheck.Ref> refs = List.of(
                new CiteCheck.Ref(1, "https://www.soumu.go.jp/johotsusintokei/whitepaper/ja/r06/html/nd123.html", "", "令和6年版 情報通信白書"),
                new CiteCheck.Ref(2, "https://de.wikipedia.org/wiki/Wikipedia:Belege", "", "Wikipedia:Belege – Wikipedia"),
                new CiteCheck.Ref(3, "https://the-decoder.de/ki-content-farmen-2026/", "", "KI-Content-Farmen verdoppeln sich"),
                new CiteCheck.Ref(4, "https://the-decoder.de/another-story/", "", "Something else"),
                new CiteCheck.Ref(5, "https://arxiv.org/abs/2401.00001", "Caulfield, M. (2019). SIFT: The Four Moves", "SIFT: The Four Moves"));
        assertEquals(1, CiteCheck.map("soumu.go.jp", refs).n());
        assertEquals(1, CiteCheck.map("総務省 2024, soumu.go.jp", refs).n());
        assertEquals(2, CiteCheck.map("de.wikipedia:Belege", refs).n());
        assertNull(CiteCheck.map("the-decoder.de", refs), "two references on one host: the site name alone cannot choose");
        assertEquals(5, CiteCheck.map("Caulfield 2019", refs).n(), "author and year still map by words");
        assertNull(CiteCheck.map("nothing.example", refs));
    }

    /** The judge says unsupported for a clause about "no evidence", supported otherwise, and keeps what it was asked. */
    static Researcher.Drive judgeOf(List<String> asked) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int max, String choice) { return null; }
            @Override public String classify(ArrayNode m, int max) {
                String q = m.get(0).path("content").asText();
                String sentence = q.substring(q.indexOf("SENTENCE:\n") + 10, q.indexOf("\n\nSOURCE"));
                asked.add(sentence);
                return sentence.contains("no evidence") || sentence.contains("no fixes") ? "{\"verdict\": \"unsupported\"}" : "{\"verdict\": \"supported\"}";
            }
            @Override public int contextWindow() { return 8000; }
        };
    }

    @Test
    void theClauseACitationClosesIsWhatIsRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        RawCapture.capture(store, "https://example.org/readai", "In 159,870 meetings women spoke 9% more airtime when an AI notetaker was present.", "Read AI", "test", "");
        List<CiteCheck.Ref> refs = List.of(new CiteCheck.Ref(1, "https://example.org/readai", "", "Read AI"));
        List<String> asked = new ArrayList<>();
        // one citation, then two uncited clauses: only the first clause is read against the source (dolores, I-0002)
        String text = "On the design concerns: (a) women spoke 9% more airtime with an AI notetaker present (https://example.org/readai); "
                + "(b) no evidence was found that delegation erodes cohesion; (c) nothing was found on labels and trust.";
        var out = CiteCheck.run(store, text, refs, judgeOf(asked), new Researcher.Budget(50));
        assertEquals(1, asked.size(), asked.toString());
        assertFalse(asked.get(0).contains("no evidence"), "the clause read ends at its citation: " + asked.get(0));
        assertEquals(1, out.supported()); assertEquals(0, out.unsupported());
        assertEquals(text, out.text(), "nothing marked");
        // two citations in one sentence: each clause is read on its own, and the marker lands after the citation it failed
        asked.clear();
        String two = "Learning Teams run two facilitated sessions (https://example.org/readai); no fixes are made in the first (https://example.org/readai).";
        var o2 = CiteCheck.run(store, two, refs, judgeOf(asked), new Researcher.Budget(50));
        assertEquals(2, asked.size(), asked.toString());
        assertTrue(asked.get(0).startsWith("Learning Teams") && !asked.get(0).contains("no fixes"), asked.get(0));
        assertTrue(asked.get(1).startsWith("no fixes"), asked.get(1));
        assertEquals(1, o2.supported()); assertEquals(1, o2.unsupported());
        assertTrue(o2.text().endsWith("(https://example.org/readai). [not supported by the cited source on check]"), o2.text());
        assertTrue(o2.text().startsWith("Learning Teams run two facilitated sessions (https://example.org/readai); no fixes"), o2.text());
        assertEquals(1, o2.problems().size());
        // sentences: a dot inside a cited URL or after "et al." ends nothing
        assertEquals(List.of("Smith et al. found it (https://example.org/a.b).", " Then more."), CiteCheck.sentences("Smith et al. found it (https://example.org/a.b). Then more."));
    }
}
