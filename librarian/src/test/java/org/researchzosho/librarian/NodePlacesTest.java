package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Places with the node running real libraries (0.8.0): a library's copy is brought up to date at its first quiet moment, after each
 * job it ends (its night is a job), and as it goes to sleep, then with its index. A command takes a library out of service while it
 * runs: the node stops it, and a move to another root, or a delete, goes on; a request in the meantime is told the library is being
 * moved, and after a delete that it is no longer served.
 */
class NodePlacesTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, soon = Node.DUE_SOON_MS;
    AccountStore store; AccountService svc; Node node; Path root;

    void start(Path tmp) throws Exception {
        Node.LEASE_MS = 4_000; Node.RENEW_MS = 250; Node.SLEEP_AFTER_MS = 0L; Node.DUE_SOON_MS = 0;
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        store = AccountService.openStore();
        svc = AccountService.start(store, "127.0.0.1", 0, self -> node = new Node(store, self, new ChildLibraries(store, self), "node_test", root));
        node.start();
    }

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); }
        finally {
            Node.LEASE_MS = lease; Node.RENEW_MS = renew; Node.SLEEP_AFTER_MS = null; Node.DUE_SOON_MS = soon; LibraryPlaces.COPY_AFTER_MS = null; LibraryPlaces.STOP_WAIT_MS = 60_000;
            Config.set(AccountService.ROOT, ""); Config.set(LibraryPlaces.COPIES, "");
        }
    }

    HttpResponse<String> get(String path, String key) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()); }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(50); }
    }

    static LibraryPlaces.Copy copy(String id) { try { return LibraryPlaces.lastCopy(id); } catch (IOException e) { return null; } }

    static boolean holds(Path copy, String endsWith) {
        try (Stream<Path> s = Files.walk(copy)) { return s.anyMatch(p -> p.toString().replace('\\', '/').endsWith(endsWith)); } catch (IOException e) { return false; }
    }

    @Test
    void aCopyIsBroughtUpToDateAtAQuietMomentAfterEachJobAndAsTheLibrarySleeps(@TempDir Path tmp) throws Exception {
        start(tmp);
        LibraryPlaces.COPY_AFTER_MS = 600_000L;
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        assertEquals(200, get("/v1/status", key).statusCode());
        // no copy yet: the first quiet moment makes one, while the library runs (its index is not in it)
        until("a first copy", 15_000, () -> copy(id) != null);
        LibraryPlaces.Copy first = copy(id);
        assertFalse(first.stopped()); assertEquals(1, first.epoch()); assertEquals(first.ended(), store.placement(id).copiedAt());
        Path files = LibraryPlaces.copy(id).resolve("files");
        assertTrue(Files.isRegularFile(files.resolve("library").resolve("catalog").resolve("library.md")));
        assertFalse(Files.exists(files.resolve("library").resolve(".index")), "while it runs, no index");
        Thread.sleep(1_500);
        assertEquals(first.ended(), copy(id).ended(), "nothing more is asked and the copy is younger than the setting's minutes: no new pass");
        // a job: its night. When it has ended, the copy holds its end
        node.night(lib);
        until("a copy after the job ended", 60_000, () -> { LibraryPlaces.Copy now = copy(id); return now != null && now.ended() > first.ended() && holds(files.resolve("library").resolve("catalog").resolve("jobs").resolve("done"), ".json"); });
        assertFalse(copy(id).stopped());
        // it sleeps: stopped, and the copy is brought up to date with its index before its lease is given back
        long before = copy(id).ended();
        Node.SLEEP_AFTER_MS = 1_000L;
        until("asleep", 20_000, () -> !node.awake(id));
        until("the lease given back", 10_000, () -> { try { return store.placement(id).leaseUntil() == 0; } catch (IOException e) { return false; } });
        LibraryPlaces.Copy asleep = copy(id);
        assertTrue(asleep.ended() > before && asleep.stopped(), asleep.toString());
        assertTrue(Files.isDirectory(files.resolve("library").resolve(".index")), "stopped, the index comes too");
        assertFalse(holds(files, "serve.lock"), "never the folder's own lease");
        // the folder is damaged; the library is brought back from its copy by the command, and its page and its status say which copy
        Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve("after-the-copy.md"), "written after the last copy");
        String r = LibraryPlacesTest.run("x", "accounts", "restore", lib.name());
        assertTrue(r.startsWith("0\n") && r.contains("The library Ann Example is brought back from its copy of " + asleep.when() + "."), r);
        assertFalse(Files.exists(LibraryPlaces.library(lib).resolve("raw").resolve("after-the-copy.md")));
        HttpResponse<String> status = get("/v1/status", key);
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(M.readTree(status.body()).path("brought_back").path("note").asText().startsWith("This library was brought back from its copy of "), status.body());
        HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/")).header("Authorization", "Bearer " + key).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(page.body().contains("<p class=\"notice\">This library was brought back from its copy of "), "the library's page says so");
    }

    @Test
    void aLibraryTakenOutOfServiceWhileItRunsIsStoppedAndMovedOrRemoved(@TempDir Path tmp) throws Exception {
        start(tmp);
        Config.set(LibraryPlaces.COPIES, "");
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library lib = ann.library();
        String id = lib.id(), key = store.mintKey(ann.account().id(), id, "write", "the test").secret();
        assertEquals(200, get("/v1/status", key).statusCode());
        assertTrue(node.awake(id));
        // while it is out of service for a moment, whoever asks is told so
        store.libraryState(id, "moving");
        HttpResponse<String> moving = get("/v1/status", key);
        assertEquals(503, moving.statusCode()); assertTrue(moving.body().contains("The library Ann Example is being moved. Ask again in a minute."), moving.body());
        until("stopped by the node", 15_000, () -> !node.awake(id));
        store.libraryState(id, "active");
        assertEquals(200, get("/v1/status", key).statusCode());
        assertEquals(2, store.placement(id).epoch(), "started again: its second start");
        // moved to another root by the command while it runs: the node stops it, the folder goes, the next request is served from there
        Path other = tmp.resolve("second-disk");
        String r = LibraryPlacesTest.run("x", "accounts", "move", lib.name(), other.toString());
        assertTrue(r.startsWith("0\n") && r.contains("The library \"Ann Example\" is at " + other.resolve("libraries").resolve(lib.name()).toAbsolutePath().normalize() + " now"), r);
        assertTrue(r.contains("Its old folder is set aside at "), r);
        assertEquals(200, get("/v1/status", key).statusCode());
        AccountStore.Library moved = store.library(id);
        assertTrue(moved.folder().startsWith(other.toAbsolutePath().normalize().toString()), moved.folder());
        assertTrue(Files.exists(LibraryPlaces.library(moved).resolve("catalog").resolve("serve.lock")), "the library runs in its new folder");
        assertFalse(Files.exists(LibraryPlaces.folder(lib)));
        // removed by the command while it runs: stopped, set aside, and told as such from then on
        r = LibraryPlacesTest.run("x", "accounts", "delete", lib.name());
        assertTrue(r.startsWith("0\n") && r.contains("is out of service"), r);
        assertFalse(node.awake(id));
        HttpResponse<String> gone = get("/v1/status", key);
        assertEquals(403, gone.statusCode()); assertTrue(gone.body().contains("The library Ann Example is no longer served here."), gone.body());
        assertNotNull(store.library(id));
    }
}
