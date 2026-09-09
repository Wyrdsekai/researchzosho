package org.researchzosho.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.librarian.Finding;
import org.researchzosho.librarian.LibrarianDaemon;
import org.researchzosho.librarian.LibrarianIndex;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Patrons;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Java client against the real daemon, in-process, on a temporary library. */
class LibrarianClientTest {

    static LibrarianDaemon daemon;
    static LibraryStore store;
    static String token;

    @BeforeAll static void up(@TempDir Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-keigo-has-no-english-equivalent", "Keigo has no direct English equivalent",
                List.of("japanese--keigo"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/hosaka2016", "Hosaka 2016", "s")), List.of(),
                new Finding.Review(1, "person", "accept", "sha256:0", Instant.now().toString()),
                "Japanese honorific register (keigo) has no direct English equivalent.\n"));
        Files.createDirectories(store.rawDir());
        Files.writeString(store.rawDir().resolve("2026-09-01-abc.md"),
                "---\nurl: https://example.org/hosaka2016\ntitle: Hosaka 2016\nfetched_at: 2026-09-01T10:00:00Z\nfetched_by: test\n---\nThe paper text.\n", StandardCharsets.UTF_8);
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- japanese--keigo — the honorific system\n", StandardCharsets.UTF_8);
        new LibrarianIndex(store).rebuild();
        Patrons.set(store, "did:key:zW", "Household A", Patrons.Level.write);
        token = Patrons.issueToken(store, "did:key:zW");
        daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
    }
    @AfterAll static void down() { if (daemon != null) daemon.stop(); }

    private static LibrarianClient client(String tok) { return new LibrarianClient(URI.create(daemon.url()), tok); }

    @Test void theNineCallsAndProvenance() throws Exception {
        LibrarianClient lib = client(token);
        JsonNode st = lib.status();
        assertEquals(LibrarianClient.CONTRACT, st.get("contract").asText());
        assertTrue(st.get("library_id").asText().startsWith("lib_"));
        JsonNode pkg = lib.ask("keigo English equivalent", 6);
        assertFalse(pkg.get("holds_nothing").asBoolean());
        assertEquals("finding", pkg.get("entries").get(0).get("kind").asText());
        assertEquals("Hosaka 2016", pkg.get("entries").get(0).get("sources").get(0).get("edition").asText());
        assertEquals("established", lib.established("keigo has no English equivalent").get("verdict").asText());
        assertEquals("accept", lib.get("F-0001-keigo-has-no-english-equivalent").get("review").get("decision").asText());
        assertEquals("The paper text.", lib.read("https://example.org/hosaka2016", 100).get("text").asText().strip());
        assertTrue(lib.read("https://example.org/hosaka2016", 100).get("edition").isNull());
        assertEquals(1, lib.searchAll("keigo", "japanese--keigo").size());
        assertEquals("japanese", lib.subjects().get(0).get("facet").asText());
        JsonNode sub = lib.submit("Korean honorifics are subtitled the same way as keigo.", List.of("https://example.org/k"), "interpretation", "low", null);
        assertEquals("draft", sub.get("state").asText());
        assertEquals("patron:did:key:zW", store.finding(sub.get("id").asText()).writer());
        lib.frontierAdd("Does Vietnamese have a keigo analogue?");
        assertEquals(1, lib.frontier().size());
        assertTrue(lib.resource("finding://F-0001-keigo-has-no-english-equivalent").contains("Keigo"));
        assertEquals(2, lib.resources(null).get("resources").size() - 1, "2 findings + 1 raw");
        JsonNode ch = lib.changes("0", 100);
        assertTrue(ch.get("changes").size() >= 2, "the seed and the submission are in the feed");
        assertEquals("added", ch.get("changes").get(0).get("event").asText());
    }

    @Test void errorsCarryStableCodesAndStatuses() {
        LibrarianClient anon = client(null);
        LibraryException nf = assertThrows(LibraryException.class, () -> anon.get("F-0000-nope"));
        assertEquals("not_found", nf.code); assertEquals(404, nf.status);
        LibraryException fb = assertThrows(LibraryException.class, () -> anon.submit("A claim long enough to be refused for lacking proof.", List.of("x"), "synthesis", "medium", null));
        assertEquals("forbidden", fb.code); assertEquals(403, fb.status);
        LibraryException ns = assertThrows(LibraryException.class, () -> client(token).submit("A claim long enough to be refused for lacking sources.", List.of(), "synthesis", "medium", null));
        assertEquals("no_sources", ns.code); assertEquals(422, ns.status);
        LibraryException bad = assertThrows(LibraryException.class, () -> client("not-a-token").status());
        assertEquals("forbidden", bad.code);
        LibraryException ia = assertThrows(LibraryException.class, () -> anon.search("x", 5, null, "not-a-cursor"));
        assertEquals("invalid_args", ia.code); assertEquals(400, ia.status);
    }

    @Test void rpcSpeaksTheStreamableHttpSubset() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var base = java.net.URI.create(daemon.url() + "/rpc");
        var init = http.send(java.net.http.HttpRequest.newBuilder(base).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, init.statusCode());
        assertTrue(init.body().contains("\"protocolVersion\""));
        var note = http.send(java.net.http.HttpRequest.newBuilder(base).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(202, note.statusCode(), "a notification is accepted with no body");
        var get = http.send(java.net.http.HttpRequest.newBuilder(base).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode(), "no server-push stream");
        var call = http.send(java.net.http.HttpRequest.newBuilder(base).header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, call.statusCode());
        assertTrue(call.body().contains("\"library_id\""));
        var list = http.send(java.net.http.HttpRequest.newBuilder(base).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertFalse(list.body().contains("\"name\":\"fix\""), "the library's endpoint serves the library only");
        assertTrue(list.body().contains("\"name\":\"library_ask\""));
    }

    @Test void researchNeedsADriveAndAJobLedgerAnswers() {
        LibraryException e = assertThrows(LibraryException.class, () -> client(token).research("anything at all", "broad", 5));
        assertEquals("unavailable", e.code); assertEquals(503, e.status);
        assertEquals("not_found", assertThrows(LibraryException.class, () -> client(token).job("J-9999")).code);
        assertEquals(0, client(token).jobs().get("active").size() + client(token).jobs().get("finished").size());
        // 0 turns is not an error any more: it is "no ceiling" (the client omits the field) — the drive is still what is missing
        assertEquals("unavailable", assertThrows(LibraryException.class, () -> client(token).research("anything at all", "broad", 0)).code);
        LibraryException ia = assertThrows(LibraryException.class, () -> client(token).research("short", "broad", 0));
        assertEquals("invalid_args", ia.code);
    }
}
