package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drive probe names the model. A router such as llama-swap answers 404 to a completion that names no
 * model, and 0.1.7's probe named none — so every router drive read as "does not answer" and research runs
 * waited forever (seen on a real node, 2026-09-12).
 */
class CrewsDriveProbeTest {

    private static HttpServer router(int loadingStatus, long delayMs) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out;
            int code;
            if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { } }
            if (loadingStatus != 0) { code = loadingStatus; out = "{\"error\":\"loading\"}".getBytes(StandardCharsets.UTF_8); }
            else if (!body.contains("\"model\"")) { code = 404; out = "{\"error\":\"no model named\"}".getBytes(StandardCharsets.UTF_8); }
            else { code = 200; out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        return s;
    }

    private static String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    @Test
    void theProbeNamesTheModelSoARouterAnswers() throws Exception {
        HttpServer s = router(0, 0);
        try {
            assertEquals(Crews.DriveState.ANSWERS, Crews.driveState(url(s), "qwen3.8-27b", Duration.ofSeconds(5)));
            assertEquals(Crews.DriveState.ANSWERS, Crews.driveState(url(s), "", Duration.ofSeconds(5)), "no configured model: the install's alias still routes");
            assertTrue(Crews.driveAnswers(url(s)));
        } finally { s.stop(0); }
    }

    @Test
    void aServerStillLoadingIsStartingNotDown() throws Exception {
        HttpServer loading = router(503, 0);
        HttpServer slow = router(0, 3_000);
        try {
            assertEquals(Crews.DriveState.STARTING, Crews.driveState(url(loading), "m", Duration.ofSeconds(5)), "llama.cpp answers 503 while the model loads");
            assertEquals(Crews.DriveState.STARTING, Crews.driveState(url(slow), "m", Duration.ofMillis(500)), "connected, the body outlasts the probe: a cold load, not an absent server");
        } finally { loading.stop(0); slow.stop(0); }
    }

    @Test
    void nothingListeningIsDown() throws Exception {
        HttpServer s = router(0, 0);
        String u = url(s);
        s.stop(0);
        assertEquals(Crews.DriveState.DOWN, Crews.driveState(u, "m", Duration.ofSeconds(5)));
        assertEquals(Crews.DriveState.DOWN, Crews.driveState("", "m", Duration.ofSeconds(5)));
    }

    @Test
    void theNightlySchedulerNeverSpins() throws Exception {
        // an hour outside 0-23 threw on every pass, and the catch came straight back: 58 GB of crews.log in half an
        // hour (2026-09-15). The hour is folded into range, and any failure waits before the next try.
        assertEquals(1, Crews.millisUntil(25, java.time.ZonedDateTime.now()) > 0 ? 1 : 0, "a folded hour is a real time");
        assertTrue(Crews.AFTER_FAILURE >= 60_000, "a failure waits at least a minute");
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("rz-nightly");
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        Thread t = Crews.nightly(store, 25, fired::incrementAndGet);
        t.start();
        Thread.sleep(1500);
        t.interrupt(); t.join(2000);
        java.nio.file.Path log = store.root().resolve("catalog").resolve("crews.log");
        long lines = java.nio.file.Files.exists(log) ? java.nio.file.Files.readAllLines(log).size() : 0;
        assertTrue(lines <= 2, "the scheduler wrote " + lines + " lines in 1.5 s: it is spinning");
        assertEquals(0, fired.get(), "an hour 25 folds to 01:00, not to now");
    }
}
