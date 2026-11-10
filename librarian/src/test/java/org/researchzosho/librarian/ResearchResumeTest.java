package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Stopping;
import org.researchzosho.tools.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research run stopped partway — between turns, as a restart stops it — and run again from its kept work: the same report as a run
 * nobody stopped, with no model turn asked twice and no page searched or fetched twice; the next process asks the model only from where
 * the last one stopped, in every worker at once.
 */
class ResearchResumeTest {
    static final ObjectMapper J = new ObjectMapper();

    /** Web tools that count their searches and their fetches. */
    static final class Counted implements Researcher.Tools {
        final AtomicInteger searches = new AtomicInteger(), fetches = new AtomicInteger();
        @Override public List<Tool> web(String focus) {
            return List.of(
                    ResearcherTest.tool("web_search", "search", "query", a -> { searches.incrementAndGet(); return "1. Example source — https://example.org/" + a.path("query").asText("q").replaceAll("\\W+", "-"); }),
                    ResearcherTest.tool("web_fetch", "fetch", "url", a -> { fetches.incrementAndGet(); return "TEXT of " + a.path("url").asText() + ": the Antikythera gears were cut by hand with files."; }));
        }
        @Override public BooleanSupplier exhausted() { return () -> false; }
    }

    static int calls(ResearcherTest.ScriptedDrive d) { return d.offered.size() + d.classifies.size(); }

