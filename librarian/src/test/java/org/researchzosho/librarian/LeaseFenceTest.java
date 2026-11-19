package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library stops before anyone else may take its lease (0.8.0 review, round B, ROB 8): its own fence ends two thirds of a lease after
 * it last heard "still yours" (by the monotonic clock, and by the wall clock, which goes on across a machine that slept); and its
 * node says "still yours" only after a renewal of the lease that succeeded that round.
 */
class LeaseFenceTest {
    long heldLease = Held.LEASE_MS, nodeLease = Node.LEASE_MS, renew = Node.RENEW_MS;
    AccountStore store; HttpServer library;

    @AfterEach void back() {
        Held.LEASE_MS = heldLease; Node.LEASE_MS = nodeLease; Node.RENEW_MS = renew; Held.WALL = System::currentTimeMillis;
        if (library != null) library.stop(0);
        if (store != null) store.close();
    }

    @Test
    void theLibrarysFenceEndsTwoThirdsOfALeaseAfterItLastHeard() throws Exception {
        Held.LEASE_MS = 3_000;
        Held h = new Held(1);
        h.heard();
        assertFalse(h.silent());
        Thread.sleep(2_200);   // past two thirds of the lease, before the whole of it
        assertTrue(h.silent(), "fenced before the store's lease can run out");
        h.heard();
        assertFalse(h.silent());
        // the machine slept: the monotonic clock did not move, the wall clock did
        AtomicLong wall = new AtomicLong(System.currentTimeMillis());
        Held.WALL = wall::get;
        h.heard();
        wall.addAndGet(60_000);
        assertTrue(h.silent(), "a machine that slept through its lease does not go on writing");
    }

    /** A node whose library is a stand-in that counts the "still yours" it is told. */
    @Test
    void theNodeSaysStillYoursOnlyAfterARenewalThatSucceeded(@TempDir Path tmp) throws Exception {
        Node.LEASE_MS = 30_000; Node.RENEW_MS = 100;
        AtomicInteger told = new AtomicInteger();
        library = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        library.createContext(Held.STILL_YOURS, x -> {
            x.getRequestBody().readAllBytes();
            told.incrementAndGet();
            byte[] b = "{\"version\":\"x\",\"open\":1}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        library.start();
        String address = "http://127.0.0.1:" + library.getAddress().getPort();
        Path root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
        Node node = new Node(store, "http://127.0.0.1:1", new Standing(address), "node_fence", root);
        node.start();
        try {
            node.ensure(lib);
            node.done(lib.id());
            long until = System.currentTimeMillis() + 5_000;
            while (told.get() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertTrue(told.get() >= 2, "told while its lease is renewed");
            // the store stops answering the renewals (it is down, or cut off): nothing more is said to the library
            node.renewer = (l, n, e, ms) -> { throw new IOException("the store does not answer"); };
            Thread.sleep(300);
            int before = told.get();
            Thread.sleep(800);
            assertEquals(before, told.get(), "no \"still yours\" after a renewal that failed");
        } finally { node.stopAll(); }
    }

    /** A runner whose every library is the one stand-in at {@code address}, always alive. */
    static class Standing implements LibraryRunner.Local {
        final String address;
        Standing(String address) { this.address = address; }
        @Override public String start(AccountStore.Library lib, long epoch) { return address; }
        @Override public boolean alive(String libraryId, long epoch) { return true; }
        @Override public void stop(String libraryId) { }
        @Override public boolean adopt(AccountStore.Library lib, String address, long epoch) { return false; }
        @Override public String ensure(AccountStore.Library lib) { return address; }
        @Override public void lost(String libraryId) { }
        @Override public void stopAll() { }
    }
}
