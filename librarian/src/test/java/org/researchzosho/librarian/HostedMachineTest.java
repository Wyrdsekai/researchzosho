package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The person who keeps a hosted library is not the person who owns the machine. Every call of the protocol is marked the library's or
 * the machine's, and none is left unmarked; under a keeper's statement the machine's calls are refused whole, a file by its path, a
 * program, a database, a tunnel and a model's address are each refused, the list of tools leaves the machine's out, and the settings
 * shown and set are the library's own. The places that act on the machine refuse in a hosted library's process whatever surface asked,
 * and take everything again once the library has stopped.
 */
class HostedMachineTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SERVICE = "http://accounts.test";

    @AfterEach void reset() throws IOException { Config.writable(null); try { Config.set("RESEARCHZOSHO_LANGUAGE", ""); Config.set("RESEARCHZOSHO_FETCH_PRIVATE", ""); } catch (IOException ignored) { } }

    @Test
    void everyCallIsMarkedAndNoMarkIsLeftOver() {
        Set<String> routes = new HashSet<>(LibrarianDaemon.v1Routes());
        for (String r : routes) assertNotNull(Hosted.reach(r), "POST /v1/" + r + " is marked the library's or the machine's in Hosted.CALLS");
        for (JsonNode t : McpServer.allTools()) assertNotNull(Hosted.reach(t.path("name").asText()), t.path("name").asText() + " is marked in Hosted.CALLS");
        for (String marked : Hosted.CALLS.keySet()) assertTrue(routes.contains(marked), marked + " is marked and is no route any more");
        assertEquals(Set.of("share", "db", "mcp", "models", "repo"), new HashSet<>(Hosted.CALLS.entrySet().stream().filter(e -> e.getValue() == Hosted.Reach.MACHINE).map(Map.Entry::getKey).toList()));
    }

    static HttpResponse<String> post(HttpClient c, String base, String statement, String route, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(base + route)).header(Statement.HEADER, statement).header("Content-Type", route.startsWith("/v1/") || route.equals("/rpc") ? "application/json" : "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aKeeperOfAHostedLibraryDoesNothingToTheMachine(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path secret = tmp.resolve("the-service-owners-file.txt");
        Files.writeString(secret, "what whoever runs the service keeps to themselves\n");
        Config.set("RESEARCHZOSHO_FETCH_PRIVATE", "deny");   // as the service starts a library
        KeyPair service = Statement.newKey();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching(SERVICE, kid -> service.getPublic()));
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = "http://127.0.0.1:" + d.port();
        String keeper = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, store.identity().id(), "a1", "Ann", Statement.KEEPER, Statement.SESSION));
        String writer = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, store.identity().id(), "a2", "Ben", Statement.WRITE, Statement.SESSION));
        try {
            assertTrue(Hosted.on(store) && Hosted.any());
            // the machine's calls, whole
            for (String call : List.of("share", "db", "mcp", "models", "repo")) {
                HttpResponse<String> r = post(c, base, keeper, "/v1/" + call, "{\"op\":\"list\"}");
                assertEquals(403, r.statusCode(), call + ": " + r.body());
                assertTrue(r.body().contains("runs under an account service"), call + ": " + r.body());
            }
            // a file by its path, whichever argument carries it
            Map<String, String> paths = new HashMap<>();
            paths.put("/v1/add", "{\"path\":\"" + secret + "\"}");
            paths.put("/v1/translate", "{\"path\":\"" + secret + "\",\"to\":\"fr\"}");
            paths.put("/v1/absorb", "{\"path\":\"" + secret + "\"}");
            paths.put("/v1/items", "{\"path\":\"" + secret + "\"}");
            paths.put("/v1/check", "{\"path\":\"" + secret + "\"}");
            paths.put("/v1/survey", "{\"path\":\"" + secret + "\"}");
            paths.put("/v1/owned", "{\"op\":\"import\",\"path\":\"" + secret + "\"}");
            paths.put("/v1/prefs", "{\"op\":\"import\",\"path\":\"" + secret + "\"}");
            paths.put("/v1/dna", "{\"op\":\"pack-import\",\"path\":\"" + secret + "\"}");
            for (Map.Entry<String, String> p : paths.entrySet()) for (String who : List.of(keeper, writer)) {
                HttpResponse<String> r = post(c, base, who, p.getKey(), p.getValue());
                assertEquals(403, r.statusCode(), p.getKey() + ": " + r.body());
                assertFalse(r.body().contains("keeps to themselves"), p.getKey() + " gave the file away: " + r.body());
            }
            // the same file named where an address belongs: a survey reads what is at a web address and nothing on the machine
            for (String body : List.of("{\"url\":\"" + secret + "\",\"kind\":\"paper\"}", "{\"url\":\"" + secret + "\",\"kind\":\"issues\"}", "{\"url\":\"" + tmp + "\",\"kind\":\"repo\"}", "{\"url\":\"" + tmp + "\"}",
                    "{\"url\":\"https://github.com/example/example\"}", "{\"url\":\"https://example.org/x.git\"}", "{\"database\":\"main\"}")) {
                HttpResponse<String> r = post(c, base, writer, "/v1/survey", body);
                assertEquals(403, r.statusCode(), body + ": " + r.body());
                assertTrue(r.body().contains("runs under an account service") || r.body().contains("Only the library owner"), body + ": " + r.body());
            }
            // a file a form names is not an upload: the Translate page takes nothing from it
            HttpResponse<String> form = post(c, base, writer, "/translate", "file.path=" + secret + "&file.name=taken.txt&to=fr");
            assertFalse(Files.exists(Translation.inbox(store).resolve("taken.txt")), "the file was not copied into the library");
            assertFalse(form.body().contains("keeps to themselves"));
            assertTrue(Uploads.files(Map.of("file.path", secret.toString(), "file.name", "taken.txt"), "file").isEmpty());
            // the settings: the library's own are shown and set, by its keeper; the service's are not
            JsonNode listed = M.readTree(post(c, base, keeper, "/v1/settings", "{\"op\":\"list\"}").body());
            assertTrue(listed.path("settings").size() > 3 && listed.path("settings").size() < 12, listed.toString());
            for (JsonNode s : listed.path("settings")) assertTrue(Hosted.ownSetting(s.path("key").asText()), s.toString());
            assertFalse(listed.has("config_file"));
            assertEquals(403, post(c, base, keeper, "/v1/settings", "{\"op\":\"set\",\"name\":\"drive\",\"value\":\"http://127.0.0.1:9\"}").statusCode());
            assertEquals(403, post(c, base, keeper, "/v1/settings", "{\"op\":\"set\",\"name\":\"brave_key\",\"value\":\"x\"}").statusCode());
            assertEquals(403, post(c, base, keeper, "/v1/settings", "{\"op\":\"check\"}").statusCode());
            assertEquals(403, post(c, base, writer, "/v1/settings", "{\"op\":\"set\",\"name\":\"language\",\"value\":\"Japanese\"}").statusCode(), "the keeper's to set, not every writer's");
            assertEquals(200, post(c, base, keeper, "/v1/settings", "{\"op\":\"set\",\"name\":\"language\",\"value\":\"Japanese\"}").statusCode());
            assertEquals("Japanese", Config.get("RESEARCHZOSHO_LANGUAGE"));
            // the library's own lists of people, accounts and keys are never written: the forms of its pages that would write them are refused
            for (String page : List.of("/settings", "/keep")) for (String posted : List.of("op=invite&name=Someone+New&level=read", "op=reader-allow&did=did%3Akey%3Ax&level=write", "op=reader-default&level=write", "op=reader-token&did=did%3Akey%3Ax", "op=key-new&label=mine&level=write", "op=check_keys"))
                for (String who : List.of(keeper, writer)) assertFalse(post(c, base, who, page, posted).body().matches("(?s).*rz_[A-Za-z0-9_-]{30,}.*"), page + " " + posted + " gave a key");
            assertFalse(Files.exists(Patrons.file(store)), "no patrons.md"); assertFalse(Files.exists(Accounts.file(store)), "no accounts.json"); assertFalse(Files.exists(ApiKeys.file(store)), "no keys.json");
            assertFalse(Files.exists(store.root().resolve("catalog").resolve("oidc-identities.json")));
            assertThrows(ProtocolError.class, () -> Patrons.set(store, "did:key:x", "X", Patrons.Level.write));
            assertThrows(ProtocolError.class, () -> ApiKeys.mint(store, "did:key:x", "x", Patrons.Level.read));
            assertThrows(ProtocolError.class, () -> Accounts.create(store, "did:key:x", "X", "a long password 1"));
            assertThrows(ProtocolError.class, () -> OutsideServices.check(store));
            // the list of tools leaves the machine's out, and calling one by name finds no such tool
            JsonNode tools = M.readTree(post(c, base, keeper, "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").body()).path("result").path("tools");
            Set<String> names = new HashSet<>(); for (JsonNode t : tools) names.add(t.path("name").asText());
            assertTrue(names.contains("library_ask") && names.contains("library_access") && names.size() > 50, names.toString());
            for (String machine : List.of("library_share", "library_db", "library_mcp", "library_models", "library_repo")) assertFalse(names.contains(machine), machine);
            assertTrue(post(c, base, keeper, "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"library_share\",\"arguments\":{\"op\":\"status\"}}}").body().contains("unknown tool"));
            // a notification's address on the service's own network is refused
            assertEquals(400, post(c, base, writer, "/v1/subscribe", "{\"url\":\"http://10.0.0.7/hook\"}").statusCode());
            // the places that act on the machine refuse here, whatever asked
            assertThrows(IOException.class, () -> Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:9"));
            assertThrows(IOException.class, () -> Config.set("RESEARCHZOSHO_ENTRY_X", "anything"));
            assertThrows(ProtocolError.class, () -> Share.share(store, List.of("status")));
            assertThrows(IOException.class, () -> Repos.obtain(store, tmp.toString()));
            assertThrows(IOException.class, () -> Repos.obtain(store, "https://example.org/x.git"));
            assertTrue(TranslationScores.setupInBackground("a test").contains("runs under an account service"));
            assertTrue(LikenessHelper.setupInBackground("a test").contains("runs under an account service"));
            assertTrue(ComicHelper.setupInBackground().contains("runs under an account service"));
            Config.set(ResearchSettings.PAUSE, "off");   // one of the library's own: written
        } finally { d.stop(); }
        // the library stopped: this process is nobody's hosted library, and the places take everything as they always did
        assertFalse(Hosted.any());
        Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:9");
        Config.set("RESEARCHZOSHO_DRIVE", "");
        assertTrue(Uploads.files(Map.of("file.path", secret.toString(), "file.name", "taken.txt"), "file").isEmpty(), "since 0.7.8 a path a form names is no upload in any library");
    }
}