    static final Researcher.Ask ASK = new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 60, List.of("how were the gears cut?", "who cut them?", "what does the dividing plate show?"));

    void resumesWhereItStopped(Path tmp, int workers, int stopAt) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib-" + workers + "-" + stopAt)); store.init();
        // the run nobody stopped
        ResearcherTest.ScriptedDrive d0 = new ResearcherTest.ScriptedDrive(); Counted t0 = new Counted();
        Researcher.Result whole = new Researcher(d0, d0, t0, s -> { }, workers, null).run(ASK, "");
        assertEquals(2, whole.rounds());

        // the same run as job J-0001, stopped once it has made stopAt model turns
        ResearcherTest.ScriptedDrive d1 = new ResearcherTest.ScriptedDrive(); Counted t1 = new Counted();
        Checkpoint first = Checkpoint.open(store, "J-0001", "research", "q");
        Researcher r1 = new Researcher(d1, d1, t1, s -> { }, workers, null).checkpoint(first);
        r1.stopWhen(() -> d1.offered.size() >= stopAt);
        assertThrows(Stopping.Requested.class, () -> r1.run(ASK, ""));
        first.close();
        assertTrue(calls(d1) >= stopAt && calls(d1) < calls(d0), "stopped partway: " + calls(d1) + " of " + calls(d0));
        String said = Checkpoint.stateOf(store, "J-0001").path("said").asText();
        assertTrue(said.matches("turn \\d+"), "where it stood, in words: " + said);

        // the next process
        ResearcherTest.ScriptedDrive d2 = new ResearcherTest.ScriptedDrive(); Counted t2 = new Counted();
        Checkpoint second = Checkpoint.open(store, "J-0001", "research", "q");
        assertTrue(second.resumed());
        Researcher.Result resumed = new Researcher(d2, d2, t2, s -> { }, workers, null).checkpoint(second).run(ASK, "");
        second.close();
        assertEquals(whole.answer(), resumed.answer(), "the same report");
        assertEquals(whole.evidence(), resumed.evidence(), "the same evidence, worker by worker");
        assertEquals(whole.turnsUsed(), resumed.turnsUsed(), "the same turns, the kept ones counted");
        assertEquals(whole.rounds(), resumed.rounds());
        assertEquals(calls(d0), calls(d1) + calls(d2), "no model call asked twice: " + calls(d1) + " before the stop, " + calls(d2) + " after it, " + calls(d0) + " in the run nobody stopped");
        assertEquals(t0.fetches.get(), t1.fetches.get() + t2.fetches.get(), "no page fetched twice");
        assertEquals(t0.searches.get(), t1.searches.get() + t2.searches.get(), "no search made twice");
        assertTrue(second.givenBack.get() >= stopAt, "what was kept came back: " + second.givenBack.get());
    }

    @Test void aRunWithOneReaderAtATimeResumesAtTheTurnItWasOn(@TempDir Path tmp) throws Exception {
        resumesWhereItStopped(tmp, 1, 3);    // in the first round's workers
        resumesWhereItStopped(tmp, 1, 13);   // in the second round, after the critic
        resumesWhereItStopped(tmp, 1, 17);   // in the write-up
    }

    @Test void aRunWithReadersSideBySideResumesEachOfThemAtItsOwnTurn(@TempDir Path tmp) throws Exception {
        resumesWhereItStopped(tmp, 3, 5);
        resumesWhereItStopped(tmp, 3, 10);
    }

    /** A worker's tool that brings back what it did beside its answer: given back, it is done again on the run's counts, not run again. */
    @Test void aKeptRecordSearchCountsAsTheSearchItWas(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Researcher r = new Researcher(new ResearcherTest.ScriptedDrive(), new Counted(), s -> { });
        ArrayNode events = J.createArrayNode();
        ObjectNode e = events.addObject(); e.put("e", "searched"); e.put("kind", "newspaper"); e.put("name", "Old papers"); e.put("id", "old-papers"); e.put("q", "gears"); e.put("from", 1900); e.put("to", 1910); e.putArray("hits");
        ObjectNode f = events.addObject(); f.put("e", "refused"); f.put("name", "Archive"); f.put("id", "archive"); f.put("q", "gears"); f.put("why", "HTTP 503");
        r.againFrom(events);
        assertTrue(r.searchedSection().contains("Old papers: gears, 1900-1910"), "searched and found nothing, as the call said: " + r.searchedSection());
        assertTrue(r.recordKindsLine().contains("newspaper ×1"), r.recordKindsLine());
    }

    /**
     * A decision the run made against its clock is part of its record. A run with a time ceiling stopped partway, its record saying it had
     * worked past the ceiling: the resumed run gives back every call and every decision kept, re-deciding none of them on the clock, which
     * stands at the time worked until the first new step; the new steps are held to the ceiling, and the workers close on their deadline.
     */
    @Test void aDecisionMadeAgainstTheClockIsGivenBackAndOnlyNewGroundIsHeldToTheCeiling(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Researcher.Ask timed = new Researcher.Ask(ASK.question(), "broad", 60, ASK.subQuestions(), "both", List.of(), 60);
        ResearcherTest.ScriptedDrive d1 = new ResearcherTest.ScriptedDrive(); Counted t1 = new Counted();
        Checkpoint first = Checkpoint.open(store, "J-0002", "research", "q");
        Researcher r1 = new Researcher(d1, d1, t1, s -> { }, 1, null).checkpoint(first);
        r1.stopWhen(() -> d1.offered.size() >= 2);
        assertThrows(Stopping.Requested.class, () -> r1.run(timed, ""));
        first.close();
        long kept = Checkpoint.lines(store, "J-0002").stream().filter(l -> l.startsWith("{\"k\":\"m|") || l.startsWith("{\"k\":\"t|")).count();
        long decisions = Checkpoint.lines(store, "J-0002").stream().filter(l -> l.startsWith("{\"k\":\"d|")).count();
        assertTrue(kept >= 3 && decisions >= 3, "calls, tools and the decisions against the clock are on the record: " + kept + " and " + decisions);
        // the record says the run had worked 61 minutes of its 60: a clock read again would end every worker at its first turn given back
        Path state = Checkpoint.dir(store, "J-0002").resolve(Checkpoint.STATE);
        ObjectNode st = (ObjectNode) J.readTree(Files.readString(state)); st.put("elapsed_ms", 61 * 60_000L); Files.writeString(state, st.toString());

        ResearcherTest.ScriptedDrive d2 = new ResearcherTest.ScriptedDrive(); Counted t2 = new Counted();
        Checkpoint second = Checkpoint.open(store, "J-0002", "research", "q");
        Researcher.Result resumed = new Researcher(d2, d2, t2, s -> { }, 1, null).checkpoint(second).run(timed, "");
        second.close();
        assertEquals(kept, second.givenBack.get(), "every call and tool kept is given back: no decision on the moved clock sent a worker another way");
        assertTrue(resumed.evidence().contains("closed on the deadline"), "the new steps are held to the ceiling: " + resumed.evidence());
        assertTrue(d2.offered.stream().anyMatch(o -> o.equals("done")), "a worker's new turn offered done alone: " + d2.offered);
        assertTrue(resumed.stats().path("rounds_cut").asBoolean(false), "no second round past the ceiling");
    }
}
