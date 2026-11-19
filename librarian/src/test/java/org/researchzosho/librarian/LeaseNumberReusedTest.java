package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A lease names its holder by a process number, and the system gives a dead holder's number to the next process it starts. That process
 * is no holder: it started after the lease was written (round G, the Windows pass of 0.8.0: the pages server was killed by its number,
 * the number went to another process within seconds, and the service's server, started next, read the lease as held by a live process
 * and refused to serve for the five minutes of the lease; "the service serves 0.8.0" and "came back serving 0.8.1" both failed).
 */
class LeaseNumberReusedTest {

    static void lease(LibraryStore store, long pid, Instant written) throws IOException {
        Path f = Lease.file(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, pid + "\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(300) + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.from(written));
    }

    static Instant thisProcessStarted() { return ProcessHandle.current().info().startInstant().orElseThrow(); }

    @Test
    void aLeaseOlderThanTheProcessItsNumberNowNamesIsFree(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        long me = ProcessHandle.current().pid();
        // the number the dead holder wrote is now this process's own, as for the service's server on Windows
        lease(store, me, thisProcessStarted().minusSeconds(60));
        try (Lease l = Lease.acquire(store)) {
            assertTrue(l.held(), "the lease of a process that ended before this one started is taken");
            assertTrue(Files.readString(Lease.file(store)).startsWith(me + "\t"));
        }
        // written after this process started: this process wrote it, and a second daemon in it is refused, as before
        lease(store, me, Instant.now());
        IOException e = assertThrows(IOException.class, () -> Lease.acquire(store));
        assertTrue(e.getMessage().contains("another daemon is serving this library: pid " + me), e.getMessage());
    }

    @Test
    void anotherProcessThatGotTheNumberAfterTheLeaseWasWrittenHoldsNothing(@TempDir Path tmp) throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"), "a child that waits: sleep");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Instant before = Instant.now().minusSeconds(120);
        Process other = new ProcessBuilder("sleep", "60").start();
        try {
            long pid = other.pid();
            lease(store, pid, before);   // its number in a lease written before it started
            Lease.Holder h = Lease.holder(store);
            assertEquals(pid, h.pid());
            assertEquals(false, h.live(), "a live process that started after the lease was written is not its holder");
            try (Lease l = Lease.acquire(store)) {
                assertTrue(l.held());
                // the holder looks again and finds the number of a process younger than the file: still its own library, the file written again
                lease(store, pid, before);
                assertNull(l.check(), "not lost to a process that got the number later");
                assertTrue(Files.readString(Lease.file(store)).startsWith(ProcessHandle.current().pid() + "\t"), "written again as this process's");
                // the same process named in a lease written after it started is a live holder: the library is lost to it, as before
                lease(store, pid, Instant.now());
                assertEquals(pid, l.check().pid());
            }
            lease(store, pid, Instant.now());
            assertThrows(IOException.class, () -> Lease.acquire(store), "a live holder that wrote its lease is refused, as before");
        } finally { other.destroyForcibly(); other.waitFor(); }
    }
}
