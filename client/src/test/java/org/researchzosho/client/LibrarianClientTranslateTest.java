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
import org.researchzosho.librarian.Translation;

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
}
