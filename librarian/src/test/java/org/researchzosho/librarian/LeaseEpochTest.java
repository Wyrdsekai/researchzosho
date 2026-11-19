package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The folder's own lease and the start it is held for (0.8.0): a lease written by a start of a library under an account service names
 * the start (the registry's epoch) and the service. A later start of the same library by the same service takes it at once from a
 * holder that still lives, frozen or cut off, instead of waiting five minutes; the earlier start finds it taken when it next looks. A
 * lease of another service, of the same start, or of a library of a person's own is not taken while it lives.
 */
class LeaseEpochTest {
    static final String SERVICE = "http://10.0.0.2:4649";

    static void held(LibraryStore store, String line) throws IOException {
        Path f = Lease.file(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void aLaterStartOfTheSameServiceTakesTheLeaseOfALiveEarlierOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        long me = ProcessHandle.current().pid();   // a holder that lives: this process
        String until = Instant.now().plusSeconds(300).toString();
        held(store, me + "\t" + Lease.hostName() + "\t" + until + "\t1\t" + SERVICE);
        Lease.Holder h = Lease.holder(store);
        assertEquals(1, h.epoch()); assertEquals(SERVICE, h.service()); assertTrue(h.live());
        // not taken: by another service, by the same start, by a library of a person's own
        for (String[] refused : new String[][]{{"2", "http://10.0.0.9:4649"}, {"1", SERVICE}, {"0", ""}}) {
            IOException e = assertThrows(IOException.class, () -> Lease.acquire(store, Long.parseLong(refused[0]), refused[1]));
            assertTrue(e.getMessage().startsWith("another daemon is serving this library"), e.getMessage());
        }
        // taken: the next start of it by the same service; the lease names it now
        try (Lease taken = Lease.acquire(store, 2, SERVICE)) {
            Lease.Holder now = Lease.holder(store);
            assertEquals(2, now.epoch()); assertEquals(SERVICE, now.service());
        }
        // a lease of before 0.8.0, or of a person's own library, says no start: not taken while it lives
        held(store, me + "\t" + Lease.hostName() + "\t" + until);
        assertEquals(0, Lease.holder(store).epoch());
        assertThrows(IOException.class, () -> Lease.acquire(store, 5, SERVICE));
        // a lease of another machine is taken by the next start when it is the same service's earlier start
        held(store, "4242\tanother-machine\t" + until + "\t3\t" + SERVICE);
        try (Lease taken = Lease.acquire(store, 4, SERVICE)) { assertEquals(4, Lease.holder(store).epoch()); }
    }
}
