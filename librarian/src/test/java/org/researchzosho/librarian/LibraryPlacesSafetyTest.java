package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library's copy and folder are never lost by the service's own acts (0.8.0 review, round A): a copy pass that cannot see the
 * library, or cannot read part of it, removes nothing from the copy and does not call it whole; a restore that fails leaves the
 * library's folder as it was; a library taken back within its grace keeps its copy; a link is not taken in for a library; a library's
 * id from an archive or a folder never puts a path outside its own folders.
 */
class LibraryPlacesSafetyTest {
    long grace = LibraryPlaces.GRACE_MS, wait = LibraryPlaces.STOP_WAIT_MS;
    @AfterEach void back() throws Exception { Config.set(LibraryPlaces.COPIES, ""); Config.set(AccountService.ROOT, ""); LibraryPlaces.GRACE_MS = grace; LibraryPlaces.STOP_WAIT_MS = wait; }

    static AccountService.Made made(AccountStore store, Path root, String name) throws Exception { return AccountService.newAccount(store, root, name, "", false, "the test"); }

    static Map<String, String> files(Path dir) throws Exception { return LibraryPlacesTest.files(dir); }

    @Test
    void aCopyPassThatCannotSeeTheLibraryRemovesNothingFromItsCopy(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            LibraryPlaces places = new LibraryPlaces(store);
            AccountStore.Library lib = made(store, root, "Ann Example").library();
            for (int i = 0; i < 5; i++) Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve("page-" + i + ".md"), "page " + i + "\n");
            LibraryPlaces.Copy first = places.bringUp(lib, 1, true);
            Path files = LibraryPlaces.copy(lib.id()).resolve("files");
            Map<String, String> held = files(files);
            assertTrue(held.size() > 5);

            // the disk goes away under the library (a volume unmounted, a network mount lost): no pass empties the copy, and none calls it whole
            Path gone = tmp.resolve("unmounted");
            Files.move(LibraryPlaces.folder(lib), gone);
            IOException e = assertThrows(IOException.class, () -> places.bringUp(lib, 2, false));
            assertTrue(e.getMessage().contains("cannot be seen"), e.getMessage());
            assertEquals(held, files(files), "the copy holds what it held");
            assertEquals(first.ended(), LibraryPlaces.lastCopy(lib.id()).ended(), "the copy's mark is the earlier pass's");
            assertEquals(first.ended(), store.placement(lib.id()).copiedAt());
            Files.move(gone, LibraryPlaces.folder(lib));

            // a part of it cannot be read: what could be read is written, nothing is removed, and the copy is not called whole
            Path raw = LibraryPlaces.library(lib).resolve("raw");
            Files.writeString(LibraryPlaces.library(lib).resolve("catalog").resolve("new.md"), "new\n");
            Files.setPosixFilePermissions(raw, PosixFilePermissions.fromString("---------"));
            try {
                IOException unread = assertThrows(IOException.class, () -> places.bringUp(lib, 3, false));
                assertTrue(unread.getMessage().contains("could not be read"), unread.getMessage());
            } finally { Files.setPosixFilePermissions(raw, PosixFilePermissions.fromString("rwxr-xr-x")); }
            Map<String, String> now = files(files);
            for (String k : held.keySet()) assertTrue(now.containsKey(k), "still in the copy: " + k);
            assertEquals("new\n", now.get("library/catalog/new.md"), "what could be read is in the copy");
            assertTrue(LibraryPlaces.lastCopy(lib.id()).interrupted(), "the copy is not called whole");
            assertEquals(first.ended(), store.placement(lib.id()).copiedAt());

