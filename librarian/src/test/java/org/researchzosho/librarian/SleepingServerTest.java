package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library stops its embeddings server after its idle minutes without a search and starts it at the next one (ModelsAwake): a
 * sleeping server is the ordinary state of a library nobody searched for a while, and its standing says so (0.8.4). It was said as
 * "not answering", with the advice to start by hand what the library starts by itself. A start that fails is a failure, with its
 * reason, never sleep. A look leaves a sleeping server asleep and writes no use. Docker is a stand-in; nothing of the machine is asked.
 */
class SleepingServerTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String NAME = "researchzosho-embed", AT = "http://127.0.0.1:1";   // its address: nothing listens there, as at a stopped container's
    final Searx.Runner realDocker = Searx.runner;
    final Map<String, String> realContainers = ModelsAwake.CONTAINERS;
    final Duration realWait = ModelsAwake.WAKE_WAIT;
    final Supplier<String> realSetting = SearchByMeaning.setting;
    final Predicate<String> realAnswers = SearchByMeaning.answers;
    final List<String> ran = new CopyOnWriteArrayList<>();
    volatile boolean running = true;
    volatile CountDownLatch startHeld;
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        SearchByMeaning.settle(); SearchByMeaning.reprobe(); Help.forgetLooks();
        ModelsAwake.CONTAINERS = Map.of(NAME, AT);
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            String c = cmd.get(1);
            if (c.equals("inspect") && cmd.get(3).contains("ExitCode")) return new Searx.Result(0, (running ? "true" : "false") + " 1\n");
            if (c.equals("inspect")) return new Searx.Result(0, running ? "true\n" : "false\n");
            if (c.equals("stop")) { running = false; return new Searx.Result(0, NAME + "\n"); }
            if (c.equals("start")) { CountDownLatch h = startHeld; if (h != null) h.await(120, TimeUnit.SECONDS); return new Searx.Result(0, NAME + "\n"); }   // it starts, and its server ends at once
            if (c.equals("logs")) return new Searx.Result(0, "loading model\nggml_cuda_init: found 1 CUDA device\nCUDA error: out of memory\nmain: exiting\n");
            return new Searx.Result(1, "not stood in: " + cmd);
        };
        SearchByMeaning.setting = () -> AT;
    }

    @AfterEach void restore() {
        CountDownLatch h = startHeld; if (h != null) h.countDown();
        SearchByMeaning.settle();
        Searx.runner = realDocker; ModelsAwake.CONTAINERS = realContainers; ModelsAwake.WAKE_WAIT = realWait;
        SearchByMeaning.setting = realSetting; SearchByMeaning.answers = realAnswers; SearchByMeaning.reprobe(); Help.forgetLooks();
        System.setProperty("user.home", home); Config.invalidate();
    }

    /** The idle rule's round: the server was last used ten minutes ago, so it is stopped. */
    private void putToSleep() throws Exception {
        Path used = ModelsAwake.stamp(NAME);
        Files.createDirectories(used.getParent()); Files.writeString(used, "");
        Files.setLastModifiedTime(used, FileTime.fromMillis(System.currentTimeMillis() - 10 * 60_000));
        assertEquals(1, ModelsAwake.tick(System.currentTimeMillis()));
        assertFalse(running);
    }

    @Test void aServerTheLibraryPutToSleepIsAsleepAndTheLookLeavesItAsleep(@TempDir Path tmp) throws Exception {
        putToSleep();
        long usedBefore = Files.getLastModifiedTime(ModelsAwake.stamp(NAME)).toMillis();
        AtomicInteger asked = new AtomicInteger();
        SearchByMeaning.answers = base -> { asked.incrementAndGet(); return false; };
        SearchByMeaning.Standing s = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals(SearchByMeaning.ASLEEP, s.state());
        assertTrue(s.sentence().startsWith("search by meaning: asleep — the library stopped the embeddings server at " + AT + " at ")
                && s.sentence().contains(" after " + ModelsAwake.embedIdleMinutes() + " minutes without a search, and the next search starts it again"), s.sentence());
        assertFalse(s.sentence().contains("embed start") || s.sentence().contains("does not answer"), "nothing to start by hand: " + s.sentence());
        assertEquals(0, asked.get(), "a sleeping server is asked nothing");
        assertEquals(usedBefore, Files.getLastModifiedTime(ModelsAwake.stamp(NAME)).toMillis(), "the look writes no use");
        assertTrue(ran.stream().noneMatch(c -> c.startsWith("docker start")), "the look starts nothing: " + ran);
        // the status and Help say the same
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode st = new LibraryProtocol(store).status(M.createObjectNode());
        assertEquals("asleep", st.path("search_by_meaning").asText());
        assertEquals(ModelsAwake.embedIdleMinutes(), st.path("search_by_meaning_idle_minutes").asInt());
        assertFalse(st.path("search_by_meaning_since").asText().isEmpty());
        String help = Help.state(store);
        assertTrue(help.contains("Search by meaning is asleep: the library stopped its server after " + ModelsAwake.embedIdleMinutes() + " minutes without a search, and the next search starts it again."), help);
        assertTrue(Help.state(store, Chrome.in("ja")).contains("意味による検索は休止中です。"));
    }

    @Test void aStartUnderWayIsStartingAndAStartThatFailedIsAFailureWithItsReason() throws Exception {
        putToSleep();
        SearchByMeaning.answers = base -> false;
        ModelsAwake.WAKE_WAIT = Duration.ofMillis(100);
        startHeld = new CountDownLatch(1);
        assertTrue(ModelsAwake.startForCall(AT), "a search finds it stopped: started in the background");   // as a search's own call does
        long until = System.currentTimeMillis() + 60_000;   // the start runs on a thread of its own: waited for, then asserted
        while (ran.stream().noneMatch(c -> c.startsWith("docker start")) && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue(ran.stream().anyMatch(c -> c.startsWith("docker start")), ran.toString());
        SearchByMeaning.Standing s = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals(SearchByMeaning.STARTING, s.state(), s.sentence());
        assertTrue(s.sentence().contains("is starting the embeddings server at " + AT + " for a search"), s.sentence());
        startHeld.countDown();   // it starts, its server ends (no memory on the card), and the wait for its answer ends
        until = System.currentTimeMillis() + 60_000;
        while (ModelsAwake.starting(AT) && System.currentTimeMillis() < until) Thread.sleep(10);
        assertFalse(ModelsAwake.starting(AT), "the start has ended");
        SearchByMeaning.reprobe();
        s = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals("not answering", s.state(), "a start that failed is a failure, not sleep: " + s.sentence());
        assertTrue(s.sentence().contains("did not come up: not loaded: its container was stopped, this request started it, and it did not answer within")
                && s.sentence().contains("it ended with code 1, its log ending «CUDA error: out of memory»"), s.sentence());
        ModelsAwake.Rest r = ModelsAwake.rest(AT);
        assertTrue(r != null && r.failed() && r.why().contains("out of memory"), String.valueOf(r));
        assertTrue(ModelsAwake.restingLine(NAME).contains("out of memory"), "the helpers' front says the same: " + ModelsAwake.restingLine(NAME));
    }

    @Test void aServerStartedByHandAfterTheStopIsNotAsleep() throws Exception {
        putToSleep();
        running = true;   // somebody ran docker start
        SearchByMeaning.answers = base -> true;
        assertNull(ModelsAwake.rest(AT));
        assertEquals("on", SearchByMeaning.standingNow(Duration.ofSeconds(60)).state());
    }
}
