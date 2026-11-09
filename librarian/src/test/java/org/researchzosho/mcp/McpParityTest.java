package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Crews;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.ProtocolError;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every feature is reachable over MCP: the tools the chat and the pages already had that the server never listed (the sources, the
 * videos, the home and career searches, the housekeeping, the spend, the podcasts followed, a repository as a starting point), the ops the
 * protocol handled that the schemas left out (settings check; a job's choose, allow and decline; a model entry's test; Markdown as an
 * export format), and the keeper's side of access. Each is a thin tool over the protocol, exercised here on a library in a temporary
 * home with stand-ins for the feed and the model server: no network, no model.
 */
class McpParityTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String REAL_HOME = System.getProperty("user.home");
    private Path home;

    @BeforeEach void point(@TempDir Path tmp) throws Exception {
        home = tmp;
        System.setProperty("user.home", tmp.toString());
        new LibraryStore(tmp.resolve("researchzosho-library")).init();
        Patrons.set(LibraryStore.open(), "did:key:zWriter", "A writer", Patrons.Level.write);
    }
    @AfterEach void restore() { System.setProperty("user.home", REAL_HOME); }

    private static Map<String, JsonNode> tools() {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode t : McpServer.allTools()) out.put(t.get("name").asText(), t);
        return out;
    }

    private static List<String> enumOf(JsonNode tool, String prop) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : tool.path("inputSchema").path("properties").path(prop).path("enum")) out.add(v.asText());
        return out;
    }

    /** A call as a writing patron (the keeper cannot be asserted over stdio); the structured result, or the RpcError. */
    private static JsonNode call(String name, String argsJson) throws Exception {
        ObjectNode args = (ObjectNode) M.readTree(argsJson);
        args.putObject("patron").put("did", "did:key:zWriter").put("name", "A writer").put("runtime", "test");
        ObjectNode params = M.createObjectNode(); params.put("name", name); params.set("arguments", args);
        return McpServer.handle("tools/call", params).get("structuredContent");
    }

    private static String errorOf(String name, String argsJson) throws Exception {
        ObjectNode args = (ObjectNode) M.readTree(argsJson);
        args.putObject("patron").put("did", "did:key:zWriter").put("name", "A writer").put("runtime", "test");
        ObjectNode req = M.createObjectNode(); req.put("jsonrpc", "2.0"); req.put("id", 1); req.put("method", "tools/call");
        ObjectNode params = req.putObject("params"); params.put("name", name); params.set("arguments", args);
        JsonNode env = McpServer.envelopeFor(req);
        assertTrue(env.has("error"), "an error was expected: " + env);
        return env.get("error").get("data").get("code").asText() + ": " + env.get("error").get("message").asText();
    }

    @Test void theToolsTheOtherSurfacesHadAreListedAndTheWidenedSchemasCarryTheirOps() {
        Map<String, JsonNode> tools = tools();
        for (String n : List.of("library_sources", "library_video", "library_home", "library_career", "library_crews", "library_spend", "library_follow", "library_repo")) {
            assertTrue(tools.containsKey(n), n + " is listed");
            assertTrue(tools.get(n).path("inputSchema").path("properties").has("patron"), n + " takes a patron");
        }
        assertTrue(enumOf(tools.get("library_settings"), "op").contains("check"), "settings: check");
        List<String> job = enumOf(tools.get("library_job"), "op");
        assertTrue(job.containsAll(List.of("stop", "pause", "resume", "choose", "allow", "decline")), "job: " + job);
        assertTrue(tools.get("library_job").path("inputSchema").path("properties").has("choice"), "job: choice");
        assertTrue(enumOf(tools.get("library_models"), "op").contains("test"), "models: test");
        assertTrue(enumOf(tools.get("library_export"), "format").contains("md"), "export: md");
        List<String> access = enumOf(tools.get("library_access"), "op");
        assertTrue(access.containsAll(List.of("collect", "readers", "allow", "remove", "invite", "requests", "approve", "deny", "keys", "key", "revoke")), "access: " + access);
        assertEquals(0, tools.get("library_access").path("inputSchema").path("required").size(), "access: request_id and claim are needed by collect alone, so nothing is required of every op");
        for (String n : List.of("library_video", "library_home", "library_career", "library_crews", "library_follow", "library_sources")) assertFalse(enumOf(tools.get(n), "op").isEmpty(), n + " declares its ops as an enum");
        assertEquals(List.of("survey", "pick", "do"), enumOf(tools.get("library_repo"), "op"));
    }

    @Test void theThinToolsAnswerFromTheProtocol() throws Exception {
        JsonNode sources = call("library_sources", "{\"op\":\"list\"}");
        assertTrue(sources.path("sources").isArray(), sources.toString());
        assertTrue(sources.has("text"));
        JsonNode home = call("library_home", "{\"op\":\"list\"}");
        assertTrue(home.path("searches").isArray() && home.path("summary").asText().contains("No home search"), home.toString());
        JsonNode career = call("library_career", "{\"op\":\"list\"}");
        assertTrue(career.path("seekers").isArray() && career.path("summary").asText().contains("Nobody"), career.toString());
        JsonNode crews = call("library_crews", "{}");
        assertTrue(crews.path("text").asText().startsWith("The housekeeping runs at"), crews.toString());
        assertTrue(crews.path("runs").isArray() && crews.has("budget") && crews.has("date"), crews.toString());
        JsonNode spend = call("library_spend", "{}");
        assertTrue(spend.path("month").asText().matches("\\d{4}-\\d{2}"), spend.toString());
        assertTrue(spend.path("hosts").isArray() && spend.path("text").asText().contains("not a bill"), spend.toString());
        JsonNode follow = call("library_follow", "{}");
        assertEquals(0, follow.path("count").asInt(-1), follow.toString());
        assertEquals("No show is followed yet.", follow.path("summary").asText());
        // the video tool reaches the protocol's video call: its moment search wants the words (the status op would probe the model that reads pictures)
        String saw = errorOf("library_video", "{\"op\":\"saw\"}");
        assertTrue(saw.startsWith("invalid_args") && saw.contains("query"), saw);
        String repo = errorOf("library_repo", "{}");
        assertTrue(repo.startsWith("invalid_args"), repo);
        // every result carries the provenance every tool's does
        for (JsonNode r : List.of(sources, home, career, crews, spend, follow)) assertEquals(LibraryProtocol.CONTRACT, r.path("contract").asText(), r.toString());
    }

    @Test void aPodcastIsFollowedAndUnfollowedThroughTheTool() throws Exception {
        var before = LibraryProtocol.PODCAST_TITLE;
        LibraryProtocol.PODCAST_TITLE = feed -> feed.contains("example.org") ? "The Example Show" : null;
        try {
            JsonNode f = call("library_follow", "{\"op\":\"follow\",\"feed\":\"https://example.org/feed.xml\",\"every\":2}");
            assertEquals("the-example-show", f.path("name").asText(), f.toString());
            assertEquals(2, f.path("every_days").asInt());
            assertFalse(f.path("already").asBoolean(true));
            assertTrue(f.path("summary").asText().startsWith("Following The Example Show as the-example-show"), f.toString());
            JsonNode list = call("library_follow", "{\"op\":\"list\"}");
            assertEquals(1, list.path("count").asInt());
            assertEquals("https://example.org/feed.xml", list.path("shows").get(0).path("feed").asText());
            JsonNode again = call("library_follow", "{\"op\":\"follow\",\"feed\":\"https://example.org/feed.xml\"}");
            assertTrue(again.path("already").asBoolean(false), again.toString());
            String bad = errorOf("library_follow", "{\"op\":\"follow\",\"feed\":\"https://nowhere.test/x\"}");
            assertTrue(bad.contains("did not answer or is not a podcast feed"), bad);
            JsonNode off = call("library_follow", "{\"op\":\"unfollow\",\"which\":\"the-example-show\"}");
            assertTrue(off.path("removed").asBoolean(false), off.toString());
            assertEquals(0, call("library_follow", "{}").path("count").asInt());
            String none = errorOf("library_follow", "{\"op\":\"unfollow\",\"which\":\"the-example-show\"}");
            assertTrue(none.startsWith("invalid_args") && none.contains("No followed podcast"), none);
        } finally { LibraryProtocol.PODCAST_TITLE = before; }
    }

    @Test void aModelEntryIsTestedThroughTheTool() throws Exception {
        var before = LibraryProtocol.MODEL_PROBE;
        try {
            JsonNode added = call("library_models", "{\"op\":\"add\",\"name\":\"stand-in\",\"drive\":\"http://127.0.0.1:1\",\"model\":\"nothing\"}");
            assertEquals("stand-in", added.path("name").asText(), added.toString());
            LibraryProtocol.MODEL_PROBE = e -> Crews.DriveState.DOWN;
            JsonNode down = call("library_models", "{\"op\":\"test\",\"name\":\"stand-in\"}");
            assertFalse(down.path("answers").asBoolean(true), down.toString());
            assertEquals("down", down.path("state").asText());
            assertTrue(down.path("note").asText().contains("does not answer"), down.toString());
            LibraryProtocol.MODEL_PROBE = e -> Crews.DriveState.ANSWERS;
            JsonNode up = call("library_models", "{\"op\":\"test\",\"name\":\"stand-in\"}");
            assertTrue(up.path("answers").asBoolean(false), up.toString());
            assertEquals("answers", up.path("state").asText());
            String unknown = errorOf("library_models", "{\"op\":\"test\",\"name\":\"no-such-entry\"}");
            assertTrue(unknown.startsWith("invalid_args") && unknown.contains("No entry is named"), unknown);
        } finally { LibraryProtocol.MODEL_PROBE = before; }
    }

    @Test void theKeepersSideOfAccessIsTheKeepersAlone() throws Exception {
        // a patron who may write, but is not the keeper: refused with one sentence; the request side stays open to everyone
        String refused = errorOf("library_access", "{\"op\":\"readers\"}");
        assertTrue(refused.startsWith("forbidden: Only the person who keeps this library"), refused);
        assertTrue(errorOf("library_access", "{\"op\":\"collect\"}").contains("request_id"), "collect still wants the request's id");
        // the keeper, in process (the chat, the terminal; over HTTP a key or a session proves them)
        LibraryProtocol p = new LibraryProtocol(LibraryStore.open());
        ObjectNode readers = p.access(keeper("{\"op\":\"readers\"}"));
        assertEquals("write", readers.path("default").asText(), readers.toString());
        assertEquals(1, readers.path("readers").size(), readers.toString());
        ObjectNode invite = p.access(keeper("{\"op\":\"invite\",\"name\":\"A cousin\",\"level\":\"read\"}"));
        assertTrue(invite.path("did").asText().startsWith("did:key:person-"), invite.toString());
        assertTrue(invite.path("link").asText().contains("/join?code="), invite.toString());
        assertEquals(2, p.access(keeper("{\"op\":\"readers\"}")).path("readers").size());
        ObjectNode allowed = p.access(keeper("{\"op\":\"allow\",\"did\":\"did:key:zWriter\",\"level\":\"read\",\"name\":\"A writer, now reading\"}"));
        assertEquals("read", allowed.path("level").asText(), allowed.toString());
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.access(keeper("{\"op\":\"allow\",\"did\":\"did:key:zX\",\"level\":\"owner\"}"))).code);
        // somebody asks in; the keeper sees the request, approves it, and the requester's collect works as before
        ObjectNode asked = p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zStranger\",\"name\":\"A stranger\",\"note\":\"the shelf on bridges\"}"));
        ObjectNode requests = p.access(keeper("{\"op\":\"requests\"}"));
        assertEquals(1, requests.path("pending").asInt(), requests.toString());
        ObjectNode approved = p.access(keeper("{\"op\":\"approve\",\"request_id\":\"" + asked.path("request_id").asText() + "\",\"level\":\"read\"}"));
        assertEquals("approved", approved.path("state").asText(), approved.toString());
        ObjectNode collected = p.access((ObjectNode) M.readTree("{\"request_id\":\"" + asked.path("request_id").asText() + "\",\"claim\":\"" + asked.path("claim").asText() + "\"}"));
        assertEquals("approved", collected.path("state").asText()); assertTrue(collected.has("token"), collected.toString());
        ObjectNode denied = p.access(keeper("{\"op\":\"deny\",\"did\":\"did:key:zStranger\"}"));
        assertEquals("deny", denied.path("level").asText(), denied.toString());
        // keys for programs: made once in full, listed by prefix, revoked by id
        ObjectNode key = p.access(keeper("{\"op\":\"key\",\"label\":\"the notes script\",\"level\":\"write\"}"));
        assertTrue(key.path("key").asText().startsWith("rz_"), key.toString());
        ObjectNode keys = p.access(keeper("{\"op\":\"keys\"}"));
        assertEquals(1, keys.path("keys").size()); assertFalse(keys.toString().contains(key.path("key").asText()), "the secret is never listed");
        ObjectNode revoked = p.access(keeper("{\"op\":\"revoke\",\"key_id\":\"" + key.path("id").asText() + "\"}"));
        assertTrue(revoked.path("revoked").asBoolean(false), revoked.toString());
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.access(keeper("{\"op\":\"revoke\",\"key_id\":\"K-nothing\"}"))).code);
        ObjectNode removed = p.access(keeper("{\"op\":\"remove\",\"did\":\"did:key:zStranger\"}"));
        assertTrue(removed.path("removed").asBoolean(false));
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.access(keeper("{\"op\":\"no-such-op\"}"))).code);
    }

    private static ObjectNode keeper(String json) throws Exception {
        ObjectNode a = (ObjectNode) M.readTree(json);
        a.putObject("patron").put("did", "person").put("name", "the person").put("runtime", "local");
        return a;
    }

    @Test void theChatCanNowSeeTheSourcesTool() {
        // the chat dispatched library_sources by name but never listed it, because the schema the table is built from did not exist
        JsonNode t = tools().get("library_sources");
        assertNotNull(t);
        assertEquals(List.of("list", "show", "add", "remove", "restore"), enumOf(t, "op"));
    }
}
