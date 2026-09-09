package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Living shelves are a search plus a set difference — testable with an injected search. */
class SerialsTest {

    @TempDir Path tmp;

    @Test
    void shelfLinesRoundTripAndDueIsByCadence() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Serials.add(store, "keigo", "敬語 字幕 翻訳 研究", 14);
        var s = Serials.shelves(store).get(0);
        assertEquals("keigo", s.slug());
        assertEquals("敬語 字幕 翻訳 研究", s.query());
        assertEquals(14, s.everyDays());
        assertTrue(s.due(LocalDate.of(2026, 9, 2)), "never checked = due");
        var checked = new Serials.Shelf("keigo", s.query(), 14, "2026-09-01");
        assertFalse(checked.due(LocalDate.of(2026, 9, 10)));
        assertTrue(checked.due(LocalDate.of(2026, 9, 15)));
        assertEquals(checked, Serials.Shelf.fromLine(checked.toLine()));
    }

    @Test
    void checkPresentsOnlyUnknownSourcesAndStampsTheShelf() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // known: a raw capture and a finding's source
        RawCapture.capture("https://known.org/paper", "text", "Known paper", "test");   // no library → null; write directly:
        Files.writeString(store.rawDir().resolve("2026-09-01-abc.md"),
                "---\nurl: https://known.org/paper\ntitle: Known\nfetched_at: t\nfetched_by: test\n---\nbody\n");
        store.write(new Finding("F-0001-x", "X", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("http://cited.org/a/", "n/a", "s")), List.of(), null, "b\n"));
        Serials.add(store, "keigo", "keigo subtitles", 7);
        var arrivals = Serials.check(store, q -> List.of(
                new String[]{"Known paper", "https://known.org/paper"},
                new String[]{"Cited (variant)", "https://cited.org/a"},
                new String[]{"Brand new", "https://new.org/thing"}), LocalDate.of(2026, 9, 2));
        assertEquals(1, arrivals.size());
        assertEquals("https://new.org/thing", arrivals.get(0).url());
        String table = Files.readString(Serials.arrivalsFile(store));
        assertTrue(table.contains("[keigo] https://new.org/thing — Brand new"), table);
        assertEquals("2026-09-02", Serials.shelves(store).get(0).lastChecked());
        // not due tomorrow → nothing searched, nothing new
        assertTrue(Serials.check(store, q -> { throw new AssertionError("not due"); }, LocalDate.of(2026, 9, 3)).isEmpty());
    }

    @Test
    void overdueListsAcceptedFindingsPastReviewBy() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-old", "Old", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-01-01", Finding.Volatility.fast, "2026-06-01",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, "b\n"));
        store.write(new Finding("F-0002-fresh", "Fresh", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-01", Finding.Volatility.slow, "2027-03-01",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, "b\n"));
        var over = Serials.overdue(store, LocalDate.of(2026, 9, 2));
        assertEquals(1, over.size());
        assertEquals("F-0001-old", over.get(0).id());
    }
}
