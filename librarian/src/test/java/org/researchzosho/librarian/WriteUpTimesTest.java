package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write-up's estimate learns from the library's own runs (0.7.0): on eight live runs it stood at four to six times what the write-up
 * then took, and it bounded the workers' time, the picture steps, the critic's round and a second write-up. The sections are now reckoned
 * at the typical worker turn, and every run records estimate and actual; the next run scales its estimate by the median ratio, clamped.
 */
class WriteUpTimesTest {
    /** The eight live pairs, minutes: estimate, actual (J-0003 … J-0013, 2026-11-08). */
    static final double[][] LIVE = {{22.5, 4}, {16, 3.7}, {18.3, 4.4}, {20.3, 4.5}, {22.8, 3.6}, {18, 9}, {27.9, 17}, {31.8, 5}};

    String home;
    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    static long min(double m) { return Math.round(m * 60_000); }

    @Test void theCorrectionIsOneBeforeAnyRunThenTheClampedMedianOfTheRecordedRatios(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertEquals(1.0, WriteUpTimes.correction(store), "the prior: nothing recorded, the estimate stands");
        assertEquals(1.0, WriteUpTimes.correction(null));
        // one run recorded: the slow-worker, fast-write-up shape of J-0003 — a ratio of 0.18, clamped to a half
        WriteUpTimes.record(store, "J-0003", min(22.5), min(4));
        assertEquals(0.5, WriteUpTimes.correction(store), 1e-9, "the clamp's floor keeps a margin");
        assertEquals(1, WriteUpTimes.recorded(store));
        assertTrue(Files.readString(WriteUpTimes.file(store)).contains("\"job\":\"J-0003\",\"estimate_ms\":1350000,\"actual_ms\":240000,\"ratio\":0.178"), Files.readString(WriteUpTimes.file(store)));
        // all eight: the median ratio is 0.23, clamped to a half
        for (int i = 1; i < LIVE.length; i++) WriteUpTimes.record(store, "J-" + i, min(LIVE[i][0]), min(LIVE[i][1]));
        assertEquals(8, WriteUpTimes.recorded(store));
        assertEquals(0.5, WriteUpTimes.correction(store), 1e-9);
        // a library whose write-ups overrun their estimates: the ceiling of the clamp
        LibraryStore slow = new LibraryStore(tmp.resolve("slow")); slow.init();
        WriteUpTimes.record(slow, "", min(5), min(20));
        assertEquals(1.5, WriteUpTimes.correction(slow), 1e-9);
        // a library whose estimates are about right keeps them: the median within the clamp
        LibraryStore right = new LibraryStore(tmp.resolve("right")); right.init();
        WriteUpTimes.record(right, "", min(10), min(8)); WriteUpTimes.record(right, "", min(10), min(12)); WriteUpTimes.record(right, "", min(10), min(9));
        assertEquals(0.9, WriteUpTimes.correction(right), 1e-9);
        // nothing is recorded without a measured time or an estimate, nor under a tenth of a second: a stand-in's run says nothing of a model
        WriteUpTimes.record(right, "", 0, min(8)); WriteUpTimes.record(right, "", min(8), 0); WriteUpTimes.record(right, "", 40, 60); WriteUpTimes.record(right, "", min(8), 99);
        assertEquals(3, WriteUpTimes.recorded(right));
        WriteUpTimes.record(right, "", 100, 100); assertEquals(4, WriteUpTimes.recorded(right), "a tenth of a second on both sides is the floor");
    }

    @Test void theFileKeepsTheLastTwentyRuns(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (int i = 0; i < 25; i++) WriteUpTimes.record(store, "J-" + i, min(10), min(i < 5 ? 30 : 10));   // the five earliest overran; they fall off
        assertEquals(20, WriteUpTimes.recorded(store));
        assertFalse(Files.readString(WriteUpTimes.file(store)).contains("\"job\":\"J-4\""), "the oldest are gone");
        assertEquals(1.0, WriteUpTimes.correction(store), 1e-9, "only the last twenty count");
    }

    @Test void aSlowWorkerFastWriteUpRunIsEstimatedWithinTwiceTheMeasuredTimeAfterOneRecordedRun(@TempDir Path tmp) throws Exception {
        // J-0003's shape: worker turns 55 s typical with a slow tail to 131 s, classify calls 9 s; the write-up took 4 minutes
        ResearchSettings.Throttle t = new ResearchSettings.Throttle(null);
        for (int i = 0; i < 8; i++) t.observe(55_000, 6_000);
        t.observe(131_000, 6_000); t.observe(120_000, 6_000);
        for (int i = 0; i < 17; i++) t.observeShort(9_000);
        assertEquals(55_000, t.typicalTurnMs()); assertEquals(120_000, t.longTurnMs());
        long atLong = Researcher.wrapUpEstimate(t.longTurnMs(), Researcher.shortCallMs(t), 1), atTypical = Researcher.wrapUpEstimate(t.typicalTurnMs(), Researcher.shortCallMs(t), 1);
        assertEquals((8 + 1) * 55_000L + 7 * 9_000L, atTypical, "the sections and the closing turn at the typical turn, the check turns at a short call");
        assertTrue(atLong > 4 * 240_000L, "at the long turn the estimate was over four times the measured four minutes: " + atLong / 60_000.0 + " min");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        long first = Math.round(atTypical * WriteUpTimes.correction(store));
        assertEquals(atTypical, first, "the first run on a library uses the estimate as it is");
        WriteUpTimes.record(store, "J-0003", atTypical, 240_000);
        long next = Math.round(atTypical * WriteUpTimes.correction(store));
        assertTrue(next >= 240_000 && next <= 2 * 240_000, "after one recorded run the estimate is within twice the measured four minutes, and never under it: " + next / 60_000.0 + " min");
    }

    @Test void everyRunLogsWhatTheWriteUpTookAgainstItsEstimateAndRecordsThePair(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a drive that takes a while a turn: a run of milliseconds yields an estimate under the floor, which is rightly not recorded
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) { try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return super.chat(history, tools, maxTokens, toolChoice); }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        var r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask("how were the gears cut?", "depth", 60, List.of("how were the gears cut?")), "");
        assertTrue(r.done(), r.summary());
        String line = log.stream().filter(l -> l.startsWith("write-up: took ")).findFirst().orElseThrow(() -> new AssertionError(log.toString()));
        assertTrue(line.matches("write-up: took \\d+\\.\\d min against an estimate of \\d+\\.\\d min"), line);
        assertEquals(1, WriteUpTimes.recorded(store), "the pair is on the record for the next run");
        // the next run on this library says how it scaled its estimate
        for (int i = 0; i < 3; i++) WriteUpTimes.record(store, "", min(20), min(4));
        log.clear();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask("how were the gears cut?", "depth", 60, List.of("how were the gears cut?")), "");
        assertTrue(log.stream().anyMatch(l -> l.equals("write-up: the estimate is scaled by 0.50× from the last 4 run(s) on this library, whose write-ups took that share of their estimates")), log.toString());
        String took = log.stream().filter(l -> l.startsWith("write-up: took ")).findFirst().orElseThrow();
        assertTrue(took.matches("write-up: took \\d+\\.\\d min against an estimate of \\d+\\.\\d min \\(\\d+\\.\\d min before the scaling by 0\\.50× from earlier runs\\)"), took);
        assertEquals(5, WriteUpTimes.recorded(store));
    }
}
