package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packing an asleep library with copies kept (0.8.0 review, round B, ROB 9): its copy is brought up to date with its search index,
 * which is right only while nothing runs it, so a request that wakes it meanwhile waits until the copy is made, as one does while
 * the library goes to sleep.
 */
class ArchiveWhileAsleepTest {
    @AfterEach void back() throws Exception { Config.set(LibraryPlaces.COPIES, ""); }

    @Test
    void aRequestThatWakesAnAsleepLibraryWaitsForItsCopyWithTheIndex(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
            Path index = LibraryPlaces.library(lib).resolve(".index");
            Files.createDirectories(index);
            for (int i = 0; i < 24_000; i++) Files.writeString(index.resolve("segment-" + i), "a piece of the index " + i);   // a copy that takes a while
            AtomicLong started = new AtomicLong();
            Node node = new Node(store, "http://127.0.0.1:1", new LeaseFenceTest.Standing("http://127.0.0.1:1") {
                @Override public String start(AccountStore.Library l, long epoch) { started.set(System.currentTimeMillis()); return super.start(l, epoch); }
            }, "node_pack", root);
            Thread packing = Thread.ofPlatform().start(() -> { try { node.archive(lib, OutputStream.nullOutputStream(), "the test"); } catch (Exception e) { throw new RuntimeException(e); } });
            Thread.sleep(150);   // the copy of the asleep library, with its index, is under way
            node.ensure(lib);   // a request comes for it
            node.done(lib.id());
            packing.join();
            LibraryPlaces.Copy c = LibraryPlaces.lastCopy(lib.id());
            assertTrue(c.stopped(), "the copy holds the index");
            assertTrue(started.get() >= c.ended(), "the library started only once its copy with the index was made: started " + started.get() + ", copy ended " + c.ended());
            node.stopAll();
        }
    }
}
