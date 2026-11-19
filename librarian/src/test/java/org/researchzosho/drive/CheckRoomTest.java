package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page check gets an answer from a model that reasons before it answers (the live check of 2026-11-18, F3 item 3: gpt-oss spent the
 * one-word question's four tokens reasoning, 72 of 99 checks went unanswered and 11 pages were left out). The question is asked a second
 * time with room only when the first answer ran out of room, never after a time out, and inside a request both asks share its one limit
 * (the suite's CheckCostTest: a check whose model does not answer waits the request's limit once).
 */
class CheckRoomTest {
    static final ObjectMapper M = new ObjectMapper();
    HttpServer server;
    final List<Integer> asked = new CopyOnWriteArrayList<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    /** A model server: what it answers each ask with, by the ask's max_tokens; it may wait first. */
    String serve(Function<Integer, String> answer, Function<Integer, Long> waitMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", x -> {
            if (!x.getRequestURI().getPath().endsWith("/chat/completions")) { x.sendResponseHeaders(404, -1); x.close(); return; }
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            int max = body.path("max_tokens").asInt();
            asked.add(max);
            try { Thread.sleep(waitMs.apply(max)); } catch (InterruptedException e) { return; }
            byte[] b = answer.apply(max).getBytes(StandardCharsets.UTF_8);
            try { x.getResponseHeaders().add("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); } catch (Exception gone) { /* the caller left */ }
            x.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String thought() { return "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"Let me consider whether the page describes\"},\"finish_reason\":\"length\"}]}"; }
    static String said(String word) { return "{\"choices\":[{\"message\":{\"content\":\"" + word + "\"},\"finish_reason\":\"stop\"}]}"; }
    static ArrayNode question() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "QUESTION: Is it about ferries? Answer with one word: yes or no."); return m; }

    @Test
    void aModelThatReasonsFirstIsAskedAgainWithRoomAndJudged() throws Exception {
        String url = serve(max -> max < DriveClient.THINKING_ROOM ? thought() : said("yes"), max -> 0L);
        DriveClient d = new DriveClient(url, "gpt-oss").decisionTimeout(Duration.ofSeconds(10));
        assertEquals(ContentJudge.Verdict.YES, new ContentJudge(null, m -> d.classify(m, 4)).ask("A page about ferries.", "Is it about ferries?").verdict());
        assertEquals(List.of(4, DriveClient.THINKING_ROOM), asked, "four tokens, then room");
    }

    @Test
    void aSlowModelIsWaitedForOnceAndTheTwoAsksShareOneLimit() throws Exception {
        // no answer within the limit: asked once, unjudged, never a no
        String url = serve(max -> said("no"), max -> 3_000L);
        DriveClient slow = new DriveClient(url, "m").decisionTimeout(Duration.ofSeconds(1));
        long t0 = System.currentTimeMillis();
        assertEquals(ContentJudge.Verdict.UNJUDGED, new ContentJudge(null, m -> slow.classify(m, 4)).ask("A page.", "Is it about ferries?").verdict());
        assertTrue(System.currentTimeMillis() - t0 < 1_900, "one limit: " + (System.currentTimeMillis() - t0) + " ms");
        assertEquals(List.of(4), asked, "never asked again after a time out");
        // a model that thought for most of the limit: the second ask has what is left of it, not a limit of its own
        stop(); asked.clear();
        String url2 = serve(max -> max < DriveClient.THINKING_ROOM ? thought() : said("yes"), max -> max < DriveClient.THINKING_ROOM ? 800L : 2_000L);
        DriveClient thinker = new DriveClient(url2, "m").decisionTimeout(Duration.ofSeconds(1));
        long t1 = System.currentTimeMillis();
        String s = thinker.classify(question(), 4);
        long took = System.currentTimeMillis() - t1;
        assertTrue(took < 1_450, "both asks within the one limit (a second limit of its own would take 1,800 ms): " + took + " ms, answered \"" + s + "\"");
        assertEquals(List.of(4, DriveClient.THINKING_ROOM), asked);
        // and such a model is asked to reason briefly, beside thinking off
        ObjectNode body = M.createObjectNode();
        DriveClient.decisionSettings(body);
        assertEquals("low", body.path("chat_template_kwargs").path("reasoning_effort").asText(), body.toString());
    }
}
