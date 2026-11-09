package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model call that waits on a server that restarted or froze is given up in minutes, not at its limit: while it waits, the server is
 * asked whether it is working on anything. The server here takes the chat request and never answers it; what it says of itself decides.
 */
class LostRequestTest {
    static final ObjectMapper M = new ObjectMapper();
    long grace, every;
    @BeforeEach void quick() { grace = Stopping.LOST_GRACE_MS; every = Stopping.LOST_EVERY_MS; Stopping.LOST_GRACE_MS = 150; Stopping.LOST_EVERY_MS = 100; }
    @AfterEach void back() { Stopping.LOST_GRACE_MS = grace; Stopping.LOST_EVERY_MS = every; }

    /** A server whose chat never answers; {@code pages} maps a path to its JSON ("" closes the connection with no answer; absent is 404). */
    static HttpServer silentChat(Map<String, String> pages, AtomicInteger looks) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/v1/chat/completions")) { ex.getRequestBody().readAllBytes(); return; }   // taken, never answered
            if (path.equals("/running") || path.endsWith("/slots")) looks.incrementAndGet();
            String page = pages.get(path);
            if (page != null && page.isEmpty()) { ex.close(); return; }
            byte[] out = (page == null ? "{}" : page).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(page == null ? 404 : 200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        return s;
    }

    static ArrayNode hello() { ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hello"); return msgs; }

    /** The failure of one chat call against the server, within a limit of four seconds. */
    static Throwable failure(HttpServer s) {
        DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").timeLimit(Duration.ofSeconds(4));
        try { c.chat(hello(), null, 8, "auto"); return null; } catch (Throwable t) { return t; }
    }

    static <T extends Throwable> T causeOf(Throwable t, Class<T> kind) {
        for (Throwable c = t; c != null; c = c.getCause()) if (kind.isInstance(c)) return kind.cast(c);
        return null;
    }

    @Test void aServerThatRestartedIsNoticedAndTheCallIsAskedAgain() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        // llama-swap after a reboot: it answers, and nothing is running
        HttpServer s = silentChat(new ConcurrentHashMap<>(Map.of("/running", "{\"running\":[]}")), looks);
        try {
            long t0 = System.nanoTime();
            Throwable t = failure(s);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Stopping.LostRequest lost = causeOf(t, Stopping.LostRequest.class);
            assertNotNull(lost, "given up as lost, not at its limit: " + t);
            assertTrue(ms < 3000, "well inside the four-second limit: " + ms + " ms");
            assertTrue(lost.getMessage().contains("is no longer working on the request") && lost.getMessage().contains("given up after"), lost.getMessage());
            assertEquals(2, looks.get(), "two looks that found it idle, never one");
            assertTrue(Researcher.serverAway(t), "a lost request is a server that was away: the run waits for it and asks again");
        } finally { s.stop(0); }
    }

    @Test void aLlamaCppServerAloneSaysItAtItsSlots() throws Exception {
        HttpServer s = silentChat(new ConcurrentHashMap<>(Map.of("/slots", "[{\"id\":0,\"is_processing\":false},{\"id\":1,\"is_processing\":false}]")), new AtomicInteger());
        try { assertNotNull(causeOf(failure(s), Stopping.LostRequest.class), "no /running page: its own slots say it is working on nothing"); }
        finally { s.stop(0); }
    }

    @Test void aServerThatIsWorkingOrLoadingKeepsTheCallToItsLimit() throws Exception {
        // a ready model with a busy slot: the request is being worked on (or queued behind another)
        HttpServer busy = silentChat(new ConcurrentHashMap<>(Map.of("/running", "{\"running\":[{\"model\":\"qwen\",\"state\":\"ready\"}]}",
                "/upstream/qwen/slots", "[{\"id\":0,\"is_processing\":true},{\"id\":1,\"is_processing\":false}]")), new AtomicInteger());
        try {
            Throwable t = failure(busy);
            assertNull(causeOf(t, Stopping.LostRequest.class), "a server that says it is working loses nothing: " + t);
            assertNotNull(causeOf(t, HttpTimeoutException.class), "it runs to its limit as before");
            assertFalse(Researcher.serverAway(t), "and a limit passed is not a server away");
        } finally { busy.stop(0); }
        // a model still starting: the request waits for it
        HttpServer loading = silentChat(new ConcurrentHashMap<>(Map.of("/running", "{\"running\":[{\"model\":\"qwen\",\"state\":\"starting\"}]}")), new AtomicInteger());
        try { assertNull(causeOf(failure(loading), Stopping.LostRequest.class), "a model that is loading is working"); }
        finally { loading.stop(0); }
    }

    @Test void aServerThatCannotSayNeverLosesACallAndASilentOneDoesAfterSixLooks() throws Exception {
        // neither page: another kind of server
        HttpServer other = silentChat(new ConcurrentHashMap<>(), new AtomicInteger());
        try { assertNull(causeOf(failure(other), Stopping.LostRequest.class), "a server with neither page cannot say, so nothing is lost on a guess"); }
        finally { other.stop(0); }
        // it answers nothing at all: frozen. (The server's hits are more than the looks: the HTTP client sends a look again when its connection is closed on it.)
        AtomicInteger hits = new AtomicInteger();
        HttpServer frozen = silentChat(new ConcurrentHashMap<>(Map.of("/running", "")), hits);
        try {
            Throwable t = failure(frozen);
            assertNotNull(causeOf(t, Stopping.LostRequest.class), "six looks with no answer: " + t);
            assertTrue(hits.get() >= DriveClient.SILENT_LOOKS, "six looks at least, not fewer: " + hits.get());
        } finally { frozen.stop(0); }
        // a blip: silent for the first looks, then answering and working. Nothing is lost.
        Map<String, String> pages = new ConcurrentHashMap<>(Map.of("/running", ""));
        HttpServer blip = silentChat(pages, new AtomicInteger());
        Thread heal = new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException e) { return; }
            pages.put("/upstream/qwen/slots", "[{\"id\":0,\"is_processing\":true}]");
            pages.put("/running", "{\"running\":[{\"model\":\"qwen\",\"state\":\"ready\"}]}");
        });
        heal.start();
        try {
            Throwable t = failure(blip);
            assertNull(causeOf(t, Stopping.LostRequest.class), "a few silent looks and then a working server lose nothing: " + t);
            assertNotNull(causeOf(t, HttpTimeoutException.class));
        } finally { heal.join(); blip.stop(0); }
    }
}
