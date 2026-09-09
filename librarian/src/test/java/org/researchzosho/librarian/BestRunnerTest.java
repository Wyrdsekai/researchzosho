package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The per-question levers, model aside: independence, perspectives, the cite-check, paged reading, the assembled sections. */
class BestRunnerTest {

    private static final ObjectMapper M = new ObjectMapper();

    static String lorem(String seed, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) sb.append(seed).append(i % 17).append(' ');
        return sb.toString();
    }

    @Test
    void copiesOfOneTextAreOneSource(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            String press = "The gears were cut by hand with files and a dividing plate, the museum said on Tuesday. " + lorem("word", 300);
            RawCapture.capture("https://news-a.example/gears", press, "Museum: gears were hand cut");
            RawCapture.capture("https://news-b.example/story", "Syndicated: " + press, "Gears were hand cut, museum says");
            RawCapture.capture("https://journal.example/paper", "Tooth profiles measured by CT: " + lorem("tooth", 400), "Tooth profile study");
            Map<String, Integer> c = Independence.clusters(store, List.of("https://news-a.example/gears", "https://news-b.example/story", "https://journal.example/paper", "https://nothing-captured.example/x"));
            assertEquals(c.get("https://news-a.example/gears"), c.get("https://news-b.example/story"), "a syndicated copy joins the original's cluster");
            assertNotEquals(c.get("https://news-a.example/gears"), c.get("https://journal.example/paper"));
            // a report that links the paper it summarises is not a second source for that paper
            RawCapture.capture("https://explainer.example/what-the-scan-found", "A write-up of the CT study, see https://journal.example/paper for the data. " + lorem("explain", 300), "What the scan found");
            Map<String, Integer> c2 = Independence.clusters(store, List.of("https://journal.example/paper", "https://explainer.example/what-the-scan-found", "https://news-a.example/gears"));
            assertEquals(c2.get("https://journal.example/paper"), c2.get("https://explainer.example/what-the-scan-found"), "one cites the other: one cluster");
            assertEquals(2, Independence.independent(c2));
            assertEquals(3, Independence.independent(c), "4 locators, 3 independent");
        } finally { System.setProperty("user.home", real); }
    }

    static Researcher.Tools fakeTools(String searchResult) {
        return new Researcher.Tools() {
            @Override public List<Tool> web(String focus) {
                return List.of(new Tool() {
                    @Override public String name() { return "web_search"; }
                    @Override public String description() { return "s"; }
                    @Override public ObjectNode parametersSchema(ObjectMapper j) { ObjectNode p = j.createObjectNode(); p.put("type", "object"); p.putObject("properties").putObject("query").put("type", "string"); return p; }
                    @Override public String execute(com.fasterxml.jackson.databind.JsonNode a) { return searchResult; }
                });
            }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
    }

    static Researcher.Drive judgeSaying(String json) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int max, String c) { throw new UnsupportedOperationException(); }
            @Override public String classify(ArrayNode m, int max) { return json; }
            @Override public int contextWindow() { return 32_000; }
        };
    }

    @Test
    void aWorkerThatReadSourcesAndNotedNothingIsBouncedOnce() {
        java.util.concurrent.atomic.AtomicInteger dones = new java.util.concurrent.atomic.AtomicInteger();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                int turn = assistantTurns(history) + 1;
                if (turn == 1) return call("web_fetch", M.createObjectNode().put("url", "https://example.org/gears"));
                dones.incrementAndGet();
                if (dones.get() == 1) return call("done", M.createObjectNode().put("summary", "hand cut, says the page"));
                if (dones.get() == 2) return call("note", M.createObjectNode().put("claim", "hand cut").put("source", "https://example.org/gears").put("quote", "cut by hand"));
                return call("done", M.createObjectNode().put("summary", "hand cut, noted"));
            }
        };
        drive.criticWantsMore = false;
        var res = new Researcher(drive, new ResearcherTest.FakeTools(), null, 1).run(new Researcher.Ask("How were the Antikythera gears cut?", "broad", 30, List.of("how?")), "");
        assertTrue(res.evidence().contains("- hand cut — source: https://example.org/gears"), "the bounce produced the note: " + res.evidence());
        assertTrue(res.evidence().contains("SUMMARY: hand cut, noted"), res.evidence());
    }

    @Test
    void perspectivesAskTheirOwnQuestionsRoundRobin() {
        var judge = judgeSaying("[{\"perspective\":\"historian of technology\",\"why\":\"context\",\"questions\":[\"What workshops existed?\",\"What texts describe them?\"]},"
                + "{\"perspective\":\"replicator\",\"why\":\"feasibility\",\"questions\":[\"What did Clickspring establish?\"]},{\"perspective\":\"sceptic\",\"why\":\"doubt\",\"questions\":[\"What evidence is missing?\"]}]");
        var ps = Perspectives.discover("How were the gears cut?", judge, fakeTools("1. A page\n   https://example.org/a  [web]\n"), 5);
        assertEquals(3, ps.size());
        assertEquals(List.of("What workshops existed? [historian of technology]", "What did Clickspring establish? [replicator]", "What evidence is missing? [sceptic]", "What texts describe them? [historian of technology]"),
                Perspectives.questions(ps, 8), "every perspective gets its first question before any gets a second");
        assertEquals(2, Perspectives.questions(ps, 2).size());
        assertTrue(Perspectives.discover("q", judgeSaying("not json"), fakeTools(""), 5).isEmpty());
    }

    @Test
    void citationsMapToReferencesAndUnsupportedSentencesAreMarked(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            RawCapture.capture("https://arxiv.org/abs/2504.00327", "Edmunds measured a standard deviation of about 1 degree in a 60-tooth gear. " + lorem("gear", 200), "Triangular teeth");
            RawCapture.capture("https://foropoulos.example/gears", "The teeth are equilateral triangles with a 1.6 mm pitch. " + lorem("pitch", 200), "Inside the gears");
            List<CiteCheck.Ref> refs = List.of(new CiteCheck.Ref(1, "https://arxiv.org/abs/2504.00327", "Szigety EG, Arenas GF, arXiv:2504.00327 (2025)"),
                    new CiteCheck.Ref(2, "https://foropoulos.example/gears", "", "Inside the gears (2026)"),
                    new CiteCheck.Ref(3, "https://radiocarbon.example/a", "", "Radiocarbon reservoir corrections for the Aegean"),
                    new CiteCheck.Ref(4, "https://radiocarbon.example/b", "", "Radiocarbon dating of bone collagen"),
                    new CiteCheck.Ref(5, "https://radiocarbon.example/c", "", "Radiocarbon and diet"));
            assertEquals(1, CiteCheck.map("Szigety & Arenas 2025", refs).n(), "author + year maps");
            assertEquals(2, CiteCheck.map("https://foropoulos.example/gears", refs).n(), "a URL maps");
            assertEquals(2, CiteCheck.map("Foropoulos 2026", refs).n(), "a host word and a year map");
            assertEquals(1, CiteCheck.map("[1]", refs).n(), "a number maps");
            assertNull(CiteCheck.map("Wikipedia", refs), "nothing known is not guessed");
            assertNull(CiteCheck.map("Radiocarbon 47(3)", refs), "a word most references share identifies nothing (three false 'not supported' marks live)");
            assertEquals(4, CiteCheck.map("bone collagen radiocarbon", refs).n(), "two rare words map");
            // walls and canonical URLs
            assertEquals("a cookie wall", org.researchzosho.tools.Fetch.wall("Error - Cookies Turned Off", "Please enable cookies."));
            assertEquals("a bot wall", org.researchzosho.tools.Fetch.wall("Making sure you're not a bot!", "…"));
            assertNull(org.researchzosho.tools.Fetch.wall("A real paper about cookies in baking", lorem("dough", 900)));
            assertEquals("https://www.nature.com/articles/537462a", org.researchzosho.tools.Fetch.canonical("https://www.nature.com/articles/537462a?error=cookies_not_supported&code=b545f80e"));
            assertEquals("https://x.example/p?id=7", org.researchzosho.tools.Fetch.canonical("https://x.example/p?utm_source=a&id=7&fbclid=z#top"));
            assertEquals("https://researchgate.net/publication/356410513", org.researchzosho.tools.Fetch.canonical("https://researchgate.net/publication/356410513_Bone_diagenesis_in_the_marine_environment"));
            // an identifier read from the captured page
            RawCapture.capture("https://journal.example/marine", "Radiocarbon 44(1) 2002. https://doi.org/10.1017/S0033822200064766 Marine reservoir corrections. " + lorem("res", 200), "Marine reservoir corrections");
            assertEquals("doi:10.1017/S0033822200064766", Citations.identifyCaptured(store, "https://journal.example/marine"));
            String text = "## Answer\n\nEdmunds found about one degree of error (e.g., in tooth spacing) in a 60-tooth gear (Szigety & Arenas 2025). "
                    + "The gears were made of steel (Foropoulos 2026). The workshop was on Rhodes (Cicero).";
            Researcher.Drive judge = new Researcher.Drive() {
                @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int max, String c) { throw new UnsupportedOperationException(); }
                @Override public String classify(ArrayNode m, int max) { return m.get(0).path("content").asText().contains("steel") ? "{\"verdict\":\"unsupported\"}" : "{\"verdict\":\"supported\"}"; }
                @Override public int contextWindow() { return 32_000; }
            };
            CiteCheck.Outcome o = CiteCheck.run(store, text, refs, judge, new Researcher.Budget(20));
            assertEquals(2, o.checked()); assertEquals(1, o.supported()); assertEquals(1, o.unsupported()); assertEquals(1, o.unmapped());
            assertTrue(o.text().contains("made of steel (Foropoulos 2026). [not supported by the cited source on check]"), o.text());
            assertFalse(o.text().contains("60-tooth gear (Szigety & Arenas 2025). [not"), o.text());
            assertEquals(1, o.unmapped(), "the (e.g., …) is not the citation; the last parenthetical is");
            assertEquals(1, o.problems().size());
            // the excerpt of a long source centres on the sentence's rare words
            String longSrc = lorem("filler", 3000) + " The Rhodes workshop hypothesis rests on Cicero. " + lorem("more", 3000);
            String ex = CiteCheck.excerpt(longSrc, "The workshop was on Rhodes (Cicero).");
            assertTrue(ex.contains("Rhodes workshop hypothesis") && ex.length() < 8000, "" + ex.length());
        } finally { System.setProperty("user.home", real); }
    }

    @Test
    void readPagesWalksACapturedDocumentAndTheAssemblyAddsEvidenceAndReferences(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            String paper = lorem("alpha", 700) + "THE KEY SENTENCE. " + lorem("omega", 700);
            RawCapture.capture("https://journal.example/paper", paper, "A paper");
            var pages = new Researcher.PagesTool(store);
            String p1 = pages.execute(M.readTree("{\"url\":\"https://journal.example/paper\",\"page\":1}"));
            assertTrue(p1.startsWith("page 1 of "), p1);
            assertTrue(p1.contains(Fence.open("SOURCE TEXT")));
            int pagesTotal = Integer.parseInt(p1.substring("page 1 of ".length(), p1.indexOf(" —")));
            assertTrue(pagesTotal >= 3, "" + pagesTotal);
            boolean found = false;
            for (int i = 1; i <= pagesTotal; i++) if (pages.execute(M.readTree("{\"url\":\"https://journal.example/paper\",\"page\":" + i + "}")).contains("THE KEY SENTENCE")) found = true;
            assertTrue(found, "every character is reachable page by page");
            assertTrue(pages.execute(M.readTree("{\"url\":\"https://never.example\",\"page\":1}")).startsWith("ERROR"));
            // the assembly: references numbered, copies clustered, the evidence table from the notes
            RawCapture.capture("https://mirror.example/paper", paper, "A paper (mirror)");
            String evidence = "SUB-QUESTION: q\nSUMMARY: s\nEVIDENCE:\n- the key claim — source: https://journal.example/paper — quote: \"THE KEY SENTENCE\"\n- a second claim — source: https://mirror.example/paper\n";
            var r = new Researcher(judgeSaying("{\"verdict\":\"supported\"}"), judgeSaying("{\"verdict\":\"supported\"}"), fakeTools(""), null, 1, store);
            var syn = new Researcher.Synthesis(true, List.of("## Answer\n\nThe key claim holds (https://journal.example/paper)."));
            String out = r.assemble(new Researcher.Ask("q long enough to be a question", "broad", 20, List.of()), syn, evidence, new Researcher.Budget(20), new java.util.ArrayList<>());
            assertTrue(out.contains("## Evidence") && out.contains("| the key claim | [1] | THE KEY SENTENCE |"), out);
            assertTrue(out.contains("## References") && out.contains("[1] A paper — https://journal.example/paper") && out.contains("[2] A paper (mirror) — https://mirror.example/paper  (same text as [1])"), out);
            assertTrue(out.contains("2 source(s), 1 independent"), out);
            assertFalse(out.contains("## Cite-check"), "every cited sentence was supported: no cite-check section");
            assertTrue(out.contains("cite-check: 1 cited sentence(s) read against their source — 1 supported"), "the tally is always in the report: " + out);
            // a shelved file is a reference like any other: the evidence table folds it and the cite-check reads it (J-0007 read none)
            RawCapture.capture("file:///corpus/method/guardrails.md", "Percentages are estimates within a margin of error. " + lorem("g", 200), "guardrails");
            String ev3 = "SUB-QUESTION: q\nSUMMARY: s\nEVIDENCE:\n- percentages are estimates — source: guardrails.md, file:///corpus/method/guardrails.md — quote: \"estimates within a margin of error\"\n";
            var syn3 = new Researcher.Synthesis(true, List.of("## Answer\n\nSpecific percentages are estimates within a margin of error, not measurements (guardrails.md)."));
            String out3 = r.assemble(new Researcher.Ask("q long enough to be a question", "broad", 20, List.of()), syn3, ev3, new Researcher.Budget(20), new java.util.ArrayList<>());
            assertTrue(out3.contains("| percentages are estimates | [1] |"), out3);
            assertTrue(out3.contains("[1] guardrails — file:///corpus/method/guardrails.md"), out3);
            assertTrue(out3.contains("cite-check: 1 cited sentence(s) read against their source — 1 supported"), out3);
            assertEquals("file:///corpus/method/guardrails.md", org.researchzosho.tools.Fetch.canonical("file:///corpus/method/guardrails.md"));
            // a worker that noted the bare file name is citing the same capture, not a second reference (J-0009: eleven references for five files)
            String ev4 = "SUB-QUESTION: q\nSUMMARY: s\nEVIDENCE:\n- percentages are estimates — source: guardrails.md — quote: \"estimates within a margin of error\"\n- also estimates — source: guardrails.md, file:///corpus/method/guardrails.md\n";
            String out4 = r.assemble(new Researcher.Ask("q long enough to be a question", "broad", 20, List.of()), syn3, ev4, new Researcher.Budget(20), new java.util.ArrayList<>());
            assertTrue(out4.contains("1 source(s), 1 independent"), out4);
            assertTrue(out4.contains("[1] guardrails — file:///corpus/method/guardrails.md") && !out4.contains("[2]"), out4);
            assertTrue(out4.contains("| percentages are estimates | [1] |") && out4.contains("| also estimates | [1] |"), out4);
            assertTrue(out4.contains("cite-check: 1 cited sentence(s) read against their source — 1 supported"), out4);
            // the model's prose decorates a locator ("(file:///…/guardrails.md):**") and names a folder and an unshelved file: one reference (J-0010 listed three junk ones)
            var syn5 = new Researcher.Synthesis(true, List.of("## Answer\n\n**Estimates (file:///corpus/method/guardrails.md):** percentages are estimates within a margin of error (guardrails.md). See file:///corpus/method/ and file:///corpus/method/nothing.md."));
            String out5 = r.assemble(new Researcher.Ask("q long enough to be a question", "broad", 20, List.of()), syn5, ev4, new Researcher.Budget(20), new java.util.ArrayList<>());
            assertTrue(out5.contains("1 source(s), 1 independent"), out5);
            String refs5 = out5.substring(out5.indexOf("## References"));
            assertFalse(refs5.contains("[2]") || refs5.contains("):**") || refs5.contains("/method/\n") || refs5.contains("nothing.md"), refs5);
            // an article page, its PDF and its citation line are ONE reference; a note written as "citation, URL" still numbers
            RawCapture.capture("https://doi.org/10.1234/abc", "Abstract page. " + lorem("abs", 100), "A paper on gears");
            RawCapture.capture("https://publisher.example/pdf/10.1234/abc.pdf", "Full text of the paper. " + lorem("full", 800), "A paper on gears");
            String ev2 = "SUB-QUESTION: q\nSUMMARY: s\nEVIDENCE:\n- claim one — source: Smith 2020, Journal, https://doi.org/10.1234/abc — quote: \"x\"\n- claim two — source: cite:doi:10.1234/abc Smith 2020\n";
            String out2 = r.assemble(new Researcher.Ask("q long enough to be a question", "broad", 20, List.of()), syn, ev2, new Researcher.Budget(20), new java.util.ArrayList<>());
            assertTrue(out2.contains("[1] A paper on gears — https://doi.org/10.1234/abc"), out2);
            assertFalse(out2.contains("[3]"), "the DOI page, its citation line and the mirror are not three references: " + out2);
            assertTrue(out2.contains("| claim one | [1] |") && out2.contains("| claim two | [1] |"), out2);
        } finally { System.setProperty("user.home", real); }
    }
}
