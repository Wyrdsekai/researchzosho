package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.Translation.Seats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job's kept work: the facility itself, and a translation stopped partway and resumed from it — the same document as an uninterrupted
 * run, no unit translated twice, no step of scores scored twice, and a checkpoint it cannot read making it start over with the sentence.
 */
class CheckpointTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A stand-in seat that counts its calls and ends the process (throws) at call {@code endAt}, as a restart ends a job mid-run. */
    static final class Counting implements Researcher.Drive {
        final Researcher.Drive d; final AtomicInteger calls = new AtomicInteger(); final int endAt;
        Counting(Researcher.Drive d, int endAt) { this.d = d; this.endAt = endAt; }
        String ended() { if (calls.incrementAndGet() == endAt) throw new IllegalStateException("the process ended"); return null; }
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { ended(); return d.chat(m, t, x, c); }
        @Override public String classify(ArrayNode m, int x) { ended(); return d.classify(m, x); }
        @Override public int contextWindow() { return d.contextWindow(); }
        @Override public String model() { return d.model(); }
    }

    /** A LaBSE stand-in that counts the pairs it scored. */
    static TranslationScores.Scorer counted(AtomicInteger pairs) {
        TranslationScores.Scorer s = TranslationTest.labseStandIn();
        return new TranslationScores.Scorer() {
            @Override public List<Double> score(List<String[]> p) throws IOException { pairs.addAndGet(p.size()); return s.score(p); }
            @Override public String name() { return s.name(); }
            @Override public String model() { return s.model(); }
            @Override public boolean higherIsBetter() { return true; }
        };
    }

    final AtomicInteger scored = new AtomicInteger();
    @BeforeEach void scorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(counted(scored), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() { TranslationScores.SCORERS = TranslationScores::configured; }

    /** A document of 140 short paragraphs: six batches to translate, three steps of scores; every seventh carries a 7 the stand-in drops, so it is flagged and revised. */
    static Path longDoc(Path dir) throws IOException {
        StringBuilder b = new StringBuilder("# The gear train\n\n");
        for (int i = 1; i <= 140; i++) b.append("Paragraph ").append(i).append(" says the gears ").append(i % 7 == 0 ? "were cut in 7 steps" : "were cut by hand").append(" with files and a dividing plate.\n\n");
        Path f = dir.resolve("gears.md");
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
        return f;
    }

    static TranslationTest.Script script() {
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.translate = t -> "訳 " + t.replace(" 7 ", " ");   // the 7 is lost: a number flag, then a revision that keeps it
        return sc;
    }

    record Run(Outcome o, int calls, String doc) { }

    Run translate(LibraryStore store, Path in, Path out, Checkpoint cp, int endAt, BooleanSupplier stop) throws IOException {
        return translate(store, in, out, cp, new Counting(TranslationTest.standIn(script(), "stand-in"), endAt), stop);
    }

    Run translate(LibraryStore store, Path in, Path out, Checkpoint cp, Counting d, BooleanSupplier stop) throws IOException {
        Seats seats = Translation.kept(new Seats(d, "stand-in", d, "stand-in"), cp);
        Outcome o = Translation.run(store, in, "ja", "", "checked", out, null, seats, null, stop, cp);
        return new Run(o, d.calls.get(), Files.readString(o.output()));
    }

    /** A library as each run finds it: the pair's record of twenty accepted units (a run adds to it when it ends, so each run has its own). */
    static LibraryStore library(Path dir) throws IOException {
        LibraryStore store = new LibraryStore(dir); store.init();
        TranslationTest.seedRecord(store);
        return store;
    }

    @Test void aTranslationStoppedPartwayResumesWithNothingTranslatedOrScoredTwice(@TempDir Path tmp) throws Exception {
        Path in = longDoc(Files.createDirectories(tmp.resolve("docs")));
        // the whole run, nothing kept
        Run whole = translate(library(tmp.resolve("whole")), in, tmp.resolve("whole.ja.md"), Checkpoint.NONE, -1, () -> false);
        LibraryStore store = library(tmp.resolve("lib"));
        int wholeScored = scored.getAndSet(0);
        assertTrue(whole.calls() > 10 && whole.o().changes().size() >= 20, "batches, the glossary call and revisions: " + whole.calls() + " calls, " + whole.o().changes().size() + " revisions");
        assertEquals(141, whole.o().labse().size(), "every paragraph and the heading scored");

        // the same translation as job J-0001, ended at its fourth call: three batches kept
        Checkpoint first = Checkpoint.open(store, "J-0001", Translation.JOB_KIND, "b1");
        assertFalse(first.resumed());
        IllegalStateException ended = assertThrows(IllegalStateException.class, () -> translate(store, in, tmp.resolve("job.ja.md"), first, 4, () -> false));
        assertEquals("the process ended", ended.getMessage());
        first.close();
        assertEquals(0, scored.get(), "it ended before any scoring");
        assertTrue(Checkpoint.stateOf(store, "J-0001").path("said").asText().startsWith("unit "), "where it stood, in words: " + Checkpoint.stateOf(store, "J-0001"));

        // the next process: it resumes from what was kept, and asks the model only from the call that was in flight
        Checkpoint second = Checkpoint.open(store, "J-0001", Translation.JOB_KIND, "b1");
        assertTrue(second.resumed());
        Run resumed = translate(store, in, tmp.resolve("job.ja.md"), second, -1, () -> false);
        second.close();
        assertEquals(whole.calls() - 3, resumed.calls(), "the three calls that came back before the end are given back, never asked again; the one in flight is asked again");
        assertEquals(3L, second.givenBack.get());
        assertEquals(whole.doc(), resumed.doc(), "the same document as the run nobody stopped");
        assertEquals(whole.o().flags(), resumed.o().flags());
        assertEquals(whole.o().labse(), resumed.o().labse());
        assertEquals(whole.o().changes(), resumed.o().changes());
        assertEquals(wholeScored, scored.getAndSet(0), "every unit scored once");

        // stopped in the scoring, after its first step of 64: the second process scores the steps after it, and translates nothing
        LibraryStore store2 = library(tmp.resolve("lib2"));
        Checkpoint third = Checkpoint.open(store2, "J-0002", Translation.JOB_KIND, "b1");
        Path out2 = tmp.resolve("job2.ja.md");
        Counting before = new Counting(TranslationTest.standIn(script(), "stand-in"), -1);
        IOException stopped = assertThrows(IOException.class, () -> translate(store2, in, out2, third, before, () -> scored.get() >= 64));
        assertEquals("Stopped during the scoring; nothing was written.", stopped.getMessage());
        third.close();
        int scoredBefore = scored.getAndSet(0);
        assertEquals(64, scoredBefore, "one step scored, then the stop");
        assertEquals("the scoring, unit 64 of 141", Checkpoint.stateOf(store2, "J-0002").path("said").asText());
        Checkpoint fourth = Checkpoint.open(store2, "J-0002", Translation.JOB_KIND, "b1");
        Run after = translate(store2, in, out2, fourth, -1, () -> false);
        fourth.close();
        assertEquals(whole.calls() - before.calls.get(), after.calls(), "no batch translated again: only the calls after the stop (the revisions, the back-translation)");
        assertEquals(wholeScored - 64, scored.get(), "the kept step is not scored again");
        assertEquals(whole.doc(), after.doc());
        assertEquals(whole.o().flags(), after.o().flags());
    }

    @Test void aCheckpointInAnotherFormOrForOtherInputsStartsTheJobOverAndSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint a = Checkpoint.open(store, "J-0007", "crews", "one");
        a.put("step|serials", M.createObjectNode().put("o", "checked"));
        a.close();
        Checkpoint b = Checkpoint.open(store, "J-0007", "crews", "one");
        assertTrue(b.resumed()); assertEquals("checked", b.get("step|serials").path("o").asText()); assertEquals("", b.startedOver());
        b.close();
        // a newer program's form: not read, the folder emptied, and the sentence
        Path state = Checkpoint.dir(store, "J-0007").resolve(Checkpoint.STATE);
        ObjectNode s = (ObjectNode) M.readTree(Files.readString(state)); s.put("format", 99); s.put("version", "9.9.9");
        Files.writeString(state, s.toString());
        Checkpoint c = Checkpoint.open(store, "J-0007", "crews", "one");
        assertFalse(c.resumed()); assertNull(c.get("step|serials"));
        assertEquals("The work kept before was written by ResearchZosho 9.9.9 in a form this version does not read, so the job started over.", c.startedOver());
        assertEquals(1, Checkpoint.lines(store, "J-0007").size(), "only this process's own first line: the old record is gone");
        c.put("step|serials", M.createObjectNode().put("o", "again"));
        c.close();
        // kept for other inputs: the same
        Checkpoint d = Checkpoint.open(store, "J-0007", "crews", "two");
        assertFalse(d.resumed());
        assertEquals("What the job works on changed since its work was kept, so the job started over.", d.startedOver());
        d.close();
        Checkpoint e = Checkpoint.open(store, "J-0007", "translate-doc", "two");
        assertEquals("The work kept before was for another kind of job, so the job started over.", e.startedOver());
        e.close();
        // a line the end of the program cut off is passed over; the rest is read
        Checkpoint f = Checkpoint.open(store, "J-0008", "crews", "x");
        f.put("a", M.getNodeFactory().textNode("kept")); f.close();
        Files.writeString(Checkpoint.dir(store, "J-0008").resolve(Checkpoint.ARTIFACTS), "{\"k\":\"b\",\"v\":\"cut of", StandardOpenOption.APPEND);
        Checkpoint g = Checkpoint.open(store, "J-0008", "crews", "x");
        assertEquals("kept", g.get("a").asText()); assertNull(g.get("b"));
        g.close();
        Checkpoint.discard(store, "J-0008");
        assertFalse(Files.exists(Checkpoint.dir(store, "J-0008")));
    }

    @Test void aSeatGivesItsCallsBackInOrderUntilTheJobGoesAnotherWay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive model = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { return M.createObjectNode().put("content", "answer " + asked.incrementAndGet()); }
            @Override public String classify(ArrayNode m, int x) { return "said " + asked.incrementAndGet(); }
            @Override public int contextWindow() { return 8000; }
        };
        ArrayNode sys = M.createArrayNode(); sys.addObject().put("role", "system").put("content", "You plan, on 2026-11-09 with " + Fence.open("X") + "."); sys.addObject().put("role", "user").put("content", "go");
        Checkpoint a = Checkpoint.open(store, "J-0009", "research", "q");
        Researcher.Drive seat = a.seat(model);
        try (var lane = Checkpoint.lane("run")) {
            assertEquals("said 1", seat.classify(sys, 10)); assertEquals("said 2", seat.classify(sys, 10)); assertEquals("answer 3", seat.chat(sys, M.createArrayNode(), 10, "required").path("content").asText());
        }
        assertEquals("said 4", seat.classify(sys, 10), "a thread with no lane keeps nothing");
        a.close();
        Checkpoint b = Checkpoint.open(store, "J-0009", "research", "q");
        Researcher.Drive again = b.seat(model);
        ArrayNode later = sys.deepCopy(); ((ObjectNode) later.get(0)).put("content", "You plan, on 2026-11-10 with " + Fence.open("X") + ".");
        try (var lane = Checkpoint.lane("run")) {
            assertEquals("said 1", again.classify(later, 10), "another day's date is not another call");
            assertEquals("said 2", again.classify(later, 10));
            assertEquals("said 5", again.classify(later, 10), "the third call kept was a chat: this one went another way, and from here the model is asked");
            assertEquals("answer 6", again.chat(sys, M.createArrayNode(), 10, "required").path("content").asText());
        }
        b.close();
        Checkpoint c = Checkpoint.open(store, "J-0009", "research", "q");
        Researcher.Drive third = c.seat(model);
        try (var lane = Checkpoint.lane("run")) {
            assertEquals("said 1", third.classify(sys, 10)); assertEquals("said 2", third.classify(sys, 10)); assertEquals("said 5", third.classify(sys, 10));
            assertEquals("answer 6", third.chat(sys, M.createArrayNode(), 10, "required").path("content").asText(), "the calls after the turn are the ones kept after it");
        }
        c.close();
    }

    /** What keeping costs: one thing kept and the position written after it, per unit of work, measured on the stand-in. */
    @Test void keepingAUnitCostsLittle(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint cp = Checkpoint.open(store, "J-0010", Translation.JOB_KIND, "x");
        String unit = "訳 Paragraph 12 says the gears were cut by hand with files and a dividing plate. ".repeat(4);
        int n = 2000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            cp.put("m|main|" + i, M.createObjectNode().put("fp", "prose|2|x").put("r", unit));
            cp.position(M.createObjectNode().put("phase", "translating").put("units_done", i).put("units_total", n), "unit " + Checkpoint.count(i) + " of " + Checkpoint.count(n));
        }
        double perUnitMs = (System.nanoTime() - t0) / 1e6 / n;
        cp.close();
        System.out.printf("checkpoint: %.3f ms per unit (one artifact appended and checkpoint.json rewritten), %d units%n", perUnitMs, n);
        assertTrue(perUnitMs < 20, "a unit's keeping is small beside a model call's seconds: " + perUnitMs + " ms");
        assertEquals(n + 1, Checkpoint.lines(store, "J-0010").size());
        JsonNode s = Checkpoint.stateOf(store, "J-0010");
        assertEquals("unit 1,999 of 2,000", s.path("said").asText());
        assertEquals(Checkpoint.FORMAT, s.path("format").asInt());
        assertFalse(s.path("version").asText().isEmpty());
    }

    /** The fence's nonce is the process's own: what is given back carries this process's, so the rule the model reads still names the markers. */
    @Test void theFenceMarkersInWhatIsGivenBackAreThisProcesss(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint a = Checkpoint.open(store, "J-0011", "research", "q");
        a.close();
        Path f = Checkpoint.dir(store, "J-0011").resolve(Checkpoint.ARTIFACTS);
        List<String> lines = new ArrayList<>();
        lines.add("{\"k\":\"#nonce\",\"v\":\"0123456789ab\"}");
        lines.add("{\"k\":\"t|r1/x|web_fetch|abc|1\",\"v\":{\"o\":\"<<<SOURCE TEXT 0123456789ab>>>\\npage\\n<<<END SOURCE TEXT 0123456789ab>>>\"}}");
        Files.write(f, lines, StandardCharsets.UTF_8);
        Checkpoint b = Checkpoint.open(store, "J-0011", "research", "q");
        assertEquals(Fence.wrap("SOURCE TEXT", "page"), b.kept("t|r1/x|web_fetch|abc|1").observation());
        b.close();
    }

    /**
     * A thread interrupted while it keeps something (the round's pool is shut down when a run stops, and every worker still busy is
     * interrupted) keeps it all the same, and the record goes on for every other thread: an interrupt closed the file's channel once, and
     * what the run got after it was lost.
     */
    @Test void anInterruptedThreadKeepsWhatItKeepsAndTheRecordGoesOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint cp = Checkpoint.open(store, "J-0012", "research", "q");
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            cp.put("by the interrupted worker", M.getNodeFactory().textNode("kept"));
            cp.position(M.createObjectNode().put("turns_used", 3), "turn 3");
        });
        t.start(); t.join();
        cp.put("after it", M.getNodeFactory().textNode("kept too"));
        cp.close();
        Checkpoint again = Checkpoint.open(store, "J-0012", "research", "q");
        assertEquals("kept", again.get("by the interrupted worker").asText());
        assertEquals("kept too", again.get("after it").asText());
        assertEquals("turn 3", again.saidBefore());
        again.close();
    }

    /** A decision against the clock is kept in the lane's order and given back while the lane is on its record; the clock stands until the job's first new step. */
    @Test void aDecisionIsGivenBackAndTheClockStandsUntilTheFirstNewStep(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint a = Checkpoint.open(store, "J-0013", "research", "q");
        try (var lane = Checkpoint.lane("run")) {
            assertTrue(a.decided("workers-time-up", () -> true));
            assertEquals(2.5, a.decidedNumber("minutes-left", () -> 2.5));
        }
        assertFalse(a.decided("workers-time-up", () -> false), "a thread with no lane decides now and keeps nothing");
        a.close();
        Checkpoint b = Checkpoint.open(store, "J-0013", "research", "q");
        long worked = b.elapsedBeforeMs();
        Thread.sleep(30);
        assertEquals(worked, b.runMs(), "the clock stands while the job gives back what it kept");
        try (var lane = Checkpoint.lane("run")) {
            assertTrue(b.decided("workers-time-up", () -> false), "given back as it was decided, whatever the clock says now");
            assertEquals(2.5, b.decidedNumber("minutes-left", () -> 99.0));
            assertEquals(worked, b.runMs());
            assertFalse(b.decided("workers-time-up", () -> false), "past the record: decided now");
        }
        Thread.sleep(30);
        assertTrue(b.runMs() >= worked + 25, "and from the first new step the clock runs on from the time worked");
        b.close();
        // a decision of another kind at a place in the record is a job that went another way: decided now, and the record after it is void
        Checkpoint c = Checkpoint.open(store, "J-0013", "research", "q");
        try (var lane = Checkpoint.lane("run")) {
            assertFalse(c.decided("write-up-time-up", () -> false));
            assertEquals(7.0, c.decidedNumber("minutes-left", () -> 7.0), "the rest of the lane's record is void");
        }
        c.close();
    }
}
