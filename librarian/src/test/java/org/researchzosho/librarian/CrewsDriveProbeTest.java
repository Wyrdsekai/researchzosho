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
}
