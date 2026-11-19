package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a library writes in its own folder is the library's, and the service reads it as such (round F, the second review, S1, S5 and
 * S16, each proved on c8e057ed): a folder whose name holds a line break wrote lines of its own into the copy's list, and the next copy
 * pass removed the absolute path one of them named; a folder swapped for a link after the walk saw it was read through into the copy;
 * a lease file naming another machine kept the library from ever being taken out of service, and a link to /dev/zero there was read
 * for ever.
 */
class PlacesFolderTrustTest {
    @TempDir Path tmp;
    AccountStore store;

    @AfterEach void down() throws Exception { Config.set(LibraryPlaces.COPIES, ""); if (store != null) store.close(); }

    @Test
    void aNameWithALineBreakIsNotCopiedAndTheListNeverNamesAPlaceOutsideTheCopy() throws Exception {
        Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        AccountStore.Account a = store.createAccount("Mal Example", "mal@example.org", false);
        Path victim = Files.createDirectories(tmp.resolve("another-library/library/catalog")).resolve("library.md");
        Files.writeString(victim, "another account's library\n");
        Path folder = tmp.resolve("root/libraries/mal"), lib = folder.resolve("library");
        Files.createDirectories(lib.resolve("catalog"));
        Files.writeString(lib.resolve("catalog/library.md"), "id: lib_mal\nname: mal\n");
        Path p = lib.resolve("x\n-1\t0\t");
        for (Path part : victim.getParent()) p = p.resolve(part.toString());
        Files.createDirectories(p);
        Files.writeString(p.resolve("library.md"), "decoy");
        store.addLibrary("lib_mal", "mal", "Mal", a.id(), folder.toString());
        LibraryPlaces places = new LibraryPlaces(store);
        places.bringUp(store.library("lib_mal"), 1, false);
        String manifest = Files.readString(tmp.resolve("copies/lib_mal/manifest.tsv"));
        assertFalse(manifest.contains(victim.toString()) || manifest.contains("x\n"), "nothing of the name is in the list: " + manifest);
        assertTrue(manifest.contains("catalog/library.md"), "the library's own files are: " + manifest);
        // a list that names a place outside the copy, as an older pass could have written it: the pass removes nothing outside
        Files.writeString(tmp.resolve("copies/lib_mal/manifest.tsv"), manifest + "12\t0\t" + victim + "\n12\t0\t../../another-library/library/catalog/library.md\n");
        places.bringUp(store.library("lib_mal"), 1, false);
        assertTrue(Files.exists(victim), "a file outside the library and its copy stays");
        assertEquals("another account's library\n", Files.readString(victim));
        for (String bad : new String[]{"/etc/passwd", "../x", "a/../../x", "a//b", "./a", "a\nb", "a\tb", "C:/x", "a\\b"}) assertFalse(LibraryPlaces.safeRel(bad), bad);
        assertTrue(LibraryPlaces.safeRel("catalog/library.md"));
    }

    @Test
    void aFolderSwappedForALinkIsNotReadThrough() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("host-secrets"));
        Files.writeString(outside.resolve("own-keys.key"), "THE-SEALING-KEY\n");
        Path lib = Files.createDirectories(tmp.resolve("lib/library"));
        Files.createSymbolicLink(lib.resolve("evil"), outside);   // what a container swaps in after the walk saw a folder there
        Path copy = tmp.resolve("copy/files/library/evil/own-keys.key");
        assertThrows(LibraryPlaces.LinkOnTheWay.class, () -> LibraryPlaces.putFrom(lib, "evil/own-keys.key", copy, System.currentTimeMillis()));
        assertFalse(Files.exists(copy, LinkOption.NOFOLLOW_LINKS), "nothing of the outside file is in the copy");
        // a file named as a link itself is not read either; a plain file is
        Files.createSymbolicLink(lib.resolve("key-link"), outside.resolve("own-keys.key"));
        assertThrows(IOException.class, () -> LibraryPlaces.putFrom(lib, "key-link", tmp.resolve("copy/files/key-link"), 0));
        Files.createDirectories(lib.resolve("catalog"));
        Files.writeString(lib.resolve("catalog/library.md"), "id: lib_x\n");
        LibraryPlaces.putFrom(lib, "catalog/library.md", tmp.resolve("copy/files/catalog/library.md"), 1_000);
        assertEquals("id: lib_x\n", Files.readString(tmp.resolve("copy/files/catalog/library.md")));
    }

    @Test
    void aLeaseFileInTheLibrarysFolderDecidesNothingForTheService() throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        AccountStore.Account a = store.createAccount("Ann Example", "ann@example.org", false);
        Path folder = tmp.resolve("root/libraries/ann");
        Files.createDirectories(folder.resolve("library/catalog"));
        Files.writeString(folder.resolve("library/catalog/serve.lock"), "1\tsome-other-host\t9999-01-01T00:00:00Z\n");
        store.addLibrary("lib_ann", "ann", "Ann's", a.id(), folder.toString());
        long was = LibraryPlaces.STOP_WAIT_MS;
        LibraryPlaces.STOP_WAIT_MS = 800;
        try {
            new LibraryPlaces(store).outOfService(store.library("lib_ann"), "removed");
            assertEquals("removed", store.library("lib_ann").state(), "taken out of service by the store's own lease");
        } finally { LibraryPlaces.STOP_WAIT_MS = was; }
        // a lease file that is a link, to a device that never ends, is no holder, and is not read
        Path zero = Path.of("/dev/zero");
        assumeTrue(Files.exists(zero));
        LibraryStore own = new LibraryStore(tmp.resolve("own")); own.init();
        Path lock = own.root().resolve("catalog/serve.lock");
        Files.deleteIfExists(lock);
        Files.createSymbolicLink(lock, zero);
        assertNull(Lease.holder(own));
    }
}
