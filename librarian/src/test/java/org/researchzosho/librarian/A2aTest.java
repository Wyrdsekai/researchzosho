package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.HangingServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library as an agent for other agents (A2A 1.0 over JSON-RPC, 0.6.6): the card and the calls answer only while the library is
 * shared; an ask is answered at once as a message; a research run is a task followed from SUBMITTED through WORKING to its
 * investigation, or CANCELED; the library's own tokens decide what a caller may do, and a refused token is said by the HTTP status.
 */
class A2aTest {

    static final ObjectMapper M = new ObjectMapper();
    String home;

    @AfterEach void restore() { if (home != null) { System.setProperty("user.home", home); Config.invalidate(); } }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> rpc(HttpClient c, String url, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String send(String text, String skill) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"m-in\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":" + quote(text) + "}]"
                + (skill == null ? "" : ",\"metadata\":{\"skill\":\"" + skill + "\"}") + "}}}";
    }

    static String call(String method, String id) { return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"" + method + "\",\"params\":{\"id\":\"" + id + "\"}}"; }

    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static List<String> ids(JsonNode array) { List<String> out = new ArrayList<>(); for (JsonNode n : array) out.add(n.path("id").asText()); return out; }

    /** A finished run as the ledger keeps it, with the investigation it wrote. */
    static void ran(LibraryStore store, String id, String question, String investigation) throws Exception {
        Path dir = store.root().resolve("catalog").resolve("jobs");
        Files.createDirectories(dir);
        ObjectNode j = M.createObjectNode();
        j.put("job_id", id); j.put("kind", "research"); j.put("patron", "");
        j.putObject("args").put("question", question);
        j.put("state", "done"); j.put("queued_at", "2026-10-20T10:00:00Z"); j.put("started_at", "2026-10-20T10:00:05Z"); j.put("ended_at", "2026-10-20T10:30:00Z");
        j.put("result", "investigation " + investigation); j.put("restarted", 0);
        Files.writeString(dir.resolve(id + ".json"), j.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void theCardAndTheCallsAnswerOnlyWhileTheLibraryIsShared(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        Files.createDirectories(tmp.resolve(".researchzosho"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init(); store.setName("The Endo papers");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.read);
        Patrons.set(store, "did:key:reader", "a cousin", Patrons.Level.read);
        String reader = Patrons.issueToken(store, "did:key:reader");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            var closed = get(c, base + "/.well-known/agent-card.json");
            assertEquals(404, closed.statusCode()); assertTrue(closed.body().contains("not shared"), closed.body());
            assertEquals(404, rpc(c, base + "/a2a", null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ListTasks\",\"params\":{}}").statusCode(), "no calls either");
            d.openDoor(0);
            String door = "http://127.0.0.1:" + d.doorPort();
            var card = get(c, door + "/.well-known/agent-card.json");
            assertEquals(200, card.statusCode(), card.body());
            JsonNode k = M.readTree(card.body());
            assertEquals("The Endo papers", k.path("name").asText());
            for (String f : List.of("name", "description", "version", "supportedInterfaces", "capabilities", "defaultInputModes", "defaultOutputModes", "skills")) assertTrue(k.hasNonNull(f), f);
            JsonNode i = k.path("supportedInterfaces").get(0);
            assertEquals(door + "/a2a", i.path("url").asText()); assertEquals("JSONRPC", i.path("protocolBinding").asText()); assertEquals("1.0", i.path("protocolVersion").asText());
            assertEquals("Bearer", k.path("securitySchemes").path("bearer").path("httpAuthSecurityScheme").path("scheme").asText());
            assertEquals(List.of("ask", "research"), ids(k.path("skills")));
            assertFalse(k.path("capabilities").path("streaming").asBoolean(true)); assertFalse(k.path("capabilities").path("pushNotifications").asBoolean(true));
            assertEquals(200, get(c, base + "/.well-known/agent-card.json").statusCode(), "read at the library's own address too, once the door is open");
            // the address the relay reported is the address on the card
            Files.writeString(tmp.resolve(".researchzosho/config"), "share.address = https://endo.example.net/\n"); Config.invalidate();
            k = M.readTree(get(c, door + "/.well-known/agent-card.json").body());
            assertEquals("https://endo.example.net/a2a", k.path("supportedInterfaces").get(0).path("url").asText());
            Files.writeString(tmp.resolve(".researchzosho/config"), ""); Config.invalidate();
            // an ask: answered at once as a message. Through the shared door only a listed reader gets in, as everywhere; at the
            // library's own address whoever it lets read may ask
            var shut = rpc(c, door + "/a2a", null, send("Where did Genzaburo Endo live?", null));
            assertEquals(403, shut.statusCode(), shut.body());
            assertEquals(-32603, M.readTree(shut.body()).path("error").path("code").asInt());
            assertEquals(200, rpc(c, base + "/a2a", null, send("Where did Genzaburo Endo live?", null)).statusCode(), "the library's own address keeps its default");
            var ask = rpc(c, door + "/a2a", reader, send("Where did Genzaburo Endo live?", null));
            assertEquals(200, ask.statusCode(), ask.body());
            JsonNode r = M.readTree(ask.body());
            assertEquals(1, r.path("id").asInt()); assertTrue(r.path("error").isMissingNode(), ask.body());
            JsonNode m = r.path("result").path("message");
            assertEquals("ROLE_AGENT", m.path("role").asText());
            assertFalse(m.path("parts").get(0).path("text").asText().isBlank());
            assertTrue(m.path("metadata").path("holds_nothing").asBoolean(), "an empty library says so");
            assertTrue(m.hasNonNull("messageId") && m.hasNonNull("contextId"));
            // the refusals, in A2A's words
            JsonNode e = M.readTree(rpc(c, door + "/a2a", reader, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"Nothing\",\"params\":{}}").body()).path("error");
            assertEquals(-32601, e.path("code").asInt());
            e = M.readTree(rpc(c, door + "/a2a", reader, call("GetTask", "J-9999")).body()).path("error");
            assertEquals(-32001, e.path("code").asInt()); assertEquals("TASK_NOT_FOUND", e.path("data").get(0).path("reason").asText());
            e = M.readTree(rpc(c, door + "/a2a", reader, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"SendStreamingMessage\",\"params\":{}}").body()).path("error");
            assertEquals(-32004, e.path("code").asInt());
            e = M.readTree(rpc(c, door + "/a2a", reader, send("x", "dance")).body()).path("error");
            assertEquals(-32602, e.path("code").asInt());
            assertEquals(-32700, M.readTree(rpc(c, door + "/a2a", reader, "{not json").body()).path("error").path("code").asInt());
            // research needs a writer's token: a reader's, none, or one the library does not know: the status says so
            var noToken = rpc(c, door + "/a2a", reader, send("Research where Genzaburo Endo lived.", "research"));
            assertEquals(403, noToken.statusCode(), noToken.body());
            assertEquals(-32603, M.readTree(noToken.body()).path("error").path("code").asInt());
            assertEquals(403, rpc(c, door + "/a2a", "not-a-token", send("Research where Genzaburo Endo lived.", "research")).statusCode());
            assertEquals(403, rpc(c, base + "/a2a", null, send("Research where Genzaburo Endo lived.", "research")).statusCode());
            d.closeDoor();
            assertEquals(404, get(c, base + "/.well-known/agent-card.json").statusCode(), "closed again: no card");
        } finally { d.stop(); }
    }

    @Test
    void aResearchRunIsATaskFollowedToItsEnd(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.read);
        Patrons.set(store, "did:key:agent", "the other agent", Patrons.Level.write);
        String token = Patrons.issueToken(store, "did:key:agent");
        store.write(new Investigation("I-0001-where-did-genzaburo-endo-live", "Where did Genzaburo Endo live?", Finding.State.draft, "model:test",
                Instant.now().toString(), List.of(), List.of(), "## Answer\n\nIn Sendai, by the family's account.\n"));
        ran(store, "J-0900", "Where did Genzaburo Endo live?", "I-0001-where-did-genzaburo-endo-live");
        // the probe that asks whether a model answers gets its answer; every request of the run itself hangs
        try (HangingServer model = new HangingServer(HangingServer.Mode.SILENT, r -> r.body().contains("\"max_tokens\":1,") || r.body().contains("\"max_tokens\":1}"), RunStopTest.PROBE_ANSWER)) {
            LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, model.url(), "placeholder-model", -1);
            d.researcherFactory = x -> new Researcher(Researcher.drive(x, "placeholder-model"), new ResearcherTest.FakeTools(), null, 1);
            d.openDoor(0);
            HttpClient c = HttpClient.newHttpClient();
            String door = "http://127.0.0.1:" + d.doorPort() + "/a2a";
            try {
                // the finished run: COMPLETED, its investigation the artifact, the question its history
                JsonNode t = M.readTree(rpc(c, door, token, call("GetTask", "J-0900")).body()).path("result");
                assertEquals("TASK_STATE_COMPLETED", t.path("status").path("state").asText(), t.toString());
                assertEquals("I-0001-where-did-genzaburo-endo-live", t.path("artifacts").get(0).path("artifactId").asText());
                assertEquals("Where did Genzaburo Endo live?", t.path("artifacts").get(0).path("name").asText());
                assertTrue(t.path("artifacts").get(0).path("parts").get(0).path("text").asText().contains("In Sendai"));
                assertEquals("Where did Genzaburo Endo live?", t.path("history").get(0).path("parts").get(0).path("text").asText());
                assertEquals("ctx-J-0900", t.path("contextId").asText());
                // a new run: SUBMITTED when filed, WORKING once its model call is out
                JsonNode sent = M.readTree(rpc(c, door, token, send("How were the placeholder gears cut?", "research")).body());
                assertTrue(sent.path("error").isMissingNode(), sent.toString());
                JsonNode task = sent.path("result").path("task");
                String id = task.path("id").asText();
                assertTrue(id.startsWith("J-"), id);
                assertEquals("TASK_STATE_SUBMITTED", task.path("status").path("state").asText());
                assertEquals("ctx-" + id, task.path("contextId").asText());
                assertEquals("How were the placeholder gears cut?", task.path("history").get(0).path("parts").get(0).path("text").asText());
                assertTrue(model.awaitHung(30), "the run sent its first request to the model");
                t = M.readTree(rpc(c, door, token, call("GetTask", id)).body()).path("result");
                assertEquals("TASK_STATE_WORKING", t.path("status").path("state").asText(), t.toString());
                // the list: both, and narrowed by state
                JsonNode l = M.readTree(rpc(c, door, token, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ListTasks\",\"params\":{}}").body()).path("result");
                assertEquals(Set.of("J-0900", id), new HashSet<>(ids(l.path("tasks"))));
                l = M.readTree(rpc(c, door, token, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ListTasks\",\"params\":{\"status\":\"TASK_STATE_WORKING\"}}").body()).path("result");
                assertEquals(List.of(id), ids(l.path("tasks")));
                // a stop: a reader may not, a writer may, and the task comes back CANCELED within seconds
                assertEquals(403, rpc(c, door, null, call("CancelTask", id)).statusCode());
                long asked = System.currentTimeMillis();
                t = M.readTree(rpc(c, door, token, call("CancelTask", id)).body()).path("result");
                assertEquals("TASK_STATE_CANCELED", t.path("status").path("state").asText(), t.toString());
                assertTrue(System.currentTimeMillis() - asked < 10_000);
                assertTrue(t.path("status").path("message").path("parts").get(0).path("text").asText().startsWith("stopped by"), t.toString());
                assertTrue(model.awaitClosed(5), "the connection to the model server was closed");
                // a finished run cannot be stopped
                JsonNode e = M.readTree(rpc(c, door, token, call("CancelTask", "J-0900")).body()).path("error");
                assertEquals(-32002, e.path("code").asInt()); assertEquals("TASK_NOT_CANCELABLE", e.path("data").get(0).path("reason").asText());
            } finally { d.stop(); }
        }
    }
}