            // the next whole pass finishes it, and is whole
            LibraryPlaces.Copy whole = places.bringUp(lib, 4, true);
            assertFalse(LibraryPlaces.lastCopy(lib.id()).interrupted());
            assertEquals(whole.ended(), store.placement(lib.id()).copiedAt());
            assertEquals(held.size() + 1, files(files).size());
        }
    }

    @Test
    void aRestoreThatFailsLeavesTheLibrarysFolderAsItWas(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        LibraryPlaces.STOP_WAIT_MS = 2_000;
        Path locked = null;
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            LibraryPlaces places = new LibraryPlaces(store);
            AccountStore.Library lib = made(store, root, "Ann Example").library();
            for (String n : List.of("a", "b", "c", "d", "e")) Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve(n + ".md"), "page " + n + "\n");
            places.bringUp(lib, 1, true);
            Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve("f.md"), "written after the copy\n");
            Map<String, String> live = files(LibraryPlaces.folder(lib));
            Path files = LibraryPlaces.copy(lib.id()).resolve("files");
            Map<String, String> copy = files(files);
            // one file of the copy cannot be read during the restore
            locked = files.resolve("library/raw/c.md");
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> AccountsCli.places(store, lib, "restore", null, false, new PrintStream(o)));
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
            AccountStore.Library now = store.library(lib.id());
            assertEquals("active", now.state());
            assertEquals(lib.folder(), now.folder());
            assertEquals(live, files(LibraryPlaces.folder(now)), "the library's folder is as it was, the file written after the copy too");
            assertTrue(store.asides(lib.id()).isEmpty(), "nothing is set aside: " + store.asides(lib.id()));
            assertFalse(Files.exists(LibraryPlaces.folder(lib).resolveSibling(LibraryPlaces.folder(lib).getFileName() + ".arriving")), "nothing half-written is left beside it");
            // the next pass loses nothing from the copy
            places.bringUp(now, 2, true);
            Map<String, String> after = files(files);
            for (String k : copy.keySet()) assertTrue(after.containsKey(k), "still in the copy: " + k);

            // and a restore that works: the copy in the folder, the earlier folder set aside
            String said = places.bring(store.library(lib.id()));
            assertTrue(said.contains("is brought back from its copy"), said);
            assertEquals(1, store.asides(lib.id()).size());
            assertTrue(Files.isRegularFile(LibraryPlaces.library(lib).resolve("raw").resolve("c.md")));
        } finally { if (locked != null && Files.exists(locked)) Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--")); }
    }

    @Test
    void aLibraryTakenBackWithinItsGraceKeepsItsCopy(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        LibraryPlaces.STOP_WAIT_MS = 2_000;
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            LibraryPlaces places = new LibraryPlaces(store);
            AccountService.Made m = made(store, root, "Ann Example");
            AccountStore.Library lib = m.library();
            Files.writeString(LibraryPlaces.library(lib).resolve("raw").resolve("a.md"), "page a\n");
            places.bringUp(lib, 1, true);
            LibraryPlaces.GRACE_MS = 1_000;
            // released, and taken back
            Path harbour = tmp.resolve("harbour");
            AccountsCli.places(store, lib, "release", harbour, false, new PrintStream(new ByteArrayOutputStream()));
            places.take(harbour, m.account(), root);
            places.bringUp(store.library(lib.id()), 2, true);
            // removed with its grace, and taken back from where it was set aside
            AccountsCli.places(store, store.library(lib.id()), "delete", null, false, new PrintStream(new ByteArrayOutputStream()));
            AccountStore.Aside folder = store.asides(lib.id()).stream().filter(a -> a.why().equals("removed") && !a.path().equals(LibraryPlaces.copy(lib.id()).toString())).findFirst().orElseThrow();
            places.take(LibraryPlaces.library(Path.of(folder.path())), null, root);
            places.bringUp(store.library(lib.id()), 3, true);
            Thread.sleep(1_200);
            places.sweep();
            assertEquals("active", store.library(lib.id()).state());
            assertTrue(Files.isDirectory(LibraryPlaces.copy(lib.id())), "the library in service keeps its copy");
            assertTrue(Files.isRegularFile(LibraryPlaces.copy(lib.id()).resolve("files").resolve("library/raw/a.md")));
            assertFalse(store.asides(lib.id()).stream().anyMatch(a -> a.path().equals(LibraryPlaces.copy(lib.id()).toString())), "its copy is not written down to be removed");
        }
    }

    @Test
    void aLinkIsNotTakenInForALibrary(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountService.Made m = made(store, root, "Ann Example");
            Path real = tmp.resolve("big-disk").resolve("my-library");
            LibraryStore own = new LibraryStore(real); own.init(); own.setName("Mine");
            Files.writeString(real.resolve("raw").resolve("a.md"), "page a\n");
            String id = own.identity().id();
            Path link = Files.createSymbolicLink(tmp.resolve("researchzosho-library"), real);
            LibraryPlaces places = new LibraryPlaces(store);
            IOException e = assertThrows(IOException.class, () -> places.take(link, m.account(), root));
            assertTrue(e.getMessage().contains("is a link to " + real.toRealPath()), e.getMessage());
            assertTrue(Files.isSymbolicLink(link) && Files.isRegularFile(real.resolve("raw").resolve("a.md")), "nothing moved");
            assertNull(store.library(id));
            assertFalse(Files.exists(LibraryPlaces.newFolder(root, "ann-example-2")));
            // a link above it is no matter: the library's own folder is taken in, by its real path
            Path above = Files.createSymbolicLink(tmp.resolve("disk-link"), tmp.resolve("big-disk"));
            Path realPath = real.toRealPath();
            LibraryPlaces.Taken t = places.take(above.resolve("my-library"), m.account(), root);
            assertFalse(Files.isSymbolicLink(LibraryPlaces.library(t.library())));
            assertEquals("page a\n", Files.readString(LibraryPlaces.library(t.library()).resolve("raw").resolve("a.md")));
            assertEquals(realPath, t.from());
        }
    }

    @Test
    void aLibrarysIdNeverLeavesItsFolders(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts"), copies = tmp.resolve("copies"), outside = tmp.resolve("not-copies");
        Files.createDirectories(copies);
        Config.set(AccountService.ROOT, root.toString());
        Config.set(LibraryPlaces.COPIES, copies.toString());
        try (AccountStore store = AccountStore.open(root.resolve("accounts.db").toString())) {
            AccountService.Made keeper = made(store, root, "Kim Example");
            // an archive whose library id climbs out of the copies' folder: refused as it is unpacked, and nothing is left
            String bad = "../not-copies";
            Path zip = tmp.resolve("crafted.rzlib.zip");
            byte[] md = ("# This library\n\nid: " + bad + "\nname: crafted\n").getBytes(StandardCharsets.UTF_8);
            ObjectNode m = Statement.M.createObjectNode().put("format", 1).put("library_id", bad).put("name", "crafted").put("title", "Crafted").put("files", 1).put("bytes", md.length);
            try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o)) {
                z.putNextEntry(new ZipEntry(LibraryArchive.MANIFEST)); z.write(Statement.M.writeValueAsBytes(m)); z.closeEntry();
                z.putNextEntry(new ZipEntry("library/catalog/library.md")); z.write(md); z.closeEntry();
            }
            IOException e = assertThrows(IOException.class, () -> AccountsCli.takeIn(store, zip, keeper.account(), AccountActs.By.cli()));
            assertTrue(e.getMessage().contains("is not a library's id"), e.getMessage());
            assertNull(store.library(bad));
            assertFalse(Files.exists(outside));
            assertFalse(Files.exists(root.resolve("taking")) && Files.list(root.resolve("taking")).findAny().isPresent(), "nothing left of the unpacking");
            // the same library as a folder of this machine's: refused as it is taken in
            Path folder = tmp.resolve("crafted-folder");
            Files.createDirectories(folder.resolve("catalog"));
            Files.write(folder.resolve("catalog").resolve("library.md"), md);
            IOException f = assertThrows(IOException.class, () -> new LibraryPlaces(store).take(folder, keeper.account(), root));
            assertTrue(f.getMessage().contains("is not a library's id"), f.getMessage());
            assertTrue(Files.isRegularFile(folder.resolve("catalog").resolve("library.md")), "nothing moved");
            // and a path built from such an id is refused, whatever asked for it
            assertThrows(IllegalArgumentException.class, () -> LibraryPlaces.copy(bad));
            assertThrows(IOException.class, () -> store.addLibrary(bad, "crafted", "Crafted", keeper.account().id(), folder.toString()));
            AccountStore.Library forged = new AccountStore.Library(bad, "crafted", "Crafted", keeper.account().id(), folder.toString(), "active", "", 0);
            assertThrows(IOException.class, () -> ContainerLibraries.sockets(forged));
            assertFalse(Files.exists(outside));
        }
    }
}
