package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A search always asks for a container of this machine's that is not running, whatever a look last said and whatever was or was not
 * written down; a look never asks for a start (0.8.4). Found on the live library on 2026-11-26: after an upgrade, its embeddings
 * container asleep with no record of its sleep, the status's look found no connection at its address and marked the address away, and
 * the search that came within half a minute went by words with "did not answer" and asked for no start. Docker is a stand-in; the
 * container's address is a closed port, as a stopped container's is.
 */
class SearchWakesWhateverWasSaidTest {

    static final ObjectMapper M = new ObjectMapper();
    final Searx.Runner realDocker = Searx.runner;
    final Map<String, String> realContainers = ModelsAwake.CONTAINERS;
    final Duration realWait = ModelsAwake.WAKE_WAIT;
    final LongSupplier realHelpersClock = Helpers.NOW;
    final Supplier<String> realSetting = SearchByMeaning.setting;
    final Predicate<String> realAnswers = SearchByMeaning.answers;
    final List<String> ran = new CopyOnWriteArrayList<>();
    volatile boolean running = false;
    volatile CountDownLatch startHeld;   // a start the test holds, so that "starting" is read while it is under way, not by luck
    String home, at;
    LibraryStore store;

    @BeforeEach void isolate(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget(); SearchByMeaning.settle(); SearchByMeaning.reprobe();
        int closed; try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
        at = "http://127.0.0.1:" + closed;   // nothing listens: a stopped container's address
        ModelsAwake.CONTAINERS = Map.of(Embed.CONTAINER, at);
        ModelsAwake.WAKE_WAIT = Duration.ofMillis(100);   // the started server never answers here: the wait for it ends at once
        Config.provide(Map.of("RESEARCHZOSHO_EMBED", at));
        SearchByMeaning.setting = () -> at;
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (!cmd.get(cmd.size() - 1).equals(Embed.CONTAINER)) return new Searx.Result(1, "Error: No such object");
            return switch (cmd.get(1)) {
                case "inspect" -> new Searx.Result(0, cmd.get(3).contains("ExitCode") ? (running ? "true 0\n" : "false 0\n") : running ? "true\n" : "false\n");
                case "start" -> { CountDownLatch h = startHeld; if (h != null) h.await(120, TimeUnit.SECONDS); yield new Searx.Result(0, Embed.CONTAINER + "\n"); }   // started; its server never comes up here
                case "logs" -> new Searx.Result(0, "");
                default -> new Searx.Result(1, "not stood in: " + cmd);
            };
        };
        // a failure to connect is taken for one that waited seconds, as a stopped container's address on Docker's network does (no route)
        Helpers.NOW = () -> System.currentTimeMillis() + 5_000;
    }

    @AfterEach void restore() throws Exception {
        CountDownLatch h = startHeld; if (h != null) h.countDown();
        until(() -> !ModelsAwake.starting(at));
        SearchByMeaning.settle();
        Searx.runner = realDocker; ModelsAwake.CONTAINERS = realContainers; ModelsAwake.WAKE_WAIT = realWait; Helpers.NOW = realHelpersClock;
        SearchByMeaning.setting = realSetting; SearchByMeaning.answers = realAnswers; SearchByMeaning.reprobe();
        Config.provide(Map.of()); System.setProperty("user.home", home); Config.invalidate(); Helpers.forget();
    }

    static void until(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
    }

    long starts() { return ran.stream().filter(c -> c.startsWith("docker start")).count(); }

    LibraryProtocol library(Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-a", "Harpsichords of Antwerp", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/a", "n/a", "s")), List.of(), null,
                "The Ruckers family built harpsichords in Antwerp for two centuries.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return new LibraryProtocol(store);
    }

    ObjectNode search(LibraryProtocol p) throws Exception { return p.search(M.createObjectNode().put("query", "plucked keyboard instrument").put("k", 5)); }

    /** The status's word after a fresh look: the look the status starts, ended. */
    String status(LibraryProtocol p) throws Exception { SearchByMeaning.reprobe(); SearchByMeaning.standingNow(Duration.ofSeconds(60)); return p.status(M.createObjectNode()).path("search_by_meaning").asText(); }

    void searchWakesIt(LibraryProtocol p) throws Exception {
        assertTrue(Helpers.away(at), "the address is taken as away, as after a look or a search that found no connection a moment ago");
        ObjectNode r = search(p);
        until(() -> starts() >= 1);
        assertEquals(1, starts(), "the search asked for the container's start, whatever was said or written before it: " + ran);
        assertEquals("Searched by words this time: search by meaning is starting.", r.path("note").asText(), r.toString());
    }

    @Test void aStoppedContainerWithNoRecordIsStartedByTheSearchAndTheLookAsksForNothing(@TempDir Path tmp) throws Exception {
        LibraryProtocol p = library(tmp);
        SearchByMeaning.Standing s = SearchByMeaning.standingNow(Duration.ofSeconds(60));   // the status's look, as at the library's start
        assertEquals(0, starts(), "a look asks for no start: " + ran);
        startHeld = new CountDownLatch(1);
        assertTrue(Help.state(store).contains("Search by meaning is stopped: its server is not running, and the next search starts it."), Help.state(store));
        Helpers.failed(at, new ConnectException("No route to host"), System.currentTimeMillis());   // what a look or a search a moment ago found
        searchWakesIt(p);
        assertEquals("stopped", s.state(), "not running, and no record of why: the next search starts it: " + s.sentence());
        assertTrue(s.sentence().contains("the next search starts it") && !s.sentence().contains("embed start"), s.sentence());
        assertEquals("starting", status(p), "while the search's start is under way");
        startHeld.countDown();
    }

    @Test void aContainerTheIdleRuleStoppedIsStartedByTheSearchWhateverTheAddressSaidAMomentAgo(@TempDir Path tmp) throws Exception {
        LibraryProtocol p = library(tmp);
        Path used = ModelsAwake.stamp(Embed.CONTAINER), stopped = ModelsAwake.stoppedMark(Embed.CONTAINER);
        Files.createDirectories(used.getParent()); Files.writeString(used, ""); Files.writeString(stopped, "5");
        Files.setLastModifiedTime(used, FileTime.fromMillis(System.currentTimeMillis() - 10 * 60_000));
        Files.setLastModifiedTime(stopped, FileTime.fromMillis(System.currentTimeMillis() - 5 * 60_000));
        assertEquals("asleep", status(p));
        assertEquals(0, starts(), "a look asks for no start: " + ran);
        Helpers.failed(at, new ConnectException("No route to host"), System.currentTimeMillis());
        searchWakesIt(p);
    }

    @Test void afterAStartThatFailedASearchTriesAgain(@TempDir Path tmp) throws Exception {
        LibraryProtocol p = library(tmp);
        Path used = ModelsAwake.stamp(Embed.CONTAINER), woke = ModelsAwake.wokeMark(Embed.CONTAINER);
        Files.createDirectories(used.getParent()); Files.writeString(used, "");
        Files.writeString(woke, "not loaded: its container was stopped, this request started it, and it did not answer within 2 minutes; it ended with code 1, its log ending «CUDA error: out of memory»");
        assertEquals("not answering", status(p), "a start that failed is a failure");
        assertEquals(0, starts(), "a look asks for no start: " + ran);
        Helpers.failed(at, new ConnectException("No route to host"), System.currentTimeMillis());
        searchWakesIt(p);
    }
}
