package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void severalFilesAFolderAndAnArchivePostedAtOnceAreEachShelvedAndThePageSaysWhatCameOfEach(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String page = Pages.addPage();
        assertTrue(page.contains("<input type=\"file\" name=\"file\" multiple required") && page.contains("<input type=\"file\" name=\"folder\" webkitdirectory multiple required"), page);
        assertTrue(page.contains("up to 500 files and 64 MB in one go") && page.contains("An archive among them (zip, tar, tar.gz, tgz) is unpacked"), page);
        Path up = Files.createTempDirectory(Uploads.DIR);
        Path a = Files.writeString(up.resolve("gears.txt"), "The Antikythera gears were cut by hand with files; the teeth are triangular.\n".repeat(8), StandardCharsets.UTF_8);
        Path b = Files.writeString(up.resolve("dials.md"), "# Dials\n\nThe back dials show the Metonic and Saros cycles.\n".repeat(8), StandardCharsets.UTF_8);
        Path empty = Files.write(up.resolve("empty.txt"), new byte[0]);
        Path zip = Files.write(up.resolve("things.zip"), ArchivesTest.trickyZip());
        Path html = Files.writeString(up.resolve("notes.html"), "<html><head><title>On the Saros dial</title></head><body><p>" + "The Saros dial predicts eclipses over 223 lunar months. ".repeat(8) + "</p></body></html>", StandardCharsets.UTF_8);
        Path x = up.resolve("thesis/sub/x.md"); Files.createDirectories(x.getParent()); Files.writeString(x, "# Notes\n\nA note deep in the folder.\n".repeat(8));
        Path y = Files.writeString(up.resolve("thesis/y.txt"), "A note at the top of the folder.\n".repeat(8));
        Files.writeString(up.resolve("thesis/photo.jpg"), "not a picture, and not a document either");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("file.path", a.toString()); form.put("file.name", "gears.txt");
        form.put("file.2.path", b.toString()); form.put("file.2.name", "dials.md");
        form.put("file.3.path", empty.toString()); form.put("file.3.name", "empty.txt");
        form.put("file.4.path", zip.toString()); form.put("file.4.name", "things.zip");
        form.put("file.5.path", html.toString()); form.put("file.5.name", "notes.html");
        form.put("folder.path", x.toString()); form.put("folder.name", "x.md"); form.put("folder.rel", "thesis/sub/x.md");
        form.put("folder.2.path", y.toString()); form.put("folder.2.name", "y.txt"); form.put("folder.2.rel", "thesis/y.txt");
        form.put("folder.3.path", up.resolve("thesis/photo.jpg").toString()); form.put("folder.3.name", "photo.jpg"); form.put("folder.3.rel", "thesis/photo.jpg");
        String out = Pages.addPost(store, new LibraryProtocol(store), Patrons.Patron.WEB, form);
        assertTrue(out.startsWith("<p><b>5 of 6 went onto the shelves.</b></p><ul>"), out);
        assertTrue(out.contains("<li><b>thesis</b> — Collection thesis: 2 added, 0 unchanged, 0 skipped of 2 document(s).</li>"), "the folder chosen whole, under a collection named for it, its picture left alone: " + out);
        assertTrue(out.contains("<li><b>gears.txt</b> · <a href=\"/entry/") && out.contains("<li><b>dials.md</b> · <a href=\"/entry/"), "a file whose title is only its name shows the name: " + out);
        assertTrue(out.contains("<li><b>notes.html</b> — On the Saros dial · <a href=\"/entry/"), "a file with a title of its own shows it: " + out);
        assertTrue(out.contains("<li><span class=\"k3\">not kept</span> <b>empty.txt</b> — No text could be read from empty.txt."), out);
        assertTrue(out.contains("<li><b>things.zip</b> — From things.zip: 2 added, 0 unchanged, 0 skipped of 2 document(s), under the collection things; 3 entries of the archive were left out. &quot;../evil.txt&quot;: its path points outside the folder, skipped. &quot;/abs.txt&quot;: its path points outside the folder, skipped. &quot;things/inner.zip&quot;: an archive inside the archive, not opened.</li>"), "the entries' names are shown escaped: " + out);
        assertFalse(Files.exists(up), "the upload, folders and all, is removed once read");
        assertEquals(7, ArchivesTest.raws(store).size(), "three files, two of the folder, two of the archive");
        // a collection named goes to every file and to the folder; a folder's files sent with their names alone are each a file
        Path up2 = Files.createTempDirectory(Uploads.DIR);
        Path c = Files.writeString(up2.resolve("c.txt"), "One more note about the Saros dial.\n".repeat(8));
        String out2 = Pages.addPost(store, new LibraryProtocol(store), Patrons.Patron.WEB, Map.of("folder.path", c.toString(), "folder.name", "c.txt", "collection", "mine"));
        assertTrue(out2.contains("<b>Added:</b> c.txt"), out2);
        assertTrue(ArchivesTest.raws(store).stream().anyMatch(r -> "mine".equals(RawCapture.collectionOf(r))), "shelved under the collection named");
        assertFalse(Files.exists(up2));
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
