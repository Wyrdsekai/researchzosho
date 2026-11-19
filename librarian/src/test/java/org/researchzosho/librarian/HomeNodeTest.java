package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library runs on the node that holds its folder, as the store writes it down (0.8.0 review, round B, ROB 3), never on another node
 * whose root happens to be the same path (the docs' own example gives every machine /srv/rz). Two nodes here have the same root path:
 * the library is served by the one it is written down for; the other refuses it; when that one falls silent the library is brought
 * from its copy to the other, which it is written down for from then on; and the first, back, serves it no more.
 */
class HomeNodeTest {
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS;
    Long takeover = Nodes.TAKEOVER_AFTER_MS;
    AccountStore store;
    final List<Node> nodes = new ArrayList<>();
    final List<NodeServer> servers = new ArrayList<>();

    @AfterEach void back() throws Exception {
        for (Node n : nodes) n.stopAll();
        for (NodeServer s : servers) s.stop();
        if (store != null) store.close();
        Node.LEASE_MS = lease; Node.RENEW_MS = renew; Nodes.TAKEOVER_AFTER_MS = takeover;
        Config.set(LibraryPlaces.COPIES, "");
    }

    /** A runner that answers at an address naming its node, and remembers what it started. */
    static final class Marked extends LeaseFenceTest.Standing {
        final List<String> started = new ArrayList<>();
        Marked(String node) { super("http://127.0.0.1:1/" + node); }
        @Override public String start(AccountStore.Library lib, long epoch) { started.add(lib.id()); return super.start(lib, epoch); }
    }

    Node node(String id, Path root, Marked runner, String issuer) throws IOException {
        Node n = new Node(store, issuer, runner, id, root);
        servers.add(NodeServer.start(n, store, issuer, "127.0.0.1", 0));
        n.start();
        nodes.add(n);
        return n;
    }

    @Test
    void aLibraryRunsOnTheNodeItIsWrittenDownForAndMovesWithItsCopy(@TempDir Path tmp) throws Exception {
        Node.LEASE_MS = 1_500; Node.RENEW_MS = 200; Nodes.TAKEOVER_AFTER_MS = 0L;
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        Path root = tmp.resolve("srv-rz");   // the same path on both machines
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        String issuer = "http://127.0.0.1:1";
        Marked onA = new Marked("node_a"), onB = new Marked("node_b");
        Node a = node("node_a", root, onA, issuer), b = node("node_b", root, onB, issuer);
        // the library is made on node B's disk (its row says so), in the folder that path names on both machines
        AccountStore.Account ann = store.createAccount("Ann Example", "", false);
        Node.Made made = b.make("ann-example", "Ann Example");
        store.addLibrary(made.id(), "ann-example", "Ann Example", ann.id(), made.folder());
        store.libraryHome(made.id(), "node_b");
        AccountStore.Library lib = store.library(made.id());
        new LibraryPlaces(store).bringUp(lib, 0, true);   // and it has a copy

        Nodes service = new Nodes(store, issuer, null);
        assertTrue(service.ensure(lib).endsWith("/node_b"), "served by the node it is written down for");
        service.done(lib.id());
        assertThrows(Node.NotHere.class, () -> a.ensure(lib), "the other node, whose root is the same path, refuses it");
        assertEquals(List.of(), onA.started);

        // node B falls silent: the library is brought from its copy to node A, written down for A from then on
        b.stopAll();
        servers.get(1).stop();
        Thread.sleep(Node.LEASE_MS + 600);
        assertTrue(service.ensure(lib).endsWith("/node_a"), "brought to the live node");
        service.done(lib.id());
        assertEquals("node_a", store.libraryHome(lib.id()));
        assertEquals(List.of(lib.id()), onA.started);
        // node B is back: it serves it no more
        Node back = node("node_b", root, new Marked("node_b"), issuer);
        assertThrows(Node.NotHere.class, () -> back.ensure(store.library(lib.id())));
        assertTrue(Files.isDirectory(LibraryPlaces.library(store.library(lib.id()))));
    }
}
