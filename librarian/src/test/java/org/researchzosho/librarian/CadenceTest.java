package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Three cadences: an incremental nightly refresh, weekly reports that propose, a monthly full rebuild. */
class CadenceTest {

    private static Finding f(String id, String title, String body, String locator) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "s")), List.of(), null, body);
    }

    @Test
    void refreshIndexesOnlyWhatChangedSinceTheStamp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-a", "Alpha claim", "Alpha body about gears.\n", "https://ex.org/a"));
        store.write(f("F-0002-b", "Beta claim", "Beta body about tablets.\n", "https://ex.org/b"));
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        assertEquals(2, idx.rebuild());
        assertEquals("none", idx.indexedWith());
        Instant stamp = idx.lastIndexed();
        assertTrue(stamp.isAfter(Instant.EPOCH));
        // nothing changed: nothing re-indexed
        Thread.sleep(20);
        assertEquals(0, idx.refresh());
        // one finding edited on disk (newer mtime), one new raw capture
        Path fa = store.findingsDir().resolve("F-0001-a.md");
        Files.writeString(fa, Files.readString(fa).replace("about gears", "about Antikythera gears"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(fa, FileTime.from(Instant.now().plusSeconds(2)));
        Files.createDirectories(store.rawDir());
        Path raw = store.rawDir().resolve("2026-09-05-new.md");
        Files.writeString(raw, "---\nurl: https://ex.org/new\ntitle: New page\nfetched_at: t\nfetched_by: test\n---\nA page about Babylonian tablets.\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(raw, FileTime.from(Instant.now().plusSeconds(2)));
        assertEquals(2, idx.refresh(), "the edited finding and the new capture, nothing else");
        assertFalse(idx.search("Antikythera", 5).isEmpty(), "the edit is searchable");
        assertTrue(idx.search("Babylonian tablets", 5).stream().anyMatch(h -> "raw".equals(h.kind())), "the capture is searchable");
        assertEquals(1, idx.search("Beta claim", 5).stream().filter(h -> h.id().equals("F-0002-b")).count(), "the untouched one is still there, once");
        // an embedder change forces a full rebuild
        Files.writeString(idx.embedderFile(), "some-other-embedder", StandardCharsets.UTF_8);
        assertEquals(3, idx.refresh(), "full rebuild: 2 findings + 1 raw");
    }

    @Test
    void cadenceFallsOnTheConfiguredDays() {
        assertTrue(Crews.Cadence.tonight(LocalDate.of(2026, 9, 6)).weekly(), "Sunday");
        assertFalse(Crews.Cadence.tonight(LocalDate.of(2026, 9, 7)).weekly(), "Monday");
        assertTrue(Crews.Cadence.tonight(LocalDate.of(2026, 10, 1)).monthly());
        assertFalse(Crews.Cadence.tonight(LocalDate.of(2026, 9, 5)).monthly());
    }

    @Test
    void weeklyReportsProposeAndThePersonPrunes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-a", "Keigo has no direct English equivalent", "Japanese keigo has no direct English equivalent in subtitles.\n", "https://ex.org/cited"));
        store.write(f("F-0002-b", "Keigo lacks a direct English equivalent", "Japanese keigo lacks any direct English equivalent in subtitles.\n", "https://ex.org/cited2"));
        store.write(f("F-0003-c", "Subtitle timing", "A subtitle line stays for seven seconds.\n", "https://ex.org/timing"));
        Files.createDirectories(store.rawDir());
        for (String[] r : new String[][]{{"cited", "https://ex.org/cited"}, {"old-orphan", "https://ex.org/nobody"}, {"young-orphan", "https://ex.org/nobody2"}}) {
            Path p = store.rawDir().resolve("2026-08-01-" + r[0] + ".md");
            Files.writeString(p, "---\nurl: " + r[1] + "\ntitle: " + r[0] + "\nfetched_at: t\nfetched_by: test\n---\ntext\n", StandardCharsets.UTF_8);
            if (!r[0].equals("young-orphan")) Files.setLastModifiedTime(p, FileTime.from(Instant.now().minusSeconds(60L * 60 * 24 * 40)));
        }
        String dup = Reports.duplicates(store);
        assertTrue(dup.startsWith("1 candidate pair"), dup);
        String report = Files.readString(Reports.duplicatesFile(store));
        assertTrue(report.contains("F-0001-a ≈ F-0002-b"), report);
        assertFalse(report.contains("F-0003-c"));
        String orph = Reports.orphans(store);
        assertTrue(orph.startsWith("1 orphan"), orph);
        String list = Files.readString(Reports.orphansFile(store));
        assertTrue(list.contains("2026-08-01-old-orphan.md"));
        assertFalse(list.contains("cited.md"), "a cited capture is never an orphan");
        assertFalse(list.contains("young-orphan"), "too young to call");
        // no crew deletes; the person does
        assertTrue(Files.exists(store.rawDir().resolve("2026-08-01-old-orphan.md")));
        assertEquals(1, Reports.prune(store));
        assertFalse(Files.exists(store.rawDir().resolve("2026-08-01-old-orphan.md")));
        assertTrue(Files.exists(store.rawDir().resolve("2026-08-01-cited.md")));
        // the crews carry the weekly steps only on a weekly night
        var nightly = Crews.runAll(store, "", "m", (q, w) -> null, 0, new Crews.Cadence(false, false));
        assertTrue(nightly.stream().noneMatch(s -> s.name().equals("duplicates")));
        assertTrue(nightly.stream().anyMatch(s -> s.name().equals("refresh")));
        assertTrue(nightly.stream().anyMatch(s -> s.name().equals("heat")));
        var weekly = Crews.runAll(store, "", "m", (q, w) -> null, 0, new Crews.Cadence(true, true));
        assertTrue(weekly.stream().anyMatch(s -> s.name().equals("orphans")));
        assertTrue(weekly.stream().anyMatch(s -> s.name().equals("rebuild")), "monthly = full rebuild");
    }
}
