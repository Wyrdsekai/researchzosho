package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Version;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node part with real libraries, each a process of its own (0.8.0, placement). A library is started as the registry's next
 * start when it is asked for; with nothing to do it is stopped and its lease given back, whatever an open page asks by itself, and
 * the next request starts it again and is answered; a request on its way keeps it up. A library whose lease has gone to another
 * node is stopped. A service started again takes back the libraries it left running, and the ones an older version of the program
 * started it stops at a quiet moment and starts itself, where their repairs run.
 */
class NodeTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, soon = Node.DUE_SOON_MS;
    AccountStore store; AccountService svc; Path root;
    /** The node behind the service: put in place of another to stand for the service started again. */
    volatile Node node;
    ChildLibraries local;

    void start(Path tmp) throws Exception {
        Node.LEASE_MS = 4_000; Node.RENEW_MS = 250; Node.SLEEP_AFTER_MS = 1_500L; Node.DUE_SOON_MS = 0;
        root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new LibraryRunner() {   // whichever node is in place now
            @Override public String ensure(AccountStore.Library lib) throws IOException { return node.ensure(lib); }
            @Override public void lost(String id) { node.lost(id); }
            @Override public void stopAll() { node.stopAll(); }
            @Override public long epoch(String id) { return node.epoch(id); }
            @Override public boolean awake(String id) { return node.awake(id); }
            @Override public String ifAwake(AccountStore.Library lib) throws IOException { return node.ifAwake(lib); }
            @Override public void done(String id) { node.done(id); }
        });
        node = new Node(store, svc.url(), local = new ChildLibraries(store, svc.url()), "node_test", root);
        node.start();
    }

    @AfterEach void stop() {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); }
        finally { Node.LEASE_MS = lease; Node.RENEW_MS = renew; Node.SLEEP_AFTER_MS = null; Node.DUE_SOON_MS = soon; }
    }

    HttpResponse<String> get(String path, String key) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()); }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(50); }
    }

    /** The library's own processes among this test's children, by the folder each was started for. */
    static List<ProcessHandle> processes(AccountStore.Library lib) {
        return ProcessHandle.current().children().filter(p -> p.isAlive() && p.info().commandLine().orElse("").contains(lib.folder())).toList();
    }

    JsonNode health(String address) throws Exception { return M.readTree(c.send(HttpRequest.newBuilder(URI.create(address + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()); }

    @Test
    void aLibrarySleepsWithNothingToDoAndTheNextRequestStartsItAgain(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        assertFalse(node.awake(id), "made, and not yet asked for");
        assertEquals(200, get("/v1/status", key).statusCode());
        AccountStore.Placement p = store.placement(id);
        assertEquals("node_test", p.node()); assertEquals(1, p.epoch()); assertTrue(p.leaseUntil() > store.clock() && !p.address().isEmpty(), p.toString());
        assertEquals(1, health(p.address()).path("epoch").asLong(), "the library knows which start it is");
        assertEquals(1, processes(lib).size(), "one process");
        String said = Files.readString(Path.of(lib.folder()).resolve("serve.log"));
        assertTrue(said.contains("This is start 1 of the library by the service's count. Its nights are the service's to start. Its heap is limited to 1024 MB."), "started as the first start, its night the scheduler's, a heap of 1 GB: " + said);
        Path lock = Path.of(lib.folder()).resolve("library").resolve("catalog").resolve("serve.lock");
        assertTrue(Files.exists(lock));

        // an open chat page asks for its runs strip by itself for as long as its tab is open: that keeps no library awake
        long t0 = System.currentTimeMillis();
        while (node.awake(id)) { assertTrue(System.currentTimeMillis() - t0 < 15_000, "asleep within 15 s of nothing to do: " + node.state(id)); get(Held.RUNS_STRIP + "?session=none", key); Thread.sleep(150); }
        long slept = System.currentTimeMillis() - t0;
        assertTrue(slept >= 1_000, "not before its time of nothing to do (1.5 s) was up: " + slept + " ms");
        until("its process gone and its lease given back", 10_000, () -> { try { return processes(lib).isEmpty() && store.placement(id).leaseUntil() == 0; } catch (IOException e) { return false; } });
        assertFalse(Files.exists(lock), "stopped by being asked to: its folder's lease is let go");
        p = store.placement(id);
        assertEquals(0, p.leaseUntil()); assertEquals("", p.address()); assertEquals(1, p.epoch());
        // asleep, the page's own asking is answered by the service with nothing, and wakes nothing
        for (int i = 0; i < 3; i++) { HttpResponse<String> strip = get(Held.RUNS_STRIP + "?session=none", key); assertEquals(200, strip.statusCode()); assertEquals("", strip.body()); }
        assertFalse(node.awake(id)); assertTrue(processes(lib).isEmpty());
        // a person's request starts it again and is answered: the registry's second start
        t0 = System.currentTimeMillis();
        HttpResponse<String> woke = get("/v1/status", key);
        System.out.println("[node] a request to a sleeping library was answered after " + (System.currentTimeMillis() - t0) + " ms");
        assertEquals(200, woke.statusCode(), woke.body()); assertEquals(id, M.readTree(woke.body()).path("library_id").asText());
        assertEquals(2, store.placement(id).epoch()); assertTrue(node.awake(id)); assertEquals(2, node.epoch(id));

        // a request on its way to the library keeps it up, however long nothing else is asked
        node.ensure(lib);
        Thread.sleep(3_000);
        assertTrue(node.awake(id), "a request is open"); assertFalse(node.sleep(id, "the test asks"), "and it is not stopped under it");
        assertEquals(2, node.state(id) == null ? 2 : store.placement(id).epoch());
        node.done(id);
        until("asleep once the request has ended", 15_000, () -> !node.awake(id));

        // the night's tasks, by the scheduler's word: the library is started for them and files them as a job
        node.night(lib);
        assertEquals(3, store.placement(id).epoch());
        Path jobs = Path.of(lib.folder()).resolve("library").resolve("catalog").resolve("jobs");
        until("the night's job on the library's ledger", 20_000, () -> {
            try (Stream<Path> s = Files.walk(jobs)) { return s.filter(f -> f.toString().endsWith(".json")).anyMatch(f -> { try { return Files.readString(f).contains("\"kind\":\"crews\"") || Files.readString(f).contains("\"kind\" : \"crews\""); } catch (IOException e) { return false; } }); }
            catch (IOException e) { return false; }
        });

        // the lease goes to another node (it ran out and was taken): this node stops the library, and says so to who asks
        until("awake for the night", 5_000, () -> node.awake(id));
        store.release(id, "node_test", 3);
        assertEquals(4, store.take(id, "node_elsewhere", 60_000));
        until("stopped, the lease being another node's", 10_000, () -> !node.awake(id) && processes(lib).isEmpty());
        HttpResponse<String> elsewhere = get("/v1/status", key);
        assertEquals(503, elsewhere.statusCode()); assertTrue(elsewhere.body().contains("is held by another machine of this service now"), elsewhere.body());
        assertEquals("node_elsewhere", store.placement(id).node()); assertEquals(4, store.placement(id).epoch(), "and it took nothing back");
    }

    /**
     * A process that serves a library's folder and that the node could not take back (it did not answer in time, or the registry lost
     * its address) is stopped before the library is started again: otherwise the new start could not take the folder's own lease, and
     * the library would not start at all (seen in the suite under load, 2026-11-17).
     */
    @Test
    void aProcessServingTheFolderThatWasNotTakenBackIsStoppedBeforeANewStart(@TempDir Path tmp) throws Exception {
        start(tmp);
        Node.SLEEP_AFTER_MS = 0L;
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        assertEquals(200, get("/v1/status", key).statusCode());
        long pid = processes(lib).get(0).pid();
        node.abandon();
        store.libraryAddress(id, "http://127.0.0.1:1");   // where nothing answers: it cannot be taken back
        Node again = new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_test", root);
        node = again;
        again.start();
        assertEquals(200, get("/v1/status", key).statusCode(), "started again, and answered");
        assertEquals(2, again.epoch(id));
        List<ProcessHandle> now = processes(lib);
        assertEquals(1, now.size(), "one process serves the folder"); assertNotEquals(pid, now.get(0).pid(), "the one that was not taken back is stopped");
    }

    /**
     * On a machine that gives no process's command line (Windows; anywhere without /proc where Java gives none), nothing can be proven,
     * so nothing is stopped by its number. An earlier start of this library by this service, which the node could not take back, does
     * not hold the library up: the next start takes its folder's lease at once, and the earlier one gives up by itself when it next
     * looks at its lease (0.8.0).
     */
    @Test
    void whereNothingCanBeProvenNothingIsStoppedAndALaterStartTakesTheFolderAtOnce(@TempDir Path tmp) throws Exception {
        start(tmp);
        Node.SLEEP_AFTER_MS = 0L;
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        assertEquals(200, get("/v1/status", key).statusCode());
        ProcessHandle earlier = processes(lib).get(0);
        node.abandon();
        store.libraryAddress(id, "http://127.0.0.1:1");   // it cannot be taken back
        ChildLibraries.READ_ARGUMENTS = false;
        try {
            Node again = new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_test", root);
            node = again;
            again.start();
            long t0 = System.currentTimeMillis();
            assertEquals(200, get("/v1/status", key).statusCode(), "the next start is answered");
            System.out.println("[node] with nothing proven, the next start answered after " + (System.currentTimeMillis() - t0) + " ms");
            assertEquals(2, again.epoch(id));
            assertTrue(earlier.isAlive(), "the earlier start was not stopped: nothing proved it was the one meant");
            until("the earlier start gives up by itself", 60_000, () -> !earlier.isAlive());
            assertEquals(200, get("/v1/status", key).statusCode());
        } finally { ChildLibraries.READ_ARGUMENTS = true; }
    }

    @Test
    void aServiceStartedAgainTakesItsLibrariesBackAndANewVersionStartsThemItself(@TempDir Path tmp) throws Exception {
        start(tmp);
        Node.SLEEP_AFTER_MS = 0L;   // nothing sleeps here: what is watched is the update
        // an older build of the program: the same classes, naming another version
        Path older = Files.createDirectories(tmp.resolve("older-build").resolve("org").resolve("researchzosho"));
        Files.writeString(older.resolve("version.txt"), "0.0.1\n", StandardCharsets.UTF_8);
        local.classpath = tmp.resolve("older-build") + File.pathSeparator + System.getProperty("java.class.path");
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        // a request of the person's is open on it until the service ends: this node starts an older build again at its first quiet
        // moment, and on a loaded machine that came before the end, so the start taken back was a later one (round G: start 3, the gates)
        node.ensure(lib);
        assertEquals(200, get("/v1/status", key).statusCode());
        String address = store.placement(id).address();
        assertEquals("0.0.1 (source tree)", health(address).path("version").asText(), "the library runs the older build");
        assertNotEquals("0.0.1 (source tree)", Version.string());
        Path repairs = Path.of(lib.folder()).resolve("library").resolve("catalog").resolve("repairs.md");
        assertTrue(Files.readString(repairs).contains("version: 0.0.1\n"), Files.readString(repairs));
        long pid = processes(lib).get(0).pid();

        // the service ends without stopping anything and is started again, on this build: the library is still running, and is its own again
        node.abandon();
        Node again = new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_test", root);
        again.ensure(lib);   // a person's request is on its way as it starts
        node = again;
        again.start();
        assertTrue(again.awake(id)); assertEquals(1, again.epoch(id), "taken back as the start it is");
        assertEquals(pid, processes(lib).get(0).pid(), "the same process");
        Thread.sleep(1_500);
        assertEquals(pid, processes(lib).get(0).pid(), "with a request open it is left as it is, older build or not");
        assertEquals(200, get("/v1/status", key).statusCode());
        again.done(id);
        // at its next quiet moment it is stopped and started by this build, and its repairs run at that start
        until("started again by this build", 30_000, () -> again.epoch(id) == 2 && again.awake(id));
        assertEquals(Version.string(), health(store.placement(id).address()).path("version").asText());
        assertNotEquals(pid, processes(lib).get(0).pid());
        String now = Version.number() == null ? "dev" : Version.number();
        until("the repairs of this version", 10_000, () -> { try { return Files.readString(repairs).contains("version: " + now + "\n"); } catch (IOException e) { return false; } });
        assertEquals(200, get("/v1/status", key).statusCode(), "and a person's next request is answered as before");
        Thread.sleep(1_000);
        assertEquals(2, again.epoch(id), "once: a library of this build is left alone");
    }
}
