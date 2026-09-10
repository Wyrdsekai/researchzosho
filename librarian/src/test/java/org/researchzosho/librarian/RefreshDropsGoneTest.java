package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A file removed by hand leaves the search on the next refresh — no full rebuild needed. */
class RefreshDropsGoneTest {

    static Finding claim(String id, String title, String body) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "person",
                "2026-09-01T10:00:00Z", "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "src")), List.of(), null, body);
    }

    @Test
    void refreshForgetsRemovedFilesAndTheirChunks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(claim("F-0001-gears", "The gears were cut by hand", "The museum states the gears were cut with files. https://museum.example/gears\n"));
        store.write(claim("F-0002-teeth", "Tooth profiles measured by CT", "CT scans show triangular teeth. https://museum.example/ct\n"));
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.rebuild();
        String raw = "R-0001-museum-page.md";
        idx.upsertRaw(raw, "Museum page on the gears", "https://museum.example/gears", "Paragraph one about gears cut with files.\n\nParagraph two about dividing plates and hand files.\n");
        Files.writeString(store.rawDir().resolve(raw), "---\nlocator: https://museum.example/gears\ntitle: Museum page on the gears\n---\nParagraph one.\n");
        assertTrue(idx.search("gears cut", 5).stream().anyMatch(h -> h.id().equals("F-0001-gears")));
        assertTrue(idx.search("dividing plates hand files", 5).stream().anyMatch(h -> raw.equals(h.id()) || h.id().startsWith(raw)), "the raw capture or a chunk of it is found");
        // a person removes the claim's file and the raw capture; nothing else changed
        Files.delete(store.findingsDir().resolve("F-0001-gears.md"));
        Files.delete(store.rawDir().resolve(raw));
        int n = idx.refresh();
        assertTrue(n >= 2, "two entries dropped, counted: " + n);
        assertFalse(idx.search("gears cut", 5).stream().anyMatch(h -> h.id().equals("F-0001-gears")), "the removed claim is gone from the search");
        assertFalse(idx.search("dividing plates hand files", 5).stream().anyMatch(h -> raw.equals(h.id()) || h.id().startsWith(raw)), "and the capture with its chunks");
        assertTrue(idx.search("tooth profiles", 5).stream().anyMatch(h -> h.id().equals("F-0002-teeth")), "the other claim stays");
        assertEquals(0, idx.dropGone(), "nothing more to drop");
    }
}
