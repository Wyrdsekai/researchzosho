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
