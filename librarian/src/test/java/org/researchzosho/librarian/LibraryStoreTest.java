package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryStoreTest {

    @TempDir
    Path tmp;

    private LibraryStore store() throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("library"));
        s.init();
        return s;
    }

    private static Finding draft(String id, String title) {
        return new Finding(id, title, List.of("test--subject"), Finding.State.draft,
                Finding.ClaimType.extraction, Finding.Confidence.medium, "person",
                "2026-09-01T10:00:00Z", "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "the source")),
                List.of(), null, "Body.\n");
    }

    @Test
    void initIsIdempotentAndSeedsCatalog() throws Exception {
        LibraryStore s = store();
        Files.writeString(s.subjectsFile(), "# my edited vocabulary\n", StandardCharsets.UTF_8);
        s.init(); // second init must not clobber the person's edits
        assertEquals("# my edited vocabulary\n", Files.readString(s.subjectsFile()));
        assertTrue(Files.isDirectory(s.rawDir()));
        assertTrue(Files.exists(s.frontierFile()));
    }

    @Test
    void idsAllocateSeriallyAndSlugTitles() throws Exception {
        LibraryStore s = store();
        String id1 = s.nextFindingId("First thing we learned!");
        assertEquals("F-0001-first-thing-we-learned", id1);
        s.write(draft(id1, "First thing we learned!"));
        assertEquals("F-0002-second", s.nextFindingId("Second"));
        // non-ascii (JA) titles fall back rather than producing an empty slug — and every issued
        // serial is SPENT whether or not it is written (ids are never reused)
        assertEquals("F-0003-entry", s.nextFindingId("字幕の翻訳"));
    }

    @Test
    void writeRoundTripsAndReadsBack() throws Exception {
        LibraryStore s = store();
        Finding f = draft(s.nextFindingId("A claim"), "A claim");
        s.write(f);
        assertEquals(f, s.finding(f.id()));
        assertNull(s.finding("F-9999-absent"));
    }

    @Test
    void scanNamesMalformedFilesInsteadOfSkippingOrCrashing() throws Exception {
        LibraryStore s = store();
        s.write(draft(s.nextFindingId("Good"), "Good"));
        Files.writeString(s.findingsDir().resolve("F-0002-bad.md"),
                "---\nschema: 1\nid: F-0002-bad\n---\nno required fields\n", StandardCharsets.UTF_8);
        LibraryStore.Scan scan = s.scanFindings();
        assertEquals(1, scan.findings().size());
        assertEquals(1, scan.problems().size());
        assertTrue(scan.problems().get(0).startsWith("F-0002-bad.md:"), scan.problems().get(0));
    }

    @Test
    void scanFlagsIdFilenameMismatch() throws Exception {
        LibraryStore s = store();
        Finding f = draft("F-0001-real-name", "Real name");
        Files.writeString(s.findingsDir().resolve("F-0001-other-name.md"), f.format(),
                StandardCharsets.UTF_8);
        LibraryStore.Scan scan = s.scanFindings();
        assertTrue(scan.findings().isEmpty());
        assertTrue(scan.problems().get(0).contains("does not match filename"));
    }

    @Test
    void frontierAndCirculationAppend() throws Exception {
        LibraryStore s = store();
        s.frontier("gap", "re-run X on a healthy substrate");
        s.frontier("trajectory", "what does the next form of noir look like?");
        String frontier = Files.readString(s.frontierFile());
        assertTrue(frontier.contains("[gap] re-run X"));
        assertTrue(frontier.contains("[trajectory] what does the next form"));
        s.circulate("desk", "have we established X?");
        assertTrue(Files.readString(s.circulationFile()).contains("desk\thave we established X?"));
    }

    @Test
    void indexRegenerationListsEntriesStatesAndProblems() throws Exception {
        LibraryStore s = store();
        Finding f = draft(s.nextFindingId("Known thing"), "Known thing");
        s.write(f);
        Files.writeString(s.findingsDir().resolve("F-0009-broken.md"), "not frontmatter",
                StandardCharsets.UTF_8);
        s.write(new Investigation("I-0001-run", "The run", Finding.State.draft, "person",
                "2026-09-01T10:00:00Z", List.of(f.id()), List.of(), "Synthesis.\n"));
        s.regenerateIndex();
        String index = Files.readString(s.indexFile());
        assertTrue(index.contains("[draft] " + f.id() + " — Known thing"));
        assertTrue(index.contains("[draft] I-0001-run — The run"));
        assertTrue(index.contains("F-0009-broken.md"));
    }

    @Test
    void aDeletedEntryNeverFreesItsId(@TempDir java.nio.file.Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp); s.init();
        String first = s.nextFindingId("smoke");
        assertTrue(first.startsWith("F-0001-"));
        // nothing was written under that id, yet the number is spent
        assertTrue(s.nextFindingId("next").startsWith("F-0002-"), "the ledger, not the directory, is the authority");
        // a file written and then removed does not free its number either
        java.nio.file.Files.writeString(s.findingsDir().resolve("F-0007-x.md"), "x");
        assertTrue(s.nextFindingId("after").startsWith("F-0008-"));
        java.nio.file.Files.delete(s.findingsDir().resolve("F-0007-x.md"));
        assertTrue(s.nextFindingId("again").startsWith("F-0009-"));
        assertTrue(s.nextInvestigationId("inv").startsWith("I-0001-"));
    }
}
