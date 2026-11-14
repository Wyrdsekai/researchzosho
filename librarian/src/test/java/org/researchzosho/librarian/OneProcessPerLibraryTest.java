package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One process serves a library, and two processes on one library never hand out the same thing: the lease is taken by one process
 * however many start together, a stale holder's lease is taken over by one of them only, a daemon that has lost its lease does not say it
 * is healthy, and two processes issuing ids never issue one twice. Each case runs real processes, JVMs of their own.
 */
class OneProcessPerLibraryTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A pid of this machine that is alive and is not this process: the JVM's parent (the test runner's). */
    static long anotherLivePid() { return ProcessHandle.current().parent().map(ProcessHandle::pid).orElseThrow(); }

    @Test void aDaemonThatLostItsLeaseIsNotHealthy(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            URI health = URI.create("http://127.0.0.1:" + d.port() + "/healthz");
            assertEquals(200, c.send(HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            // another process takes the library, as one does once this one's lease has lapsed in a pause
            long other = anotherLivePid();
            Files.writeString(Lease.file(store), other + "\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(300) + "\n", StandardCharsets.UTF_8);
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, r.statusCode(), "a node that lost its lease must not answer healthy: " + r.body());
            JsonNode j = M.readTree(r.body());
            assertFalse(j.path("ok").asBoolean(true));
            assertTrue(j.path("why").asText().contains("pid " + other), r.body());
        } finally { d.stop(); }
    }

    /** Starts {@code n} children taking the lease together; the pids that said they held it. */
    static List<String> leaseRound(Path tmp, Path lib, int n, String round) throws Exception {
        Path go = tmp.resolve("go-" + round);
        List<Process> children = new ArrayList<>();
        for (int i = 0; i < n; i++) children.add(CrossProcess.start(tmp.resolve("home"), "lease", lib.toString(), go.toString(), "3000"));
        Thread.sleep(2500);   // every child is up and waiting
        Files.writeString(go, "go");
        List<String> held = new ArrayList<>();
        for (Process p : children) for (String line : CrossProcess.output(p).split("\n")) if (line.startsWith("held ")) held.add(line);
        return held;
    }

    @Test void processesStartingTogetherNeverBothTakeTheLease(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (int round = 0; round < 3; round++) {
            List<String> held = leaseRound(tmp, store.root(), 8, "free-" + round);
            assertEquals(1, held.size(), "one process takes a free lease, whatever starts beside it: " + held);
        }
        // a lease whose holder is gone is taken over, by one of them only
        Process gone = CrossProcess.start(tmp.resolve("home"), "home");
        CrossProcess.output(gone);
        Files.writeString(Lease.file(store), gone.pid() + "\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(300) + "\n", StandardCharsets.UTF_8);
        List<String> took = leaseRound(tmp, store.root(), 8, "stale");
        assertEquals(1, took.size(), "a stale holder's lease goes to one process: " + took);
    }

    @Test void twoProcessesNeverIssueTheSameId(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path go = tmp.resolve("go");
        int n = 6, each = 150;
        List<Process> children = new ArrayList<>();
        for (int i = 0; i < n; i++) children.add(CrossProcess.start(tmp.resolve("home"), "ids", store.root().toString(), go.toString(), String.valueOf(each)));
        Thread.sleep(2500);
        Files.writeString(go, "go");
        List<String> ids = new ArrayList<>();
        for (Process p : children) for (String line : CrossProcess.output(p).split("\n")) if (line.startsWith("J-")) ids.add(line.strip());
        Set<String> distinct = new HashSet<>(ids);
        assertEquals(n * each, ids.size(), "every child issued its ids");
        assertEquals(ids.size(), distinct.size(), "no id twice: " + (ids.size() - distinct.size()) + " issued more than once");
        assertEquals("J-" + String.format("%04d", n * each + 1), store.nextJobId(), "the counter stands after the last one issued");
    }

    @Test void aProcessKilledWhileItHoldsTheCounterDoesNotBlockIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Process holder = CrossProcess.start(tmp.resolve("home"), "hold-serials", store.root().toString());
        BufferedReader said = new BufferedReader(new InputStreamReader(holder.getInputStream()));
        assertEquals("holding", said.readLine());
        holder.destroyForcibly().waitFor(30, TimeUnit.SECONDS);   // killed in the step, as a crash would end it
        long t0 = System.currentTimeMillis();
        assertEquals("J-0001", store.nextJobId());
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "the counter is free as soon as its holder is gone");
    }
}
