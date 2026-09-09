package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Shelf reading marks what the source no longer supports; the backup is a dated zip, pruned. */
class InventoryAndBackupTest {

    private static Finding f(String id, String title, String body, String locator) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "model:test", Instant.now().toString(), "2026-09-03", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "s")), List.of(),
                new Finding.Review(1, "librarian:test", "accepted", "sha256:x", Instant.now().toString()), body);
    }

    @Test
    void inventoryDisputesWhatTheSourceDoesNotSupportAndLogsTheRest(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-ok", "Keigo has no English equivalent", "Keigo has no direct English equivalent.\n", "https://ex.org/a"));
        store.write(f("F-0002-bad", "Subtitles keep every honorific", "English subtitles keep every honorific verbatim.\n", "https://ex.org/b"));
        store.write(f("F-0003-nocapture", "Uncaptured claim", "A claim whose source was never captured.\n", "https://ex.org/never"));
        Files.createDirectories(store.rawDir());
        Files.writeString(store.rawDir().resolve("2026-09-01-aaa.md"), "---\nurl: https://ex.org/a\ntitle: A\nfetched_at: t\nfetched_by: test\n---\nKeigo has no direct English equivalent, says the paper.\n", StandardCharsets.UTF_8);
        Files.writeString(store.rawDir().resolve("2026-09-01-bbb.md"), "---\nurl: https://ex.org/b\ntitle: B\nfetched_at: t\nfetched_by: test\n---\nSubtitlers usually drop honorifics entirely.\n", StandardCharsets.UTF_8);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Inventory.Checker stub = (claim, src) -> src.contains("drop honorifics")
                ? "{\"verdict\":\"unsupported\",\"reason\":\"the source says honorifics are dropped\"}"
                : "{\"verdict\":\"supported\",\"reason\":\"stated verbatim\"}";
        var checks = Inventory.run(store, stub, 3);
        assertEquals(3, checks.size());
        var byId = new java.util.HashMap<String, Inventory.Check>();
        for (var c : checks) byId.put(c.id(), c);
        assertEquals("supported", byId.get("F-0001-ok").verdict());
        assertEquals("unsupported", byId.get("F-0002-bad").verdict());
        assertEquals("no-capture", byId.get("F-0003-nocapture").verdict());
        Finding bad = store.finding("F-0002-bad");
        assertEquals(Finding.State.disputed, bad.state());
        assertEquals("inventory", bad.review().reviewer());
        assertEquals(2, bad.review().round());
        assertTrue(bad.body().contains("DISPUTED-BY: inventory"));
        assertTrue(Files.readString(store.frontierFile()).contains("F-0002-bad"));
        assertEquals(3, Files.readAllLines(Inventory.log(store)).size());
        // the next night starts with the least recently checked — everything was checked; the unsupported one is disputed so it is out
        var again = Inventory.run(store, stub, 1);
        assertEquals(1, again.size());
        assertNotEquals("F-0002-bad", again.get(0).id());
        // the changes feed carries the recall notice
        assertTrue(Changes.since(store, 0, 100).stream().anyMatch(c -> c.id().equals("F-0002-bad") && c.event().equals("state:accepted→disputed")));
    }

    @Test
    void excerptCentresOnTheClaimsVocabulary() {
        String far = "x ".repeat(6000) + "the honorific system keigo in subtitles " + "y ".repeat(6000);
        String e = Inventory.excerpt(far, "keigo honorific subtitles");
        assertTrue(e.length() <= Inventory.SOURCE_CHARS);
        assertTrue(e.contains("keigo"), "the window holds the matching passage");
    }

    @Test
    void backupZipsEverythingButTheIndexAndPrunes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-a", "A", "A body.\n", "https://ex.org/a"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path target = tmp.resolve("backups");
        Files.createDirectories(target);
        for (int i = 0; i < 3; i++) Files.writeString(target.resolve("lib-2026-01-0" + (i + 1) + ".zip"), "old", StandardCharsets.UTF_8);
        Path zip = Backup.run(store, target, 2);
        assertTrue(Files.exists(zip));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertNotNull(z.getEntry("findings/F-0001-a.md"));
            assertNotNull(z.getEntry("catalog/subjects.md"));
            assertTrue(z.stream().noneMatch(e -> e.getName().startsWith(".index")), "the index is rebuildable, not backed up");
        }
        try (var s = Files.list(target)) { assertEquals(2, s.count(), "pruned to keep=2: today's and the newest old one"); }
        assertEquals(tmp.resolve("lib-backups"), Backup.dir(store));
    }
}
