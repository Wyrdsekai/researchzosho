package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request the service routes to a library on a node of its own machine is going on there until it has ended (0.8.0 review, round
 * B, ROB 14): the node does not put the library to sleep under it, and lets it sleep once the service says the request ended.
 */
class RemoteRequestTest {
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS;
    AccountStore store; NodeServer server; Node node;

    @AfterEach void back() {
        Node.LEASE_MS = lease; Node.RENEW_MS = renew;
        if (node != null) node.stopAll();
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    @Test
    void aRoutedRequestKeepsTheLibraryAwakeOnItsNodeUntilItEnds(@TempDir Path tmp) throws Exception {
        Node.LEASE_MS = 30_000; Node.RENEW_MS = 60_000;
        Path root = tmp.resolve("node-root");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
        String issuer = "http://127.0.0.1:1";
        node = new Node(store, issuer, new LeaseFenceTest.Standing("http://127.0.0.1:1"), "node_remote", root);
        server = NodeServer.start(node, store, issuer, "127.0.0.1", 0);
        node.start();
        Nodes nodes = new Nodes(store, issuer, null);   // the service, which runs no node of its own
        nodes.ensure(lib);   // a request routed to the library, still being answered
        assertFalse(node.sleep(lib.id(), "the test"), "the node does not stop a library a request is on its way to");
        nodes.done(lib.id());   // the service says it ended
        long until = System.currentTimeMillis() + 5_000;
        boolean slept = false;
        while (!slept && System.currentTimeMillis() < until) { slept = node.sleep(lib.id(), "the test"); if (!slept) Thread.sleep(50); }
        assertTrue(slept, "and stops it once the request ended");
    }
}
