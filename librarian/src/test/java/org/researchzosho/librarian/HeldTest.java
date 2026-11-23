package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.drive.DriveClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Version;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hosted library's own side of its lease (0.8.0): it is one start of the library, by the count the service's registry keeps. It
 * answers the statements made for that start and no other; a statement of a later start ends it. The service's own statement opens
 * the service's own calls and nothing a person calls, and a person's opens none of the service's. "Still yours" is answered with
 * what the library is doing, where the runs strip an open page asks for by itself is not counted as use. A library that is not told
 * "still yours" for the length of a lease takes it for lost and ends, its jobs left for its next start.
 */
class HeldTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SERVICE = "http://accounts.test";
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    KeyPair key = Statement.newKey();
    LibrarianDaemon d; String base, here;
    long leaseWas = Held.LEASE_MS, waitWas = LibrarianDaemon.GIVE_UP_WAIT_MS, leastWas = LibrarianDaemon.GIVE_UP_LEAST_MS;

    /** A stand-in for the service's answer to a use (0.8.0, stage 3): the night the service starts is asked for, and is let start. */
    HttpServer uses;

    void start(Path tmp, long epoch) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        uses = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        uses.createContext("/", x -> { x.getRequestBody().readAllBytes(); byte[] b = "{\"use\":\"use_t\",\"state\":\"open\",\"said\":\"\",\"ceiling_dollars\":-1,\"ceiling_minutes\":-1,\"closed\":true}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        uses.start();
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching(SERVICE, "http://127.0.0.1:" + uses.getAddress().getPort(), kid -> kid.equals("k1") ? key.getPublic() : null));
        if (epoch > 0) d.heldAs(epoch);
        base = "http://127.0.0.1:" + d.port(); here = store.identity().id();
    }

    @AfterEach void stop() { if (d != null) d.stop(); if (uses != null) uses.stop(0); Held.LEASE_MS = leaseWas; LibrarianDaemon.GIVE_UP_WAIT_MS = waitWas; LibrarianDaemon.GIVE_UP_LEAST_MS = leastWas; }

    String person(long epoch) { return Statement.sign(key.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "acct_1", "Ann Example", Statement.KEEPER, Statement.SESSION).epoch(epoch)); }
    String service(long epoch) { return Statement.sign(key.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "service", "the account service", Statement.READ, Statement.SERVICE).epoch(epoch)); }

    HttpResponse<String> get(String path, String statement) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(base + path)).header(Statement.HEADER, statement).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(String path, String statement) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(base + path)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()); }

    @Test
    void aLibraryAnswersTheStatementsOfItsOwnStartAndTheServicesCallsAreTheServicesAlone(@TempDir Path tmp) throws Exception {
        start(tmp, 3);
        assertEquals(3, M.readTree(c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("epoch").asLong(), "the health check says which start answers");
        assertEquals(200, get("/v1/status", person(3)).statusCode());
        for (long earlier : new long[]{2, 0}) {
            HttpResponse<String> r = get("/v1/status", person(earlier));
            assertEquals(403, r.statusCode()); assertTrue(r.body().contains("made for an earlier start of this library"), r.body());
        }
        // the service's own statement opens no call of a person's, and a person's none of the service's
        HttpResponse<String> asPerson = get("/v1/status", service(3));
        assertEquals(403, asPerson.statusCode()); assertTrue(asPerson.body().contains("opens only its own calls"), asPerson.body());
        HttpResponse<String> notTheService = post(Held.STILL_YOURS, person(3));
        assertEquals(403, notTheService.statusCode()); assertTrue(notTheService.body().contains("the account service's own"), notTheService.body());
        assertEquals(403, post(Held.NIGHT, person(3)).statusCode());
        assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(base + Held.STILL_YOURS)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "and nobody's without a statement");
        assertEquals(403, post(Held.STILL_YOURS, service(2)).statusCode(), "nor the service's for an earlier start");
        // "still yours": what the library is doing
        HttpResponse<String> held = post(Held.STILL_YOURS, service(3));
        assertEquals(200, held.statusCode(), held.body());
        JsonNode s = M.readTree(held.body());
        assertEquals(3, s.path("epoch").asLong()); assertEquals(Version.string(), s.path("version").asText());
        assertEquals(0, s.path("open").asInt()); assertEquals(0, s.path("jobs_running").asInt()); assertEquals(0, s.path("jobs_waiting").asInt()); assertEquals(0, s.path("jobs_held").asInt());
        assertFalse(s.path("night").asBoolean()); assertEquals(0, s.path("due_at").asLong());
        // a chat turn that has ended is kept a while for the page that reads its end: it is no turn in progress, and one being answered is
        Pages.Live ended = new Pages.Live(), going = new Pages.Live();
        ended.done = true;
        Pages.LIVE.put("held-test-ended", ended);
        try {
            assertFalse(M.readTree(post(Held.STILL_YOURS, service(3)).body()).path("chat_live").asBoolean(), "a turn that ended");
            Pages.LIVE.put("held-test-going", going);
            assertTrue(M.readTree(post(Held.STILL_YOURS, service(3)).body()).path("chat_live").asBoolean(), "a turn being answered");
        } finally { Pages.LIVE.remove("held-test-ended"); Pages.LIVE.remove("held-test-going"); }
        assertTrue(s.path("idle_ms").asLong() < 5_000, "a person's request a moment ago: " + s);
        // what a page asks for by itself is not use: the runs strip, the health check, the service's own calls
        Thread.sleep(400);
        get(Held.RUNS_STRIP + "?session=none", person(3)); post(Held.STILL_YOURS, service(3));
        c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
        long idle = M.readTree(post(Held.STILL_YOURS, service(3)).body()).path("idle_ms").asLong();
        assertTrue(idle >= 400, "nothing a person asked for since: " + idle);
        assertEquals(200, get("/v1/status", person(3)).statusCode());
        assertTrue(M.readTree(post(Held.STILL_YOURS, service(3)).body()).path("idle_ms").asLong() < 400, "a person's request is use");
        // the night's tasks, started by the service's word
        HttpResponse<String> night = post(Held.NIGHT, service(3));
        assertEquals(200, night.statusCode(), night.body()); assertTrue(M.readTree(night.body()).has("job_id"), night.body());
        assertNull(d.gaveUpLine(), "and it holds its library");
    }

    @Test
    void aStatementOfALaterStartEndsTheLibrary(@TempDir Path tmp) throws Exception {
        LibrarianDaemon.GIVE_UP_WAIT_MS = 500; LibrarianDaemon.GIVE_UP_LEAST_MS = 300;
        start(tmp, 3);
        assertEquals(200, get("/v1/status", person(3)).statusCode());
        HttpResponse<String> later = get("/v1/status", person(4));
        assertEquals(403, later.statusCode()); assertTrue(later.body().contains("replaced by a later one"), later.body());
        assertNotNull(d.gaveUpLine()); assertTrue(d.gaveUpLine().contains("the account service has started this library again (start 4; this is start 3)"), d.gaveUpLine());
        d.join();   // it ends by itself
    }

    @Test
    void aLibraryNotToldStillYoursForTheLengthOfALeaseEnds(@TempDir Path tmp) throws Exception {
        // a lease of three seconds, told every fifth of one: with 600 ms told every 100 ms, one pause of 300 ms in this thread on a busy
        // machine was a silence past the fence, and the library ended while it was still being told (a three-core runner, once)
        Held.LEASE_MS = 3_000; LibrarianDaemon.GIVE_UP_WAIT_MS = 300; LibrarianDaemon.GIVE_UP_LEAST_MS = 100;
        start(tmp, 1);
        for (int i = 0; i < 30; i++) { Thread.sleep(200); assertEquals(200, post(Held.STILL_YOURS, service(1)).statusCode()); }   // twice the lease, told every fifth of a second
        assertNull(d.gaveUpLine(), "told still yours: it goes on");
        long t0 = System.currentTimeMillis();
        d.join();   // nobody tells it any more
        long after = System.currentTimeMillis() - t0;
        // the fence is two thirds of a lease after the last word (Held.silent, round B): two seconds of a three-second lease, and
        // the test's own last post comes just before t0, so it ends no sooner than that and well before the service could take it over
        assertTrue(after >= Held.LEASE_MS * 2 / 3 - 50 && after < 10_000, "it ended " + after + " ms after the last word, a lease of " + Held.LEASE_MS + " ms");
        assertTrue(d.gaveUpLine().contains("the account service has not said for") && d.gaveUpLine().contains("that the library is still this start's"), d.gaveUpLine());
    }

    /**
     * The moment a start of a library has not been told "still yours" for a lease, it asks no model and writes nothing more, on any
     * thread and before its watch has looked: a process frozen and let go must not race the start that took the library over (0.8.0).
     * Its watch is kept from ending it here, so what is seen is the fence itself.
     */
    @Test
    void aStartThatHasNotBeenToldItIsStillHeldAsksNoModelAndWritesNothing(@TempDir Path tmp) throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { if (x.getRequestURI().getPath().endsWith("/chat/completions")) asked.incrementAndGet(); byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
        model.start();
        // a lease of two seconds: held across the word and the first call however loaded the machine (a lease of 0.4 s could run out between
        // them), and let run out by a silence longer than it; its watch may look; it does not end within the test
        Held.LEASE_MS = 2_000; LibrarianDaemon.GIVE_UP_WAIT_MS = 60_000; LibrarianDaemon.GIVE_UP_LEAST_MS = 60_000;
        try {
            start(tmp, 1);
            DriveClient drive = new DriveClient("http://127.0.0.1:" + model.getAddress().getPort(), "stand-in");
            ArrayNode hi = M.createArrayNode(); hi.addObject().put("role", "user").put("content", "hi");
            Path file = tmp.resolve("lib").resolve("catalog").resolve("a-note.txt");
            assertEquals(200, post(Held.STILL_YOURS, service(1)).statusCode());
            assertEquals("ok", drive.classify(hi, 8));
            int held = asked.get();
            assertTrue(held >= 1, "held, it asks its model");
            AtomicWrite.text(file, "written while held");
            Thread.sleep(2_500);   // nobody says "still yours" for longer than a lease
            assertEquals(held, asked.get());
            drive.classify(hi, 8);
            assertEquals(held, asked.get(), "no request reached the model");
            assertThrows(IOException.class, () -> AtomicWrite.text(file, "written after"), "and nothing is written into the library");
            assertEquals("written while held", Files.readString(file));
        } finally { model.stop(0); }
    }

    @Test
    void aLibraryStartedWithNoEpochAnswersAnyStartsStatementAndKeepsNoWatch(@TempDir Path tmp) throws Exception {
        Held.LEASE_MS = 200;
        start(tmp, 0);
        assertEquals(200, get("/v1/status", person(0)).statusCode()); assertEquals(200, get("/v1/status", person(7)).statusCode());
        assertFalse(M.readTree(c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).has("epoch"));
        Thread.sleep(600);
        assertNull(d.gaveUpLine(), "no start was counted: nothing to lose");
        assertEquals(200, get("/v1/status", person(0)).statusCode());
    }
}
