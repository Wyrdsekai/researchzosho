package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/** The plan the housekeeping will follow, read from its own files: shelves due by cadence, questions by demand. */
class TonightTest {

    @Test
    void shelvesDueAndQuestionsAskedTwiceAreOnThePlan(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Serials.add(store, "lead", "lead paint regulation news", 7);          // never checked: due
        Serials.add(store, "titan", "Titan methane lakes", 30);
        // a shelf checked yesterday with a 30-day cadence is not due
        Path shelves = store.root().resolve("catalog").resolve("shelves.md");
        java.nio.file.Files.writeString(shelves, java.nio.file.Files.readString(shelves)
                .replaceAll("(titan \\| Titan methane lakes \\| every 30 days \\| last )\\S+", "$1" + LocalDate.now().minusDays(1)));
        Frontier.demand(store, "when was lead paint banned in Japan", "did:key:a");
        Frontier.demand(store, "when was lead paint banned in Japan", "did:key:b");   // asked twice: researchable
        Frontier.demand(store, "who painted the kura", "did:key:a");                 // once: waits
        Tonight.Plan p = Tonight.plan(store, LocalDate.now());
        assertEquals(1, p.due().size(), "one shelf due: " + p.due());
        assertEquals("lead", p.due().get(0).slug());
        assertEquals(1, p.picks().size(), "one question asked twice: " + p.picks());
        assertTrue(p.picks().get(0).text().contains("Japan"));
        assertEquals(0, p.stillOpen());
        String text = Tonight.text(p);
        assertTrue(text.contains("runs tonight   lead:") && text.contains("not yet due    titan:"), text);
        assertTrue(text.contains("run 1: when was lead paint banned in Japan  [asked, asked 2]"), text);
        assertFalse(text.contains("kura"), "asked once: not on the plan");
    }

    @Test
    void theNextRunIsTodayBeforeTheHourAndTomorrowAfter() {
        LocalDate d = LocalDate.of(2026, 9, 9);
        assertEquals(d, Tonight.nextRun(d, LocalTime.of(1, 0)));
        assertEquals(d.plusDays(1), Tonight.nextRun(d, LocalTime.of(9, 0)));
    }
}
