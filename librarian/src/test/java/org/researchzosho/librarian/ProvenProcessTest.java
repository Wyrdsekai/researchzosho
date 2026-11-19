package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service stops a process only when it has proved the process is the one it means (0.8.0). A library's lease file outlives a
 * crash, and the system uses a process number again: the number in the file proves nothing. A process is stopped only when it was
 * started as this program's library daemon under this service for this library, no later than the lease file was written, and the
 * lease is current by its own expiry. Otherwise nothing is stopped, the start is refused with a sentence, and the lease's own expiry
 * frees the folder. That the stale daemon of an earlier start by this same service IS stopped is NodeTest's
 * aProcessServingTheFolderThatWasNotTakenBackIsStoppedBeforeANewStart.
 */
class ProvenProcessTest {
    static final String SERVICE = "http://127.0.0.1:47999", REFUSED = "cannot be started now: another process holds its folder. Whoever runs the service has to look into it.";

    @Test
    void whatProvesAProcessIsAStartOfThisLibraryByThisService() {
        Instant now = Instant.now(), before = now.minusSeconds(60), written = now.minusSeconds(10), expires = now.plusSeconds(200);
        List<String> ours = List.of("-Xmx1g", "-cp", "a.jar", "org.researchzosho.Main", "serve", "--host", "127.0.0.1", "--port", "4700", "--vouched-by", SERVICE, "--library-id", "lib_1", "--epoch", "2");
        assertTrue(ChildLibraries.proven(ours, before, written, expires, now, SERVICE, "lib_1"));
        assertFalse(ChildLibraries.proven(ours, before, written, expires, now, "http://127.0.0.1:1", "lib_1"), "another service's start");
        assertFalse(ChildLibraries.proven(ours, before, written, expires, now, SERVICE, "lib_2"), "another library's start");
        assertFalse(ChildLibraries.proven(ours, now.minusSeconds(5), written, expires, now, SERVICE, "lib_1"), "started after the lease file was written: the number was used again");
        assertTrue(ChildLibraries.proven(ours, written.plusMillis(1500), written, expires, now, SERVICE, "lib_1"), "1.5 s after the lease file's time: within the allowance for coarse times");
        assertFalse(ChildLibraries.proven(ours, written.plusSeconds(3), written, expires, now, SERVICE, "lib_1"), "3 s after: outside it");
        assertFalse(ChildLibraries.proven(ours, before, written, now.minusSeconds(1), now, SERVICE, "lib_1"), "a lease run out by its own expiry");
        assertFalse(ChildLibraries.proven(List.of("sleep", "600"), before, written, expires, now, SERVICE, "lib_1"), "another program");
        assertFalse(ChildLibraries.proven(List.of("org.researchzosho.Main", "serve", "--port", "4649"), before, written, expires, now, SERVICE, "lib_1"), "a person's own library daemon");
        assertFalse(ChildLibraries.proven(List.of("org.researchzosho.Main", "translate", "--vouched-by", SERVICE, "--library-id", "lib_1"), before, written, expires, now, SERVICE, "lib_1"), "not serve");
        assertFalse(ChildLibraries.proven(List.of("org.researchzosho.Main", "serve", "--vouched-by", SERVICE), before, written, expires, now, SERVICE, "lib_1"), "no library named");
        assertFalse(ChildLibraries.proven(List.of(), before, written, expires, now, SERVICE, "lib_1"), "nothing known of its arguments");
        assertFalse(ChildLibraries.proven(ours, null, written, expires, now, SERVICE, "lib_1"), "its start not known");
    }

    /** The library's folder held, by its lease file, by the process given: the file as a library writes it, written now. */
    static void heldBy(AccountStore.Library lib, long pid) throws IOException {
        Path f = Lease.file(new LibraryStore(LibraryPlaces.library(lib)));
        Files.createDirectories(f.getParent());
        Files.writeString(f, pid + "\t" + Lease.hostName() + "\t" + Instant.now().plusSeconds(300) + "\n", StandardCharsets.UTF_8);
    }

    static List<ProcessHandle> libraries(AccountStore.Library lib) {
        return ProcessHandle.current().children().filter(p -> p.isAlive() && p.info().commandLine().orElse("").contains(lib.folder())).toList();
    }

    @Test
    void aProgramWhoseNumberALeaseFileNamesIsNotStoppedAndTheStartIsRefused(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
            Process other = new ProcessBuilder("sleep", "600").start();   // any program of the machine's user, with the number a dead library's lease names
            try {
                Thread.sleep(200);
                heldBy(lib, other.pid());
                IOException e = assertThrows(IOException.class, () -> new ChildLibraries(store, SERVICE).start(lib, 1));
                assertEquals("The library Ann Example " + REFUSED, e.getMessage());
                assertTrue(other.isAlive(), "the other program is not stopped");
                assertTrue(libraries(lib).isEmpty(), "and no library was started on the folder it holds");
            } finally { other.destroy(); other.waitFor(); }
        }
    }

    @Test
    void aStartOfTheLibraryByAnotherServiceOnThisMachineIsNotStopped(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
            ChildLibraries another = new ChildLibraries(store, "http://127.0.0.1:47998");   // another account service on this machine
            try {
                another.start(lib, 1);
                List<ProcessHandle> theirs = libraries(lib);
                assertEquals(1, theirs.size());
                // the instrument: the whole command line of a real library's process is read (Java's own reading of it is cut short and empty)
                List<String> args = ChildLibraries.argumentsOf(theirs.get(0));
                assertEquals("http://127.0.0.1:47998", args.get(args.indexOf("--vouched-by") + 1), String.join(" ", args).length() + " characters");
                assertEquals(lib.id(), args.get(args.indexOf("--library-id") + 1));
                IOException e = assertThrows(IOException.class, () -> new ChildLibraries(store, SERVICE).start(lib, 2));
                assertEquals("The library Ann Example " + REFUSED, e.getMessage());
                assertTrue(theirs.get(0).isAlive(), "the other service's start is not stopped");
                assertEquals(theirs, libraries(lib));
            } finally { another.stopAll(); }
        }
    }

    @Test
    void aLibraryDaemonServesOnlyTheLibraryItWasStartedFor(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
            store.addLibrary("lib_0000000000000000", "not-ann", "Not Ann", lib.owner(), lib.folder());   // a row that names Ann's folder for another library
            AccountStore.Library wrong = store.library("lib_0000000000000000");
            IOException e = assertThrows(IOException.class, () -> new ChildLibraries(store, SERVICE).start(wrong, 1));
            assertTrue(e.getMessage().contains("did not start"), e.getMessage());
            String log = Files.readString(LibraryPlaces.log(LibraryPlaces.folder(lib)));
            assertTrue(log.contains("holds the library " + lib.id() + ", not lib_0000000000000000, so it is not served."), log);
        }
    }
}
