package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A change on the feed reaches a subscribed address, signed; a dead address is retried and logged; the feed stays the truth. */
class WebhooksTest {

    static final ObjectMapper M = new ObjectMapper();

    record Received(String body, String sig, String event) { }

    static HttpServer receiver(List<Received> got, int status) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/hook", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            got.add(new Received(body, x.getRequestHeaders().getFirst("X-ResearchZosho-Signature"), x.getRequestHeaders().getFirst("X-ResearchZosho-Event")));
            x.sendResponseHeaders(status, -1); x.close();
        });
        s.start();
        return s;
    }

    static void waitFor(List<?> l, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (l.size() < n && System.currentTimeMillis() < end) Thread.sleep(50);
    }

    @Test
    void aChangeIsPostedSignedToTheSubscriberAndUnsubscribeStopsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.set(store, "did:key:zW", "Household A", Patrons.Level.read);
        var p = new LibraryProtocol(store);
        List<Received> got = new CopyOnWriteArrayList<>();
        HttpServer s = receiver(got, 204);
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/hook";
            ObjectNode r = p.subscribe((ObjectNode) M.readTree("{\"url\":\"" + url + "\",\"events\":[\"retired\",\"revised\"],\"patron\":{\"did\":\"did:key:zW\"}}"));
            String secret = r.get("secret").asText();
            assertFalse(secret.isBlank(), "a secret is made and returned once");
            assertEquals(1, Webhooks.list(store).size());
            // a change of a kind they asked for
            Changes.append(store, "finding", "F-0007-gears", "retired", "superseded by F-0009");
            waitFor(got, 1);
            assertEquals(1, got.size(), "delivered");
            JsonNode body = M.readTree(got.get(0).body());
            assertEquals("F-0007-gears", body.path("change").path("id").asText());
            assertEquals("retired", got.get(0).event());
            assertEquals(store.identity().id(), body.path("library_id").asText());
            assertEquals("sha256=" + Webhooks.hmac(secret, got.get(0).body()), got.get(0).sig(), "signed with the secret");
            // a kind they did not ask for is not posted
            Changes.append(store, "finding", "F-0008", "accepted", "");
            Thread.sleep(400);
            assertEquals(1, got.size(), "accepted was not subscribed");
            // the feed still has both
            assertEquals(2, Changes.since(store, 0, 10).size());
            String log = Files.readString(Webhooks.log(store));
            assertTrue(log.contains("delivered") && log.contains("retired F-0007-gears"), log);
            // unsubscribe
            assertTrue(p.unsubscribe((ObjectNode) M.readTree("{\"url\":\"" + url + "\",\"patron\":{\"did\":\"did:key:zW\"}}")).get("removed").asBoolean());
            Changes.append(store, "finding", "F-0007-gears", "revised", "");
            Thread.sleep(400);
            assertEquals(1, got.size(), "nothing more after unsubscribe");
        } finally { s.stop(0); }
    }

    @Test
    void aDeadAddressIsRetriedThenLoggedAndTheFeedStillHasTheChange(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<Received> got = new CopyOnWriteArrayList<>();
        HttpServer s = receiver(got, 500);
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/hook";
            Webhooks.add(store, "did:key:zW", url, "s3cret", List.of());
            Changes.append(store, "finding", "F-0001", "disputed", "why");
            waitFor(got, 3);
            long end = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < end && !(Files.exists(Webhooks.log(store)) && Files.readString(Webhooks.log(store)).contains("failed"))) Thread.sleep(100);
            assertEquals(3, got.size(), "three tries");
            String log = Files.readString(Webhooks.log(store));
            assertTrue(log.contains("failed") && log.contains("status 500") && log.contains("seq 1"), log);
            assertEquals(1, Changes.since(store, 0, 10).size(), "the feed is the truth regardless");
        } finally { s.stop(0); }
    }

    @Test
    void anAddressTheLibraryMustNotPostToIsRefused(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        var p = new LibraryProtocol(store);
        Patrons.set(store, "did:key:zW", "A", Patrons.Level.read);
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.subscribe((ObjectNode) M.readTree("{\"url\":\"http://169.254.169.254/latest\",\"patron\":{\"did\":\"did:key:zW\"}}"))).code);
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.subscribe((ObjectNode) M.readTree("{\"url\":\"ftp://example.org/x\",\"patron\":{\"did\":\"did:key:zW\"}}"))).code);
        assertEquals("forbidden", assertThrows(ProtocolError.class, () -> p.subscribe((ObjectNode) M.readTree("{\"url\":\"http://127.0.0.1:1/x\"}"))).code, "anonymous cannot subscribe");
    }

    @Test
    void aSubscriptionNamesEventsTheShortWay() {
        var h = new Webhooks.Hook("did:key:z1", "http://127.0.0.1:1/x", "s", java.util.List.of("retired", "supersedes"));
        assertTrue(h.wants("state:accepted→retired"), "the guide's --events retired matches the feed's state:accepted→retired");
        assertTrue(h.wants("supersedes:F-0001,F-0002"));
        assertFalse(h.wants("state:draft→accepted"));
        assertFalse(h.wants("edited"));
        assertTrue(new Webhooks.Hook("d", "u", "s", java.util.List.of()).wants("edited"), "no filter: everything");
        assertTrue(new Webhooks.Hook("d", "u", "s", java.util.List.of("state:draft→accepted")).wants("state:draft→accepted"), "the full spelling still works");
    }
}
