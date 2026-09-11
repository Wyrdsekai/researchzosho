package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A directory is just a library: a listing is a submit there, finding a library is an ask there. */
class DirectoryTest {

    @Test
    void publishThenFind(@TempDir Path tmp) throws Exception {
        // the directory: a library anyone may read, where listers write
        LibraryStore dir = new LibraryStore(tmp.resolve("dir")); dir.init();
        Patrons.setDefault(dir, Patrons.Level.read);
        Patrons.set(dir, "did:key:ours", "our library", Patrons.Level.write);
        String writer = Patrons.issueToken(dir, "did:key:ours");
        LibrarianDaemon d = LibrarianDaemon.start(dir, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            String dirUrl = "http://127.0.0.1:" + d.port();
            // our library, with a couple of subjects in its vocabulary
            LibraryStore ours = new LibraryStore(tmp.resolve("ours")); ours.init();
            java.nio.file.Files.writeString(ours.root().resolve("catalog").resolve("subjects.md"),
                    "# Subjects\n\n- osaka-genealogy — family records of Osaka\n- edo-trade — merchant houses of the Edo period\n");
            var listing = Directory.listing(ours, "https://library.example.org/");
            String claim = listing.get("claim").asText();
            assertTrue(claim.contains("is a ResearchZosho library at https://library.example.org") && claim.contains("osaka-genealogy") && claim.contains("request_access"), claim);
            assertEquals("is listed at", listing.get("triple").get("predicate").asText());
            String id = Directory.publish(ours, dirUrl, writer, "https://library.example.org");
            assertTrue(id.startsWith("F-"), id);
            assertEquals(1, Directory.published(ours).size());
            // anyone can find it, no token, since the directory's default is read
            String found = Directory.find(dirUrl, null, "who has records of Osaka families");
            assertTrue(found.contains("Library: ours"), found);
            assertTrue(Directory.find(dirUrl, null, "quantum chromodynamics").contains("lists nothing"), "an honest miss");
            // a listing is a draft on the directory until its owner accepts it, like any submission
            assertTrue(dir.scanFindings().findings().stream().anyMatch(f -> f.id().equals(id) && f.state() == Finding.State.draft));
        } finally { d.stop(); }
    }

    @Test
    void publishingNeedsAWriterAndARealAddress(@TempDir Path tmp) throws Exception {
        LibraryStore dir = new LibraryStore(tmp.resolve("dir")); dir.init();
        Patrons.setDefault(dir, Patrons.Level.read);   // a directory that lists only who it knows
        LibrarianDaemon d = LibrarianDaemon.start(dir, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            LibraryStore ours = new LibraryStore(tmp.resolve("ours")); ours.init();
            var e = assertThrows(java.io.IOException.class, () -> Directory.publish(ours, "http://127.0.0.1:" + d.port(), "", "https://x.example"));
            assertTrue(e.getMessage().contains("forbidden"), e.getMessage());
            assertThrows(java.io.IOException.class, () -> Directory.publish(ours, "http://169.254.169.254", "t", "https://x.example"));
        } finally { d.stop(); }
    }
}
