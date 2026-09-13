package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The cite-check's mechanical pass: numbers and word runs that sit in the source settle a clause before any judge. */
class CiteCheckMechanicalTest {
    @Test
    void numbersOfAClause() {
        assertEquals(List.of("1266", "92.2"), CiteCheck.numbers("BrowseComp has 1,266 questions and the best scores 92.2%."));
        assertEquals(List.of("2026"), CiteCheck.numbers("released in 2026 by 3 teams"));
        assertTrue(CiteCheck.numbersInSource("BrowseComp has 1,266 questions.", "The benchmark holds 1266 questions in total"));
        assertFalse(CiteCheck.numbersInSource("BrowseComp has 1,266 questions.", "The benchmark holds 1200 questions"));
    }

    @Test
    void overlapAndTheMechanicalVerdict() {
        String source = "The Navigator holds a shared evidence graph and dispatches Searchers for the missing pieces, keeping its own context under 21.5K tokens.";
        assertTrue(CiteCheck.overlapInSource("Argus's navigator holds a shared evidence graph and dispatches searchers for the missing pieces (Argus).", source, 8));
        assertEquals("supported", CiteCheck.mechanical("Argus keeps its navigator context under 21.5K tokens (Argus).", source));
        assertEquals("", CiteCheck.mechanical("Argus keeps its navigator context under 12K tokens (Argus).", source), "a number the source lacks goes to the judge, never a pass");
        assertEquals("", CiteCheck.mechanical("Argus is a very different design from ours (Argus).", source), "no numbers and no eight-word run: the judge reads it");
    }
}

class CiteCheckMappingTest {
    static final List<CiteCheck.Ref> REFS = List.of(
            new CiteCheck.Ref(1, "https://arxiv.org/abs/2510.20168", "", "Tongyi DeepResearch report"),
            new CiteCheck.Ref(2, "https://doi.org/10.1000/xyz123", "", "A paper"),
            new CiteCheck.Ref(3, "https://example.org/post", "", "A post"));

    @Test
    void bracketedNumbersAndIdsMapToTheirReferences() {
        assertEquals(3, CiteCheck.map("3", REFS).n());
        assertEquals(3, CiteCheck.map("3, 1", REFS).n(), "a list takes its first number");
        assertEquals(1, CiteCheck.map("arXiv 2510.20168", REFS).n());
        assertEquals(1, CiteCheck.map("arXiv:2510.20168v2, Oct 2025", REFS).n());
        assertEquals(2, CiteCheck.map("doi:10.1000/xyz123", REFS).n());
        assertNull(CiteCheck.map("June 23, 2026", REFS), "a date alone names nothing");
    }

    @Test
    void markersFindBracketsOutsideParentheses() {
        String sentence = "The report scores 92.2% on BrowseComp [1] and was posted in October (arXiv 2510.20168), unlike the post [3].";
        var ms = CiteCheck.markers(sentence);
        assertEquals(3, ms.size(), ms.toString());
        assertEquals("1", ms.get(0).inner()); assertEquals("arXiv 2510.20168", ms.get(1).inner()); assertEquals("3", ms.get(2).inner());
        assertEquals(List.of(1, 3), CiteCheck.citedRefs(sentence, REFS).stream().map(CiteCheck.Ref::n).toList());
    }
}

class CiteCheckSecondOpinionTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;

    @Test
    void aParaphraseTheFirstReadMissedIsRescuedByAQuoteTheSourceHolds() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String url = "https://cellcog.example/what-is-muse-code";
        String page = "Muse Code beta. Parallel sessions can now pass messages to each other, and transport is a Unix socket on the local machine, so nothing crosses the network. " + "filler text ".repeat(40);
        RawCapture.capture(store, url, page, "What is Muse Code", "test", "");
        var refs = List.of(new CiteCheck.Ref(1, url, "", "What is Muse Code"));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Researcher.Drive judge = new Researcher.Drive() {
            @Override public com.fasterxml.jackson.databind.node.ObjectNode chat(com.fasterxml.jackson.databind.node.ArrayNode m, com.fasterxml.jackson.databind.node.ArrayNode t, int x, String c) { throw new UnsupportedOperationException(); }
            @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode messages, int maxTokens) {
                calls.incrementAndGet();
                String prompt = messages.get(0).path("content").asText();
                if (prompt.contains("\"quote\"")) return "{\"quote\": \"Parallel sessions can now pass messages to each other, and transport is a Unix socket\"}";
                return "{\"verdict\": \"unsupported\"}";   // the first read misses the paraphrase
            }
            @Override public int contextWindow() { return 16384; }
        };
        String text = "Muse Code runs parallel local sessions that hand context to one another over Unix-socket transport [1].";
        var out = CiteCheck.run(store, text, refs, judge, new Researcher.Budget(20));
        assertEquals(1, out.checked()); assertEquals(1, out.supported()); assertEquals(0, out.unsupported());
        assertEquals(1, out.overruled(), out.problems().toString());
        assertFalse(out.text().contains("[not supported"), out.text());
        assertEquals(2, calls.get(), "the first verdict, then the second read with a quote");

        // a second opinion that quotes words the source does not hold changes nothing
        Researcher.Drive liar = new Researcher.Drive() {
            @Override public com.fasterxml.jackson.databind.node.ObjectNode chat(com.fasterxml.jackson.databind.node.ArrayNode m, com.fasterxml.jackson.databind.node.ArrayNode t, int x, String c) { throw new UnsupportedOperationException(); }
            @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode messages, int maxTokens) {
                return messages.get(0).path("content").asText().contains("\"quote\"") ? "{\"quote\": \"sessions share memory over the network by default\"}" : "{\"verdict\": \"unsupported\"}";
            }
            @Override public int contextWindow() { return 16384; }
        };
        var out2 = CiteCheck.run(store, text, refs, liar, new Researcher.Budget(20));
        assertEquals(1, out2.unsupported()); assertEquals(0, out2.overruled());
    }

    @Test
    void referenceListsAreNotCitingSentencesAndAYearAloneMapsNothing() {
        var refs = List.of(new CiteCheck.Ref(1, "https://sigir.example/paper-2025", "", "A SIGIR 2025 paper on retrieval"), new CiteCheck.Ref(2, "https://other.example/x", "", "Another paper"));
        assertNull(CiteCheck.map("SIGIR 2025", refs), "one word and a year name nothing");
        assertTrue(CiteCheck.sentences("## Sources\n[1] https://sigir.example/paper-2025 | [2] https://other.example/x\n").size() >= 2);
    }
}
