package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report's basis: the link on the line, the checked-by section, what was left for later, and the writer told so. */
class ReportBasisTest {

    static final String WRITEUP = "## Answer\n\nTwo homes fit.\n\n"
            + "- 108 Example St, Cambridgeport — 4 bd, 2,327 sq ft, $1,670,000 [50]\n"
            + "- 159 Sample St #1 — condo, 4 bd [28]\n"
            + "- A third place the sources never named\n\n"
            + "## Why\n\n- Porter Square is under the line [2]\n\n"
            + "## Sources\n\n[50] https://example.org/listing/108\n[28] https://example.org/4-bedrooms\n[2] https://example.org/market\n";

    @Test void aCitedLineOfTheAnswerGetsItsSourcesAddressAsTheLinkOnTheLine() {
        ReportBasis.Check c = ReportBasis.check(WRITEUP, Map.of(50, "https://example.org/listing/108", 28, "https://example.org/4-bedrooms", 2, "https://example.org/market"));
        assertTrue(c.text().contains("$1,670,000 [50] — [page](https://example.org/listing/108)"), c.text());
        assertTrue(c.text().contains("[28] — [page](https://example.org/4-bedrooms)"), c.text());
        assertTrue(c.text().contains("[2] — [page](https://example.org/market)"), "a cited line outside the answer gets its link too");
        assertEquals(3, c.added());
        assertEquals(1, c.unlinked(), "the third place names nothing to link");
        assertTrue(c.lines().stream().anyMatch(l -> l.startsWith("link: 3 lines of the answer named a thing with a source and no link")), c.lines().toString());
        assertTrue(c.lines().stream().anyMatch(l -> l.contains("1 line of the answer name something with no source and no link: \"A third place the sources never named\"")), c.lines().toString());
        assertTrue(c.text().contains("[50] https://example.org/listing/108\n"), "the Sources section is left as it is");
    }

    @Test void aLineThatAlreadyLinksIsLeftAlone() {
        String t = "## Answer\n\n- [108 Example St](https://example.org/listing/108) — $1,670,000 [50]\n\n## Sources\n\n[50] https://example.org/listing/108\n";
        ReportBasis.Check c = ReportBasis.check(t, Map.of(50, "https://example.org/listing/108"));
        assertEquals(0, c.added()); assertEquals(1, c.linked()); assertEquals(0, c.unlinked());
        assertFalse(c.text().contains("— [page]"), c.text());
    }

    @Test void theCheckedBySectionIsLookedForAndItsLinesMustSayHow() {
        ReportBasis.Check none = ReportBasis.check(WRITEUP, Map.of());
        assertFalse(none.checkedSection());
        assertTrue(none.lines().stream().anyMatch(l -> l.startsWith("checked-by: the report has no section 'How each point was checked'")), none.lines().toString());
        String with = WRITEUP.replace("## Why", "## How each point was checked\n\n- An inside pocket: checked by looking at the frames at 4:12 [3]\n- Silent, no voiceover: read in the description, not checked\n- Canvas: yes\n\n## Why");
        ReportBasis.Check c = ReportBasis.check(with, Map.of());
        assertTrue(c.checkedSection());
        assertEquals(1, c.unsaid());
        assertTrue(c.text().contains("- Canvas: yes (how it was checked is not said)"), c.text());
        assertTrue(c.text().contains("- An inside pocket: checked by looking at the frames at 4:12 [3]\n"), "a line that says how is left as it is");
        assertTrue(c.lines().stream().anyMatch(l -> l.startsWith("checked-by: 1 line of 'How each point was checked' does not say how")), c.lines().toString());
    }

    @Test void whatWasLeftForLaterIsASectionOfItsOwn() {
        String s = ReportBasis.openSection(List.of("who cut them?", "what tools survive?"), false);
        assertTrue(s.startsWith("## Open questions and sub-goals\n\nQuestions this run left for later, each with why.\n\n- who cut them? — not researched this run: the budget ran out before it.\n- what tools survive? — not researched"), s);
        assertTrue(ReportBasis.openSection(List.of("who cut them?"), true).startsWith("The library adds the sub-questions the run could not afford:"));
    }

    @Test void theWriterIsToldTheBasisAndTheChecksSayWhatTheWriteUpLacks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        var r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 2, store).run(
                new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 60, List.of("how were the gears cut?", "who cut them?")), "");
        boolean told = drive.histories.stream().anyMatch(h -> h.toString().contains("THE REPORT'S BASIS") && h.toString().contains("How each point was checked") && h.toString().contains("[108 Example St, Cambridge](https://example.org/listing/108)"));
        assertTrue(told, "the writer's prompt carries the basis with its literal examples");
        assertTrue(r.answer().contains("basis checked-by: the report has no section"), "the Checks section says the write-up has no checked-by section: " + r.answer());
    }

    @Test void aRunSaysWhatItLeftForLaterInTheReport() {
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(
                new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 5, List.of("how were the gears cut?", "who cut them?")), "");
        assertEquals(List.of("who cut them?"), r.openQuestions());
        assertTrue(r.answer().contains("## Open questions and sub-goals\n\nQuestions this run left for later, each with why.\n\n- who cut them? — not researched this run: the budget ran out before it."), r.answer());
    }
}
