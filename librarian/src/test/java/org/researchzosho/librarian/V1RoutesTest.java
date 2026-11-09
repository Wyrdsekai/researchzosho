package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tool has its POST /v1/{name} route, and a run is controlled by POST /v1/jobs/{id}. The daemon runs on a temporary library with a
 * model server address nothing listens at; the keeper's key proves the caller. Each route is asked with an op nothing takes, so the
 * handler answers from its own validation and no route reaches a model or the web; the routes that give their answer from the shelves
 * alone are read for it.
 */
class V1RoutesTest {
    private static final ObjectMapper M = new ObjectMapper();

    /** The routes a body with an unknown op would send past validation to a model or a program; they are listed in the map all the same. */
    static final Set<String> NOT_ASKED_HERE = Set.of("help", "jobs/{id}", "crews/run");

    private static HttpResponse<String> post(HttpClient c, String base, String key, String route, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(base + "/v1/" + route)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyToolHasItsRouteAndTheNewOnesAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ApiKeys.Minted key = ApiKeys.mint(store, Accounts.OWNER, "the test", Patrons.Level.write);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // every tool's name is a route: no "route not found" for any of them
            Set<String> routes = LibrarianDaemon.v1Routes();
            for (JsonNode t : McpServer.allTools()) {
                String name = t.path("name").asText().substring("library_".length());
                assertTrue(routes.contains(name), "POST /v1/" + name + " is a route");
            }
            for (String route : routes) {
                if (NOT_ASKED_HERE.contains(route)) continue;
                HttpResponse<String> r = post(c, base, key.secret(), route, "{\"op\":\"no-such-op\"}");
                assertFalse(r.statusCode() == 404 && r.body().contains("Nothing is served at /v1/"), "POST /v1/" + route + " is served: " + r.statusCode() + " " + r.body());
                assertTrue(r.statusCode() == 200 || r.statusCode() == 400 || r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 503, "POST /v1/" + route + ": " + r.statusCode() + " " + r.body());
            }
            HttpResponse<String> gone = post(c, base, key.secret(), "no_such_tool", "{}");
            assertEquals(404, gone.statusCode()); assertTrue(gone.body().contains("Nothing is served at /v1/no_such_tool"), gone.body());
            // the routes added for the tools the daemon's map lacked, each read for its answer
            assertEquals(List.of(), missing(M.readTree(post(c, base, key.secret(), "who", "{\"op\":\"list\"}").body()), "people"));
            JsonNode models = M.readTree(post(c, base, key.secret(), "models", "{\"op\":\"list\"}").body());
            assertTrue(models.path("models").isArray(), models.toString());
            assertTrue(M.readTree(post(c, base, key.secret(), "dossier", "{\"op\":\"list\"}").body()).path("dossiers").isArray());
            assertTrue(M.readTree(post(c, base, key.secret(), "suggest", "{}").body()).path("suggestions").isArray());
            JsonNode job = M.readTree(post(c, base, key.secret(), "job", "{}").body());
            assertTrue(job.path("active").isArray() && job.has("paused"), job.toString());
            JsonNode crews = M.readTree(post(c, base, key.secret(), "crews", "{}").body());
            assertTrue(crews.path("text").asText().startsWith("The housekeeping runs at"), crews.toString());
            JsonNode spend = M.readTree(post(c, base, key.secret(), "spend", "{}").body());
            assertTrue(spend.path("month").asText().matches("\\d{4}-\\d{2}"), spend.toString());
            assertEquals(0, M.readTree(post(c, base, key.secret(), "follow", "{}").body()).path("count").asInt(-1));
            assertTrue(M.readTree(post(c, base, key.secret(), "home", "{\"op\":\"list\"}").body()).path("searches").isArray());
            assertTrue(M.readTree(post(c, base, key.secret(), "career", "{\"op\":\"list\"}").body()).path("seekers").isArray());
            assertTrue(M.readTree(post(c, base, key.secret(), "sources", "{}").body()).path("sources").isArray());
            assertTrue(M.readTree(post(c, base, key.secret(), "dna", "{}").body()).has("summary"));
            assertTrue(M.readTree(post(c, base, key.secret(), "prefs", "{}").body()).has("summary"));
            // the tools that stand for one op of a wider call: the op is put in for them
            HttpResponse<String> look = post(c, base, key.secret(), "look", "{}");
            assertEquals(400, look.statusCode()); assertTrue(look.body().contains("video"), look.body());
            HttpResponse<String> records = post(c, base, key.secret(), "records", "{}");
            assertEquals(400, records.statusCode()); assertTrue(records.body().contains("address"), records.body());
            HttpResponse<String> board = post(c, base, key.secret(), "board", "{}");
            assertEquals(400, board.statusCode()); assertTrue(board.body().contains("employer"), board.body());
            HttpResponse<String> abstracts = post(c, base, key.secret(), "patent_abstracts", "{}");
            assertEquals(200, abstracts.statusCode()); assertTrue(abstracts.body().contains("No pool of patents"), abstracts.body());
            // the keeper's side of access, as the key proves the keeper; a reader's key cannot
            JsonNode readers = M.readTree(post(c, base, key.secret(), "access", "{\"op\":\"readers\"}").body());
            assertTrue(readers.path("readers").isArray() && readers.has("default"), readers.toString());
            ApiKeys.Minted reads = ApiKeys.mint(store, Accounts.OWNER, "reads", Patrons.Level.read);
            HttpResponse<String> refused = post(c, base, reads.secret(), "access", "{\"op\":\"readers\"}");
            assertEquals(403, refused.statusCode()); assertTrue(refused.body().contains("reads only"), refused.body());
            Patrons.set(store, "did:key:zReader", "A reader", Patrons.Level.write);
            String token = Patrons.issueToken(store, "did:key:zReader");
            HttpResponse<String> notKeeper = post(c, base, token, "access", "{\"op\":\"readers\"}");
            assertEquals(403, notKeeper.statusCode()); assertTrue(notKeeper.body().contains("Only the person who keeps this library"), notKeeper.body());
        } finally { d.stop(); }
    }

    private static List<String> missing(JsonNode r, String field) { return r.has(field) ? List.of() : List.of(field + " in " + r); }

    @Test
    void aRunIsControlledByItsOwnRoute(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ApiKeys.Minted key = ApiKeys.mint(store, Accounts.OWNER, "the test", Patrons.Level.write);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        String pauseBefore = Config.get(ResearchSettings.PAUSE);
        try {
            HttpResponse<String> noId = post(c, base, key.secret(), "jobs/", "{\"op\":\"stop\"}");
            assertEquals(404, noId.statusCode()); assertTrue(noId.body().contains("controlled by its id"), noId.body());
            HttpResponse<String> noOp = post(c, base, key.secret(), "jobs/J-0001", "{}");
            assertEquals(400, noOp.statusCode()); assertTrue(noOp.body().contains("op is stop, pause, resume, choose, allow or decline"), noOp.body());
            HttpResponse<String> unknown = post(c, base, key.secret(), "jobs/J-0001", "{\"op\":\"stop\"}");
            assertEquals(404, unknown.statusCode()); assertTrue(unknown.body().contains("No research run has the id J-0001"), unknown.body());
            // the runner paused, a run filed, stopped by its route before it starts, the runner let go
            JsonNode paused = M.readTree(post(c, base, key.secret(), "jobs/J-0001", "{\"op\":\"pause\"}").body());
            assertTrue(paused.path("paused").asBoolean(false), paused.toString());
            ObjectNode args = M.createObjectNode(); args.put("question", "What does the daemon do with a stop sent by route?");
            String id = d.jobs().submit("research", "did:key:zTest", args);
            JsonNode stopped = M.readTree(post(c, base, key.secret(), "jobs/" + id, "{\"op\":\"stop\"}").body());
            assertEquals(id, stopped.path("job_id").asText(), stopped.toString());
            assertEquals("stopped", stopped.path("state").asText(), stopped.toString());
            HttpResponse<String> again = post(c, base, key.secret(), "jobs/" + id, "{\"op\":\"stop\"}");
            assertTrue(again.statusCode() == 200 ? again.body().contains("\"stopped\"") : again.statusCode() == 400 && again.body().contains("is not queued or running"), "a second stop: " + again.statusCode() + " " + again.body());
            HttpResponse<String> choose = post(c, base, key.secret(), "jobs/" + id, "{\"op\":\"choose\",\"choice\":\"1\"}");
            assertEquals(400, choose.statusCode()); assertTrue(choose.body().contains("is not waiting for a choice"), choose.body());
            HttpResponse<String> allow = post(c, base, key.secret(), "jobs/" + id, "{\"op\":\"allow\"}");
            assertEquals(400, allow.statusCode()); assertTrue(allow.body().contains("is not waiting for an answer about help"), allow.body());
            JsonNode resumed = M.readTree(post(c, base, key.secret(), "jobs/J-0001", "{\"op\":\"resume\"}").body());
            assertFalse(resumed.path("paused").asBoolean(true), resumed.toString());
            // a read key cannot control a run
            ApiKeys.Minted reads = ApiKeys.mint(store, Accounts.OWNER, "reads", Patrons.Level.read);
            HttpResponse<String> refused = post(c, base, reads.secret(), "jobs/" + id, "{\"op\":\"stop\"}");
            assertEquals(403, refused.statusCode(), refused.body());
            // the GET stays what it was
            HttpResponse<String> got = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/jobs/" + id)).header("Authorization", "Bearer " + key.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, got.statusCode(), got.body()); assertEquals("stopped", M.readTree(got.body()).path("state").asText(), got.body());
        } finally {
            d.stop();
            Config.set(ResearchSettings.PAUSE, pauseBefore == null ? "" : pauseBefore);
        }
    }
}
