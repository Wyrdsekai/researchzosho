package org.researchzosho.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Corpus;
import org.researchzosho.librarian.LibrarianDaemon;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.ModelEntries;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Translation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client's call for a document's translation against the real daemon, in-process, on a temporary library and a home of its own: the
 * notes go onto the wire as asked, the library's setting when they are left out, and the answer and the job say which the run was filed
 * with. Only filing is tested here; the model is a closed port, so the run itself fails, as it would with no model up.
 */
class LibrarianClientTranslateTest {

    static LibrarianDaemon daemon;
    static LibraryStore store;
    static String token, raw, realHome;

    @BeforeAll static void up(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        ModelEntries.put("homebox", "http://127.0.0.1:1", "stand-in", null, null, null, null);   // a model entry, so a translation is offered; nothing listens there
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path doc = Files.createDirectories(tmp.resolve("docs")).resolve("clock.md");
        Files.writeString(doc, "# The clock\n\nThe first wheel turns once in 7 hours and carries the hand around the dial.\n", StandardCharsets.UTF_8);
        raw = Corpus.addFile(store, doc, "", false).getFileName().toString();
        Patrons.set(store, "did:key:zT", "a program at homebox", Patrons.Level.write);
        token = Patrons.issueToken(store, "did:key:zT");
        daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
    }

    @AfterAll static void down() {
        if (daemon != null) daemon.stop();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    @Test void theNotesGoOntoTheWireAsAskedAndTheSettingDecidesWhenLeftOut() throws Exception {
        LibrarianClient lib = new LibrarianClient(URI.create(daemon.url()), token);
        JsonNode end = lib.translate(raw, "Japanese", null, "quick", "end");
        assertEquals("end", end.path("notes").asText(), end.toString());
        assertEquals("ja", end.path("to").asText()); assertEquals("quick", end.path("quality").asText());
        assertEquals("end", lib.job(end.path("job_id").asText()).path("notes").asText(), "the job says what it was filed with");
        JsonNode off = lib.translate(raw, "ja", "txt", "quick", "off");
        assertEquals("off", off.path("notes").asText()); assertEquals("txt", off.path("as").asText());
        assertEquals("off", lib.job(off.path("job_id").asText()).path("notes").asText());
        // left out: the library's setting, off unless set, end when it says so
        assertEquals("off", lib.translate(raw, "ja", null, "quick", null).path("notes").asText());
        Config.set(Translation.NOTES_SETTING, "end");
        try { assertEquals("end", lib.translate(raw, "ja", null, "quick", null).path("notes").asText(), "the setting is the default"); }
        finally { Config.set(Translation.NOTES_SETTING, ""); }
        // a word that is neither: refused with the sentence that says both
        LibraryException bad = assertThrows(LibraryException.class, () -> lib.translate(raw, "ja", null, "quick", "sideways"));
        assertEquals("invalid_args", bad.code); assertEquals(400, bad.status); assertEquals(Translation.NOTES_SAID, bad.getMessage());
    }

    @Test void aDirectionGoesOntoTheWireAndTheJobSaysIt() throws Exception {
        LibrarianClient lib = new LibrarianClient(URI.create(daemon.url()), token);
        String direction = "as if you were Shakespeare";
        JsonNode filed = lib.translate(raw, "ja", null, "quick", null, direction);
        assertEquals(direction, filed.path("direction").asText(), filed.toString());
        JsonNode job = lib.job(filed.path("job_id").asText());
        assertEquals(direction, job.path("translation").path("direction").asText(), job.toString());
        assertTrue(job.path("translation").path("said").asText().endsWith("In the direction given: \"" + direction + "\"."), job.toString());
        assertFalse(lib.translate(raw, "ja", null, "quick", null).has("direction"), "the five-argument call files none");
        LibraryException tooLong = assertThrows(LibraryException.class, () -> lib.translate(raw, "ja", null, "quick", null, "x".repeat(Translation.DIRECTION_MAX + 1)));
        assertEquals("invalid_args", tooLong.code); assertEquals(400, tooLong.status);
        assertTrue(tooLong.getMessage().startsWith("A direction for a translation is at most " + Translation.DIRECTION_MAX + " characters"), tooLong.getMessage());
    }

    /** A comic as an archive of page pictures: three white pages, which is all the plan needs to count them. */
    static Path volume(Path dir) throws Exception {
        Path cbz = dir.resolve("volume.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            for (int n = 1; n <= 3; n++) {
                BufferedImage page = new BufferedImage(300, 420, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = page.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 300, 420); g.dispose();
                ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(page, "png", png);
                zip.putNextEntry(new ZipEntry(String.format("%03d.png", n))); zip.write(png.toByteArray()); zip.closeEntry();
            }
        }
        return cbz;
    }

