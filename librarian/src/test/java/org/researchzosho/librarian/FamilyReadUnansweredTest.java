package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A read whose model gives no answer at all is not a read that found nobody. It says that the model at its address could not be reached
 * or did not answer, and what to do; it files nothing from that file and does not write the file down as read, so the next read does the
 * whole file; it stops after the first file when the model cannot be reached; and the command ends with a code that is not 0.
 */
class FamilyReadUnansweredTest {

    static final String ADDRESS = "http://127.0.0.1:9";
    static final String MINER = "Tom Ellis worked as a miner in York.";
    static final String SISTER = "Ruth Ellis is the sister of Tom Ellis.";
    static final String FACTS = """
            {"people": [], "facts": [
             {"subject": "Tom Ellis", "relation": "occupation", "object": "miner", "date": "", "quote": "Tom Ellis worked as a miner in York."}]}""";

    @AfterEach void restore() { GenealogyProfile.useReader(null); }

    record Ran(int rc, String out) { }

    static Ran run(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out, wasErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(out, true, StandardCharsets.UTF_8);
        System.setOut(both); System.setErr(both);
        Integer rc;
        try { rc = new GenealogyProfile().cli(store, args); } finally { System.setOut(was); System.setErr(wasErr); }
        return new Ran(rc == null ? -1 : rc, out.toString(StandardCharsets.UTF_8));
    }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    private static Path folder(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("family-sources"));
        Files.writeString(dir.resolve("a-notes.txt"), MINER + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b-notes.txt"), SISTER + "\n", StandardCharsets.UTF_8);
        return dir;
    }

    /** The files of the folder the library has written down as read. */
    private static List<String> readBefore(LibraryStore store, Path dir) throws Exception {
        return FamilyFolder.plan(store, dir).stream().filter(FamilyFolder.Item::readBefore).map(it -> it.file().getFileName().toString()).toList();
    }

    @Test
    void aFolderReadStopsAtTheFirstFileWhenTheModelCannotBeReachedAndMarksNothingRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp);
        List<String> asked = new ArrayList<>();
        // the scripted drive: every question ends in the connection error the model server's address gives when nothing listens there
        GenealogyProfile.useReader(prompt -> { asked.add(prompt); throw new FamilyAccount.Unanswered(ADDRESS, new ConnectException("Connection refused")); });
        Ran r = run(store, "read", dir.toString());
        assertNotEquals(0, r.rc(), r.out());
        assertFalse(r.out().contains("did not find anybody from a family"), r.out());
        assertFalse(r.out().contains("Reading the same file again usually fixes this"), r.out());
        assertTrue(r.out().contains("The model at " + ADDRESS + " could not be reached, so the library read nothing from a-notes.txt and added nothing from it to your library. It did not mark the file as read."), r.out());
        assertTrue(r.out().contains("The library stopped here and did not read the other file, because reading a file needs the model."), r.out());
        assertTrue(r.out().contains("Start the model, or check that " + ADDRESS + " is the right address of the model (the setting RESEARCHZOSHO_DRIVE), then give the same command again."), r.out());
        assertFalse(r.out().contains("WHAT HAPPENS NEXT"), "no next step that needs the model: " + r.out());
        assertEquals(1, asked.size(), "the second file was not tried");
        assertEquals(List.of(), readBefore(store, dir), "nothing is written down as read");

        // the model answers again: the same command reads both files
        GenealogyProfile.useReader(prompt -> prompt.contains("worked as a miner") ? FACTS : "{\"people\": [], \"facts\": []}");
        Ran again = run(store, "read", dir.toString());
        assertEquals(0, again.rc(), again.out());
        assertEquals(List.of("a-notes.txt", "b-notes.txt"), readBefore(store, dir));
    }

    @Test
    void aFileWithAPartTheModelDidNotAnswerFilesNothingAndIsReadWholeNextTime(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family-sources"));
        // two parts: the first answered, the second not answered in time
        String filler = "The shop sold tea and rice to the people of the town. ";
        Files.writeString(dir.resolve("a-notes.txt"), MINER + " " + filler.repeat(70) + "\n\nThe second part. " + filler.repeat(70) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b-notes.txt"), SISTER + "\n", StandardCharsets.UTF_8);
        assertEquals(2, FamilyAccount.pieces(Files.readString(dir.resolve("a-notes.txt"))).size());
        GenealogyProfile.useReader(prompt -> {
            if (prompt.contains("The second part.")) throw new FamilyAccount.Unanswered(ADDRESS, new HttpTimeoutException("the model server at " + ADDRESS + " gave no answer within 5 minutes"));
            return prompt.contains("worked as a miner") ? FACTS : "{\"people\": [], \"facts\": []}";
        });
        Ran r = run(store, "read", dir.toString());
        assertNotEquals(0, r.rc(), r.out());
        assertTrue(r.out().contains("The model at " + ADDRESS + " did not answer, so the library read nothing from a-notes.txt and added nothing from it to your library. It did not mark the file as read."), r.out());
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().subject().equals("Tom Ellis")), "nothing of the file is filed");
        // a model that answers late is not out of reach: the next file is read
        assertEquals(List.of("b-notes.txt"), readBefore(store, dir));
        assertTrue(r.out().contains("Check that the model at " + ADDRESS + " is running and not busy with other work, then give the same command again. It reads the files that are not marked as read."), r.out());
    }

    @Test
    void aFileReadWithTheLibrarysOwnDriveAtAnAddressWhereNothingListens(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = Files.writeString(tmp.resolve("notes.txt"), MINER + "\n", StandardCharsets.UTF_8);
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }   // closed again: nothing listens there
        String address = "http://127.0.0.1:" + port;
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        try {
            Config.set("RESEARCHZOSHO_DRIVE", address);
            Ran r = run(store, "read", file.toString());
            assertEquals(1, r.rc(), r.out());
            assertTrue(r.out().contains("The model at " + address + " could not be reached, so the library read nothing from notes.txt and added nothing from it to your library. It did not mark the file as read."), r.out());
            assertFalse(r.out().contains("did not find anybody from a family"), r.out());
            assertTrue(FamilyReads.everyRow(store).isEmpty(), "the file is not written down as read");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
