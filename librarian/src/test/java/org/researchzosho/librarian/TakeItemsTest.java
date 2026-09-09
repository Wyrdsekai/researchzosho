package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The three items taken from the 2026-09-08 reference sweep: head+tail cut, one daemon per library, tool-true prompts. */
class TakeItemsTest {

    @Test
    void aLongObservationKeepsItsHeadAndItsTail() {
        String head = "HEAD-".repeat(1_000), tail = "-TAIL".repeat(1_000);
        String obs = head + "x".repeat(20_000) + tail;
        String cut = Researcher.cut(obs);
        assertTrue(cut.startsWith("HEAD-HEAD-"), "the opening survives");
        assertTrue(cut.endsWith("-TAIL-TAIL"), "the ending survives: the conclusion and the last error line live there");
        assertTrue(cut.contains("characters cut from the middle"), "the cut says what it removed");
        assertTrue(cut.length() < Researcher.OBSERVATION_CAP + 100);
        assertSame(head, Researcher.cut(head), "a short observation passes through untouched");
    }

    @Test
    void theWorkerRegisterNamesOnlyTheToolsTheAskOffers() throws Exception {
        Researcher r = new Researcher(null, new ResearcherTest.FakeTools(), null, 1);
        var m = Researcher.class.getDeclaredMethod("workerRegister", Researcher.Ask.class);
        m.setAccessible(true);
        String shelvesOnly = (String) m.invoke(r, new Researcher.Ask("q", "broad", 10, List.of(), "shelves", List.of()));
        assertFalse(shelvesOnly.contains("web_search"), "the web is closed: web_search must not be named\n" + shelvesOnly);
        assertFalse(shelvesOnly.contains("web_fetch"), "the web is closed: web_fetch must not be named\n" + shelvesOnly);
        assertFalse(shelvesOnly.contains("shelf_search"), "no store: shelf_search is not offered either");
        assertTrue(shelvesOnly.contains("read_pages") && shelvesOnly.contains("Finish with done"));
        String both = (String) m.invoke(r, new Researcher.Ask("q", "depth", 10, List.of()));
        assertTrue(both.contains("web_search") && both.contains("web_fetch") && both.contains("paywalled"));
    }

    @Test
    void oneDaemonPerLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon first = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            Lease.Holder h = Lease.holder(store);
            assertNotNull(h); assertEquals(ProcessHandle.current().pid(), h.pid());
            // a second daemon on the same library while the first is alive: refused, naming the holder
            IOException e = assertThrows(IOException.class, () -> LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1));
            assertTrue(e.getMessage().contains("pid " + h.pid()), e.getMessage());
            assertTrue(Files.exists(Lease.file(store)), "the refused start must not delete the live holder's lease");
        } finally { first.stop(); }
        assertFalse(Files.exists(Lease.file(store)), "stop releases the lease");

        // a lease left by a daemon that died: the pid is gone, so the next start takes over
        Files.writeString(Lease.file(store), "999999999\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(600) + "\n");
        LibrarianDaemon second = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try { assertEquals(ProcessHandle.current().pid(), Lease.holder(store).pid()); } finally { second.stop(); }

        // a lease from another host cannot be pid-checked: it holds until it expires, then is taken over
        Files.writeString(Lease.file(store), "4242\tsome-other-box\t" + Instant.now().plusSeconds(600) + "\n");
        assertThrows(IOException.class, () -> LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1));
        Files.writeString(Lease.file(store), "4242\tsome-other-box\t" + Instant.now().minusSeconds(1) + "\n");
        LibrarianDaemon third = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try { assertEquals(ProcessHandle.current().pid(), Lease.holder(store).pid()); } finally { third.stop(); }
    }
}
