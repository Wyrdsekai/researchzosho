package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The answer appears as it is written (0.7.0): the drive's pieces reach the page as it polls, and a stop ends the turn with what came so far kept. */
class ChatStreamTest {

    static final ObjectMapper M = new ObjectMapper();

    /** A model server that streams {@code pieces} one every {@code gapMs}, and answers a plain request whole. */
    static HttpServer model(String[] pieces, int gapMs, AtomicInteger streams) throws Exception {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!x.getRequestURI().getPath().endsWith("/chat/completions")) { x.sendResponseHeaders(404, -1); x.close(); return; }
            if (body.contains("\"stream\":true")) {
                streams.incrementAndGet();
                x.getResponseHeaders().set("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                try (OutputStream os = x.getResponseBody()) {
                    for (String p : pieces) {
                        os.write(("data: {\"choices\":[{\"delta\":{\"content\":" + M.writeValueAsString(p) + "},\"index\":0}]}\n\n").getBytes(StandardCharsets.UTF_8)); os.flush();
                        try { Thread.sleep(gapMs); } catch (InterruptedException ignored) { }
                    }
                    os.write("data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
                return;
            }
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + M.writeValueAsString(String.join("", pieces)) + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, out.length);
            try (OutputStream os = x.getResponseBody()) { os.write(out); }
        });
        h.start();
        return h;
    }

    static HttpResponse<String> post(HttpClient c, String url, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theAnswerArrivesInPiecesAndAStopKeepsWhatCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        AtomicInteger streams = new AtomicInteger();
        HttpServer fake = model(new String[]{"The ", "saros ", "is ", "223 ", "synodic ", "months."}, 250, streams);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + fake.getAddress().getPort(), "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            s.append(M.createObjectNode().put("role", "user").put("content", "hello"));   // a conversation exists once something is said in it
            CompletableFuture<HttpResponse<String>> turn = CompletableFuture.supplyAsync(() -> { try { return post(c, base + "/chat", "page=1&session=" + s.id + "&say=what+is+the+saros"); } catch (Exception e) { throw new RuntimeException(e); } });
            String partial = "";
            for (int i = 0; i < 60 && !turn.isDone(); i++) {
                Thread.sleep(100);
                String live = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/live?session=" + s.id)).build(), HttpResponse.BodyHandlers.ofString()).body();
                String text = M.readTree(live).path("text").asText("");
                if (!text.isEmpty() && text.length() < "The saros is 223 synodic months.".length()) partial = text;
            }
            HttpResponse<String> view = turn.get();
            assertEquals(200, view.statusCode(), view.body());
            assertTrue(streams.get() >= 1, "the drive was asked to stream");
            assertFalse(partial.isEmpty(), "a piece of the answer was on the page before the whole: " + partial);
            assertTrue(view.body().contains("The saros is 223 synodic months."), "the whole answer in place when the turn ends: " + view.body());
            String after = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/live?session=" + s.id)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(M.readTree(after).path("done").asBoolean(), after);
            // the page: a caret while it is written, a stop beside it, polled by the script
            String page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(Pages.CHAT_PAGE_JS.contains("/chat/live?session=") && Pages.CHAT_PAGE_JS.contains("className='caret'") && Pages.CHAT_PAGE_JS.contains("op=stop") && page.contains(".caret{"), Pages.CHAT_PAGE_JS);
        } finally { d.stop(); fake.stop(0); }
        // a long answer, stopped: the turn ends within seconds, what came is kept, and the conversation says so
        String[] many = new String[40]; for (int i = 0; i < many.length; i++) many[i] = "word" + i + " ";
        AtomicInteger streams2 = new AtomicInteger();
        HttpServer slow = model(many, 200, streams2);
        LibrarianDaemon d2 = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + slow.getAddress().getPort(), "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d2.port();
            Librarian.Session s = Librarian.Session.open(store);
            s.append(M.createObjectNode().put("role", "user").put("content", "hello"));
            long t0 = System.currentTimeMillis();
            CompletableFuture<HttpResponse<String>> turn = CompletableFuture.supplyAsync(() -> { try { return post(c, base + "/chat", "page=1&session=" + s.id + "&say=tell+me+everything"); } catch (Exception e) { throw new RuntimeException(e); } });
            for (int i = 0; i < 50; i++) { Thread.sleep(100); if (M.readTree(c.send(HttpRequest.newBuilder(URI.create(base + "/chat/live?session=" + s.id)).build(), HttpResponse.BodyHandlers.ofString()).body()).path("text").asText("").length() > 10) break; }
            HttpResponse<String> stop = post(c, base + "/chat", "op=stop&session=" + s.id);
            assertEquals(200, stop.statusCode(), stop.body());
            HttpResponse<String> view = turn.get();
            long took = System.currentTimeMillis() - t0;
            assertTrue(took < 7_000, "the turn ended within seconds of the stop, not after the whole answer: " + took + " ms");
            assertTrue(view.body().contains("Stopped by you") && view.body().contains("word0 "), "what came is kept and the conversation says it was stopped: " + view.body());
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var last = again.messages().get(again.messages().size() - 1);
            assertEquals("assistant", last.path("role").asText()); assertTrue(last.path("content").asText().contains("(Stopped by you.)"), last.toString());
        } finally { d2.stop(); slow.stop(0); }
    }
}
