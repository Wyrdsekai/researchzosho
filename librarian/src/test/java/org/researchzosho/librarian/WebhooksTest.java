package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Main;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
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
    void aStateChangeIsPostedWithTheArrowSpelledForTheHeaderAndExactInTheBody(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<Received> got = new CopyOnWriteArrayList<>();
        HttpServer s = receiver(got, 204);
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/hook";
            Webhooks.add(store, "did:key:zW", url, "s3cret", List.of("accepted"));
            Changes.append(store, "finding", "F-0004", "state:draft→accepted", "");
            waitFor(got, 1);
            assertEquals(1, got.size(), "a state change is delivered");
            assertEquals("state:draft->accepted", got.get(0).event());
            assertEquals("state:draft→accepted", M.readTree(got.get(0).body()).path("change").path("event").asText());
        } finally { s.stop(0); }
        assertEquals("state:accepted->retired", Webhooks.headerForm("state:accepted→retired"));
        assertEquals("retired", Webhooks.headerForm("retired"));
    }

    @Test
    void aProgramOnTheLibrarysOwnMachineMaySubscribeButNotAtOneOfItsServices() {
        List<String> own = List.of("loopback:8202", "loopback:8888", "drive.example:8211");
        assertNull(Webhooks.refusal("http://127.0.0.1:7070/api/library/webhook/x", own), "another program on loopback");
        assertNull(Webhooks.refusal("http://localhost:7070/hook", own), "the same, by name");
        assertEquals("one of the library's own services", Webhooks.refusal("http://127.0.0.1:8202/v1/embeddings", own));
        assertEquals("one of the library's own services", Webhooks.refusal("http://localhost:8888/search", own), "localhost and 127.0.0.1 are one host");
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
    void aCommandDoesNotWaitForAnAddressWhoseLastPostFailed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        // a receiver that takes the connection and never answers, as a machine that hangs does
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            String url = "http://127.0.0.1:" + silent.getLocalPort() + "/hook";
            Webhooks.add(store, "did:key:zW", url, "s3cret", List.of());
            Files.createDirectories(Webhooks.log(store).getParent());
            Files.writeString(Webhooks.log(store), Instant.now() + "\tfailed\tdid:key:zW\t" + url + "\tdisputed F-0001\tstatus 500 after 3 tries; the feed still has it (seq 1)\n");
            assertTrue(Webhooks.lastFailed(store, Webhooks.list(store).get(0)));
            Changes.append(store, "finding", "F-0002", "retired", "why");
            assertTrue(Webhooks.Sent.any(), "the post was made");
            long t0 = System.currentTimeMillis();
            Webhooks.flush(Webhooks.FLUSH_MS);
            assertTrue(System.currentTimeMillis() - t0 < 2500, "the command did not wait five seconds for a receiver that is down: " + (System.currentTimeMillis() - t0) + " ms");
            String log = Files.readString(Webhooks.log(store));
            assertTrue(log.contains("\tnot waited for\t") && log.contains("the last post to this address failed; the feed still has it (seq 1)"), log);
        }
    }

    @Test
    void anAddressWhoseLastPostFailedIsWaitedForAgainOnceItAnswers(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<Received> got = new CopyOnWriteArrayList<>();
        // the receiver is back, and takes a moment to answer, as a machine that has just woken does
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/hook", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            got.add(new Received(body, "", "")); x.sendResponseHeaders(204, -1); x.close();
        });
        s.start();
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/hook";
            Webhooks.add(store, "did:key:zW", url, "s3cret", List.of());
            Files.createDirectories(Webhooks.log(store).getParent());
            Files.writeString(Webhooks.log(store), Instant.now() + "\tfailed\tdid:key:zW\t" + url + "\tdisputed F-0001\tstatus 500 after 3 tries; the feed still has it (seq 1)\n");
            Changes.append(store, "finding", "F-0002", "retired", "why");
            Webhooks.flush(Webhooks.FLUSH_MS);   // the command ends here
            String log = Files.readString(Webhooks.log(store));
            assertTrue(log.contains("\tdelivered\t") && !log.contains("\tnot waited for\t"), log);
            assertFalse(Webhooks.lastFailed(store, Webhooks.list(store).get(0)), "the next command waits for this address again");
            assertEquals(1, got.size());
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
        var h = new Webhooks.Hook("did:key:z1", "http://127.0.0.1:1/x", "s", List.of("retired", "supersedes"));
        assertTrue(h.wants("state:accepted→retired"), "the guide's --events retired matches the feed's state:accepted→retired");
        assertTrue(h.wants("supersedes:F-0001,F-0002"));
        assertFalse(h.wants("state:draft→accepted"));
        assertFalse(h.wants("edited"));
        assertTrue(new Webhooks.Hook("d", "u", "s", List.of()).wants("edited"), "no filter: everything");
        assertTrue(new Webhooks.Hook("d", "u", "s", List.of("state:draft→accepted")).wants("state:draft→accepted"), "the full spelling still works");
    }

    @Test
    void somebodyWhoMayNoLongerReadIsSentNothingAndTheLogSaysWhy(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        var p = new LibraryProtocol(store);
        Patrons.set(store, "did:key:cousin", "a cousin", Patrons.Level.read);
        List<Received> got = new CopyOnWriteArrayList<>(), toUnlisted = new CopyOnWriteArrayList<>();
        HttpServer s = receiver(got, 204), u = receiver(toUnlisted, 204);
        try {
            p.subscribe((ObjectNode) M.readTree("{\"url\":\"http://127.0.0.1:" + s.getAddress().getPort() + "/hook\",\"patron\":{\"did\":\"did:key:cousin\"}}"));
            // the owner takes the cousin's access away: the hook stays in the file, and nothing more is posted to it
            Patrons.set(store, "did:key:cousin", "a cousin", Patrons.Level.deny);
            Changes.append(store, "node", "Mari Endo", "merged", "Mari Endo (1) -> Mari Endo by person");
            Thread.sleep(400);
            assertEquals(0, got.size(), "a patron who may no longer read is sent nothing: " + got);
            String log = Files.readString(Webhooks.log(store));
            assertTrue(log.contains("withheld\tdid:key:cousin") && log.contains("may no longer read"), log);
            // a program nobody listed subscribed while the library was open to everyone; then the owner closes it
            Webhooks.add(store, "did:key:unlisted", "http://127.0.0.1:" + u.getAddress().getPort() + "/hook", "s3", List.of());
            Patrons.setDefault(store, Patrons.Level.deny);
            Changes.append(store, "finding", "F-0002", "retired", "");
            Thread.sleep(400);
            assertEquals(0, toUnlisted.size(), "closing the library closes its webhooks too: " + toUnlisted);
            // given access back, the cousin is sent the next change
            Patrons.set(store, "did:key:cousin", "a cousin", Patrons.Level.read);
            Changes.append(store, "finding", "F-0003", "retired", "");
            waitFor(got, 1);
            assertEquals(1, got.size(), "read access again, posts again");
        } finally { s.stop(0); u.stop(0); }
    }

    @Test
    void aChangeMadeAtTheCommandLineIsPostedBeforeTheProcessEnds(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        List<Received> toReader = new CopyOnWriteArrayList<>(), toAunt = new CopyOnWriteArrayList<>();
        HttpServer r = receiver(toReader, 204), a = receiver(toAunt, 204);
        try {
            System.setProperty("user.home", tmp.toString());   // the command opens ~/researchzosho-library
            LibraryStore store = new LibraryStore(tmp.resolve("researchzosho-library")); store.init();
            Finding mill = new Finding(store.nextFindingId("Leeds mill closed-on 1910"), "The Leeds mill closed in 1910.", List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                    Finding.Confidence.medium, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                    List.of(new Finding.Source("https://example.org/register", "n/a", "a register")), List.of(), null, "The Leeds mill closed in 1910.\n",
                    new Finding.Triple("Leeds mill", "closed-on", "1910"), List.of());
            store.write(mill);
            Patrons.set(store, "did:key:reader", "a cousin", Patrons.Level.read);
            Patrons.set(store, "did:key:aunt", "an aunt", Patrons.Level.write);
            Webhooks.add(store, "did:key:reader", "http://127.0.0.1:" + r.getAddress().getPort() + "/hook", "s1", List.of());
            Webhooks.add(store, "did:key:aunt", "http://127.0.0.1:" + a.getAddress().getPort() + "/hook", "s2", List.of());
            assertEquals(0, Main.runToExit(new String[]{"retire", mill.id()}));
            // no waiting here: when the command has ended, the posts have been made, to a reader as to a writer
            assertEquals(1, toAunt.size(), toAunt.toString());
            assertEquals(1, toReader.size(), toReader.toString());
            assertTrue(toReader.get(0).body().contains(mill.id()), toReader.get(0).body());
        } finally { System.setProperty("user.home", realHome); r.stop(0); a.stop(0); }
    }
}
