package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library's folder is changed by the machine that holds it (0.8.0 review, round B, ROB 4 = BILL 6): delete (with its grace or at
 * once), move, restore and release of a library on a node's disk are done by that node, through its own call; what is set aside is
 * written down for that node, and swept there; and a library whose folder this machine cannot see, and no node holds, is refused
 * before anything changes, never marked removed here.
 */
class PlacesOnTheNodeTest {
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, grace = LibraryPlaces.GRACE_MS, wait = LibraryPlaces.STOP_WAIT_MS;
    AccountStore store; Node node; NodeServer server;

    @AfterEach void back() throws Exception {
        if (node != null) node.stopAll();
        if (server != null) server.stop();
        if (store != null) store.close();
        Node.LEASE_MS = lease; Node.RENEW_MS = renew; LibraryPlaces.GRACE_MS = grace; LibraryPlaces.STOP_WAIT_MS = wait;
        Config.set(AccountService.ROOT, ""); Config.set(AccountService.SERVICE, ""); Config.set(LibraryPlaces.COPIES, "");
    }

    static String said(ByteArrayOutputStream o) { return o.toString(StandardCharsets.UTF_8); }

    /** A node of its own (the second machine), with a library made on its disk. */
    AccountStore.Library onTheNode(Path tmp, String name) throws Exception {
        AccountStore.Account a = store.createAccount(name, "", false);
        Node.Made m = node.make(a.login().replace(' ', '-'), name);
        store.addLibrary(m.id(), a.login().replace(' ', '-'), name, a.id(), m.folder());
        store.libraryHome(m.id(), node.id());
        Files.writeString(LibraryPlaces.library(Path.of(m.folder())).resolve("raw").resolve("a.md"), "a page\n");
        return store.library(m.id());
    }

    void start(Path tmp) throws Exception {
        Node.LEASE_MS = 3_000; Node.RENEW_MS = 200; LibraryPlaces.STOP_WAIT_MS = 5_000;
        Config.set(AccountService.ROOT, tmp.resolve("service-root").toString());   // the service's machine: no library of the node's is under it
        Config.set(AccountService.SERVICE, "http://127.0.0.1:1");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        node = new Node(store, "http://127.0.0.1:1", new LeaseFenceTest.Standing("http://127.0.0.1:1"), "node_second", tmp.resolve("node-root"));
        server = NodeServer.start(node, store, "http://127.0.0.1:1", "127.0.0.1", 0);
        node.start();
    }

    @Test
    void theNodeThatHoldsALibraryDeletesMovesAndReleasesIt(@TempDir Path tmp) throws Exception {
        start(tmp);
        // deleted with its grace, from the service's command line: set aside on the node's disk, written down for the node
        AccountStore.Library ann = onTheNode(tmp, "Ann Example");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        AccountsCli.places(store, ann, "delete", null, false, new PrintStream(o, true, StandardCharsets.UTF_8));
        assertEquals("removed", store.library(ann.id()).state());
        assertEquals(1, store.asides(ann.id()).size(), said(o));
        AccountStore.Aside set = store.asides(ann.id()).get(0);
        assertTrue(set.path().startsWith(tmp.resolve("node-root").toString()) && Files.isDirectory(Path.of(set.path())), "set aside under the node's root: " + set.path());
        assertEquals("node_second", store.asideNode(set.path()), "written down for the node whose disk holds it");
        assertTrue(said(o).contains("is out of service"), said(o));
        // its grace over: the service's sweep leaves the node's disk alone; the node's own removes it
        LibraryPlaces.GRACE_MS = 0;
        store.aside(set.path(), ann.id(), "removed", System.currentTimeMillis() - 1, "node_second");
        assertEquals(0, new LibraryPlaces(store, "").sweep(), "not the service machine's to remove");
        assertTrue(Files.isDirectory(Path.of(set.path())));
        assertEquals(1, new LibraryPlaces(store, "node_second").sweep());
        assertFalse(Files.exists(Path.of(set.path())));

        // moved under another root of the node's machine, then released to a folder there
        AccountStore.Library bo = onTheNode(tmp, "Bo Example");
        Path other = tmp.resolve("node-disk2");
        AccountsCli.places(store, bo, "move", other, false, new PrintStream(new ByteArrayOutputStream()));
        assertTrue(store.library(bo.id()).folder().startsWith(other.toString()), store.library(bo.id()).folder());
        assertEquals("active", store.library(bo.id()).state());
        Path harbour = tmp.resolve("node-harbour");
        AccountsCli.places(store, store.library(bo.id()), "release", harbour, false, new PrintStream(new ByteArrayOutputStream()));
        assertEquals("released", store.library(bo.id()).state());
        assertTrue(Files.isRegularFile(harbour.resolve("raw").resolve("a.md")));

        // deleted with its grace, and taken back within it, on the node whose disk holds what was set aside
        LibraryPlaces.GRACE_MS = grace;   // its days of grace: the node's sweep leaves it alone meanwhile
        AccountStore.Library eve = onTheNode(tmp, "Eve Example");
        AccountsCli.places(store, eve, "delete", null, false, new PrintStream(new ByteArrayOutputStream()));
        String setAside = store.asides(eve.id()).stream().filter(x -> x.why().equals("removed")).findFirst().orElseThrow().path();
        String back = AccountsCli.takeBackOnNode(store, LibraryPlaces.library(Path.of(setAside)));
        assertTrue(back != null && back.contains("is taken back"), String.valueOf(back));
        assertEquals("active", store.library(eve.id()).state());
        assertEquals("node_second", store.libraryHome(eve.id()));
        assertTrue(Files.isRegularFile(LibraryPlaces.library(store.library(eve.id())).resolve("raw").resolve("a.md")));

        // removed at once (an account's removal does this)
        AccountStore.Library cat = onTheNode(tmp, "Cat Example");
        Path folder = LibraryPlaces.folder(cat);
        AccountsCli.places(store, cat, "delete", null, true, new PrintStream(new ByteArrayOutputStream()));
        assertFalse(Files.exists(folder), "removed from the node's disk");
        assertEquals("removed", store.library(cat.id()).state());
    }

