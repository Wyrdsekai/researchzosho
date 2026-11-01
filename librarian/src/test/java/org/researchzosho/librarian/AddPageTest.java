package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Add page: a file posted from the browser, or a page by its address, goes onto the shelves through the same add as the command line's, and the upload folder is dropped after. */
class AddPageTest {
    @Test void aFilePostedFromTheBrowserIsShelvedAndItsUploadFolderDropped(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String page = Pages.addPage();
        assertTrue(page.contains("enctype=\"multipart/form-data\"") && page.contains("<input type=\"file\" name=\"file\"") && page.contains("name=\"url\""), page);
        assertTrue(Pages.addPost(store, new LibraryProtocol(store), Patrons.Patron.PERSON, Map.of()).startsWith("<p>Choose a file, or write a page's address.</p>"));
        // only a file this server wrote for an upload counts as one: its own folder, named for an upload, directly under the temp folder
        Path up = Files.createTempDirectory(Uploads.DIR);
        Path f = up.resolve("saros notes.txt");
        Files.writeString(f, "The Saros dial of the Antikythera mechanism predicts eclipses over 223 lunar months.\n".repeat(20), StandardCharsets.UTF_8);
        assertTrue(Uploads.isUpload(f));
        Path elsewhere = Files.createDirectories(tmp.resolve(Uploads.DIR + "x")).resolve("saros notes.txt");
        assertFalse(Uploads.isUpload(elsewhere), "an upload folder is directly under the temp folder, not anywhere a name matches");
        assertFalse(Uploads.isUpload(tmp.resolve("saros notes.txt")));
        String out = Pages.addPost(store, new LibraryProtocol(store), Patrons.Patron.WEB, Map.of("file.path", f.toString(), "file.name", "saros notes.txt", "collection", "notes"));
        assertTrue(out.contains("<b>Added:</b> saros notes.txt") && out.contains("It is on the shelves now"), out);
        assertFalse(Files.exists(up), "the upload folder is removed once the file is read");
        assertTrue(Files.exists(tmp.resolve(Uploads.DIR + "x")), "a folder that is not an upload is left alone");
    }

    @Test void whileSignInIsRequiredSomeoneSignedInMayPostAFileButNotNameAPathOnTheMachine(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path mine = tmp.resolve("on the machine.txt"); Files.writeString(mine, "A file that lies on the library's own machine.\n".repeat(20), StandardCharsets.UTF_8);
        Path up = Files.createTempDirectory(Uploads.DIR);
        Path posted = up.resolve("posted.txt"); Files.writeString(posted, "A file the browser posted, the person's own.\n".repeat(20), StandardCharsets.UTF_8);
        Patrons.set(store, "did:key:friend", "A friend", Patrons.Level.write);
        Patrons.Patron friend = new Patrons.Patron("did:key:friend", "A friend", "web");   // signed in, with write access, not the keeper
        WebAccess.OVERRIDE = Boolean.TRUE;
        try {
            String refused = Pages.addPost(store, new LibraryProtocol(store), friend, Map.of("file.path", mine.toString(), "file.name", "on the machine.txt"));
            assertTrue(refused.contains("Only the library owner can add files and folders on this machine") && refused.contains("a file of your own on the Add page"), refused);
            assertTrue(Files.exists(mine), "a path that is not an upload is never deleted");
            String added = Pages.addPost(store, new LibraryProtocol(store), friend, Map.of("file.path", posted.toString(), "file.name", "posted.txt"));
            assertTrue(added.contains("<b>Added:</b> posted.txt"), added);
            assertFalse(Files.exists(up));
        } finally { WebAccess.OVERRIDE = null; }
    }
}
