package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research worker keeps to one llama.cpp slot, and a quick decision takes the spare slot when there is one (2026-10-15: 39 % of a
 * local run's prompt tokens were processed again because workers and decisions hopped between slots). A server that is not llama.cpp,
 * or has one slot, is sent as before.
 */
class DriveSlotPinTest {

    static final ObjectMapper M = new ObjectMapper();

    /** A llama.cpp server with {@code slots} slots, or one that says nothing of itself when {@code slots} is null; it keeps the bodies it was sent. */
    static HttpServer fake(Integer slots, List<JsonNode> bodies) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out; int code = 200;
            if (path.equals("/props") && slots != null) out = ("{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":" + slots + "}").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/v1/chat/completions")) {
                bodies.add(M.readTree(ex.getRequestBody().readAllBytes()));
                out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"yes\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/models")) out = "{\"data\":[{\"id\":\"m\"}]}".getBytes(StandardCharsets.UTF_8);
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        return s;
    }

    static ArrayNode hello() { ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hello"); return msgs; }

    @Test
    void noRequestEverNamesASlot() throws Exception {
        // a llama.cpp server with four slots, with one, and one that says nothing of itself: a chat turn and a quick decision name no slot,
        // since two conversations sent to one named slot were answered with each other's replies (DriveClient says how it was measured)
        for (Integer slots : new Integer[]{4, 1, null}) {
            List<JsonNode> bodies = new CopyOnWriteArrayList<>();
            HttpServer s = fake(slots, bodies);
            try {
                DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
                c.contextWindow();
                c.chat(hello(), null, 8, "auto"); c.classify(hello(), 8);
                assertEquals(2, bodies.size());
                for (JsonNode b : bodies) assertFalse(b.has("id_slot"), "slots=" + slots + ": " + b);
            } finally { s.stop(0); }
        }
    }

    static ArrayNode picture() {
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "What words are on this frame?");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + "A".repeat(600_000));
        return msgs;
    }

    @Test
    void aPictureTakesNoCachedPromptAndCountsAsAPictureForItsTime() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer s = fake(4, bodies);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            c.contextWindow();
            c.classify(picture(), 8);
            JsonNode b = bodies.get(bodies.size() - 1);
            assertFalse(b.has("id_slot"), "a picture names no slot: " + b.path("id_slot"));
            assertFalse(b.path("cache_prompt").asBoolean(true), "and llama.cpp does not reuse a cached prompt for it");
            // a picture read is as cold as a request can be made: temperature 0, and a seed named, the same every time (two machines got two readings of one balloon's picture, 2026-11-14)
            assertEquals(0.0, b.path("temperature").asDouble(-1)); assertTrue(b.has("seed"), "a picture's reading names a seed: " + b); assertEquals(DriveClient.PICTURE_SEED, b.path("seed").asLong(-1));
            c.classify(hello(), 8);
            assertFalse(bodies.get(bodies.size() - 1).has("id_slot"), "words alone name none either");
            assertFalse(bodies.get(bodies.size() - 1).has("cache_prompt"), "and keep the server's own caching");
            assertFalse(bodies.get(bodies.size() - 1).has("seed"), "and no seed");
        } finally { s.stop(0); }
        // the time limit counts a picture as about 1,500 tokens, never as its base64
        ObjectNode body = M.createObjectNode(); body.set("messages", picture());
        int chars = DriveClient.promptChars(body);
        assertTrue(chars < 6_000 && chars >= DriveClient.PICTURE_CHARS, "a 600,000-character picture counts as " + chars);
        assertTrue(DriveClient.carriesPicture(picture()) && !DriveClient.carriesPicture(hello()));
        ObjectNode words = M.createObjectNode(); words.set("messages", hello());
        assertEquals(words.toString().length(), DriveClient.promptChars(words), "words count as they are");
    }

    @Test
    void aServerThatIsNotLlamaCppIsSentNoLlamaCppField() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer s = fake(null, bodies);   // a server that says nothing of itself: not llama.cpp
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            c.classify(picture(), 8);
            assertFalse(bodies.get(bodies.size() - 1).has("cache_prompt"), "only llama.cpp is told not to reuse its cache");
            assertFalse(bodies.get(bodies.size() - 1).has("seed"), "and only llama.cpp is named a seed: another server may refuse a field it does not know");
        } finally { s.stop(0); }
    }
}

