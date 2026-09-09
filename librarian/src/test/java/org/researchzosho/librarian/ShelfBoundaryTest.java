package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wyrdsekai's two findings before the first public commit (2026-09-07): an id or uri with a path in it
 * read files outside the shelf; a stdio client could name itself "person" and skip the allow list.
 * Plus the small ones: an array body is a 400, the ask's changes route reads the log's tail.
 */
class ShelfBoundaryTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void anIdOrUriIsAShelfNameNeverAPath(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // RawCapture.capture writes to the opened library
        try { shelfNames(home.resolve("researchzosho-library")); } finally { System.setProperty("user.home", real); }
    }

    private void shelfNames(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Files.writeString(tmp.resolve("catalog").resolve("secret.md"), "api.key = hunter2\n");
        var p = new LibraryProtocol(store);
        for (String id : new String[]{"../catalog/secret.md", "../catalog/secret", "F-../../catalog/secret", "raw/../catalog/secret.md", "..", "a/b"}) {
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.get((ObjectNode) M.readTree("{\"id\":\"" + id + "\"}")), id);
            assertEquals("invalid_args", e.code, id);
        }
        for (String uri : new String[]{"finding://../catalog/secret", "article://../catalog/secret"}) {
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.resourcesRead(uri), uri);
            assertEquals("invalid_args", e.code, uri);
        }
        // raw:// takes a locator (a URL is legal), so a path there is simply a locator nothing was captured for
        ProtocolError rawE = assertThrows(ProtocolError.class, () -> p.resourcesRead("raw://../catalog/secret.md"));
        assertEquals("not_found", rawE.code);
        // a locator with a path in it never becomes a direct file hit; it is looked up as a URL and found nowhere
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.read((ObjectNode) M.readTree("{\"locator\":\"raw/../catalog/secret.md\"}")));
        assertEquals("not_found", e.code);
        assertNull(store.finding("../catalog/secret"));
        assertNull(store.investigation("../catalog/secret"));
        assertNull(LibraryStore.under(tmp.resolve("findings"), "../catalog/secret.md"));
        assertNull(LibraryStore.under(tmp.resolve("findings"), ""));
        assertEquals(tmp.resolve("findings").toAbsolutePath().normalize().resolve("F-0001-x.md"), LibraryStore.under(tmp.resolve("findings"), "F-0001-x.md"));
        // a real raw capture is still found by its file name
        Path raw = RawCapture.capture("https://example.org/p", "hello there", "P");
        assertEquals("raw", kind(p, raw.getFileName().toString()));
        assertNotNull(p.get((ObjectNode) M.readTree("{\"id\":\"" + raw.getFileName() + "\"}")).get("entry"));
    }

    private static String kind(LibraryProtocol p, String id) throws Exception {
        var m = LibraryProtocol.class.getDeclaredMethod("kindOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(p, id);
    }

    @Test
    void aStdioClientCannotBeThePerson(@TempDir Path tmp) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            new LibraryStore(tmp.resolve("researchzosho-library")).init();
            Patrons.setDefault(LibraryStore.open(), Patrons.Level.deny);
            JsonNode env = org.researchzosho.mcp.McpServer.envelopeFor(M.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{\"patron\":{\"did\":\"person\"}}}}"));
            assertEquals("forbidden", env.get("error").get("data").get("code").asText(), env.toString());
            JsonNode anon = org.researchzosho.mcp.McpServer.envelopeFor(M.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}"));
            assertEquals("forbidden", anon.get("error").get("data").get("code").asText(), "deny by default means deny: " + anon);
        } finally {
            System.setProperty("user.home", real);
        }
    }

    @Test
    void overRpcThePatronsRuntimeComesFromTheEnvelopeNotTheTransport(@TempDir Path tmp) throws Exception {
        // Wyrdsekai (2026-09-08): the daemon read the runtime from the top-level body, which an rpc envelope
        // does not have, so circulation said "via http" instead of "via wyrdsekai"
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.set(store, "did:key:zW", "Household A", Patrons.Level.write);
        String token = Patrons.issueToken(store, "did:key:zW");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"library_ask\","
                    + "\"arguments\":{\"question\":\"what do we hold on gears\",\"patron\":{\"did\":\"did:key:zW\",\"runtime\":\"wyrdsekai\"}}}}";
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/rpc"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(rpc)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("holds_nothing"), "the ask reached the protocol: " + r.body());
            String log = Files.readString(store.circulationFile());
            assertTrue(log.contains("did:key:zW (Household A) via wyrdsekai"), log);
            assertFalse(log.contains("via http"), log);
            // and a did the token does not prove is still refused, from the envelope too
            String bad = rpc.replace("did:key:zW", "did:key:other");
            HttpResponse<String> r2 = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/rpc"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(bad)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(r2.body().contains("forbidden"), r2.body());
        } finally {
            d.stop();
        }
    }

    @Test
    void anArrayBodyIsA400NotA503(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/v1/status"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("[1,2]")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), r.body());
            assertEquals("invalid_args", M.readTree(r.body()).get("error").get("code").asText());
        } finally {
            d.stop();
        }
    }

    @Test
    void theChangesTailReadsFromTheEnd(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Path f = Changes.file(store);
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4000; i++) sb.append(i).append("\t2026-09-0").append(1 + i % 7).append("T00:00:00Z\tfinding\tF-").append(i).append("\tadded\tdetail ").append("x".repeat(40)).append('\n');
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        List<Changes.Change> tail = Changes.tail(store, 500);
        assertEquals(500, tail.size());
        assertEquals(3501, tail.get(0).seq(), "oldest of the last 500 first");
        assertEquals(4000, tail.get(499).seq());
        assertEquals(4000, Changes.tail(store, 10_000).size(), "a limit past the log returns it all");
        assertEquals(List.of(), Changes.tail(store, 0));
    }
}
