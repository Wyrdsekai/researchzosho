package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One copy pass or restore of a library at a time, whatever process makes it (0.8.0 review, round B, ROB 7): a pass holds a lock in
 * the copy's folder, and taking the library out of service waits for a pass under way (its node's, as it goes to sleep) before its
 * folder and its copy change, so a delete at once leaves no copy written back after it.
 */
class CopyLockTest {
    long wait = LibraryPlaces.STOP_WAIT_MS;
    @AfterEach void back() throws Exception { Config.set(LibraryPlaces.COPIES, ""); LibraryPlaces.STOP_WAIT_MS = wait; }

    @Test
    void aDeleteAtOnceWaitsForTheCopyPassUnderWayAndLeavesNoCopy(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        LibraryPlaces.STOP_WAIT_MS = 10_000;
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
            Path raw = LibraryPlaces.library(lib).resolve("raw");
            for (int i = 0; i < 24_000; i++) Files.writeString(raw.resolve("page-" + i + ".md"), "page " + i);   // a pass that takes a while
            // the node's pass as the library goes to sleep, under way
            AtomicReference<Throwable> passed = new AtomicReference<>();
            Thread pass = Thread.ofPlatform().start(() -> { try { new LibraryPlaces(store).bringUp(lib, 1, true); } catch (Throwable e) { passed.set(e); } });
            Thread.sleep(120);
            // the operator removes it at once, meanwhile
            AccountsCli.places(store, lib, "delete", null, true, new PrintStream(new ByteArrayOutputStream()));
            pass.join();
            assertEquals("removed", store.library(lib.id()).state());
            assertFalse(Files.exists(LibraryPlaces.folder(lib)), "its folder is gone");
            assertFalse(Files.exists(LibraryPlaces.copy(lib.id())), "and its copy, not written back by the pass that was under way");
            assertNull(passed.get());
        }
    }
}
