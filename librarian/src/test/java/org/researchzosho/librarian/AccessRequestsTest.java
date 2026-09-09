package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A stranger asks in, the owner answers, the requester collects the token once with the claim secret. */
class AccessRequestsTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void requestApproveClaimOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);   // a closed library: asking still works
        var p = new LibraryProtocol(store);
        ObjectNode r = p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zStranger\",\"name\":\"Cousin Ana\",\"note\":\"the Nakamura branch in Osaka\"}"));
        String id = r.get("request_id").asText(), claim = r.get("claim").asText();
        assertEquals("pending", r.get("state").asText());
        assertFalse(claim.isBlank(), "the claim secret is given once");
        assertEquals(1, AccessRequests.pending(store));
        // asking again from the same did does not make a second request or a second secret
        ObjectNode again = p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zStranger\"}"));
        assertEquals(id, again.get("request_id").asText()); assertFalse(again.has("claim"));
        // pending, from the requester's side; a wrong secret is refused
        assertEquals("pending", p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"" + claim + "\"}")).get("state").asText());
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"nope\"}"))).code);
        // the owner approves for reading
        AccessRequests.approve(store, id, Patrons.Level.read);
        assertEquals(0, AccessRequests.pending(store));
        ObjectNode got = p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"" + claim + "\"}"));
        assertEquals("approved", got.get("state").asText());
        String token = got.get("token").asText();
        assertFalse(token.isBlank());
        assertEquals("did:key:zStranger", Patrons.resolve(store, token).did(), "the token proves the did");
        assertEquals(Patrons.Level.read, Patrons.resolve(store, token).level());
        // once: a second collection gets no token
        ObjectNode second = p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"" + claim + "\"}"));
        assertEquals("claimed", second.get("state").asText(), "the second look says it was already collected"); assertFalse(second.has("token"), second.toString());
        // and the request file holds no token any more
        assertFalse(java.nio.file.Files.readString(AccessRequests.file(store)).contains(token), "the token is burned from the record after the claim");
        // a listed reader cannot ask again
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zStranger\"}"))).code);
    }

    @Test
    void denyCarriesAReasonAndTheChangeFeedRecordsAnApproval(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        var p = new LibraryProtocol(store);
        ObjectNode filed = p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zX\",\"name\":\"X\"}"));
        String id = filed.get("request_id").asText(), claim = filed.get("claim").asText();
        AccessRequests.deny(store, id, "not a member of the society");
        // a wrong claim is refused even for a denied request: the reason is for the requester alone
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"whatever\"}"))).code);
        ObjectNode o = p.access((ObjectNode) M.readTree("{\"request_id\":\"" + id + "\",\"claim\":\"" + claim + "\"}"));
        assertEquals("denied", o.get("state").asText());
        assertEquals("not a member of the society", o.get("reason").asText());
        assertFalse(o.has("token"));
        // an approval goes on the changes feed, so the owner's other tools see it
        String id2 = p.requestAccess((ObjectNode) M.readTree("{\"did\":\"did:key:zY\"}")).get("request_id").asText();
        AccessRequests.approve(store, id2, Patrons.Level.write);
        assertTrue(Changes.since(store, 0, 10).stream().anyMatch(c -> c.event().equals("approved") && c.id().equals("did:key:zY")));
    }

    @Test
    void overHttpAStrangerCanAskWithNoTokenEvenWhenTheDefaultIsDeny(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/v1/request_access"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"did\":\"did:key:zHttp\",\"name\":\"over http\",\"note\":\"hello\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            var j = M.readTree(r.body());
            assertEquals("pending", j.get("state").asText());
            assertTrue(j.get("claim").asText().length() > 20);
            // but a closed library still answers nothing else to a stranger
            HttpResponse<String> ask = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/v1/ask"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"anything at all here\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, ask.statusCode(), ask.body());
        } finally { d.stop(); }
    }

    @Test
    void theLimitsHold(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        for (int i = 0; i < AccessRequests.MAX_PENDING; i++) AccessRequests.request(store, "did:key:z" + i, "", "");
        assertThrows(java.io.IOException.class, () -> AccessRequests.request(store, "did:key:zMore", "", ""), "the fifty-first waits");
        assertThrows(java.io.IOException.class, () -> AccessRequests.request(store, "person", "", ""), "the keeper does not ask");
    }
}
