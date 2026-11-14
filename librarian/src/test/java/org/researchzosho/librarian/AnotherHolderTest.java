package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.HangingServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A daemon that finds another live process holding its library's lease stops working on the library: it takes no new job, stops the
 * running one without writing anything of it (its record stays running, for that process or the next start to resume from its kept
 * work), refuses every request that would write with 503 and the sentence of {@code /healthz}, and ends; the serve command ends with
 * one line that says who holds the library and a code that is not zero. A lease that only ran out, with nobody else holding it, is taken
 * again and work goes on.
 */
class AnotherHolderTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String PROBE_ANSWER = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    long watch, giveUpWait, least;

    @BeforeEach void quick() {
        watch = Lease.WATCH_MS; giveUpWait = LibrarianDaemon.GIVE_UP_WAIT_MS; least = LibrarianDaemon.GIVE_UP_LEAST_MS;
        Lease.WATCH_MS = 100;
        LibrarianDaemon.GIVE_UP_WAIT_MS = 15_000;
    }

    @AfterEach void back() { Lease.WATCH_MS = watch; LibrarianDaemon.GIVE_UP_WAIT_MS = giveUpWait; LibrarianDaemon.GIVE_UP_LEAST_MS = least; }

    /** Every file of the library, by its path, with a hash of what it holds. */
    static Map<String, String> files(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) out.put(root.relativize(p).toString(), HexFormat.of().formatHex(sha.digest(Files.readAllBytes(p))));
        }
        return out;
    }

    /** The files added, removed or changed between two looks. */
    static String changed(Map<String, String> a, Map<String, String> b) {
        StringBuilder out = new StringBuilder();
        for (String k : a.keySet()) if (!b.containsKey(k)) out.append(" -").append(k); else if (!a.get(k).equals(b.get(k))) out.append(" ~").append(k);
        for (String k : b.keySet()) if (!a.containsKey(k)) out.append(" +").append(k);
        return out.toString();
    }

    static void anotherTakes(LibraryStore store, long pid) throws Exception {
        Files.writeString(Lease.file(store), pid + "\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(300) + "\n", StandardCharsets.UTF_8);
    }

    static HttpResponse<String> post(String url, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void aJobInTheMiddleOfAStepStopsAndNothingOfItIsWrittenOnceAnotherProcessHoldsTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        LibrarianDaemon.GIVE_UP_LEAST_MS = 3_000;   // time to ask it a read and a write before it ends
        // the probe that asks whether a model answers gets its answer; every request of the run itself hangs, in the middle of a step
        try (HangingServer model = new HangingServer(HangingServer.Mode.SILENT, r -> r.body().contains("\"max_tokens\":1,") || r.body().contains("\"max_tokens\":1}"), PROBE_ANSWER)) {
            LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, model.url(), "placeholder-model", -1);
            daemon.researcherFactory = d -> new Researcher(Researcher.drive(d, "placeholder-model"), new ResearcherTest.FakeTools(), null, 1);
            String base = "http://127.0.0.1:" + daemon.port();
            try {
                ObjectNode ask = M.createObjectNode();
                ask.put("question", "How were the placeholder gears cut?");
                ask.putArray("sub_questions").add("how were they cut?");
                String id = daemon.research(ask, Patrons.Patron.PERSON).path("job_id").asText();
                assertTrue(model.awaitHung(30), "the run sent its first request to the model");
                assertEquals("running", daemon.jobs().get(id).path("state").asText());
                // another live process takes the library (this one was paused past its lease, and a second service started)
                long other = OneProcessPerLibraryTest.anotherLivePid();
                anotherTakes(store, other);
                long t0 = System.currentTimeMillis();
                while (daemon.gaveUpLine() == null && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20);
                assertNotNull(daemon.gaveUpLine(), "the daemon found another process holding the library");
                Map<String, String> atTheLoss = files(store.root());
                // reads go on; a request that would write is refused with the sentence /healthz says
                HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, health.statusCode());
                String why = M.readTree(health.body()).path("why").asText();
                assertTrue(why.contains("pid " + other), why);
                HttpResponse<String> read = post(base + "/v1/status", "{}");
                assertEquals(200, read.statusCode(), "a read goes on: " + read.body());
                HttpResponse<String> write = post(base + "/v1/research", "{\"question\":\"How were the placeholder gears polished afterwards?\"}");
                assertEquals(503, write.statusCode(), write.body());
                assertEquals(why, M.readTree(write.body()).path("error").path("message").asText(), "the same sentence");
                HttpResponse<String> form = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/chat")).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("say=hello")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, form.statusCode(), "a form writes: refused");
                assertEquals(why, form.body().strip());
                // the run stops, and the daemon ends
                assertTrue(daemon.endedWithin(20_000), "the daemon ended once its job had stopped");
                assertTrue(daemon.jobs().running().isEmpty(), "the job's thread ended");
                assertTrue(model.awaitClosed(5), "the model call in the middle of the step was let go");
                // nothing of it, nor anything else, was written into the library after the loss
                Map<String, String> after = files(store.root());
                assertEquals(atTheLoss, after, "the library as it was at the loss; changed: " + changed(atTheLoss, after));
                JsonNode j = M.readTree(Files.readString(store.root().resolve("catalog").resolve("jobs").resolve("active").resolve(id + ".json")));
                assertEquals("running", j.path("state").asText(), "its record stays running: the holder, or the next start, resumes it from its kept work");
                assertFalse(j.has("ended_at"));
                assertTrue(daemon.gaveUpLine().contains("pid " + other), daemon.gaveUpLine());
            } finally { daemon.stop(); }
        }
    }

    @Test void aLeaseThatOnlyRanOutIsTakenAgainAndWorkGoesOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            // the laptop slept: its own lease ran out an hour ago, and nobody took the library meanwhile
            long me = ProcessHandle.current().pid();
            Files.writeString(Lease.file(store), me + "\t" + Lease.hostName() + "\t" + Instant.now().minusSeconds(3600) + "\n", StandardCharsets.UTF_8);
            long t0 = System.currentTimeMillis();
            Lease.Holder h = Lease.holder(store);
            while (!Instant.now().isBefore(h.expires()) && System.currentTimeMillis() - t0 < 10_000) { Thread.sleep(20); h = Lease.holder(store); }
            assertEquals(me, h.pid());
            assertTrue(Instant.now().isBefore(h.expires()), "written again for five more minutes: " + h);
            // and its file gone altogether: written again too
            Files.delete(Lease.file(store));
            t0 = System.currentTimeMillis();
            while (!Files.exists(Lease.file(store)) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20);
            assertEquals(me, Lease.holder(store).pid());
            HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + daemon.port() + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
            assertNull(daemon.gaveUpLine(), "work goes on");
            assertFalse(daemon.endedWithin(300));
            assertNull(daemon.jobs().gaveUp());
            assertEquals("J-0001", store.nextJobId(), "the library is written to as before");
        } finally { daemon.stop(); }
    }

    @Test void theServeCommandEndsWithALineSayingWhoHoldsTheLibraryAndACodeThatIsNotZero(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Process serve = CrossProcess.start(Map.of("RESEARCHZOSHO_LIBRARY", store.root().toString(), "RESEARCHZOSHO_SCORERS", "off", "RESEARCHZOSHO_LIKENESS", "off",
                "RESEARCHZOSHO_LOCAL_HELPERS", "off", "RESEARCHZOSHO_LOG", "stdout"), tmp.resolve("home"), "serve");
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(serve.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder said = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null) { said.append(line).append('\n'); if (line.startsWith("The Librarian is at")) break; }
            assertNotNull(line, "the server started: " + said);
            long me = ProcessHandle.current().pid();   // this test's process takes the library: alive, on this machine
            anotherTakes(store, me);
            while ((line = out.readLine()) != null) said.append(line).append('\n');
            assertTrue(serve.waitFor(60, TimeUnit.SECONDS), "the server ended: " + said);
            assertEquals(LibrarianCli.GAVE_UP, serve.exitValue(), said.toString());
            String last = said.toString().strip().lines().reduce((a, b) -> b).orElse("");
            assertTrue(last.startsWith("Stopped: another process holds this library's lease (pid " + me + " on " + Lease.hostName()), last);
        } finally { serve.destroyForcibly(); }
    }
}
