package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a library under the account service lives (0.8.0, places), on libraries made for the test with nothing running them: its
 * copy brought up to date (only what changed is written; what is gone is removed; its index only while it is stopped; its own lease
 * never; a link not followed), the copy brought back into its folder with a note the library shows, a folder moved to another root,
 * a library removed with its days of grace or at once, an ordinary library's folder taken in and let go again, and what was set
 * aside removed when its grace is over.
 */
class LibraryPlacesTest {
    long grace = LibraryPlaces.GRACE_MS, wait = LibraryPlaces.STOP_WAIT_MS;
    @AfterEach void back() throws Exception { Config.set(LibraryPlaces.COPIES, ""); LibraryPlaces.GRACE_MS = grace; LibraryPlaces.STOP_WAIT_MS = wait; Config.set("RESEARCHZOSHO_REPORT_LENGTH", ""); }

    /** Every file under a folder, by its path inside it, with what it holds. */
    static Map<String, String> files(Path dir) throws Exception {
        Map<String, String> m = new TreeMap<>();
        if (!Files.isDirectory(dir)) return m;
        try (Stream<Path> s = Files.walk(dir)) { for (Path p : s.filter(Files::isRegularFile).toList()) m.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.ISO_8859_1)); }
        return m;
    }

    static AccountStore.Library library(AccountStore store, Path root, String name) throws Exception {
        return AccountService.newAccount(store, root, name, "", false, "the test").library();
    }

    @Test
    void aCopyIsBroughtUpToDateWithWhatChangedAndBroughtBackWithANote(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            LibraryPlaces places = new LibraryPlaces(store);
            AccountStore.Library lib = library(store, root, "Ann Example");
            Path folder = LibraryPlaces.folder(lib), library = LibraryPlaces.library(lib);
            Files.writeString(library.resolve("raw").resolve("harbour.md"), "The harbour was built in stages.\n");
            Files.createDirectories(library.resolve(".index"));
            Files.writeString(library.resolve(".index").resolve("segments_1"), "an index");
            Files.writeString(library.resolve("catalog").resolve("serve.lock"), "12345\thost\t2026-11-17T00:00:00Z\n");
            Files.createDirectories(LibraryPlaces.home(folder));
            Files.writeString(LibraryPlaces.home(folder).resolve("config"), "language = Japanese\n");
            Files.writeString(LibraryPlaces.log(folder), "a log line\n");
            Path outside = Files.writeString(tmp.resolve("not-the-librarys.txt"), "somebody else's");
            Files.createSymbolicLink(library.resolve("raw").resolve("link.md"), outside);
            assertNull(LibraryPlaces.lastCopy(lib.id()));

            // the first pass, the library stopped: everything of the library and its settings, its index too; never its lease, its log, a link
            LibraryPlaces.Copy first = places.bringUp(lib, 3, true);
            Path copy = LibraryPlaces.copy(lib.id());
            Map<String, String> held = files(copy.resolve("files"));
            assertEquals("The harbour was built in stages.\n", held.get("library/raw/harbour.md"));
            assertEquals("an index", held.get("library/.index/segments_1")); assertEquals("language = Japanese\n", held.get("home/config"));
            assertFalse(held.containsKey("library/catalog/serve.lock"), "the folder's own lease is never in a copy");
            assertFalse(held.containsKey("library/raw/link.md") || held.values().contains("somebody else's"), "a link is not followed");
            assertFalse(held.keySet().stream().anyMatch(k -> k.startsWith("tmp/") || k.startsWith("user/") || k.equals("serve.log")));
            assertEquals(first.files(), held.size()); assertEquals(first.changed(), held.size()); assertEquals(0, first.removed());
            assertEquals(first.ended(), store.placement(lib.id()).copiedAt()); assertEquals(3, first.epoch()); assertTrue(first.stopped());
            LibraryPlaces.Copy said = LibraryPlaces.lastCopy(lib.id());
            assertEquals(first.ended(), said.ended()); assertFalse(said.interrupted()); assertTrue(said.stopped());

            // nothing changed: nothing is written
            LibraryPlaces.Copy again = places.bringUp(lib, 3, true);
            assertEquals(0, again.changed()); assertEquals(0, again.removed()); assertEquals(first.files(), again.files());

            // one file changed, one added, one removed, the index changed while the library runs: written, added, removed; the index left as it was
            Files.writeString(library.resolve("raw").resolve("harbour.md"), "The harbour was built in two stages.\n");
            Files.setLastModifiedTime(library.resolve("raw").resolve("harbour.md"), FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            Files.writeString(library.resolve("raw").resolve("lighthouse.md"), "The lighthouse was lit that autumn.\n");
            Files.delete(LibraryPlaces.home(folder).resolve("config"));
            Files.writeString(library.resolve(".index").resolve("segments_2"), "a newer index");
            LibraryPlaces.Copy running = places.bringUp(lib, 4, false);
            held = files(copy.resolve("files"));
            assertEquals(2, running.changed(), running.toString()); assertEquals(1, running.removed());
            assertEquals("The harbour was built in two stages.\n", held.get("library/raw/harbour.md")); assertTrue(held.containsKey("library/raw/lighthouse.md")); assertFalse(held.containsKey("home/config"));
            assertEquals("an index", held.get("library/.index/segments_1")); assertFalse(held.containsKey("library/.index/segments_2"), "while it runs, the index is the last stopped copy's");
            assertFalse(running.stopped());
            LibraryPlaces.Copy stopped = places.bringUp(lib, 4, true);
            assertEquals(1, stopped.changed()); assertEquals("a newer index", files(copy.resolve("files")).get("library/.index/segments_2"), "stopped, the index comes too");

            // a pass that was cut off: the copy says so, and the next pass finishes it
            Files.writeString(copy.resolve("updating"), "2026-11-17T10:00:00Z\n");
            Files.writeString(copy.resolve("files").resolve("library").resolve("raw").resolve("harbour.md" + LibraryPlaces.HALF), "half");
            assertTrue(LibraryPlaces.lastCopy(lib.id()).interrupted());
            places.bringUp(lib, 4, true);
            assertFalse(LibraryPlaces.lastCopy(lib.id()).interrupted());
            assertFalse(Files.exists(copy.resolve("files").resolve("library").resolve("raw").resolve("harbour.md" + LibraryPlaces.HALF)), "what was half written is gone");

            // the folder is lost and brought back from the copy: what the copy holds, a note the library shows, the old folder set aside
            Files.writeString(library.resolve("raw").resolve("after-the-copy.md"), "written after the last copy");
            String back = places.bring(lib);
            assertTrue(back.startsWith("The library Ann Example is brought back from its copy of ") && back.contains("What was in its folder is set aside at "), back);
            Map<String, String> now = files(library);
            assertEquals("The harbour was built in two stages.\n", now.get("raw/harbour.md")); assertEquals("a newer index", now.get(".index/segments_2"));
            assertFalse(now.containsKey("raw/after-the-copy.md"), "what came after the copy is not in it");
            for (Path p : new Path[]{LibraryPlaces.tmp(folder), LibraryPlaces.user(folder), LibraryPlaces.home(folder)}) assertTrue(Files.isDirectory(p), p.toString());
            LibraryStore ls = new LibraryStore(library);
            JsonNode note = LibraryPlaces.broughtBack(ls);
            assertNotNull(note);
            assertTrue(Pages.broughtBackSaid(note).startsWith("This library was brought back from its copy of ") && Pages.broughtBackSaid(note).endsWith(". What was done in it after that is not in it."), Pages.broughtBackSaid(note));
            JsonNode status = new LibraryProtocol(ls).status(Statement.M.createObjectNode().put("patron", "person"));
            assertEquals(Pages.broughtBackSaid(note), status.path("brought_back").path("note").asText(), status.toString());
            List<AccountStore.Aside> asides = store.asides(lib.id());
            assertEquals(1, asides.size()); assertEquals("replaced-by-its-copy", asides.get(0).why());
            assertEquals("written after the last copy", files(Path.of(asides.get(0).path())).get("library/raw/after-the-copy.md"), "set aside, not lost");
            // a week later the page says nothing of it
            Files.writeString(library.resolve("catalog").resolve(LibraryPlaces.BROUGHT_BACK), Statement.M.createObjectNode().put("copy_ended", 1).put("brought_at", System.currentTimeMillis() - 8L * 24 * 3_600_000).toString());
            assertNull(LibraryPlaces.broughtBack(ls));
        }
    }

    @Test
    void aFolderIsMovedToAnotherRootAndWhatIsSetAsideGoesWhenItsGraceIsOver(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts"), other = tmp.resolve("second-disk");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            LibraryPlaces places = new LibraryPlaces(store);
            AccountStore.Library lib = library(store, root, "Ann Example");
            Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve("harbour.md"), "The harbour.\n");
            Map<String, String> before = files(LibraryPlaces.library(lib));
            places.outOfService(lib, "moving");   // nothing runs it: at once
            assertEquals("moving", store.library(lib.id()).state());
            Path at = places.moveTo(lib, other);
            assertEquals(other.resolve("libraries").resolve(lib.name()).toAbsolutePath().normalize(), at);
            assertEquals(at.toString(), store.library(lib.id()).folder());
            Map<String, String> arrived = files(LibraryPlaces.library(at));
            assertEquals(LibraryPlaces.library(lib).toAbsolutePath().normalize() + "\n", arrived.remove("catalog/" + LibraryPlaces.MOVED_FROM), "it says where it was, so its jobs' paths are put right at its start");
            assertEquals(before, arrived, "the library as it was, file for file");
            assertFalse(Files.exists(LibraryPlaces.folder(lib)), "the old folder is not where it was");
            List<AccountStore.Aside> asides = store.asides(lib.id());
            assertEquals(1, asides.size()); assertEquals("moved", asides.get(0).why());
            assertTrue(Path.of(asides.get(0).path()).startsWith(root.resolve("aside")), "set aside under the old root: " + asides.get(0).path());
            assertEquals(before, files(LibraryPlaces.library(Path.of(asides.get(0).path()))));
            assertThrows(Exception.class, () -> places.moveTo(store.library(lib.id()), other), "a folder is there already");
            // the grace: nothing goes before it is over; then it goes, and is forgotten
            assertEquals(0, places.sweep());
            LibraryPlaces.GRACE_MS = 0;
            Path something = Files.createDirectories(tmp.resolve("something"));
            places.aside(store.library(lib.id()), something, "test");
            Thread.sleep(5);
            assertEquals(1, places.sweep(), "what was set aside with no grace goes; the moved folder's 30 days are not over");
            assertTrue(Files.exists(Path.of(asides.get(0).path())));
            store.aside(asides.get(0).path(), lib.id(), "moved", System.currentTimeMillis() - 1);   // its 30 days over
            assertEquals(1, places.sweep());
            assertFalse(Files.exists(Path.of(asides.get(0).path()))); assertTrue(store.asides(lib.id()).isEmpty());
            Map<String, String> still = files(LibraryPlaces.library(at)); still.remove("catalog/" + LibraryPlaces.MOVED_FROM);
            assertEquals(before, still, "and the library is untouched");
        }
    }

    static String run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int rc = AccountsCli.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return rc + "\n" + o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryLibraryIsTakenInWithoutItsReadersAndLetGoWithThem(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        try {
            // a library of this machine's own, with a reader and a key of its own
            Path own = tmp.resolve("own-library");
            LibraryStore ls = new LibraryStore(own); ls.init(); ls.setName("Harbour history");
            Files.writeString(own.resolve("raw").resolve("harbour.md"), "The harbour.\n");
            Accounts.Account ben = Accounts.create(ls, "did:rz:ben-test", "Ben Example", "ben's long password");
            Patrons.set(ls, ben.did(), "Ben Example", Patrons.Level.write);
            ApiKeys.mint(ls, ben.did(), "Ben's agent", Patrons.Level.read);
            String id = ls.identity().id();
            Config.set("RESEARCHZOSHO_REPORT_LENGTH", "short");
            try (AccountStore store = AccountService.openStore()) { store.createAccount("Ann Example", "", false); }
            // nobody named: refused; a folder that is no library: refused
            assertTrue(run("x", "accounts", "take", own.toString()).contains("Say whose library it is: --for \"<name>\"."));
            assertTrue(run("x", "accounts", "take", tmp.toString(), "--for", "Ann Example").contains("There is no library at"));
            // served by a process: refused, and nothing moves
            try (Lease served = Lease.acquire(new LibraryStore(own))) {
                String r = run("x", "accounts", "take", own.toString(), "--for", "Ann Example");
                assertTrue(r.startsWith("1\n") && r.contains("is being served"), r);
                assertTrue(Files.isDirectory(own));
            }
            Path ownReal = own.toRealPath();   // the take names the folder's real place: on macOS /var is /private/var
            String r = run("x", "accounts", "take", own.toString(), "--for", "Ann Example");
            assertTrue(r.startsWith("0\n"), r);
            assertTrue(r.contains("The library \"Harbour history\" is taken in as ann-example, kept by Ann Example. It keeps its id, its shelves and its jobs."), r);
            assertTrue(r.contains("nothing is left at " + ownReal), r);
            assertTrue(r.contains("Its own settings came with it: report_length = short."), r);
            assertTrue(r.contains("    reader: Ben Example, reads and writes") && r.contains("    key: Ben's agent ("), r);
            assertFalse(Files.exists(own));
            AccountStore.Library lib;
            try (AccountStore store = AccountService.openStore()) {
                lib = store.library(id);
                assertNotNull(lib, "it keeps its id"); assertEquals("Harbour history", lib.title()); assertTrue(lib.active());
                assertEquals("The harbour.\n", Files.readString(LibraryPlaces.library(lib).resolve("raw").resolve("harbour.md")));
                assertTrue(Files.readString(LibraryPlaces.home(LibraryPlaces.folder(lib)).resolve("config")).contains("RESEARCHZOSHO_REPORT_LENGTH = short"));
            }
            assertTrue(run("x", "accounts", "take", LibraryPlaces.library(lib).toString(), "--for", "Ann Example").contains("The service has this library already, as ann-example"));
            // released: an ordinary library again, its reader in it as before
            Path home = tmp.resolve("back-home");
            r = run("x", "accounts", "release", "ann-example", home.toString());
            assertTrue(r.startsWith("0\n") && r.contains("is released: it is an ordinary library at " + home.toAbsolutePath().normalize()), r);
            LibraryStore again = new LibraryStore(home);
            assertEquals(id, again.identity().id()); assertEquals("The harbour.\n", Files.readString(home.resolve("raw").resolve("harbour.md")));
            assertEquals(List.of("Ben Example"), Accounts.list(again).stream().filter(a -> !a.did().equals(Accounts.OWNER)).map(Accounts.Account::name).toList(), "its own reader is in it");
            assertFalse(Files.exists(home.resolve("catalog").resolve("serve.lock")));
            try (AccountStore store = AccountService.openStore()) {
                assertEquals("released", store.library(id).state());
                assertFalse(store.librariesOf(store.library(id).owner()).stream().anyMatch(l -> l.id().equals(id)), "not on its keeper's list");
            }
            // and taken in again: it comes back to the account that kept it
            r = run("x", "accounts", "take", home.toString());
            assertTrue(r.startsWith("0\n") && r.contains("is taken in as ann-example, kept by Ann Example"), r);
            // a library that never had a name of its own was called by its folder's name: it keeps that name under the service, whose folder for it is "library"
            Path plain = tmp.resolve("plain-notes");
            LibraryStore made = new LibraryStore(plain); made.init(); made.identity();   // as `researchzosho init` makes one: an id, no name
            assertTrue(Files.readAllLines(made.libraryFile()).stream().noneMatch(l -> l.startsWith("name: ")));
            r = run("x", "accounts", "take", plain.toString(), "--for", "Ann Example");
            assertTrue(r.startsWith("0\n") && r.contains("The library \"plain-notes\" is taken in as ann-example-2"), r);
            try (AccountStore store = AccountService.openStore()) { assertEquals("plain-notes", new LibraryStore(LibraryPlaces.library(store.libraryByName("ann-example-2"))).identity().name()); }
        } finally { Config.set(AccountService.ROOT, ""); }
    }

    @Test
    void aLibraryIsRemovedWithItsGraceOrAtOnce(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        try {
            AccountStore.Library one, two;
            try (AccountStore store = AccountService.openStore()) {
                one = library(store, root, "Ann Example"); two = library(store, root, "Ben Example");
                new LibraryPlaces(store).bringUp(one, 1, true); new LibraryPlaces(store).bringUp(two, 1, true);
            }
            String r = run("x", "accounts", "delete", one.name());
            assertTrue(r.startsWith("0\n") && r.contains("is out of service. Its folder is set aside at ") && r.contains("and it is removed with its copy on "), r);
            assertTrue(r.contains("Until then it comes back with: researchzosho accounts take "), r);
            assertFalse(Files.exists(LibraryPlaces.folder(one)));
            assertTrue(Files.exists(LibraryPlaces.copy(one.id())), "its copy stays for the days of grace");
            try (AccountStore store = AccountService.openStore()) {
                assertEquals("removed", store.library(one.id()).state());
                assertEquals(2, store.asides(one.id()).size(), "its folder and its copy, to go together");
                assertTrue(store.asides(one.id()).stream().allMatch(a -> a.until() > System.currentTimeMillis() + 29L * 24 * 3_600_000));
            }
            assertTrue(run("x", "accounts", "delete", one.name()).contains("No library in service is named"));
            r = run("x", "accounts", "delete", two.name(), "--now");
            assertTrue(r.startsWith("0\n") && r.contains("is removed, with its copy and everything set aside for it."), r);
            assertFalse(Files.exists(LibraryPlaces.folder(two))); assertFalse(Files.exists(LibraryPlaces.copy(two.id())));
            // a library out of service is told as such to whoever asks for it through the service
            try (AccountStore store = AccountService.openStore()) {
                assertEquals("removed", store.library(two.id()).state());
                assertTrue(store.asides(two.id()).isEmpty());
            }
        } finally { Config.set(AccountService.ROOT, ""); }
    }

    /** A machine's disk is lost: whoever runs the service brings the library back from its copy under another machine's root, and its jobs' paths are put right at its start. */
    @Test
    void aLibraryIsBroughtBackFromItsCopyUnderAnotherRoot(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts"), other = tmp.resolve("machine-b");
        Config.set(AccountService.ROOT, root.toString());
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        try {
            AccountStore.Library lib;
            try (AccountStore store = AccountService.openStore()) {
                lib = library(store, root, "Ann Example");
                Path doc = Files.writeString(LibraryPlaces.library(lib).resolve("catalog").resolve("a-document.md"), "The harbour.\n");
                Files.createDirectories(LibraryPlaces.library(lib).resolve("catalog").resolve("jobs").resolve("active"));
                Files.writeString(LibraryPlaces.library(lib).resolve("catalog").resolve("jobs").resolve("active").resolve("J-0001.json"), "{\"args\":{\"path\":\"" + doc + "\"}}");
                new LibraryPlaces(store).bringUp(lib, 1, true);
            }
            String r = run("x", "accounts", "restore", lib.name(), other.toString());
            assertTrue(r.startsWith("0\n") && r.contains("is brought back from its copy of "), r);
            try (AccountStore store = AccountService.openStore()) {
                AccountStore.Library now = store.library(lib.id());
                assertEquals(LibraryPlaces.newFolder(other.toAbsolutePath().normalize(), lib.name()).toString(), now.folder());
                LibraryStore ls = new LibraryStore(LibraryPlaces.library(now));
                assertNotNull(LibraryPlaces.broughtBack(ls));
                assertEquals(1, LibraryPlaces.relocated(ls), "at its start, the job record that named the old place is put right");
                String job = Files.readString(LibraryPlaces.library(now).resolve("catalog").resolve("jobs").resolve("active").resolve("J-0001.json"));
                assertTrue(job.contains(LibraryPlaces.library(now).resolve("catalog").resolve("a-document.md").toString()), job);
                assertEquals(0, LibraryPlaces.relocated(ls), "once");
            }
        } finally { Config.set(AccountService.ROOT, ""); }
    }

    @Test
    void aLibraryThatDoesNotStopIsPutBackInServiceAndNothingChanges(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountStore.Library lib = library(store, root, "Ann Example");
            assertEquals(1, store.take(lib.id(), "a-node-that-does-not-answer", 60_000));   // held, and nobody will stop it
            LibraryPlaces.STOP_WAIT_MS = 500;
            Exception e = assertThrows(Exception.class, () -> new LibraryPlaces(store).outOfService(lib, "moving"));
            assertEquals("The library Ann Example did not stop within 0 seconds of being taken out of service, so nothing was changed and it is served as before.", e.getMessage());
            assertEquals("active", store.library(lib.id()).state());
            assertTrue(Files.isDirectory(LibraryPlaces.library(lib)));
        }
    }
}