    /** A move between machines: brought from its copy onto the machine whose root is named, the copy fetched from the one that holds it. */
    @Test
    void aLibraryIsMovedOntoAnotherMachineFromItsCopy(@TempDir Path tmp) throws Exception {
        start(tmp);
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        Node other = new Node(store, "http://127.0.0.1:1", new LeaseFenceTest.Standing("http://127.0.0.1:1"), "node_third", tmp.resolve("third-root"));
        NodeServer otherServer = NodeServer.start(other, store, "http://127.0.0.1:1", "127.0.0.1", 0);
        other.start();
        try {
            AccountStore.Library fay = onTheNode(tmp, "Fay Example");
            Path was = LibraryPlaces.folder(fay);
            // a fourth machine with the same root path (the doc's own example): the path names no one machine, its id does
            store.nodeSeen("node_fourth", "http://127.0.0.1:1", tmp.resolve("third-root").toString(), "test");
            IOException same = assertThrows(IOException.class, () -> AccountsCli.places(store, fay, "restore", tmp.resolve("third-root"), false, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
            assertTrue(same.getMessage().contains("name the machine by its id"), same.getMessage());
            assertEquals("active", store.library(fay.id()).state(), "nothing was changed");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            AccountsCli.places(store, fay, "restore", Path.of("node_third"), false, new PrintStream(o, true, StandardCharsets.UTF_8));
            AccountStore.Library now = store.library(fay.id());
            assertEquals("node_third", store.libraryHome(fay.id()), said(o));
            assertTrue(said(o).contains("Its folder on node_second is set aside at "), "the command says what became of the folder on the machine it left: " + said(o));
            assertEquals("active", now.state());
            assertTrue(now.folder().startsWith(tmp.resolve("third-root").toString()), now.folder());
            assertEquals("a page\n", Files.readString(LibraryPlaces.library(now).resolve("raw").resolve("a.md")));
            assertFalse(Files.exists(was), "the folder it left on the other machine is set aside there");
            assertTrue(store.asides(fay.id()).stream().anyMatch(a -> { try { return store.asideNode(a.path()).equals("node_second"); } catch (IOException e) { return false; } }), "written down for that machine");
        } finally { other.stopAll(); otherServer.stop(); }
    }

    /** A take on a service that runs no library itself (round C, round B's run): the archive goes to a node, which takes it in on its disk. */
    @Test
    void anArchiveTakenInOnAServiceThatRunsNoLibraryIsTakenInOnANode(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountStore.Library gil = onTheNode(tmp, "Gil Example");
        Path archive = tmp.resolve("gil.rzlib.zip");
        try (var o = Files.newOutputStream(archive)) { LibraryArchive.write(o, LibraryPlaces.folder(gil), gil, ""); }
        AccountsCli.places(store, gil, "delete", null, true, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals("removed", store.library(gil.id()).state());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        AccountsCli.take(store, archive, null, new PrintStream(o, true, StandardCharsets.UTF_8));
        AccountStore.Library back = store.library(gil.id());
        assertEquals("active", back.state(), said(o));
        assertEquals("node_second", store.libraryHome(gil.id()), "written down for the node that took it in");
        assertTrue(back.folder().startsWith(tmp.resolve("node-root").toString()), "under the node's root, not the service's: " + back.folder());
        assertEquals("a page\n", Files.readString(LibraryPlaces.library(back).resolve("raw").resolve("a.md")));
    }

    /** A library taken back within its grace (round C, round B's run): what is left of the folder it was set aside in goes too. */
    @Test
    void aLibraryTakenBackLeavesNothingOfWhereItWasSetAside(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountStore.Library hal = onTheNode(tmp, "Hal Example");
        Files.createDirectories(LibraryPlaces.home(LibraryPlaces.folder(hal)));
        Files.writeString(LibraryPlaces.home(LibraryPlaces.folder(hal)).resolve("latest-version.txt"), "0.8.0\n");
        Files.writeString(LibraryPlaces.folder(hal).resolve("serve.log"), "a line\n");
        AccountsCli.places(store, hal, "delete", null, false, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        AccountStore.Aside set = store.asides(hal.id()).stream().filter(a -> a.path().contains("/aside/")).findFirst().orElseThrow();
        String said = AccountsCli.takeBackOnNode(store, LibraryPlaces.library(Path.of(set.path())));
        assertEquals("active", store.library(hal.id()).state(), said);
        assertFalse(Files.exists(Path.of(set.path())), "nothing left of the folder it was set aside in");
        assertTrue(store.asides(hal.id()).stream().noneMatch(a -> a.path().equals(set.path())), "nor its record");
    }

    /** A copy's time says its zone (round C, round B's run: a service in a container said UTC's hour as if it were the reader's). */
    @Test
    void aCopysTimeSaysItsZone() {
        LibraryPlaces.Copy c = new LibraryPlaces.Copy("lib_x", 1_795_000_000_000L, 1_795_000_060_000L, 1, true, 3, 300, 3, 0, false);
        assertTrue(c.when().matches("\\d{1,2} \\w+ \\d{4}, \\d{2}:\\d{2} \\S+"), c.when());
    }

    /** A request to a library whose machine stopped waits out its lease, and another machine that reaches its folder serves it (round C). */
    @Test
    void aLibraryWhoseMachineStoppedIsServedByAnotherThatReachesItsFolder(@TempDir Path tmp) throws Exception {
        start(tmp);
        Node other = new Node(store, "http://127.0.0.1:1", new LeaseFenceTest.Standing("http://127.0.0.1:1"), "node_third", tmp.resolve("third-root"));
        NodeServer otherServer = NodeServer.start(other, store, "http://127.0.0.1:1", "127.0.0.1", 0);
        other.start();
        try {
            AccountStore.Library ivy = onTheNode(tmp, "Ivy Example");
            Files.writeString(tmp.resolve("node-root").resolve("node.id"), "node_second\n");   // its disk's own mark, as a node writes it there; the other machine sees that disk
            Nodes nodes = new Nodes(store, "http://127.0.0.1:1", null);   // the service, which runs no node of its own
            nodes.ensure(ivy);
            nodes.done(ivy.id());
            assertEquals("node_second", store.placement(ivy.id()).node());
            server.stop(); node.abandon();   // its machine stops: no answer, and its lease left to run out
            server = null; node = null;
            long t0 = System.currentTimeMillis();
            String at = nodes.ensure(store.library(ivy.id()));
            assertTrue(at != null && !at.isEmpty(), "served");
            assertEquals("node_third", store.placement(ivy.id()).node(), "by the machine that reaches its folder, after " + (System.currentTimeMillis() - t0) + " ms");
        } finally { other.stopAll(); otherServer.stop(); }
    }

    @Test
    void aLibraryThisMachineCannotSeeAndNoNodeHoldsIsNeverMarkedRemoved(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountStore.Account a = store.createAccount("Dee Example", "", false);
        store.addLibrary("lib_00000000000000dd", "dee-example", "Dee Example", a.id(), tmp.resolve("elsewhere").resolve("libraries").resolve("dee-example").toString());
        AccountStore.Library dee = store.library("lib_00000000000000dd");
        IOException e = assertThrows(IOException.class, () -> AccountsCli.places(store, dee, "delete", null, false, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains("is not on this machine"), e.getMessage());
        assertEquals("active", store.library(dee.id()).state(), "nothing changed");
        assertTrue(store.asides(dee.id()).isEmpty());
    }
}
