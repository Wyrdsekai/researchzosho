package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.HangingServer;
import org.researchzosho.Stopping;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that took the request and never answers: without a stop, the call ends at its time limit and fails as a request that
 * did not get through; with a stop, the call ends within seconds of it and the connection is closed. The owner's run sat six hours on
 * one such request (2026-09-24) and his stop was never seen, because the stop was looked at only between two turns.
 */
class DriveStopTest {
    static final ObjectMapper M = new ObjectMapper();

    static ArrayNode task() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "Summarise the placeholder topic."); return m; }

    /** Runs {@code call} on its own thread under a stop that is asked for once the server holds the request; what it ended with, and when. */
    static Object[] stoppedWhileHanging(HangingServer server, Supplier<Object> call) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Object> ended = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { ended.set(Stopping.within(stop::get, call)); } catch (Throwable e) { ended.set(e); }
        });
        t.start();
        assertTrue(server.awaitHung(10), "the request reached the server");
        long asked = System.nanoTime();
        stop.set(true);
        t.join(10_000);
        long ms = (System.nanoTime() - asked) / 1_000_000;
        assertFalse(t.isAlive(), "the call ended after the stop");
        return new Object[]{ended.get(), ms};
    }

    @ParameterizedTest
    @EnumSource(HangingServer.Mode.class)
    void withoutAStopTheTimeLimitEndsTheCall(HangingServer.Mode mode) throws Exception {
        try (HangingServer s = new HangingServer(mode)) {
            DriveClient c = new DriveClient(s.url(), "placeholder-model").timeLimit(Duration.ofSeconds(2));
            long t0 = System.nanoTime();
            RuntimeException e = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(RuntimeException.class, () -> c.chat(task(), null, 64, "auto")));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 8_000, "ended at its limit, not later: " + ms + " ms");
            assertTrue(e.getMessage().startsWith("chat() failed against"), "a request that did not get through, which a research run does not ask again with the same limit: " + e);
            assertInstanceOf(HttpTimeoutException.class, e.getCause(), "a transport failure: " + e);
            assertTrue(e.getCause().getMessage().contains("gave no answer within 2 seconds") || e.getCause().getMessage().contains("did not finish its answer within 2 seconds"), e.getCause().getMessage());
            assertTrue(s.awaitClosed(5), "the connection was closed when the call was given up");
            // a decision and a one-word question end the same way
            s.reset();
            String[] said = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> new String[]{c.classify(task(), 4), DriveClient.lastClassifyProblem()});
            assertEquals("", said[0]);
            assertTrue(said[1].contains("did not answer in time") && said[1].contains("gave no answer within 2 seconds"), said[1]);
            s.reset();
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(IOException.class, () -> c.postChat(M.createObjectNode().set("messages", task()))));
        }
    }

    /**
     * The HTTP client keeps a request's own time limit too, and when it gives the request up first its text is "request timed out". With the
     * two limits the same, which comes first is a matter of milliseconds: on Linux the wait, on macOS mostly the client. Here the client's is
     * the shorter, so it comes first everywhere.
     */
    @Test
    void whenTheHttpClientGivesTheRequestUpFirstTheCallSaysSoInTheSameWords() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.SILENT); HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.url() + "/v1/chat/completions")).timeout(Duration.ofSeconds(1))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            long t0 = System.nanoTime();
            HttpTimeoutException t = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(HttpTimeoutException.class,
                    () -> Stopping.send(http, req, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(15), "the placeholder server")));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 8_000, "ended at the request's own limit, not the wait's: " + ms + " ms");
            assertEquals("the placeholder server gave no answer within 1 second, so the request was given up and its connection closed", t.getMessage());
            assertInstanceOf(HttpTimeoutException.class, t.getCause(), "the client's own failure is kept beneath it: " + t.getCause());
            assertTrue(s.awaitClosed(5), "the connection was closed when the request was given up");
        }
    }

    @ParameterizedTest
    @EnumSource(HangingServer.Mode.class)
    void aStopEndsTheChatCallInFlightWithinSecondsAndClosesTheConnection(HangingServer.Mode mode) throws Exception {
        try (HangingServer s = new HangingServer(mode)) {
            DriveClient c = new DriveClient(s.url(), "placeholder-model");   // the drive's own limit: minutes
            Object[] r = stoppedWhileHanging(s, () -> c.chat(task(), null, 64, "required"));
            assertInstanceOf(Stopping.Requested.class, r[0], "the call ended because of the stop: " + r[0]);
            assertTrue((long) r[1] < 3_000, "within seconds of the stop: " + r[1] + " ms");
            assertTrue(s.awaitClosed(5), "the connection to the model server was closed");
        }
    }

    @Test
    void aStopEndsAOneWordQuestionAndATypedDecisionInFlight() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.SILENT)) {
            DriveClient c = new DriveClient(s.url(), "placeholder-model");
            Object[] r = stoppedWhileHanging(s, () -> c.classify(task(), 4));
            assertInstanceOf(Stopping.Requested.class, r[0], "a stop is no empty answer: " + r[0]);
            assertTrue((long) r[1] < 3_000, r[1] + " ms");
            s.reset();
            Judge judge = new Judge(c);
            Object[] j = stoppedWhileHanging(s, () -> judge.noul("A page about the placeholder topic.", "Is this page about the placeholder topic?"));
            assertInstanceOf(Stopping.Requested.class, j[0], "a stop is no unanswered decision: " + j[0]);
            assertTrue((long) j[1] < 3_000, j[1] + " ms");
            assertTrue(s.awaitClosed(5));
        }
    }

    @Test
    void aStopEndsAStreamedAnswerThatStoppedComing(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // streaming is a setting: a scratch config, never the machine's own
        Config.invalidate();
        // the server says what it is at once (a llama.cpp server with one slot); the stream it starts is what stops coming
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY, r -> r.line().startsWith("GET "), "{\"total_slots\":1}")) {
            Config.set("stream", "on");
            DriveClient c = new DriveClient(s.url(), "placeholder-model");
            StringBuilder shown = new StringBuilder();
            DriveClient.streamTo(shown::append);
            Object[] r = stoppedWhileHanging(s, () -> c.chat(task(), null, 64, "auto"));
            assertInstanceOf(Stopping.Requested.class, r[0], "a stopped stream is not asked again without the stream: " + r[0]);
            assertTrue((long) r[1] < 3_000, r[1] + " ms");
            assertEquals(1, s.hung.size(), "one request, not a second one after the stream: " + s.hung);
            assertTrue(s.hung.get(0).body().contains("\"stream\":true"), "the request was a streamed one: " + s.hung.get(0).body());
        } finally {
            DriveClient.streamTo(null);
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theStopIsAskedOfTheThreadTheWorkRunsOnAndIsGoneAfter() {
        assertFalse(Stopping.requested());
        assertTrue(Stopping.within(() -> true, Stopping::requested));
        assertFalse(Stopping.requested(), "the stop belongs to the work it was set for");
        assertTrue(Stopping.within(() -> true, () -> Stopping.within(() -> false, Stopping::requested)), "an inner stop never hides the outer one");
    }
}
