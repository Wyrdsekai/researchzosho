package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.tools.Tool;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a stop stops, said as it is. A stop that comes after a run's research and before its report is filed files nothing. A stop that
 * comes once the filing began leaves the report, and `research stop` says so and what is left undone. The nightly tasks end after the
 * task they are on, and `research stop` says that.
 */
class StopWhatItStopsTest {

    static final ObjectMapper M = new ObjectMapper();

    /** A stop asked for once the run's research has returned: true on the thread that files, after Researcher.run and before anything else. */
    static BooleanSupplier afterTheResearch() {
        return () -> {
            List<String> frames = Arrays.stream(Thread.currentThread().getStackTrace()).filter(f -> f.getClassName().equals(Researcher.class.getName())).map(StackTraceElement::getMethodName).toList();
            return frames.contains("file") && !frames.contains("run");
        };
    }

    @Test
    void aStopThatComesAfterTheResearchAndBeforeTheFilingFilesNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        Researcher runner = new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store);
        BooleanSupplier stop = afterTheResearch();
        runner.stopWhen(stop);
        Researcher.Ask ask = new Researcher.Ask("How were the placeholder gears cut?", "depth", 60, List.of("how were the gears cut?"));
        assertThrows(Stopping.Requested.class, () -> Stopping.within(stop, () -> {
            try { return Researcher.file(store, runner, ask, "patron:person", "J-0007", "asked", "asked"); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertTrue(!Files.exists(store.investigationsDir()) || Files.list(store.investigationsDir()).findAny().isEmpty(), "nothing was filed");
    }

    @Test
    void theJobIsMarkedAsFilingOnlyWhenItsReportIsAboutToBeFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        Researcher.Ask ask = new Researcher.Ask("How were the placeholder gears cut?", "depth", 60, List.of("how were the gears cut?"));
        // no web tools and no address in anything the model writes: nothing is read, and the gate refuses the report
        Researcher.Tools none = new Researcher.Tools() {
            @Override public List<Tool> web(String focus) { return List.of(); }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
        ResearcherTest.ScriptedDrive fromMemory = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                try { return (ObjectNode) M.readTree(super.chat(history, tools, maxTokens, toolChoice).toString().replace("https://example.org/gears", "the old book")); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return super.classify(messages, maxTokens).replace("https://example.org/gears", "the old book"); }
        };
        fromMemory.criticWantsMore = false;
        AtomicInteger asked = new AtomicInteger();
        Researcher refused = new Researcher(fromMemory, fromMemory, none, null, 1, store);
        refused.beginFiling(() -> { asked.incrementAndGet(); return true; });
        Researcher.Filed r = Researcher.file(store, refused, ask, "patron:person", "J-0008", "asked", "asked");
        assertFalse(r.admitted(), r.reason());
        assertEquals(0, asked.get(), "no report to file: the job is never marked as filing one");
        Researcher admitted = new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store);
        admitted.beginFiling(() -> { asked.incrementAndGet(); return true; });
        assertTrue(Researcher.file(store, admitted, ask, "patron:person", "J-0009", "asked", "asked").admitted());
        assertEquals(1, asked.get(), "asked once, just before the report is filed");
    }

    /** What `researchzosho research stop` printed. */
    static String stop(LibraryStore store, String id) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
        try { LibrarianCli.research(store, new String[]{"researchzosho", "research", "stop", id}); } finally { System.setOut(out); }
        return o.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theStopSaysWhatItStops(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.setDefault(store, Patrons.Level.write);
        CountDownLatch filing = new CountDownLatch(1), crewsRunning = new CountDownLatch(1), plainRunning = new CountDownLatch(1);
        Jobs[] holder = new Jobs[1];
        AtomicReference<String> afterStop = new AtomicReference<>("");
        Jobs jobs = new Jobs(store, (job, drive) -> {
            String id = job.path("job_id").asText();
            String q = job.path("args").path("question").asText("");
            if (job.path("kind").asText().equals("crews")) crewsRunning.countDown();
            else if (q.equals("files")) { assertTrue(holder[0].beginFiling(id), "no stop yet: the filing begins"); filing.countDown(); }
            else plainRunning.countDown();
            for (int i = 0; i < 500 && !holder[0].stopRequested(id); i++) Thread.sleep(20);
            if (q.equals("files")) return "investigation I-0001-placeholder";
            if (q.equals("plain")) afterStop.set(holder[0].beginFiling(id) ? "began filing" : "files nothing");
            throw new Researcher.Stopped();
        }, List.of("", "", ""), 3);
        holder[0] = jobs;
        String filed = jobs.submit("research", "person", (ObjectNode) M.readTree("{\"question\":\"files\"}"));
        String plain = jobs.submit("research", "person", (ObjectNode) M.readTree("{\"question\":\"plain\"}"));
        jobs.start();
        try {
            assertTrue(filing.await(10, TimeUnit.SECONDS) && plainRunning.await(10, TimeUnit.SECONDS));
            String said = stop(store, plain);
            assertTrue(said.contains("Nothing from it is filed"), said);
            said = stop(store, filed);
            assertTrue(said.contains("had finished its research and begun filing its report") && said.contains("the report stays in your library"), said);
            assertFalse(said.contains("Nothing from it is filed"), said);
            long t0 = System.currentTimeMillis();
            while (!jobs.active().isEmpty() && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50);
            assertEquals("files nothing", afterStop.get(), "a stop that came first: the filing never begins");
            // the nightly tasks run on the first worker, when it is free
            String crews = jobs.submit("crews", "", M.createObjectNode());
            assertTrue(crewsRunning.await(20, TimeUnit.SECONDS));
            said = stop(store, crews);
            assertTrue(said.contains("nightly tasks") && said.contains("after the task they are on now"), said);
            assertFalse(said.contains("Nothing from it is filed"), said);
        } finally {
            jobs.stop();
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
