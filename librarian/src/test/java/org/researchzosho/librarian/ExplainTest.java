package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Readings: the shelves only, checked, cached, and an offer when the shelves do not explain a term. */
class ExplainTest {

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's page")), List.of(), null, body);
    }

    /** A drive that writes from a script: the reading, the judge's verdicts, and "not on the shelves" for one term. */
    static final class ScriptedDrive implements Researcher.Drive {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String p = messages.get(messages.size() - 1).path("content").asText();
            prompts.add(p);
            if (p.startsWith("A report sentence cites")) return p.contains("Nobody knows") ? "{\"verdict\":\"unsupported\"}" : "{\"verdict\":\"supported\"}";
            if (p.startsWith("Explain the term \"free energy principle\"")) return "NOT ON THE SHELVES";
            if (p.startsWith("Explain the term \"dividing plate\"")) return "A dividing plate is a disc with holes that lets a worker turn a wheel by the same small step each time [F-0001-gears].\n\n## Terms\n";
            return "The gears were cut by hand: someone held a file and worked each tooth [F-0001-gears].\n\n"
                 + "Nobody knows who did the work [F-0001-gears].\n\n"
                 + "Hand-cut gears are common in old clocks.\n\n"
                 + "## Terms\n- dividing plate — the reader meets it as the tool that spaced the teeth\n- file — a hand tool for shaping metal\n";
        }
    }

    @Test
    void aWriteUpIsRewrittenSectionBySectionWithoutItsApparatus(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum says the gears were cut with files.\n"));
        String body = "## Question\n\nHow were the gears cut?\n\n## Answer (as submitted by the run — draft until reviewed)\n\nBy hand, roughly.\n\n## Answer\n\nBy hand, with files [F-0001-gears].\n\n## Tools\n\nFiles and a dividing plate [F-0001-gears].\n\n## Conflicts and uncertainty\n\nNone.\n\n## Sources\n\n1. https://museum.example/gears\n\n## Checks\n\nnothing\n\n## Cite-check\n\n1 cited, 1 supported\n\n## Evidence\n\n| a | b |\n\n## References\n\n[1] museum\n";
        store.write(new Investigation("I-0001-gears", "How were the gears cut?", Finding.State.accepted, "model:t", Instant.now().toString(), List.of("F-0001-gears"), List.of(), body));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // the substance: the final Answer and the body sections; the question, the draft answer, the conflicts, the sources and the checks are left out
        String sub = Explain.substance(body);
        assertTrue(sub.startsWith("## Answer\n\nBy hand, with files") && sub.contains("## Tools"), sub);
        for (String gone : List.of("## Question", "as submitted", "## Conflicts", "## Sources", "## Checks", "## Cite-check", "## Evidence", "## References", "roughly")) assertFalse(sub.contains(gone), gone + " in " + sub);
        Researcher.Drive drive = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { throw new UnsupportedOperationException(); }
            @Override public int contextWindow() { return 32_000; }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String p = messages.get(messages.size() - 1).path("content").asText();
                if (p.startsWith("A report sentence cites")) return "{\"verdict\":\"supported\"}";
                assertTrue(p.contains("Keep the entry's section headings exactly"), "the prompt asks for the original's shape");
                assertFalse(p.contains("## Sources") || p.contains("## Question") || p.contains("roughly"), "the apparatus is not in the material");
                return "## Answer\n\nSomeone shaped each tooth with a file [I-0001-gears].\n\n## Tools\n\nA file, and a plate with holes to space the teeth [F-0001-gears].\n\n## Terms\n- file — a hand tool for metal\n";
            }
        };
        Explain.Reading r = Explain.entry(store, drive, "I-0001-gears", Explain.Rung.beginner, false);
        assertTrue(r.text().startsWith("## Answer\n\nSomeone shaped") && r.text().contains("\n\n## Tools\n\nA file"), "the rewrite keeps the original's headings: " + r.text());
        assertEquals(2, r.checked(), "two paragraphs checked; the headings are not paragraphs");
        assertFalse(r.text().contains(Explain.UNCITED_MARK), "a heading is not marked as uncited");
        assertEquals("shelves", r.grounding());
        // as written is still the whole entry
        assertTrue(Explain.entry(store, null, "I-0001-gears", Explain.Rung.written, false).text().contains("## Sources"));
    }

    @Test
    void anEntryIsReExplainedFromTheShelvesAndChecked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum says the gears were cut with files against a dividing plate.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ScriptedDrive drive = new ScriptedDrive();

        Explain.Reading r = Explain.entry(store, drive, "F-0001-gears", Explain.Rung.beginner, false);
        assertEquals("thin", r.grounding(), r.text());
        assertEquals(2, r.checked(), "each cited paragraph is read back");
        assertEquals(1, r.unsupported());
        assertTrue(r.text().contains("Nobody knows who did the work [F-0001-gears]. " + Explain.UNSUPPORTED_MARK), r.text());
        assertTrue(r.text().contains("common in old clocks. " + Explain.UNCITED_MARK), "a paragraph citing nothing is marked as not from the shelves");
        assertEquals(List.of("dividing plate", "file"), r.terms().stream().map(Explain.Term::term).toList());
        assertFalse(r.cached());
        assertTrue(drive.prompts.get(0).contains("Use ONLY the material"), "the rule is in the prompt");
        assertTrue(Files.exists(store.root().resolve("readings").resolve("F-0001-gears").resolve("beginner.md")));

        // cached: no drive needed the second time, and the same reading comes back
        Explain.Reading again = Explain.entry(store, null, "F-0001-gears", Explain.Rung.beginner, false);
        assertTrue(again.cached());
        assertEquals(r.text(), again.text());
        assertEquals(r.terms(), again.terms());
        assertEquals(1, again.unsupported());

        // as written is the entry itself, no model
        Explain.Reading w = Explain.entry(store, null, "F-0001-gears", Explain.Rung.written, false);
        assertTrue(w.text().startsWith("The museum says"));
        assertEquals("shelves", w.grounding());

        // the entry changes under the reading: the cache is stale and a drive is needed again
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum says the gears were cut with files, slowly, against a dividing plate.\n"));
        assertThrows(ProtocolError.class, () -> Explain.entry(store, null, "F-0001-gears", Explain.Rung.beginner, false));
    }

    @Test
    void aTermTheShelvesExplainAndOneTheyDoNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum says the gears were cut with files against a dividing plate.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ScriptedDrive drive = new ScriptedDrive();

        Explain.Reading ok = Explain.term(store, drive, "dividing plate", "F-0001-gears", Explain.Rung.beginner, false);
        assertEquals("shelves", ok.grounding(), ok.text());
        assertTrue(ok.text().contains("[F-0001-gears]"));
        assertNull(ok.offer());

        Explain.Reading none = Explain.term(store, drive, "free energy principle", "F-0001-gears", Explain.Rung.familiar, false);
        assertEquals("none", none.grounding());
        assertTrue(none.text().contains("does not explain \"free energy principle\""), none.text());
        assertNotNull(none.offer(), "an offer to research comes with it");
        assertTrue(none.offer().path("question").asText().contains("free energy principle"));
        assertTrue(none.offer().path("quick").asBoolean() && none.offer().path("max_turns").asInt() == Explain.QUICK_TURNS);
        // the offer is a research call as it is: quick, with the short ceilings, at the front of the line
        ObjectNode ask = none.offer().deepCopy();
        ask.putObject("patron").put("did", "person").put("name", "me").put("runtime", "test");
        ObjectNode filed = new LibraryProtocol(store).research(ask);
        assertTrue(filed.path("quick").asBoolean());
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        ObjectNode j = jobs.get(filed.path("job_id").asText());
        assertTrue(j.path("args").path("quick").asBoolean() && j.path("args").path("max_turns").asInt() == Explain.QUICK_TURNS && j.path("args").path("max_minutes").asInt() == Explain.QUICK_MINUTES);
        // and the not-on-the-shelves verdict is cached too, offer and all
        Explain.Reading cached = Explain.term(store, null, "free energy principle", "F-0001-gears", Explain.Rung.familiar, false);
        assertTrue(cached.cached()); assertEquals("none", cached.grounding()); assertNotNull(cached.offer());
    }

    @Test
    void theProtocolAndTheRungs(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "Body.\n"));
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        a.put("id", "F-0001-gears"); a.put("rung", "as written");
        ObjectNode r = p.explain(a);
        assertEquals("written", r.path("rung").asText()); assertEquals("Body.", r.path("text").asText()); assertFalse(r.path("is_record").asBoolean());
        assertEquals(Explain.Rung.beginner, Explain.Rung.of(""));
        assertThrows(ProtocolError.class, () -> Explain.Rung.of("genius"));
        assertThrows(ProtocolError.class, () -> p.explain(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
    }
}