    @Test void aComicIsRefusedWithWhatASetUpFetchesAndFiledOnTheYes(@TempDir Path tmp) throws Exception {
        Path cbz = volume(Files.createDirectories(tmp.resolve("comics")));
        String comic = RawCapture.capture(store, "file://" + cbz.toAbsolutePath().normalize(), "A volume of three pages.", "volume", "researchzosho-add", "").getFileName().toString();
        LibrarianClient lib = new LibrarianClient(URI.create(daemon.url()), token);
        // the comic helper is not set up in this home: the call is refused with what a set-up fetches, as a sentence and as data
        LibraryException asked = assertThrows(LibraryException.class, () -> lib.translate(comic, "English", null, "quick", null, null, null, false));
        assertEquals("confirm", asked.code); assertEquals(422, asked.status);
        assertTrue(asked.getMessage().startsWith("Reading a comic's pages needs the comic helper, which is not set up on this machine yet.") && asked.getMessage().endsWith("send the same call with setup=true to set the comic helper up and translate it."), asked.getMessage());
        JsonNode helper = asked.error.path("comic_helper");
        assertFalse(helper.path("set_up").asBoolean(true)); assertTrue(helper.path("download_bytes").asLong() > 0, helper.toString());
        assertTrue(helper.path("fetches").size() >= 3 && helper.path("fetches").get(0).hasNonNull("licence"), "each piece with its size and its licence: " + helper);
        // the same call with the yes: filed as a comic, and the job sets the helper up first
        JsonNode filed = lib.translate(comic, "English", null, "quick", null, null, null, true);
        assertEquals(3, filed.path("comic").path("pages").asInt(), filed.toString()); assertTrue(filed.path("comic_setup").asBoolean()); assertEquals("cbz", filed.path("as").asText());
        assertTrue(filed.path("summary").asText().contains("(a comic of 3 pages, the language of its lettering not read yet) into English as cbz, quick"), filed.path("summary").asText());
        assertEquals("", filed.path("comic").path("from").asText()); assertEquals("at-start", filed.path("comic").path("from_how").asText(), "no helper in this home to look with: the run finds the language when it starts");
        // from names the language the comic is lettered in: it reaches the library, which files the job with it and says it back
        JsonNode korean = lib.translate(comic, "English", null, "quick", null, null, null, true, "Korean");
        assertEquals("ko", korean.path("from").asText()); assertEquals("ko", korean.path("comic").path("from").asText()); assertEquals("named", korean.path("comic").path("from_how").asText());
        assertTrue(korean.path("summary").asText().contains("(a comic of 3 pages, its lettering read as Korean) into English as cbz, quick"), korean.path("summary").asText());
        // a language the helper does not read, and a comic said to be in the language asked for, are refused with the reason
        LibraryException notRead = assertThrows(LibraryException.class, () -> lib.translate(comic, "English", null, "quick", null, null, null, true, "Arabic"));
        assertEquals("invalid_args", notRead.code); assertTrue(notRead.getMessage().endsWith("Arabic is not read."), notRead.getMessage());
        assertEquals("\"volume.cbz\" is written in English already.", assertThrows(LibraryException.class, () -> lib.translate(comic, "English", null, "quick", null, null, null, true, "en")).getMessage());
        // reads says which way a comic's pages are read: it reaches the library, which files the job with it and says it back with how it is known
        JsonNode edition = lib.translate(comic, "German", null, "quick", null, null, null, true, "English", "right-to-left");
        assertEquals("rtl", edition.path("comic").path("reads").asText(), edition.toString()); assertEquals("said", edition.path("comic").path("reads_how").asText());
        assertEquals("ltr", korean.path("comic").path("reads").asText()); assertEquals("found", korean.path("comic").path("reads_how").asText(), "nothing said: a Korean comic is read from the left");
        LibraryException sideways = assertThrows(LibraryException.class, () -> lib.translate(comic, "German", null, "quick", null, null, null, true, "English", "sideways"));
        assertEquals("invalid_args", sideways.code); assertTrue(sideways.getMessage().startsWith("reads is right-to-left or left-to-right"), sideways.getMessage());
        // a document takes from the same way
        assertEquals("de", lib.translate(raw, "ja", null, "quick", null, null, null, false, "German").path("from").asText());
        // said to be a document, the archive is none the document reader takes; a word that is neither on nor off is refused
        assertEquals("invalid_args", assertThrows(LibraryException.class, () -> lib.translate(comic, "English", null, "quick", null, null, "off", false)).code);
        assertEquals("comic is on (the file is a comic, whatever the program finds) or off (it is a document).", assertThrows(LibraryException.class, () -> lib.translate(comic, "English", null, "quick", null, null, "maybe", false)).getMessage());
        // a document is translated as before by the longer call, and an error the library did not send has no data
        assertFalse(lib.translate(raw, "ja", null, "quick", null, null, null, false).has("comic"));
        assertNull(new LibraryException("unavailable", 0, "no answer").error);
    }
}
