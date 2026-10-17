package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    void aWorkerKeepsToItsSlotAndAQuickDecisionTakesTheSpareOne() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer s = fake(4, bodies);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            c.contextWindow();   // a set window (the test environment's) skips the probe; the pin asks on its own
            c.chat(hello(), null, 8, "auto");
            assertFalse(bodies.get(0).has("id_slot"), "no worker pinned: the server picks");
            DriveClient.pinSlot(1, 3);
            try {
                c.chat(hello(), null, 8, "auto");
                assertEquals(1, bodies.get(1).path("id_slot").asInt(), "worker 1 → slot 1");
                c.classify(hello(), 8);
                assertEquals(3, bodies.get(2).path("id_slot").asInt(), "a quick decision → the spare slot past the three workers");
            } finally { DriveClient.unpinSlot(); }
            DriveClient.pinSlot(5, 3);
            try { c.chat(hello(), null, 8, "auto"); assertEquals(1, bodies.get(3).path("id_slot").asInt(), "worker 5 on a four-slot server → slot 1 (5 mod 4)"); }
            finally { DriveClient.unpinSlot(); }
            DriveClient.pinSlot(0, 4);
            try { c.classify(hello(), 8); assertFalse(bodies.get(4).has("id_slot"), "four workers on four slots: no spare, the server picks for a decision"); }
            finally { DriveClient.unpinSlot(); }
            c.chat(hello(), null, 8, "auto");
            assertFalse(bodies.get(5).has("id_slot"), "unpinned again");
        } finally { s.stop(0); }
    }

    @Test
    void aOneSlotServerAndAServerThatSaysNothingAreSentAsBefore() throws Exception {
        for (Integer slots : new Integer[]{1, null}) {
            List<JsonNode> bodies = new CopyOnWriteArrayList<>();
            HttpServer s = fake(slots, bodies);
            try {
                DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
                DriveClient.pinSlot(2, 3);
                try { c.chat(hello(), null, 8, "auto"); c.classify(hello(), 8); } finally { DriveClient.unpinSlot(); }
                for (JsonNode b : bodies) assertFalse(b.has("id_slot"), "slots=" + slots + ": " + b);
            } finally { s.stop(0); }
        }
    }
}
