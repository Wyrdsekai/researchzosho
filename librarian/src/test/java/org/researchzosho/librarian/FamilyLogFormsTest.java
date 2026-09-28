package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The research log of a person who carried more than one name: each search is followed by the name it carried and the years the person
 * carried that name, or by "both names" for the search that finds the record of the change. Nothing new is stored. Invented names only.
 */
class FamilyLogFormsTest {

    @Test
    void eachSearchIsFollowedByTheNameItCarriedWithItsYears(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyQuestionsPeriodsTest.kenji(tmp);
        SearchLog.add(store, List.of(
                new SearchLog.Entry("2026-09-20", "森田健二 (born 1905): …", "web", "\"森田健二\" 広島", 1932, 1980, 3, "J-1"),
                new SearchLog.Entry("2026-09-21", "森田健二 (born 1905): …", "loc-newspapers", "\"Kenji Endo\"", 1905, 1932, 0, "J-1"),
                new SearchLog.Entry("2026-09-22", "森田健二 (born 1905): …", "ndl-fulltext", "遠藤健二 森田家 婿養子", 0, 0, 1, "J-2"),
                new SearchLog.Entry("2026-09-23", "森田健二 (born 1905): …", "web", "健二 広島 1932", 0, 0, 5, "J-2")));
        Looked.add(store, new Looked.Entry("2026-09-23", "森田健二", "Endou Kenji 1905", List.of("familysearch"), "", ""));
        String log = FamilyNamesFilingTest.run(store, "log", "森田健二");
        assertTrue(log.contains("Every search the library made for 森田健二 (born 遠藤), newest first."), "the heading names the person with the name at birth: " + log);
        assertTrue(log.contains("each line ends with the name the search used and the years the person carried that name"), log);
        assertTrue(line(log, "\"森田健二\" 広島").endsWith("[name: 森田健二, from 1932]"), log);
        assertTrue(line(log, "\"Kenji Endo\"").endsWith("[name: 遠藤健二, 1905–1932]"), "a search in romaji is marked with the name it is a form of: " + log);
        assertTrue(line(log, "遠藤健二 森田家 婿養子").endsWith("[both names]"), "the search for the record of the change: " + log);
        assertTrue(line(log, "健二 広島 1932").endsWith("[none of the names]"), log);
        assertTrue(line(log, "Endou Kenji 1905").endsWith("[name: 遠藤健二, 1905–1932]"), "a search written down by hand is marked too: " + log);
    }

    @Test
    void aPersonWithOneNameHasTheLogAsBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", "q"),
                new FamilyAccount.Fact("Tom Hale", "died-on", "1920", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-20", "Tom Hale (born 1850): …", "web", "\"Tom Hale\" 1850", 0, 0, 3, "J-1")));
        String log = FamilyNamesFilingTest.run(store, "log", "Tom Hale");
        assertTrue(log.contains("Every search the library made for Tom Hale, newest first."), log);
        assertTrue(line(log, "\"Tom Hale\" 1850").endsWith("[J-1]"), "nothing is added after a line of a person with one name: " + log);
        assertFalse(log.contains("[name:") || log.contains("[both names]"), log);
    }

    /** The line of the log that holds these words. */
    private static String line(String log, String words) {
        return log.lines().filter(l -> l.contains(words)).findFirst().orElseThrow(() -> new AssertionError("no line with " + words + " in:\n" + log)).strip();
    }
}
