package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API keys: made once in full and kept hashed, a bearer token over the protocol and the MCP route, its level enforced, revocable. */
class ApiKeysTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void aKeyIsShownOnceAndKeptAsItsPrefixAndHash(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        ApiKeys.Minted m = ApiKeys.mint(store, Accounts.OWNER, "the notes script", Patrons.Level.read);
        assertTrue(m.secret().startsWith("rz_") && m.secret().length() > 40, m.secret());
        assertEquals(m.secret().substring(0, 11), m.key().prefix());
        String file = Files.readString(ApiKeys.file(store), StandardCharsets.UTF_8);
        assertFalse(file.contains(m.secret()), "the secret is never written");
        assertTrue(file.contains(m.key().prefix()) && file.contains(Patrons.sha256(m.secret())), file);
        assertEquals(1, ApiKeys.list(store, Accounts.OWNER).size());
        assertEquals("the notes script", ApiKeys.list(store, Accounts.OWNER).get(0).label());
        assertTrue(ApiKeys.revoke(store, Accounts.OWNER, m.key().prefix()), "revoked by its prefix");
        assertNull(ApiKeys.resolve(store, m.secret()));
        assertFalse(ApiKeys.revoke(store, "did:key:zSomeoneElse", "K-nothing"));
    }

    @Test
    void aProgramSignsInWithTheKeyAndAReadKeyNeverWrites(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);   // strangers get nothing: whatever a key does, it does as its person
        ApiKeys.Minted read = ApiKeys.mint(store, Accounts.OWNER, "reads", Patrons.Level.read), write = ApiKeys.mint(store, Accounts.OWNER, "writes", Patrons.Level.write);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer " + read.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), "without the key, a stranger: " + r.body());
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/bookmarks")).header("Authorization", "Bearer " + read.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"https://example.com/a\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("reads only"), r.body());
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/bookmarks")).header("Authorization", "Bearer " + write.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"https://example.com/a\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(403, r.statusCode(), "a write key does what the person may: " + r.body());
            // over the MCP route too
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/rpc")).header("Authorization", "Bearer " + read.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(M.readTree(r.body()).has("error"), r.body());
            assertEquals(LocalDate.now().toString(), ApiKeys.byId(store, read.key().id()).used(), "its day of last use is noted");
            // a wrong key, and a revoked one, are refused with a reason
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer rz_not-a-key-at-all").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(ApiKeys.revoke(store, Accounts.OWNER, read.key().id()));
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer " + read.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("revoked"), r.body());
        } finally { d.stop(); }
    }

    @Test
    void aReadersKeyIsCappedByTheirListedLevel(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);
        String did = "did:key:zReaderOne";
        Patrons.set(store, did, "Reader One", Patrons.Level.read);
        ApiKeys.Minted k = ApiKeys.mint(store, did, "their script", Patrons.Level.write);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer " + k.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/bookmarks")).header("Authorization", "Bearer " + k.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"https://example.com/a\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), "a write key of a reader who may only read still only reads: " + r.body());
            Patrons.set(store, did, "Reader One", Patrons.Level.deny);
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer " + k.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), "denied as a reader, their key is refused too: " + r.body());
        } finally { d.stop(); }
    }

    @Test
    void theKeepPageMakesAKeyShowsItOnceAndRevokesIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/settings")).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("op=key-new&label=the+notes+script&level=write")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            int at = r.body().indexOf("rz_");
            assertTrue(at > 0 && r.body().contains("shown this once"), "the key is on the page, once");
            String secret = r.body().substring(at, r.body().indexOf("</code>", at));
            assertTrue(secret.length() > 40, secret);
            HttpResponse<String> keep = c.send(HttpRequest.newBuilder(URI.create(base + "/keep")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(keep.body().contains("the notes script") && keep.body().contains(secret.substring(0, 11) + "…") && !keep.body().contains(secret), "listed by its prefix, never in full again");
            String id = ApiKeys.list(store, Accounts.OWNER).get(0).id();
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/settings")).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("op=key-revoke&id=" + id)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(r.statusCode() == 302 || r.statusCode() == 303, String.valueOf(r.statusCode()));
            assertTrue(ApiKeys.list(store, Accounts.OWNER).isEmpty());
        } finally { d.stop(); }
    }
}
