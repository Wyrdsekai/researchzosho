package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The findings shelf as the pages read it (0.8.0): read once, and a file read again only when it is new or changed on the disk.
 * Another process writes the folder too (the command line beside the service, a job), so a change it makes between two asks is
 * seen at the second: a file of another size, one of the same size at another time, one rewritten in the same second with the same
 * size, a new file, a file gone.
 */
class FindingsCacheTest {
    LibraryStore store;
    long maxBytes = FindingsCache.MAX_BYTES;

    @BeforeEach void clear() { FindingsCache.clear(); }
    @AfterEach void back() { FindingsCache.clear(); FindingsCache.MAX_BYTES = maxBytes; }

    static String text(String id, String title, String body) {
        return new Finding(id, title, new ArrayList<>(List.of("history")), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "person",
                "2026-11-17T09:00:00Z", "2026-11-17", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/a", "", "why")), List.of(), null, body).format();
    }

    /** A file written by another process, as the command line beside the service writes one; its time set to {@code ago} ms before now. */
    static void written(Path file, String content, long ago) throws Exception {
        Path src = Files.writeString(file.resolveSibling(".incoming-" + file.getFileName()), content, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("sh", "-c", "cat \"$0\" > \"$1\" && rm \"$0\"", src.toString(), file.toString()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0, new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ago));
    }

    static String titles(LibraryStore.Scan s) { return String.join(",", s.findings().stream().map(Finding::title).toList()) + " | problems " + s.problems().size(); }

    @Test
    void aShelfIsReadOnceAndAFileAgainOnlyWhenItChanged(@TempDir Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path dir = store.findingsDir();
        written(dir.resolve("F-0001-one.md"), text("F-0001-one", "One", "the first"), 60_000);
        written(dir.resolve("F-0002-two.md"), text("F-0002-two", "Two", "the second"), 60_000);
        Files.writeString(dir.resolve("F-0003-bad.md"), "not a finding", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dir.resolve("F-0003-bad.md"), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        long r0 = FindingsCache.READS.get();
        assertEquals("One,Two | problems 1", titles(store.scanFindings()));
        assertEquals(3, FindingsCache.READS.get() - r0, "each file read once");
        assertEquals("One,Two | problems 1", titles(new LibraryStore(tmp.resolve("lib")).scanFindings()), "another store of the same folder");
        assertEquals(3, FindingsCache.READS.get() - r0, "and nothing read again: nothing changed");

        // what one caller does to its findings no other caller sees
        store.scanFindings().findings().get(0).subjects().add("changed by a caller");
        assertEquals(List.of("history"), store.scanFindings().findings().get(0).subjects());

        // another process: a file of another size
        written(dir.resolve("F-0001-one.md"), text("F-0001-one", "One, longer now", "the first"), 30_000);
        assertEquals("One, longer now,Two | problems 1", titles(store.scanFindings()));
        // the same size at another time
        written(dir.resolve("F-0002-two.md"), text("F-0002-two", "Owt", "the second"), 20_000);
        assertEquals("One, longer now,Owt | problems 1", titles(store.scanFindings()));
        // a new file, and a file gone
        written(dir.resolve("F-0004-four.md"), text("F-0004-four", "Four", "the fourth"), 10_000);
        Files.delete(dir.resolve("F-0003-bad.md"));
        assertEquals("One, longer now,Owt,Four | problems 0", titles(store.scanFindings()));
        long r1 = FindingsCache.READS.get();
        store.scanFindings();
        assertEquals(r1, FindingsCache.READS.get(), "and after them nothing read again");
    }

    /** One finding by its id is read the same way: kept while its file is the one read, read again once another process changed it. */
    @Test
    void oneFindingByItsIdIsReadTheSameWay(@TempDir Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = store.findingsDir().resolve("F-0001-one.md");
        written(f, text("F-0001-one", "One", "the first"), 60_000);
        assertEquals("One", store.finding("F-0001-one").title());
        long r = FindingsCache.READS.get();
        assertEquals("One", store.finding("F-0001-one").title());
        assertEquals("One", store.scanFindings().findings().get(0).title());
        assertEquals(r, FindingsCache.READS.get(), "kept, for the scan too");
        store.finding("F-0001-one").subjects().add("changed by a caller");
        assertEquals(List.of("history"), store.finding("F-0001-one").subjects());
        written(f, text("F-0001-one", "One, changed", "the first"), 30_000);
        assertEquals("One, changed", store.finding("F-0001-one").title());
        written(f, text("F-0001-one", "Two, changed", "the first"), 20_000);   // the same size, another time
        assertEquals("Two, changed", store.finding("F-0001-one").title());
        Files.writeString(f, "not a finding", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> store.finding("F-0001-one"), "a named entry that is not a finding throws, as it did");
        Files.delete(f);
        assertNull(store.finding("F-0001-one"));
    }

    /** Two writes in one second with the same size have the same size and, on a disk that keeps seconds, the same time: a file that had not settled when it was read is read again. */
    @Test
    void aFileRewrittenWithTheSameSizeAndTheSameTimeIsSeenWhenItHadNotSettled(@TempDir Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = store.findingsDir().resolve("F-0001-one.md");
        written(f, text("F-0001-one", "Abc", "x"), 0);
        FileTime t = Files.getLastModifiedTime(f);
        assertEquals("Abc | problems 0", titles(store.scanFindings()));
        written(f, text("F-0001-one", "Xyz", "x"), 0);
        Files.setLastModifiedTime(f, t);   // the same size and the same time as when it was read
        assertEquals("Xyz | problems 0", titles(store.scanFindings()));
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        assertEquals("Xyz | problems 0", titles(store.scanFindings()));
        long r = FindingsCache.READS.get();
        store.scanFindings();
        assertEquals(r, FindingsCache.READS.get(), "settled now: kept");
    }

    /** What is kept is bounded: of one shelf at most so many bytes of files (the rest read at every ask), and at most so many shelves. */
    @Test
    void whatIsKeptIsBounded(@TempDir Path tmp) throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (int i = 1; i <= 4; i++) written(store.findingsDir().resolve("F-000" + i + "-n.md"), text("F-000" + i + "-n", "N" + i, "body"), 60_000);
        long one = Files.size(store.findingsDir().resolve("F-0001-n.md"));
        FindingsCache.MAX_BYTES = one * 2;
        store.scanFindings();
        long r = FindingsCache.READS.get();
        assertEquals("N1,N2,N3,N4 | problems 0", titles(store.scanFindings()));
        assertEquals(2, FindingsCache.READS.get() - r, "two kept, two read again");
        FindingsCache.MAX_BYTES = maxBytes;
        for (int s = 0; s < FindingsCache.SHELVES + 1; s++) {
            LibraryStore other = new LibraryStore(tmp.resolve("lib" + s)); other.init();
            written(other.findingsDir().resolve("F-0001-n.md"), text("F-0001-n", "N", "body"), 60_000);
            other.scanFindings();
        }
        r = FindingsCache.READS.get();
        new LibraryStore(tmp.resolve("lib0")).scanFindings();
        assertEquals(1, FindingsCache.READS.get() - r, "the shelf asked for longest ago was let go");
    }
}
